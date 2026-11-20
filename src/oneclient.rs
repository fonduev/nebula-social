use std::sync::{Arc, Mutex};
use axum::{
    extract::{
        Path, Query, State,
        ws::{Message, WebSocket, WebSocketUpgrade},
    },
    http::StatusCode,
    response::IntoResponse,
    routing::{delete, get, post, put},
    Json, Router,
};
use chrono::Utc;
use md5::Digest;
use serde::{Deserialize, Serialize};
use serde_json::json;
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
    pub next_id: Arc<Mutex<i64>>,
}

impl Default for OneClientStore {
    fn default() -> Self {
        let store = Self {
            friends: Arc::new(Mutex::new(Vec::new())),
            requests: Arc::new(Mutex::new(Vec::new())),
            groups: Arc::new(Mutex::new(Vec::new())),
            messages: Arc::new(Mutex::new(Vec::new())),
            player_names: Arc::new(Mutex::new(std::collections::HashMap::new())),
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
    let name = q.username.unwrap_or_else(|| "FondueETC".to_string());
    let uuid = q.player.unwrap_or_else(|| "b178088f-1f77-4dec-9bc9-8bc9ecefd49d".to_string());
    state.oneclient.player_names.lock().unwrap().insert(uuid, name);

    Json(json!({
        "token": "nebula_oneclient_session_token"
    }))
}

pub async fn status_get() -> impl IntoResponse {
    Json(json!({ "status": "online" }))
}

pub async fn status_put() -> impl IntoResponse {
    Json(json!({ "status": "ok" }))
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

pub async fn websocket_handler(ws: WebSocketUpgrade) -> impl IntoResponse {
    ws.on_upgrade(handle_socket)
}

async fn handle_socket(mut socket: WebSocket) {
    let init_pkt = json!({
        "CosmeticsInfo": {
            "cosmetics": {}
        }
    });
    let _ = socket.send(Message::Text(init_pkt.to_string().into())).await;

    while let Some(Ok(msg)) = socket.recv().await {
        if let Message::Close(_) = msg {
            break;
        }
    }
}

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/account/login", post(login))
        .route("/account/status", get(status_get).put(status_put))
        .route("/account/mods", put(mods_put))
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
