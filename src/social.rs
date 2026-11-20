use std::time::Duration as StdDuration;

use axum::{
    extract::{Path, Query, State},
    http::HeaderMap,
    Json,
};
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sqlx::{PgPool, Row};
use uuid::Uuid;

use crate::{
    auth::{optional_identity, AuthedIdentity},
    error::AppError,
    AppState,
};

// ---------------------------------------------------------------------------
// DTOs
// ---------------------------------------------------------------------------

#[derive(Serialize)]
pub struct AuthorDto {
    pub username: String,
    pub kind: String,
    pub verified: bool,
}

#[derive(Serialize)]
pub struct PostDto {
    pub id: Uuid,
    pub body: String,
    pub created_at: DateTime<Utc>,
    pub author: AuthorDto,
    pub like_count: i64,
    pub comment_count: i64,
    pub liked: bool,
}

#[derive(Serialize)]
pub struct CommentDto {
    pub id: Uuid,
    pub body: String,
    pub created_at: DateTime<Utc>,
    pub author: AuthorDto,
}

fn post_dto(row: &sqlx::postgres::PgRow) -> Result<PostDto, AppError> {
    Ok(PostDto {
        id: row.try_get("id")?,
        body: row.try_get("body")?,
        created_at: row.try_get("created_at")?,
        author: AuthorDto {
            username: row.try_get("mc_name")?,
            kind: row.try_get("kind")?,
            verified: row.try_get("verified")?,
        },
        like_count: row.try_get("like_count")?,
        comment_count: row.try_get("comment_count")?,
        liked: row.try_get("liked")?,
    })
}

fn comment_dto(row: &sqlx::postgres::PgRow) -> Result<CommentDto, AppError> {
    Ok(CommentDto {
        id: row.try_get("id")?,
        body: row.try_get("body")?,
        created_at: row.try_get("created_at")?,
        author: AuthorDto {
            username: row.try_get("mc_name")?,
            kind: row.try_get("kind")?,
            verified: row.try_get("verified")?,
        },
    })
}

// ---------------------------------------------------------------------------
// SQL reutilizado
// ---------------------------------------------------------------------------

/// `$1` = id del post, `$2` = identidad del que mira (puede ser null).
const POST_SELECT: &str = "
    select p.id, p.body, p.created_at, i.mc_name, i.kind, i.verified,
           (select count(*) from likes l where l.post_id = p.id) as like_count,
           (select count(*) from comments c where c.post_id = p.id) as comment_count,
           exists (select 1 from likes l2 where l2.post_id = p.id and l2.identity_id = $2::uuid) as liked
    from posts p
    join identities i on i.id = p.author_id
    where p.id = $1 and not i.claim_revoked";

const COMMENT_SELECT: &str = "
    select c.id, c.body, c.created_at, i.mc_name, i.kind, i.verified
    from comments c
    join identities i on i.id = c.author_id
    where c.post_id = $1
    order by c.created_at asc
    limit 200";

async fn fetch_post(
    db: &PgPool,
    post_id: Uuid,
    viewer: Option<Uuid>,
) -> Result<PostDto, AppError> {
    let row = sqlx::query(POST_SELECT)
        .bind(post_id)
        .bind(viewer)
        .fetch_optional(db)
        .await?
        .ok_or(AppError::NotFound)?;
    post_dto(&row)
}

async fn identity_id_by_name(db: &PgPool, name: &str) -> Result<Option<Uuid>, AppError> {
    Ok(sqlx::query(
        "select i.id from name_claims c
         join identities i on i.id = c.identity_id
         where c.name_key = lower($1) and not i.claim_revoked",
    )
    .bind(name)
    .fetch_optional(db)
    .await?
    .map(|row| row.get::<Uuid, _>("id")))
}

// ---------------------------------------------------------------------------
// Salud
// ---------------------------------------------------------------------------

pub async fn health(State(state): State<AppState>) -> Json<Value> {
    let db_ok = sqlx::query("select 1").execute(&state.db).await.is_ok();
    Json(json!({ "status": "ok", "db": db_ok }))
}

// ---------------------------------------------------------------------------
// Feed y posts
// ---------------------------------------------------------------------------

#[derive(Deserialize)]
pub struct FeedQuery {
    pub limit: Option<i64>,
    pub before: Option<DateTime<Utc>>,
    pub before_id: Option<Uuid>,
}

pub async fn feed(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(query): Query<FeedQuery>,
) -> Result<Json<Value>, AppError> {
    let limit = query.limit.unwrap_or(20).clamp(1, 50);
    if query.before.is_some() != query.before_id.is_some() {
        return Err(AppError::BadRequest(
            "before y before_id van juntos".to_string(),
        ));
    }
    let viewer = optional_identity(&state, &headers)
        .await
        .map(|(identity_id, _)| identity_id);

    // Feed global: no se filtra por servidor de Minecraft.
    let rows = sqlx::query(
        "select p.id, p.body, p.created_at, i.mc_name, i.kind, i.verified,
                (select count(*) from likes l where l.post_id = p.id) as like_count,
                (select count(*) from comments c where c.post_id = p.id) as comment_count,
                exists (select 1 from likes l2 where l2.post_id = p.id and l2.identity_id = $3::uuid) as liked
         from posts p
         join identities i on i.id = p.author_id
         where not i.claim_revoked
           and ($1::timestamptz is null
                or (p.created_at, p.id) < ($1::timestamptz, $2::uuid))
         order by p.created_at desc, p.id desc
         limit $4",
    )
    .bind(query.before)
    .bind(query.before_id)
    .bind(viewer)
    .bind(limit + 1)
    .fetch_all(&state.db)
    .await?;

    let has_more = rows.len() as i64 > limit;
    let mut posts = rows
        .iter()
        .take(limit as usize)
        .map(post_dto)
        .collect::<Result<Vec<_>, _>>()?;

    let (next_before, next_before_id) = if has_more {
        match posts.last() {
            Some(last) => (Some(last.created_at), Some(last.id)),
            None => (None, None),
        }
    } else {
        (None, None)
    };
    posts.shrink_to_fit();

    Ok(Json(json!({
        "posts": posts,
        "next_before": next_before,
        "next_before_id": next_before_id,
    })))
}

#[derive(Deserialize)]
pub struct NewPostBody {
    pub body: String,
}

pub async fn create_post(
    State(state): State<AppState>,
    me: AuthedIdentity,
    Json(body): Json<NewPostBody>,
) -> Result<(axum::http::StatusCode, Json<PostDto>), AppError> {
    let text = body.body.trim().to_string();
    let length = text.chars().count();
    if length == 0 || length > 280 {
        return Err(AppError::BadRequest(
            "el post debe tener entre 1 y 280 caracteres".to_string(),
        ));
    }

    state
        .limiter
        .check(me.identity_id, "post", 3, StdDuration::from_secs(60))
        .map_err(AppError::RateLimited)?;

    let row = sqlx::query("insert into posts (author_id, body) values ($1, $2) returning id")
        .bind(me.identity_id)
        .bind(&text)
        .fetch_one(&state.db)
        .await?;
    let post_id: Uuid = row.get("id");

    let post = fetch_post(&state.db, post_id, Some(me.identity_id)).await?;
    Ok((axum::http::StatusCode::CREATED, Json(post)))
}

pub async fn get_post(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<Uuid>,
) -> Result<Json<PostDto>, AppError> {
    let viewer = optional_identity(&state, &headers)
        .await
        .map(|(identity_id, _)| identity_id);
    Ok(Json(fetch_post(&state.db, id, viewer).await?))
}

pub async fn delete_post(
    State(state): State<AppState>,
    me: AuthedIdentity,
    Path(id): Path<Uuid>,
) -> Result<axum::http::StatusCode, AppError> {
    let deleted = sqlx::query("delete from posts where id = $1 and author_id = $2")
        .bind(id)
        .bind(me.identity_id)
        .execute(&state.db)
        .await?
        .rows_affected();

    if deleted == 0 {
        // O no existe, o no es tuyo: no revelamos cual de las dos.
        return Err(AppError::NotFound);
    }
    Ok(axum::http::StatusCode::NO_CONTENT)
}

// ---------------------------------------------------------------------------
// Likes
// ---------------------------------------------------------------------------

pub async fn like(
    State(state): State<AppState>,
    me: AuthedIdentity,
    Path(id): Path<Uuid>,
) -> Result<axum::http::StatusCode, AppError> {
    state
        .limiter
        .check(me.identity_id, "like", 10, StdDuration::from_secs(60))
        .map_err(AppError::RateLimited)?;

    let inserted = sqlx::query(
        "insert into likes (identity_id, post_id) values ($1, $2) on conflict do nothing",
    )
    .bind(me.identity_id)
    .bind(id)
    .execute(&state.db)
    .await?
    .rows_affected();

    if inserted == 0 {
        // Idempotente: el post no existe (o ya estaba el like). Comprobamos que exista.
        let exists = sqlx::query("select 1 from posts where id = $1")
            .bind(id)
            .fetch_optional(&state.db)
            .await?
            .is_some();
        if !exists {
            return Err(AppError::NotFound);
        }
    } else {
        sqlx::query(
            "insert into notifications (recipient_id, actor_id, kind, post_id)
             select p.author_id, $1, 'like', p.id from posts p
             where p.id = $2 and p.author_id <> $1",
        )
        .bind(me.identity_id)
        .bind(id)
        .execute(&state.db)
        .await?;
    }

    Ok(axum::http::StatusCode::NO_CONTENT)
}

pub async fn unlike(
    State(state): State<AppState>,
    me: AuthedIdentity,
    Path(id): Path<Uuid>,
) -> Result<axum::http::StatusCode, AppError> {
    sqlx::query("delete from likes where identity_id = $1 and post_id = $2")
        .bind(me.identity_id)
        .bind(id)
        .execute(&state.db)
        .await?;
    Ok(axum::http::StatusCode::NO_CONTENT)
}

// ---------------------------------------------------------------------------
// Comentarios
// ---------------------------------------------------------------------------

pub async fn list_comments(
    State(state): State<AppState>,
    Path(id): Path<Uuid>,
) -> Result<Json<Vec<CommentDto>>, AppError> {
    let rows = sqlx::query(COMMENT_SELECT)
        .bind(id)
        .fetch_all(&state.db)
        .await?;
    let comments = rows
        .iter()
        .map(comment_dto)
        .collect::<Result<Vec<_>, _>>()?;
    Ok(Json(comments))
}

pub async fn create_comment(
    State(state): State<AppState>,
    me: AuthedIdentity,
    Path(id): Path<Uuid>,
    Json(body): Json<NewPostBody>,
) -> Result<(axum::http::StatusCode, Json<CommentDto>), AppError> {
    let text = body.body.trim().to_string();
    let length = text.chars().count();
    if length == 0 || length > 300 {
        return Err(AppError::BadRequest(
            "el comentario debe tener entre 1 y 300 caracteres".to_string(),
        ));
    }

    state
        .limiter
        .check(me.identity_id, "comment", 5, StdDuration::from_secs(60))
        .map_err(AppError::RateLimited)?;

    let row = sqlx::query(
        "insert into comments (post_id, author_id, body)
         select p.id, $2, $3 from posts p where p.id = $1
         returning id",
    )
    .bind(id)
    .bind(me.identity_id)
    .bind(&text)
    .fetch_optional(&state.db)
    .await?;
    let comment_id: Uuid = row.ok_or(AppError::NotFound)?.get("id");

    sqlx::query(
        "insert into notifications (recipient_id, actor_id, kind, post_id)
         select p.author_id, $1, 'comment', p.id from posts p
         where p.id = $2 and p.author_id <> $1",
    )
    .bind(me.identity_id)
    .bind(id)
    .execute(&state.db)
    .await?;

    let row = sqlx::query(
        "select c.id, c.body, c.created_at, i.mc_name, i.kind, i.verified
         from comments c join identities i on i.id = c.author_id
         where c.id = $1",
    )
    .bind(comment_id)
    .fetch_one(&state.db)
    .await?;

    Ok((axum::http::StatusCode::CREATED, Json(comment_dto(&row)?)))
}

// ---------------------------------------------------------------------------
// Perfiles y follow
// ---------------------------------------------------------------------------

pub async fn profile(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(username): Path<String>,
) -> Result<Json<Value>, AppError> {
    let viewer = optional_identity(&state, &headers)
        .await
        .map(|(identity_id, _)| identity_id);

    let row = sqlx::query(
        "select i.id, i.mc_name, i.kind, i.verified, i.bio, i.created_at,
                (select count(*) from follows f where f.followee_id = i.id) as followers,
                (select count(*) from follows f where f.follower_id = i.id) as following,
                (select count(*) from posts p where p.author_id = i.id and not i.claim_revoked) as posts,
                ($2::uuid is not null
                 and exists (select 1 from follows f2
                             where f2.follower_id = $2::uuid and f2.followee_id = i.id)) as is_following
         from name_claims c
         join identities i on i.id = c.identity_id
         where c.name_key = lower($1) and not i.claim_revoked",
    )
    .bind(&username)
    .bind(viewer)
    .fetch_optional(&state.db)
    .await?
    .ok_or(AppError::NotFound)?;

    Ok(Json(json!({
        "username": row.get::<String, _>("mc_name"),
        "kind": row.get::<String, _>("kind"),
        "verified": row.get::<bool, _>("verified"),
        "bio": row.get::<String, _>("bio"),
        "created_at": row.get::<DateTime<Utc>, _>("created_at"),
        "followers": row.get::<i64, _>("followers"),
        "following": row.get::<i64, _>("following"),
        "posts": row.get::<i64, _>("posts"),
        "is_following": row.get::<bool, _>("is_following"),
    })))
}

pub async fn follow(
    State(state): State<AppState>,
    me: AuthedIdentity,
    Path(username): Path<String>,
) -> Result<axum::http::StatusCode, AppError> {
    let target = identity_id_by_name(&state.db, &username)
        .await?
        .ok_or(AppError::NotFound)?;
    if target == me.identity_id {
        return Err(AppError::BadRequest(
            "no puedes seguirte a ti mismo".to_string(),
        ));
    }

    let inserted = sqlx::query(
        "insert into follows (follower_id, followee_id) values ($1, $2) on conflict do nothing",
    )
    .bind(me.identity_id)
    .bind(target)
    .execute(&state.db)
    .await?
    .rows_affected();

    if inserted == 1 {
        sqlx::query("insert into notifications (recipient_id, actor_id, kind) values ($1, $2, 'follow')")
            .bind(target)
            .bind(me.identity_id)
            .execute(&state.db)
            .await?;
    }

    Ok(axum::http::StatusCode::NO_CONTENT)
}

pub async fn unfollow(
    State(state): State<AppState>,
    me: AuthedIdentity,
    Path(username): Path<String>,
) -> Result<axum::http::StatusCode, AppError> {
    let target = identity_id_by_name(&state.db, &username)
        .await?
        .ok_or(AppError::NotFound)?;

    sqlx::query("delete from follows where follower_id = $1 and followee_id = $2")
        .bind(me.identity_id)
        .bind(target)
        .execute(&state.db)
        .await?;

    Ok(axum::http::StatusCode::NO_CONTENT)
}

// ---------------------------------------------------------------------------
// Busqueda
// ---------------------------------------------------------------------------

#[derive(Deserialize)]
pub struct SearchQuery {
    pub q: String,
    pub limit: Option<i64>,
}

pub async fn search(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(query): Query<SearchQuery>,
) -> Result<Json<Vec<PostDto>>, AppError> {
    let term = query.q.trim().to_string();
    if term.chars().count() < 2 {
        return Err(AppError::BadRequest(
            "busca al menos 2 caracteres".to_string(),
        ));
    }
    let limit = query.limit.unwrap_or(20).clamp(1, 50);
    let viewer = optional_identity(&state, &headers)
        .await
        .map(|(identity_id, _)| identity_id);

    let rows = sqlx::query(
        "select p.id, p.body, p.created_at, i.mc_name, i.kind, i.verified,
                (select count(*) from likes l where l.post_id = p.id) as like_count,
                (select count(*) from comments c where c.post_id = p.id) as comment_count,
                exists (select 1 from likes l2 where l2.post_id = p.id and l2.identity_id = $3::uuid) as liked
         from posts p
         join identities i on i.id = p.author_id
         where not i.claim_revoked
           and (p.search @@ websearch_to_tsquery('simple', $1)
                or lower(p.body) like '%' || lower($1) || '%')
         order by p.created_at desc, p.id desc
         limit $2",
    )
    .bind(&term)
    .bind(limit)
    .bind(viewer)
    .fetch_all(&state.db)
    .await?;

    let posts = rows
        .iter()
        .map(post_dto)
        .collect::<Result<Vec<_>, _>>()?;
    Ok(Json(posts))
}

// ---------------------------------------------------------------------------
// Notificaciones
// ---------------------------------------------------------------------------

#[derive(Deserialize)]
pub struct NotificationsQuery {
    pub since: Option<DateTime<Utc>>,
}

pub async fn notifications(
    State(state): State<AppState>,
    me: AuthedIdentity,
    Query(query): Query<NotificationsQuery>,
) -> Result<Json<Value>, AppError> {
    let unread: i64 = sqlx::query_scalar(
        "select count(*) from notifications where recipient_id = $1 and read_at is null",
    )
    .bind(me.identity_id)
    .fetch_one(&state.db)
    .await?;

    let rows = sqlx::query(
        "select n.id, n.kind, n.created_at, (n.read_at is not null) as read,
                a.mc_name, n.post_id
         from notifications n
         join identities a on a.id = n.actor_id
         where n.recipient_id = $1
           and ($2::timestamptz is null or n.created_at > $2::timestamptz)
         order by n.created_at desc
         limit 50",
    )
    .bind(me.identity_id)
    .bind(query.since)
    .fetch_all(&state.db)
    .await?;

    let items = rows
        .iter()
        .map(|row| {
            json!({
                "id": row.get::<Uuid, _>("id"),
                "kind": row.get::<String, _>("kind"),
                "created_at": row.get::<DateTime<Utc>, _>("created_at"),
                "read": row.get::<bool, _>("read"),
                "actor": { "username": row.get::<String, _>("mc_name") },
                "post_id": row.get::<Option<Uuid>, _>("post_id"),
            })
        })
        .collect::<Vec<_>>();

    Ok(Json(json!({ "unread": unread, "items": items })))
}
