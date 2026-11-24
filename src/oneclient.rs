use std::sync::{Arc, Mutex};
use axum::{
    extract::{
        Path, Query, State,
        ws::{Message, WebSocket, WebSocketUpgrade},
    },
    http::{header, HeaderMap, StatusCode},
    response::IntoResponse,
    routing::{delete, get, post, put},
    Json, Router,
};
use chrono::Utc;
use md5::Digest;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use tokio::sync::broadcast;
use uuid::Uuid;

use crate::AppState;

#[derive(Clone, Serialize, Deserialize)]
pub struct FriendRecord {
    pub player: String,
    pub kind: String,
    pub since: String,
    pub online: bool,
    pub status: String,
}

#[derive(Clone, Serialize, Deserialize)]
pub struct RequestRecord {
    pub id: i64,
    pub player: String,
    pub incoming: bool,
    pub created_at: String,
}

#[derive(Clone, Serialize, Deserialize)]
pub struct MessageRecord {
    pub id: i64,
    pub group_id: i64,
    pub sender: String,
    pub content: String,
    pub sent_at: String,
}

#[derive(Clone, Serialize, Deserialize)]
pub struct GroupRecord {
    pub id: i64,
    pub kind: String,
    pub name: Option<String>,
    pub members: Vec<String>,
    pub unread: bool,
    pub special: bool,
}

#[derive(Clone)]
pub struct OneClientStore {
    pub friends: Arc<Mutex<Vec<FriendRecord>>>,
    pub requests: Arc<Mutex<Vec<RequestRecord>>>,
    pub groups: Arc<Mutex<Vec<GroupRecord>>>,
    pub messages: Arc<Mutex<Vec<MessageRecord>>>,
    pub player_names: Arc<Mutex<std::collections::HashMap<String, String>>>,
    pub ws_tx: broadcast::Sender<String>,
    pub next_id: Arc<Mutex<i64>>,
}

impl Default for OneClientStore {
    fn default() -> Self {
        let (ws_tx, _) = broadcast::channel(256);
        let store = Self {
            friends: Arc::new(Mutex::new(Vec::new())),
            requests: Arc::new(Mutex::new(Vec::new())),
            groups: Arc::new(Mutex::new(Vec::new())),
            messages: Arc::new(Mutex::new(Vec::new())),
            player_names: Arc::new(Mutex::new(std::collections::HashMap::new())),
            ws_tx,
            next_id: Arc::new(Mutex::new(1)),
        };

        // Pre-register FondueETC
        store.player_names.lock().unwrap().insert(
            "b178088f-1f77-4dec-9bc9-8bc9ecefd49d".to_string(),
            "FondueETC".to_string(),
        );

        store
    }
}

impl OneClientStore {
    pub fn alloc_id(&self) -> i64 {
        let mut id = self.next_id.lock().unwrap();
        let cur = *id;
        *id += 1;
        cur
    }
}

fn offline_uuid(mc_name: &str) -> Uuid {
    let mut hasher = md5::Md5::new();
    hasher.update(format!("OfflinePlayer:{}", mc_name).as_bytes());
    let mut bytes = hasher.finalize();
    bytes[6] = (bytes[6] & 0x0f) | 0x30;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    Uuid::from_bytes(bytes.into())
}

// ---------------------------------------------------------------------------
// Route Handlers
// ---------------------------------------------------------------------------

#[derive(Deserialize)]
pub struct LoginQuery {
    pub username: Option<String>,
    pub player: Option<String>,
}

pub async fn login(
    State(state): State<AppState>,
    Query(q): Query<LoginQuery>,
) -> impl IntoResponse {
    let name = q
        .username
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| "FondueETC".to_string());
    let uuid = q
        .player
        .as_deref()
        .and_then(|raw| Uuid::parse_str(raw.trim()).ok())
        .map(|u| u.hyphenated().to_string())
        .unwrap_or_else(|| offline_uuid(&name).hyphenated().to_string());

    state.oneclient.player_names.lock().unwrap().insert(uuid.clone(), name.clone());
    if let Err(err) = upsert_nebula_player(&state, &uuid, &name, true).await {
        tracing::warn!("nebula_players upsert failed for {} ({}): {}", name, uuid, err);
    }

    // El token codifica el nombre para que el wardrobe sepa de quien es lo equipado.
    Json(json!({
        "token": format!("nebula.{}", hex::encode(name.as_bytes())),
        "uuid": uuid
    }))
}

fn bearer_username(headers: &HeaderMap) -> Option<String> {
    let raw = headers
        .get(header::AUTHORIZATION)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("Bearer ").or_else(|| v.strip_prefix("bearer ")))
        .map(str::trim)?;
    let encoded = raw.strip_prefix("nebula.")?;
    let bytes = hex::decode(encoded).ok()?;
    String::from_utf8(bytes).ok().filter(|s| !s.trim().is_empty())
}

async fn upsert_nebula_player(
    state: &AppState,
    uuid: &str,
    username: &str,
    badge_enabled: bool,
) -> Result<(), sqlx::Error> {
    sqlx::query(
        "INSERT INTO nebula_players (uuid, username, badge_enabled, last_seen) \
         VALUES ($1, $2, $3, now()) \
         ON CONFLICT (uuid) DO UPDATE SET \
         username = EXCLUDED.username, \
         badge_enabled = EXCLUDED.badge_enabled, \
         last_seen = now()",
    )
    .bind(uuid)
    .bind(username)
    .bind(badge_enabled)
    .execute(&state.db)
    .await?;
    Ok(())
}

async fn resolve_uuid_for_username(state: &AppState, username: &str) -> Option<String> {
    if let Ok(Some((uuid,))) = sqlx::query_as::<_, (String,)>(
        "SELECT uuid FROM nebula_players WHERE lower(username) = lower($1) ORDER BY last_seen DESC LIMIT 1",
    )
    .bind(username)
    .fetch_optional(&state.db)
    .await
    {
        return Some(uuid);
    }
    let map = state.oneclient.player_names.lock().unwrap();
    for (id, name) in map.iter() {
        if name.eq_ignore_ascii_case(username) {
            return Some(id.clone());
        }
    }
    None
}

async fn query_nebula_snapshot_data(
    state: &AppState,
    raw_ids: &[String],
) -> (Vec<String>, serde_json::Map<String, Value>) {
    let mut normalized = Vec::with_capacity(raw_ids.len());
    for s in raw_ids {
        if let Ok(u) = Uuid::parse_str(s.trim()) {
            normalized.push(u.hyphenated().to_string());
        }
    }
    if normalized.is_empty() {
        return (Vec::new(), serde_json::Map::new());
    }

    let mut users = Vec::new();
    let mut equipped_map = serde_json::Map::new();

    if let Ok(rows) = sqlx::query_as::<_, (String, bool, Option<String>)>(
        "SELECT np.uuid, np.badge_enabled, ce.equipped \
         FROM nebula_players np \
         LEFT JOIN LATERAL ( \
             SELECT equipped FROM cosmetic_equipped \
             WHERE profile = np.username OR lower(profile) = lower(np.username) OR profile = np.uuid \
             ORDER BY updated_at DESC LIMIT 1 \
         ) ce ON true \
         WHERE np.uuid = ANY($1)",
    )
    .bind(&normalized)
    .fetch_all(&state.db)
    .await
    {
        for (uuid, badge_enabled, equipped_json) in rows {
            if badge_enabled {
                users.push(uuid.clone());
            }
            if let Some(raw_eq) = equipped_json {
                if let Ok(Value::Object(obj)) = serde_json::from_str::<Value>(&raw_eq) {
                    if !obj.is_empty() {
                        equipped_map.insert(uuid, Value::Object(obj));
                    }
                }
            }
        }
    }

    (users, equipped_map)
}

pub async fn status_get() -> impl IntoResponse {
    Json(json!({ "status": "online" }))
}

pub async fn status_put(State(state): State<AppState>, headers: HeaderMap) -> impl IntoResponse {
    if let Some(name) = bearer_username(&headers) {
        let _ = sqlx::query("UPDATE nebula_players SET last_seen = now() WHERE lower(username) = lower($1)")
            .bind(&name)
            .execute(&state.db)
            .await;
    }
    Json(json!({ "status": "ok" }))
}

#[derive(Deserialize)]
pub struct HeartbeatPayload {
    pub uuid: String,
    pub username: Option<String>,
    pub badge_enabled: Option<bool>,
}

pub async fn nebula_heartbeat(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(payload): Json<HeartbeatPayload>,
) -> impl IntoResponse {
    let Ok(u) = Uuid::parse_str(payload.uuid.trim()) else {
        return StatusCode::BAD_REQUEST.into_response();
    };
    let uuid = u.hyphenated().to_string();
    let name = payload
        .username
        .filter(|s| !s.trim().is_empty())
        .or_else(|| bearer_username(&headers))
        .unwrap_or_else(|| "Player".to_string());
    let badge = payload.badge_enabled.unwrap_or(true);
    state.oneclient.player_names.lock().unwrap().insert(uuid.clone(), name.clone());
    match upsert_nebula_player(&state, &uuid, &name, badge).await {
        Ok(()) => Json(json!({ "ok": true, "uuid": uuid, "badge_enabled": badge })).into_response(),
        Err(err) => {
            tracing::warn!("heartbeat db error: {}", err);
            StatusCode::INTERNAL_SERVER_ERROR.into_response()
        }
    }
}

#[derive(Deserialize)]
pub struct BadgesLookupPayload {
    pub uuids: Option<Vec<String>>,
    pub players: Option<Vec<String>>,
}

pub async fn nebula_badges_lookup(
    State(state): State<AppState>,
    Json(payload): Json<BadgesLookupPayload>,
) -> impl IntoResponse {
    let ids = payload.uuids.or(payload.players).unwrap_or_default();
    let (users, equipped) = query_nebula_snapshot_data(&state, &ids).await;
    Json(json!({
        "users": users,
        "equipped": equipped
    }))
}

pub async fn mods_put() -> impl IntoResponse {
    StatusCode::OK
}

pub async fn friends_get(State(state): State<AppState>) -> impl IntoResponse {
    let items = state.oneclient.friends.lock().unwrap().clone();
    Json(json!({
        "items": items,
        "cursor": null
    }))
}

pub async fn incoming_requests_get(State(state): State<AppState>) -> impl IntoResponse {
    let all = state.oneclient.requests.lock().unwrap().clone();
    let items: Vec<_> = all.into_iter().filter(|r| r.incoming).collect();
    Json(json!({
        "items": items,
        "cursor": null
    }))
}

pub async fn outgoing_requests_get(State(state): State<AppState>) -> impl IntoResponse {
    let all = state.oneclient.requests.lock().unwrap().clone();
    let items: Vec<_> = all.into_iter().filter(|r| !r.incoming).collect();
    Json(json!({
        "items": items,
        "cursor": null
    }))
}

pub async fn send_friend_request(
    State(state): State<AppState>,
    Path(player): Path<String>,
) -> impl IntoResponse {
    let req_id = state.oneclient.alloc_id();
    let rec = RequestRecord {
        id: req_id,
        player: player.clone(),
        incoming: false,
        created_at: Utc::now().to_rfc3339(),
    };
    state.oneclient.requests.lock().unwrap().push(rec);

    Json(json!({
        "id": req_id,
        "player": player,
        "created_at": Utc::now().to_rfc3339()
    }))
}

pub async fn accept_friend_request(
    State(state): State<AppState>,
    Path(id): Path<i64>,
) -> impl IntoResponse {
    let mut reqs = state.oneclient.requests.lock().unwrap();
    let mut found_player = None;
    if let Some(pos) = reqs.iter().position(|r| r.id == id) {
        found_player = Some(reqs.remove(pos).player);
    }
    drop(reqs);

    if let Some(p) = found_player {
        let mut friends = state.oneclient.friends.lock().unwrap();
        if !friends.iter().any(|f| f.player == p) {
            friends.push(FriendRecord {
                player: p,
                kind: "friend".to_string(),
                since: Utc::now().to_rfc3339(),
                online: true,
                status: "online".to_string(),
            });
        }
    }

    StatusCode::OK
}

pub async fn decline_friend_request(
    State(state): State<AppState>,
    Path(id): Path<i64>,
) -> impl IntoResponse {
    let mut reqs = state.oneclient.requests.lock().unwrap();
    if let Some(pos) = reqs.iter().position(|r| r.id == id) {
        reqs.remove(pos);
    }
    StatusCode::OK
}

pub async fn cancel_friend_request(
    State(state): State<AppState>,
    Path(id_or_player): Path<String>,
) -> impl IntoResponse {
    let mut reqs = state.oneclient.requests.lock().unwrap();
    if let Ok(id) = id_or_player.parse::<i64>() {
        if let Some(pos) = reqs.iter().position(|r| r.id == id) {
            reqs.remove(pos);
            return StatusCode::OK;
        }
    }
    if let Some(pos) = reqs.iter().position(|r| r.player.eq_ignore_ascii_case(&id_or_player)) {
        reqs.remove(pos);
        return StatusCode::OK;
    }
    StatusCode::OK
}

pub async fn remove_friend(
    State(state): State<AppState>,
    Path(player): Path<String>,
) -> impl IntoResponse {
    let mut friends = state.oneclient.friends.lock().unwrap();
    if let Some(pos) = friends.iter().position(|f| f.player.eq_ignore_ascii_case(&player)) {
        friends.remove(pos);
    }
    StatusCode::OK
}

pub async fn blocked_get() -> impl IntoResponse {
    Json(json!({
        "items": [],
        "cursor": null
    }))
}

#[derive(Deserialize)]
pub struct ResolvePlayersPayload {
    pub ids: Option<Vec<String>>,
}

pub async fn resolve_players(
    State(state): State<AppState>,
    Json(payload): Json<ResolvePlayersPayload>,
) -> impl IntoResponse {
    let ids = payload.ids.unwrap_or_default();
    let map = state.oneclient.player_names.lock().unwrap();
    let mut list = Vec::new();
    for id in ids {
        let name = map.get(&id).cloned().unwrap_or_else(|| "Player".to_string());
        list.push(json!({
            "id": id,
            "username": name
        }));
    }

    Json(json!({
        "players": list
    }))
}

pub async fn lookup_player_by_username(
    State(state): State<AppState>,
    Path(username): Path<String>,
) -> impl IntoResponse {
    let map = state.oneclient.player_names.lock().unwrap();
    for (id, name) in map.iter() {
        if name.eq_ignore_ascii_case(&username) {
            return Json(json!({ "id": id, "username": name })).into_response();
        }
    }
    drop(map);

    let off_uuid = offline_uuid(&username).to_string();
    state.oneclient.player_names.lock().unwrap().insert(off_uuid.clone(), username.clone());

    Json(json!({ "id": off_uuid, "username": username })).into_response()
}

pub async fn groups_get(State(state): State<AppState>) -> impl IntoResponse {
    let grps = state.oneclient.groups.lock().unwrap().clone();
    let mut items = Vec::new();
    for g in grps {
        let msgs = state.oneclient.messages.lock().unwrap();
        let last = msgs.iter().filter(|m| m.group_id == g.id).last().cloned();
        let last_obj = last.map(|m| json!({
            "content": m.content,
            "sender": m.sender,
            "sent_at": m.sent_at
        }));

        items.push(json!({
            "id": g.id,
            "kind": g.kind,
            "name": g.name,
            "members": g.members,
            "last_message": last_obj,
            "unread": g.unread,
            "special": g.special
        }));
    }

    Json(json!({
        "items": items,
        "cursor": null
    }))
}

pub async fn open_dm(
    State(state): State<AppState>,
    Path(player): Path<String>,
) -> impl IntoResponse {
    let self_id = "b178088f-1f77-4dec-9bc9-8bc9ecefd49d".to_string();
    let mut grps = state.oneclient.groups.lock().unwrap();
    if let Some(existing) = grps.iter().find(|g| g.kind == "dm" && g.members.contains(&player)) {
        return Json(json!({
            "id": existing.id,
            "kind": "dm",
            "name": null,
            "members": existing.members,
            "last_message": null,
            "unread": false,
            "special": false
        })).into_response();
    }

    let new_id = state.oneclient.alloc_id();
    let new_grp = GroupRecord {
        id: new_id,
        kind: "dm".to_string(),
        name: None,
        members: vec![self_id.clone(), player.clone()],
        unread: false,
        special: false,
    };
    grps.push(new_grp);

    Json(json!({
        "id": new_id,
        "kind": "dm",
        "name": null,
        "members": [self_id, player],
        "last_message": null,
        "unread": false,
        "special": false
    })).into_response()
}

pub async fn group_messages_get(
    State(state): State<AppState>,
    Path(group_id): Path<i64>,
) -> impl IntoResponse {
    let msgs = state.oneclient.messages.lock().unwrap();
    let filtered: Vec<_> = msgs.iter()
        .filter(|m| m.group_id == group_id)
        .map(|m| json!({
            "id": m.id,
            "sender": m.sender,
            "content": m.content,
            "sent_at": m.sent_at
        }))
        .collect();

    Json(filtered)
}

#[derive(Deserialize)]
pub struct SendMessagePayload {
    pub content: String,
}

pub async fn group_message_post(
    State(state): State<AppState>,
    Path(group_id): Path<i64>,
    Json(payload): Json<SendMessagePayload>,
) -> impl IntoResponse {
    let msg_id = state.oneclient.alloc_id();
    let rec = MessageRecord {
        id: msg_id,
        group_id,
        sender: "b178088f-1f77-4dec-9bc9-8bc9ecefd49d".to_string(),
        content: payload.content.clone(),
        sent_at: Utc::now().to_rfc3339(),
    };
    state.oneclient.messages.lock().unwrap().push(rec);

    Json(json!({
        "id": msg_id,
        "sender": "b178088f-1f77-4dec-9bc9-8bc9ecefd49d",
        "content": payload.content,
        "sent_at": Utc::now().to_rfc3339()
    }))
}

pub async fn delegations_get() -> impl IntoResponse {
    Json(json!([]))
}

pub async fn party_get() -> impl IntoResponse {
    StatusCode::NOT_FOUND
}

pub async fn websocket_handler(
    ws: WebSocketUpgrade,
    State(state): State<AppState>,
    headers: HeaderMap,
) -> impl IntoResponse {
    let name = bearer_username(&headers).unwrap_or_else(|| "Player".to_string());
    ws.on_upgrade(move |socket| handle_socket(socket, state, name))
}

async fn handle_socket(mut socket: WebSocket, state: AppState, player_name: String) {
    let player_uuid = resolve_uuid_for_username(&state, &player_name)
        .await
        .unwrap_or_else(|| offline_uuid(&player_name).hyphenated().to_string());

    if player_name != "Player" {
        let _ = upsert_nebula_player(&state, &player_uuid, &player_name, true).await;
        let presence_pkt = json!({
            "type": "PlayerPresence",
            "player": player_uuid,
            "online": true,
            "status": "online"
        });
        let _ = state.oneclient.ws_tx.send(presence_pkt.to_string());
    }

    let init_pkt = json!({
        "type": "CosmeticsInfo",
        "cosmetics": {}
    });
    if socket.send(Message::Text(init_pkt.to_string().into())).await.is_err() {
        return;
    }

    let mut rx = state.oneclient.ws_tx.subscribe();

    loop {
        tokio::select! {
            bcast = rx.recv() => {
                match bcast {
                    Ok(txt) => {
                        if socket.send(Message::Text(txt.into())).await.is_err() {
                            break;
                        }
                    }
                    Err(broadcast::error::RecvError::Lagged(_)) => continue,
                    Err(broadcast::error::RecvError::Closed) => break,
                }
            }
            incoming = socket.recv() => {
                let Some(Ok(msg)) = incoming else { break };
                match msg {
                    Message::Close(_) => break,
                    Message::Text(text) => {
                        let Ok(val) = serde_json::from_str::<Value>(&text) else { continue };
                        match val.get("type").and_then(Value::as_str) {
                            Some("SubscribePlayers") => {
                                let req_id = val.get("request_id").and_then(Value::as_i64);
                                let players: Vec<String> = val
                                    .get("players")
                                    .and_then(Value::as_array)
                                    .map(|arr| {
                                        arr.iter()
                                            .filter_map(Value::as_str)
                                            .map(str::to_string)
                                            .collect()
                                    })
                                    .unwrap_or_default();
                                let (users, equipped) = query_nebula_snapshot_data(&state, &players).await;
                                let snapshot = json!({
                                    "type": "SubscriptionSnapshot",
                                    "equipped": equipped,
                                    "active_emotes": {},
                                    "particle_colors": {},
                                    "users": users,
                                    "rejected": [],
                                    "request_id": req_id
                                });
                                if socket.send(Message::Text(snapshot.to_string().into())).await.is_err() {
                                    break;
                                }
                            }
                            Some("GetActiveCosmetics") => {
                                let players: Vec<String> = val
                                    .get("players")
                                    .and_then(Value::as_array)
                                    .map(|arr| {
                                        arr.iter()
                                            .filter_map(Value::as_str)
                                            .map(str::to_string)
                                            .collect()
                                    })
                                    .unwrap_or_default();
                                let (_users, equipped) = query_nebula_snapshot_data(&state, &players).await;
                                let mut flat = serde_json::Map::new();
                                for (u, slots) in equipped {
                                    if let Value::Object(obj) = slots {
                                        let ids: Vec<Value> = obj.values().cloned().collect();
                                        flat.insert(u, Value::Array(ids));
                                    }
                                }
                                let info = json!({
                                    "type": "CosmeticsInfo",
                                    "cosmetics": flat
                                });
                                if socket.send(Message::Text(info.to_string().into())).await.is_err() {
                                    break;
                                }
                            }
                            Some("SetEquippedCosmetic") => {
                                if let Some(slot) = val.get("slot").and_then(Value::as_str) {
                                    let cosmetic_id = val.get("cosmetic_id").cloned().unwrap_or(Value::Null);
                                    let pkt = json!({
                                        "type": "PlayerCosmeticEquipped",
                                        "player": player_uuid,
                                        "slot": slot,
                                        "cosmetic_id": cosmetic_id
                                    });
                                    let _ = state.oneclient.ws_tx.send(pkt.to_string());
                                }
                            }
                            Some("PlayEmote") => {
                                if let Some(eid) = val.get("emote_id").and_then(Value::as_i64) {
                                    let pkt = json!({
                                        "type": "PlayerEmoteStarted",
                                        "player": player_uuid,
                                        "emote_id": eid
                                    });
                                    let _ = state.oneclient.ws_tx.send(pkt.to_string());
                                }
                            }
                            Some("StopEmote") => {
                                let pkt = json!({
                                    "type": "PlayerEmoteStopped",
                                    "player": player_uuid
                                });
                                let _ = state.oneclient.ws_tx.send(pkt.to_string());
                            }
                            _ => {}
                        }
                    }
                    _ => {}
                }
            }
        }
    }
}

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/account/login", post(login))
        .route("/account/status", get(status_get).put(status_put))
        .route("/account/mods", put(mods_put))
        .route("/v1/nebula/badges", post(nebula_badges_lookup))
        .route("/v1/nebula/heartbeat", put(nebula_heartbeat))
        .route("/v1/social/friends", get(friends_get))
        .route("/v1/social/requests/incoming", get(incoming_requests_get))
        .route("/v1/social/requests/outgoing", get(outgoing_requests_get))
        .route("/social/requests/{player}", post(send_friend_request).delete(cancel_friend_request))
        .route("/social/requests/{id}/accept", post(accept_friend_request))
        .route("/social/requests/{id}/decline", post(decline_friend_request))
        .route("/social/requests/{id}/cancel", post(cancel_friend_request).delete(cancel_friend_request))
        .route("/v1/social/requests/{id}", delete(cancel_friend_request))
        .route("/social/friends/{player}", delete(remove_friend))
        .route("/v1/social/blocked", get(blocked_get))
        .route("/players/resolve", post(resolve_players))
        .route("/players/by-username/{username}", get(lookup_player_by_username))
        .route("/v1/groups", get(groups_get))
        .route("/groups/dm/{player}", post(open_dm))
        .route("/groups/{id}/messages", get(group_messages_get).post(group_message_post))
        .route("/groups/delegations", get(delegations_get))
        .route("/v1/party", get(party_get))
        .route("/websocket", get(websocket_handler))
}
