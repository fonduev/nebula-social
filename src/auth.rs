use argon2::{
    password_hash::{PasswordHash, PasswordHasher, PasswordVerifier, SaltString},
    Argon2,
};
use axum::{
    extract::{FromRequestParts, Query, State},
    http::{header, request::Parts, HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    Json,
};
use chrono::{DateTime, Utc};
use hmac::{Hmac, Mac};
use md5::Md5;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use sqlx::Row;
use uuid::Uuid;

use crate::{error::AppError, AppState};

const SESSION_DAYS: i32 = 30;
const DEVICE_CODE_MINUTES: i32 = 10;
const VERIFY_CODE_MINUTES: i32 = 15;
/// Margen del proof del launcher: 5 minutos.
const PREMIUM_PROOF_SKEW_SECONDS: i64 = 300;

// ---------------------------------------------------------------------------
// Sesiones y extractores
// ---------------------------------------------------------------------------

/// Sesion web: la cuenta Nebula, sin jugador de Minecraft vinculado.
pub struct AuthedAccount {
    pub account_id: Uuid,
    pub email_verified: bool,
    pub token_hash: String,
}

/// Sesion de juego: la cuenta Nebula **y** el jugador de Minecraft.
pub struct AuthedIdentity {
    pub account_id: Uuid,
    pub identity_id: Uuid,
    pub mc_uuid: Uuid,
    pub mc_name: String,
    pub kind: String,
    pub token_hash: String,
}

#[derive(Serialize)]
pub struct IdentityDto {
    pub username: String,
    pub uuid: Uuid,
    pub kind: String,
    pub verified: bool,
}

fn identity_dto(row: &sqlx::postgres::PgRow) -> Result<IdentityDto, AppError> {
    Ok(IdentityDto {
        username: row.try_get("mc_name")?,
        uuid: row.try_get("mc_uuid")?,
        kind: row.try_get("kind")?,
        verified: row.try_get("verified")?,
    })
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

fn token_hash(token: &str) -> String {
    let mut hasher = Sha256::new();
    hasher.update(token.as_bytes());
    hex::encode(hasher.finalize())
}

/// 256 bits de entropia a partir de dos UUID v4 (no hace falta `rand`).
fn new_token() -> String {
    format!("{}{}", Uuid::new_v4().simple(), Uuid::new_v4().simple())
}

/// Codigo de vinculacion de 6 caracteres, sin letras/numeros confundibles.
fn new_device_code() -> String {
    const ALPHABET: &[u8] = b"ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    Uuid::new_v4()
        .into_bytes()
        .iter()
        .take(6)
        .map(|byte| ALPHABET[(*byte as usize) % ALPHABET.len()] as char)
        .collect()
}

fn new_email_code() -> String {
    let bytes = Uuid::new_v4().into_bytes();
    let value = u32::from_be_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]) % 1_000_000;
    format!("{value:06}")
}

fn hash_password(password: &str) -> Result<String, AppError> {
    let salt = SaltString::encode_b64(&Uuid::new_v4().into_bytes())
        .map_err(|e| AppError::Internal(e.to_string()))?;
    Argon2::default()
        .hash_password(password.as_bytes(), &salt)
        .map(|hash| hash.to_string())
        .map_err(|e| AppError::Internal(e.to_string()))
}

fn verify_password(password: &str, stored: &str) -> bool {
    match PasswordHash::new(stored) {
        Ok(parsed) => Argon2::default()
            .verify_password(password.as_bytes(), &parsed)
            .is_ok(),
        Err(_) => false,
    }
}

fn valid_email(email: &str) -> bool {
    let mut parts = email.split('@');
    let (Some(local), Some(domain), None) = (parts.next(), parts.next(), parts.next()) else {
        return false;
    };
    !local.is_empty() && domain.contains('.') && domain.len() >= 3 && email.len() <= 254
}

/// Nombre de Minecraft: 3-16 caracteres, letras, numeros y guion bajo.
fn valid_mc_name(name: &str) -> bool {
    (3..=16).contains(&name.chars().count())
        && name.chars().all(|c| c.is_ascii_alphanumeric() || c == '_')
}

fn name_key(name: &str) -> String {
    name.to_lowercase()
}

/// El mismo UUID que calcula Java con
/// `UUID.nameUUIDFromBytes(("OfflinePlayer:" + nombre).getBytes())`:
/// MD5 del texto con los bits de version 3 y variante RFC 4122.
fn offline_uuid(name: &str) -> Uuid {
    let digest = Md5::digest(format!("OfflinePlayer:{name}").as_bytes());
    let mut bytes = [0u8; 16];
    bytes.copy_from_slice(&digest);
    bytes[6] = (bytes[6] & 0x0f) | 0x30;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    Uuid::from_bytes(bytes)
}

async fn create_session(
    state: &AppState,
    account_id: Uuid,
    identity_id: Option<Uuid>,
    scope: &str,
) -> Result<String, AppError> {
    let token = new_token();
    sqlx::query(
        "insert into sessions (token_hash, account_id, identity_id, scope, expires_at)
         values ($1, $2, $3, $4, now() + make_interval(days => $5))",
    )
    .bind(token_hash(&token))
    .bind(account_id)
    .bind(identity_id)
    .bind(scope)
    .bind(SESSION_DAYS)
    .execute(&state.db)
    .await?;
    Ok(token)
}

fn bearer(headers: &HeaderMap) -> Option<String> {
    let token = headers
        .get(header::AUTHORIZATION)?
        .to_str()
        .ok()?
        .strip_prefix("Bearer ")?
        .trim();
    if token.is_empty() {
        None
    } else {
        Some(token.to_string())
    }
}

/// Identidad del jugador si el header trae un token de juego valido.
/// Para endpoints publicos: token ausente o invalido -> `None`, nunca error.
pub async fn optional_identity(state: &AppState, headers: &HeaderMap) -> Option<(Uuid, String)> {
    let token = bearer(headers)?;
    sqlx::query(
        "select i.id, i.mc_name from sessions s
         join identities i on i.id = s.identity_id
         where s.token_hash = $1 and s.expires_at > now()",
    )
    .bind(token_hash(&token))
    .fetch_optional(&state.db)
    .await
    .ok()
    .flatten()
    .map(|row| (row.get::<Uuid, _>("id"), row.get::<String, _>("mc_name")))
}

impl FromRequestParts<AppState> for AuthedAccount {
    type Rejection = AppError;

    async fn from_request_parts(
        parts: &mut Parts,
        state: &AppState,
    ) -> Result<Self, Self::Rejection> {
        let token = bearer(&parts.headers).ok_or(AppError::Unauthorized)?;
        let hash = token_hash(&token);
        let row = sqlx::query(
            "select a.id, a.email_verified from sessions s
             join accounts a on a.id = s.account_id
             where s.token_hash = $1 and s.expires_at > now()",
        )
        .bind(&hash)
        .fetch_optional(&state.db)
        .await?
        .ok_or(AppError::Unauthorized)?;

        Ok(AuthedAccount {
            account_id: row.get("id"),
            email_verified: row.get("email_verified"),
            token_hash: hash,
        })
    }
}

impl FromRequestParts<AppState> for AuthedIdentity {
    type Rejection = AppError;

    async fn from_request_parts(
        parts: &mut Parts,
        state: &AppState,
    ) -> Result<Self, Self::Rejection> {
        let token = bearer(&parts.headers).ok_or(AppError::Unauthorized)?;
        let hash = token_hash(&token);
        let row = sqlx::query(
            "select s.account_id, i.id, i.mc_uuid, i.mc_name, i.kind
             from sessions s
             join identities i on i.id = s.identity_id
             where s.token_hash = $1 and s.expires_at > now()",
        )
        .bind(&hash)
        .fetch_optional(&state.db)
        .await?
        .ok_or_else(|| {
            AppError::forbidden(
                "cuenta_no_vinculada",
                "esta sesion no tiene un jugador de Minecraft vinculado",
            )
        })?;

        Ok(AuthedIdentity {
            account_id: row.get("account_id"),
            identity_id: row.get("id"),
            mc_uuid: row.get("mc_uuid"),
            mc_name: row.get("mc_name"),
            kind: row.get("kind"),
            token_hash: hash,
        })
    }
}

// ---------------------------------------------------------------------------
// Cuenta Nebula (web: registrar, verificar correo, entrar)
// ---------------------------------------------------------------------------

#[derive(Deserialize)]
pub struct RegisterBody {
    pub email: String,
    pub password: String,
}

pub async fn register(
    State(state): State<AppState>,
    Json(body): Json<RegisterBody>,
) -> Result<(StatusCode, Json<Value>), AppError> {
    let email = body.email.trim().to_lowercase();
    if !valid_email(&email) {
        return Err(AppError::BadRequest("email no valido".to_string()));
    }
    if body.password.len() < 8 || body.password.len() > 200 {
        return Err(AppError::BadRequest(
            "la contrasena debe tener entre 8 y 200 caracteres".to_string(),
        ));
    }

    let exists = sqlx::query("select 1 from accounts where lower(email) = $1")
        .bind(&email)
        .fetch_optional(&state.db)
        .await?
        .is_some();
    if exists {
        return Err(AppError::conflict(
            "email_taken",
            "ese email ya tiene cuenta Nebula",
        ));
    }

    let password_hash = hash_password(&body.password)?;
    let code = new_email_code();

    let row = sqlx::query(
        "insert into accounts (email, password_hash, verify_code_hash, verify_code_expires_at)
         values ($1, $2, $3, now() + make_interval(mins => $4))
         returning id",
    )
    .bind(&email)
    .bind(&password_hash)
    .bind(token_hash(&code))
    .bind(VERIFY_CODE_MINUTES)
    .fetch_one(&state.db)
    .await?;
    let account_id: Uuid = row.get("id");

    // Hasta que exista proveedor de correo, el codigo sale en el log del servidor.
    tracing::info!("codigo de verificacion de {email}: {code}");

    Ok((
        StatusCode::CREATED,
        Json(json!({
            "account_id": account_id,
            "email_verified": false,
            "dev_code": if state.echo_verify_code { Some(code) } else { None },
        })),
    ))
}

#[derive(Deserialize)]
pub struct VerifyBody {
    pub email: String,
    pub code: String,
}

pub async fn verify_email(
    State(state): State<AppState>,
    Json(body): Json<VerifyBody>,
) -> Result<StatusCode, AppError> {
    let email = body.email.trim().to_lowercase();
    let updated = sqlx::query(
        "update accounts
         set email_verified = true, verify_code_hash = null, verify_code_expires_at = null
         where lower(email) = $1 and verify_code_hash = $2 and verify_code_expires_at > now()",
    )
    .bind(&email)
    .bind(token_hash(body.code.trim()))
    .execute(&state.db)
    .await?
    .rows_affected();

    if updated == 0 {
        return Err(AppError::BadRequest(
            "codigo incorrecto o caducado".to_string(),
        ));
    }
    Ok(StatusCode::NO_CONTENT)
}

#[derive(Deserialize)]
pub struct LoginBody {
    pub email: String,
    pub password: String,
}

pub async fn login(
    State(state): State<AppState>,
    Json(body): Json<LoginBody>,
) -> Result<Json<Value>, AppError> {
    let email = body.email.trim().to_lowercase();
    let row = sqlx::query(
        "select id, email, email_verified, password_hash from accounts where lower(email) = $1",
    )
    .bind(&email)
    .fetch_optional(&state.db)
    .await?
    .ok_or(AppError::Unauthorized)?;

    let stored: String = row.get("password_hash");
    if !verify_password(&body.password, &stored) {
        return Err(AppError::Unauthorized);
    }

    let account_id: Uuid = row.get("id");
    let token = create_session(&state, account_id, None, "web").await?;

    let identity = sqlx::query(
        "select mc_name, mc_uuid, kind, verified from identities
         where account_id = $1 order by created_at limit 1",
    )
    .bind(account_id)
    .fetch_optional(&state.db)
    .await?
    .map(|row| identity_dto(&row))
    .transpose()?;

    Ok(Json(json!({
        "token": token,
        "account": {
            "id": account_id,
            "email": row.get::<String, _>("email"),
            "email_verified": row.get::<bool, _>("email_verified"),
        },
        "identity": identity,
    })))
}

pub async fn logout(
    State(state): State<AppState>,
    account: AuthedAccount,
) -> Result<StatusCode, AppError> {
    sqlx::query("delete from sessions where token_hash = $1")
        .bind(&account.token_hash)
        .execute(&state.db)
        .await?;
    Ok(StatusCode::NO_CONTENT)
}

pub async fn me(
    State(state): State<AppState>,
    account: AuthedAccount,
) -> Result<Json<Value>, AppError> {
    let identities = sqlx::query(
        "select mc_name, mc_uuid, kind, verified from identities
         where account_id = $1 order by created_at",
    )
    .bind(account.account_id)
    .fetch_all(&state.db)
    .await?
    .iter()
    .map(identity_dto)
    .collect::<Result<Vec<_>, _>>()?;

    Ok(Json(json!({
        "account_id": account.account_id,
        "email_verified": account.email_verified,
        "identities": identities,
    })))
}

// ---------------------------------------------------------------------------
// Elegibilidad: que pantalla pinta el cliente
// ---------------------------------------------------------------------------

#[derive(Deserialize)]
pub struct EligibilityQuery {
    pub mc_name: String,
    pub mc_uuid: Option<Uuid>,
}

pub async fn eligibility(
    State(state): State<AppState>,
    Query(query): Query<EligibilityQuery>,
) -> Result<Json<Value>, AppError> {
    let mc_name = query.mc_name.trim().to_string();
    if !valid_mc_name(&mc_name) {
        return Err(AppError::BadRequest(
            "nombre de Minecraft no valido".to_string(),
        ));
    }

    // 1. Ya vinculado: manda el UUID, no el nombre.
    let lookup_uuid = query.mc_uuid.unwrap_or_else(|| offline_uuid(&mc_name));
    if let Some(row) = sqlx::query(
        "select mc_name, mc_uuid, kind, verified, claim_revoked from identities where mc_uuid = $1",
    )
    .bind(lookup_uuid)
    .fetch_optional(&state.db)
    .await?
    {
        let revoked: bool = row.get("claim_revoked");
        if !revoked {
            return Ok(Json(json!({
                "allowed": true,
                "kind": row.get::<String, _>("kind"),
                "reason": "linked",
                "identity": identity_dto(&row)?,
            })));
        }
    }

    // 2. El nombre pertenece a alguien.
    if let Some(row) = sqlx::query(
        "select c.premium, c.mc_name, i.mc_uuid, i.kind, i.verified, i.claim_revoked
         from name_claims c join identities i on i.id = c.identity_id
         where c.name_key = $1",
    )
    .bind(name_key(&mc_name))
    .fetch_optional(&state.db)
    .await?
    {
        let premium: bool = row.get("premium");
        let revoked: bool = row.get("claim_revoked");
        let reason = if premium {
            "name_reserved"
        } else if revoked {
            "name_released"
        } else {
            "name_taken"
        };
        return Ok(Json(json!({
            "allowed": false,
            "kind": if premium { "premium" } else { "offline" },
            "reason": reason,
            "identity": identity_dto(&row)?,
        })));
    }

    // 3. Nombre liberado: existio una identidad con ese nombre y su claim fue revocado
    //    (por ejemplo un offline al que un premium le quito el nombre). Se puede volver a vincular.
    let released = sqlx::query(
        "select 1 from identities where lower(mc_name) = $1 and claim_revoked limit 1",
    )
    .bind(name_key(&mc_name))
    .fetch_optional(&state.db)
    .await?
    .is_some();
    if released {
        return Ok(Json(json!({
            "allowed": false,
            "kind": "offline",
            "reason": "name_released",
            "offline_uuid": offline_uuid(&mc_name),
        })));
    }

    // 4. Nombre libre: necesita cuenta Nebula con correo verificado.
    Ok(Json(json!({
        "allowed": false,
        "kind": "offline",
        "reason": "needs_link",
        "offline_uuid": offline_uuid(&mc_name),
    })))
}

// ---------------------------------------------------------------------------
// Vinculacion por codigo de dispositivo (offline con cuenta Nebula)
// ---------------------------------------------------------------------------

#[derive(Deserialize)]
pub struct DeviceBody {
    pub mc_name: String,
}

pub async fn device_create(
    State(state): State<AppState>,
    Json(body): Json<DeviceBody>,
) -> Result<(StatusCode, Json<Value>), AppError> {
    let mc_name = body.mc_name.trim().to_string();
    if !valid_mc_name(&mc_name) {
        return Err(AppError::BadRequest(
            "nombre de Minecraft no valido".to_string(),
        ));
    }

    // Un nombre premium no se puede reclamar desde el flujo offline.
    let premium_claim = sqlx::query(
        "select 1 from name_claims where name_key = $1 and premium = true",
    )
    .bind(name_key(&mc_name))
    .fetch_optional(&state.db)
    .await?
    .is_some();
    if premium_claim {
        return Err(AppError::conflict(
            "name_reserved",
            format!("{mc_name} pertenece a una cuenta premium verificada"),
        ));
    }

    // Limpieza barata de codigos viejos.
    sqlx::query("delete from device_codes where expires_at < now() - interval '1 day'")
        .execute(&state.db)
        .await?;

    let code = new_device_code();
    sqlx::query(
        "insert into device_codes (code, mc_name, mc_uuid, expires_at)
         values ($1, $2, $3, now() + make_interval(mins => $4))",
    )
    .bind(&code)
    .bind(&mc_name)
    .bind(offline_uuid(&mc_name))
    .bind(DEVICE_CODE_MINUTES)
    .execute(&state.db)
    .await?;

    Ok((
        StatusCode::CREATED,
        Json(json!({
            "code": code,
            "verify_url": format!("{}?code={}", state.link_base_url, code),
            "expires_in": DEVICE_CODE_MINUTES * 60,
        })),
    ))
}

#[derive(Deserialize)]
pub struct ApproveBody {
    pub code: String,
}

/// La web aprueba el codigo con la cuenta Nebula (correo verificado).
pub async fn device_approve(
    State(state): State<AppState>,
    account: AuthedAccount,
    Json(body): Json<ApproveBody>,
) -> Result<Json<Value>, AppError> {
    if !account.email_verified {
        return Err(AppError::forbidden(
            "email_no_verificado",
            "verifica tu correo antes de vincular un jugador",
        ));
    }

    let code = body.code.trim().to_uppercase();
    let mut tx = state.db.begin().await?;

    let row = sqlx::query(
        "select mc_name, mc_uuid, identity_id, expires_at
         from device_codes where code = $1 for update",
    )
    .bind(&code)
    .fetch_optional(&mut *tx)
    .await?
    .ok_or(AppError::NotFound)?;

    let expires_at: DateTime<Utc> = row.get("expires_at");
    if expires_at < Utc::now() {
        return Err(AppError::Gone("code_expired"));
    }

    // Ya aprobado antes: idempotente.
    if let Some(identity_id) = row.get::<Option<Uuid>, _>("identity_id") {
        let identity = sqlx::query(
            "select mc_name, mc_uuid, kind, verified from identities where id = $1",
        )
        .bind(identity_id)
        .fetch_one(&mut *tx)
        .await?;
        return Ok(Json(json!({ "identity": identity_dto(&identity)? })));
    }

    let mc_name: String = row.get("mc_name");
    let mc_uuid: Uuid = row.get("mc_uuid");

    // Primer claim gana; el premium manda.
    if let Some(claim) = sqlx::query(
        "select c.premium, i.account_id from name_claims c
         join identities i on i.id = c.identity_id
         where c.name_key = $1",
    )
    .bind(name_key(&mc_name))
    .fetch_optional(&mut *tx)
    .await?
    {
        let premium: bool = claim.get("premium");
        let owner: Uuid = claim.get("account_id");
        if premium {
            return Err(AppError::conflict(
                "name_reserved",
                format!("{mc_name} pertenece a una cuenta premium verificada"),
            ));
        }
        if owner != account.account_id {
            return Err(AppError::conflict(
                "name_reserved",
                format!("el nombre {mc_name} ya esta vinculado a otra cuenta Nebula"),
            ));
        }
    }

    let identity_id: Uuid = match sqlx::query("select id, account_id from identities where mc_uuid = $1")
        .bind(mc_uuid)
        .fetch_optional(&mut *tx)
        .await?
    {
        Some(existing) => {
            let owner: Uuid = existing.get("account_id");
            if owner != account.account_id {
                return Err(AppError::conflict(
                    "already_linked",
                    "ese jugador ya esta vinculado a otra cuenta Nebula",
                ));
            }
            existing.get("id")
        }
        None => {
            let row = sqlx::query(
                "insert into identities (account_id, mc_uuid, mc_name, kind)
                 values ($1, $2, $3, 'offline') returning id",
            )
            .bind(account.account_id)
            .bind(mc_uuid)
            .bind(&mc_name)
            .fetch_one(&mut *tx)
            .await?;
            row.get("id")
        }
    };

    // Un jugador solo puede tener un nombre reclamado: si cambio de nick, el viejo se suelta.
    sqlx::query("delete from name_claims where identity_id = $1 and name_key <> $2")
        .bind(identity_id)
        .bind(name_key(&mc_name))
        .execute(&mut *tx)
        .await?;

    sqlx::query(
        "insert into name_claims (name_key, mc_name, identity_id)
         values ($1, $2, $3) on conflict (name_key) do nothing",
    )
    .bind(name_key(&mc_name))
    .bind(&mc_name)
    .bind(identity_id)
    .execute(&mut *tx)
    .await?;

    sqlx::query(
        "update device_codes set account_id = $1, identity_id = $2, approved_at = now()
         where code = $3",
    )
    .bind(account.account_id)
    .bind(identity_id)
    .bind(&code)
    .execute(&mut *tx)
    .await?;

    let identity = sqlx::query("select mc_name, mc_uuid, kind, verified from identities where id = $1")
        .bind(identity_id)
        .fetch_one(&mut *tx)
        .await?;
    let dto = identity_dto(&identity)?;

    tx.commit().await?;

    Ok(Json(json!({ "identity": dto })))
}

#[derive(Deserialize)]
pub struct TokenBody {
    pub code: String,
}

/// El juego recoge el token: 202 mientras nadie lo haya aprobado.
pub async fn device_token(
    State(state): State<AppState>,
    Json(body): Json<TokenBody>,
) -> Result<Response, AppError> {
    let code = body.code.trim().to_uppercase();
    let row = sqlx::query(
        "select account_id, identity_id, approved_at, consumed_at, expires_at
         from device_codes where code = $1",
    )
    .bind(&code)
    .fetch_optional(&state.db)
    .await?
    .ok_or(AppError::NotFound)?;

    if row.get::<Option<DateTime<Utc>>, _>("consumed_at").is_some() {
        return Err(AppError::Gone("code_used"));
    }
    let expires_at: DateTime<Utc> = row.get("expires_at");
    if expires_at < Utc::now() {
        return Err(AppError::Gone("code_expired"));
    }

    let (Some(account_id), Some(identity_id)) = (
        row.get::<Option<Uuid>, _>("account_id"),
        row.get::<Option<Uuid>, _>("identity_id"),
    ) else {
        return Ok((
            StatusCode::ACCEPTED,
            Json(json!({ "status": "pending" })),
        )
            .into_response());
    };

    let identity =
        sqlx::query("select mc_name, mc_uuid, kind, verified from identities where id = $1")
            .bind(identity_id)
            .fetch_one(&state.db)
            .await?;
    let dto = identity_dto(&identity)?;

    let token = create_session(&state, account_id, Some(identity_id), "game").await?;
    sqlx::query("update device_codes set consumed_at = now() where code = $1")
        .bind(&code)
        .execute(&state.db)
        .await?;

    Ok(Json(json!({ "token": token, "user": dto })).into_response())
}

// ---------------------------------------------------------------------------
// Vinculacion premium: proof firmado por el launcher
// ---------------------------------------------------------------------------

#[derive(Deserialize)]
pub struct PremiumBody {
    pub mc_uuid: Uuid,
    pub mc_name: String,
    /// Unix timestamp (segundos) del momento en que el launcher firmo.
    pub issued_at: i64,
    /// HMAC-SHA256 en hex de "{mc_uuid}|{mc_name}|{issued_at}".
    pub proof: String,
    /// Access token de Mojang/Microsoft que el launcher ya tiene. Si viene, manda sobre el
    /// proof: se le pregunta a Mojang de quien es. Nunca se registra en el log.
    #[serde(default)]
    pub mc_access_token: Option<String>,
}

#[derive(Deserialize)]
struct MojangProfile {
    /// UUID de 32 caracteres sin guiones.
    id: String,
    name: String,
}

/// Pregunta a Mojang a quien pertenece ese access token.
/// Devuelve el perfil (uuid + nombre reales), que es la prueba de propiedad.
async fn mojang_profile(access_token: &str) -> Result<MojangProfile, AppError> {
    let client = reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(6))
        .build()
        .map_err(|e| AppError::Internal(e.to_string()))?;

    let response = client
        .get("https://api.minecraftservices.com/minecraft/profile")
        .bearer_auth(access_token.trim())
        .send()
        .await
        .map_err(|e| {
            tracing::warn!("no se pudo contactar con Mojang: {e}");
            AppError::Internal("no se pudo verificar la cuenta con Mojang".to_string())
        })?;

    if response.status() == reqwest::StatusCode::UNAUTHORIZED {
        return Err(AppError::UnauthorizedWith("token_invalid"));
    }
    if !response.status().is_success() {
        tracing::warn!("Mojang respondio {}", response.status());
        return Err(AppError::Internal(
            "no se pudo verificar la cuenta con Mojang".to_string(),
        ));
    }

    response
        .json::<MojangProfile>()
        .await
        .map_err(|e| AppError::Internal(format!("respuesta de Mojang ilegible: {e}")))
}

pub async fn link_premium(
    State(state): State<AppState>,
    Json(body): Json<PremiumBody>,
) -> Result<Json<Value>, AppError> {
    let mc_name = body.mc_name.trim().to_string();
    if !valid_mc_name(&mc_name) {
        return Err(AppError::BadRequest(
            "nombre de Minecraft no valido".to_string(),
        ));
    }

    // Prueba de identidad, en orden de fuerza:
    //  1. access token de Mojang (el launcher ya tiene la sesion de Microsoft): se pregunta a
    //     Mojang de quien es ese token. No hay secreto compartido que robar ni suplantar.
    //  2. proof HMAC del launcher, como respaldo cuando no hay token.
    let canonical_name = match body
        .mc_access_token
        .as_deref()
        .map(str::trim)
        .filter(|token| !token.is_empty())
    {
        Some(access_token) => {
            let profile = mojang_profile(access_token).await?;
            let mojang_uuid = Uuid::parse_str(&profile.id)
                .map_err(|_| AppError::UnauthorizedWith("token_invalid"))?;
            if mojang_uuid != body.mc_uuid || !profile.name.eq_ignore_ascii_case(&mc_name) {
                tracing::warn!(
                    "el token de Mojang no coincide con el payload: token={}/{}, payload={}/{}",
                    profile.name,
                    mojang_uuid,
                    mc_name,
                    body.mc_uuid
                );
                return Err(AppError::UnauthorizedWith("token_mismatch"));
            }
            Some(profile.name)
        }
        None => {
            let secret = state.hmac_secret.as_deref().ok_or_else(|| {
                AppError::Internal(
                    "falta LAUNCHER_HMAC_SECRET y no vino mc_access_token".to_string(),
                )
            })?;

            let skew = (Utc::now().timestamp() - body.issued_at).abs();
            if skew > PREMIUM_PROOF_SKEW_SECONDS {
                tracing::warn!(
                    "proof premium fuera de ventana: issued_at={} desfase={}s",
                    body.issued_at,
                    skew
                );
                return Err(AppError::UnauthorizedWith("proof_expired"));
            }

            let payload = format!("{}|{}|{}", body.mc_uuid, mc_name, body.issued_at);
            let mut mac = Hmac::<Sha256>::new_from_slice(secret.as_bytes())
                .map_err(|e| AppError::Internal(e.to_string()))?;
            mac.update(payload.as_bytes());
            let proof = hex::decode(body.proof.trim())
                .map_err(|_| AppError::UnauthorizedWith("proof_invalid"))?;
            // Aqui llega tambien el caso "el launcher firmo con otro secreto". El log no guarda el proof.
            mac.verify_slice(&proof).map_err(|_| {
                tracing::warn!(
                    "proof premium con firma invalida para {} ({}): revisa que el launcher use el mismo LAUNCHER_HMAC_SECRET",
                    mc_name,
                    body.mc_uuid
                );
                AppError::UnauthorizedWith("proof_invalid")
            })?;
            None
        }
    };

    // Con token, el nombre canonico lo pone Mojang (evita diferencias de mayusculas).
    let mc_name = canonical_name.unwrap_or(mc_name);

    let mut tx = state.db.begin().await?;

    // El launcher no conoce cuentas Nebula (manda solo uuid, nombre, issued_at y proof),
    // asi que la cuenta se crea aqui la primera vez: sin email ni contrasena, solo para
    // agrupar identidades. Si luego el jugador quiere una cuenta con email, se le asocia.
    let existing = sqlx::query("select id, account_id from identities where mc_uuid = $1")
        .bind(body.mc_uuid)
        .fetch_optional(&mut *tx)
        .await?;

    let (identity_id, account_id): (Uuid, Uuid) = match existing {
        Some(row) => {
            let id: Uuid = row.get("id");
            let owner: Uuid = row.get("account_id");
            sqlx::query(
                "update identities
                 set mc_name = $2, kind = 'premium', verified = true, claim_revoked = false
                 where id = $1",
            )
            .bind(id)
            .bind(&mc_name)
            .execute(&mut *tx)
            .await?;
            (id, owner)
        }
        None => {
            let account = sqlx::query("insert into accounts default values returning id")
                .fetch_one(&mut *tx)
                .await?;
            let account_id: Uuid = account.get("id");
            let row = sqlx::query(
                "insert into identities (account_id, mc_uuid, mc_name, kind, verified)
                 values ($1, $2, $3, 'premium', true) returning id",
            )
            .bind(account_id)
            .bind(body.mc_uuid)
            .bind(&mc_name)
            .fetch_one(&mut *tx)
            .await?;
            (row.get("id"), account_id)
        }
    };

    // Si el nombre lo tenia reclamado un offline de otra identidad, su claim se revoca:
    // el premium manda y sus posts salen del feed publico (no se borran).
    sqlx::query(
        "update identities set claim_revoked = true
         where id = (select identity_id from name_claims where name_key = $1)
           and id <> $2",
    )
    .bind(name_key(&mc_name))
    .bind(identity_id)
    .execute(&mut *tx)
    .await?;

    // Un jugador solo puede tener un nombre reclamado: si cambio de nick, el viejo se suelta.
    sqlx::query("delete from name_claims where identity_id = $1 and name_key <> $2")
        .bind(identity_id)
        .bind(name_key(&mc_name))
        .execute(&mut *tx)
        .await?;

    sqlx::query(
        "insert into name_claims (name_key, mc_name, identity_id, premium)
         values ($1, $2, $3, true)
         on conflict (name_key) do update
         set identity_id = excluded.identity_id, mc_name = excluded.mc_name, premium = true",
    )
    .bind(name_key(&mc_name))
    .bind(&mc_name)
    .bind(identity_id)
    .execute(&mut *tx)
    .await?;

    tx.commit().await?;

    let identity =
        sqlx::query("select mc_name, mc_uuid, kind, verified from identities where id = $1")
            .bind(identity_id)
            .fetch_one(&state.db)
            .await?;
    let dto = identity_dto(&identity)?;

    let token = create_session(&state, account_id, Some(identity_id), "game").await?;

    Ok(Json(json!({ "token": token, "user": dto })))
}

#[cfg(test)]
mod tests {
    use super::offline_uuid;

    /// Valores reales de `UUID.nameUUIDFromBytes("OfflinePlayer:" + nombre)` en Java.
    /// Si esto cambia, las cuentas offline dejan de reconocerse: es la clave de la identidad.
    #[test]
    fn offline_uuid_matches_java() {
        assert_eq!(
            offline_uuid("Notch").to_string(),
            "b50ad385-829d-3141-a216-7e7d7539ba7f"
        );
        assert_eq!(
            offline_uuid("renee").to_string(),
            "6ba67cda-6ffc-3fac-8abf-76bb61071206"
        );
        assert_eq!(
            offline_uuid("Player_One").to_string(),
            "0e0c6371-8599-3a43-a117-30cb7e62c579"
        );
    }
}
