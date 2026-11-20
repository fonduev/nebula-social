use axum::{
    http::{header, HeaderValue, StatusCode},
    response::{IntoResponse, Response},
    Json,
};
use serde_json::json;

/// Error de la API. Todo lo que devuelve el servidor pasa por aqui para que el
/// formato sea siempre `{ "error": "...", "code": "..." }` y no se filtre detalle
/// interno. El `code` es lo que usa el cliente para decidir que pantalla pintar
/// (por ejemplo `cuenta_no_vinculada`).
#[derive(Debug)]
pub enum AppError {
    BadRequest(String),
    Unauthorized,
    /// 401 con codigo: proof_expired, proof_invalid. Sirve para distinguir un desfase
    /// de reloj (relee el archivo) de una firma mal calculada (secreto distinto).
    UnauthorizedWith(&'static str),
    /// 403 con codigo: cuenta_no_vinculada, email_no_verificado.
    Forbidden(&'static str, String),
    NotFound,
    /// 409 con codigo: name_reserved, already_linked.
    Conflict(&'static str, String),
    /// 410: codigo de vinculacion caducado o ya usado.
    Gone(&'static str),
    /// 429 con segundos de espera.
    RateLimited(u64),
    Internal(String),
}

impl AppError {
    pub fn forbidden(code: &'static str, message: impl Into<String>) -> Self {
        AppError::Forbidden(code, message.into())
    }

    pub fn conflict(code: &'static str, message: impl Into<String>) -> Self {
        AppError::Conflict(code, message.into())
    }
}

impl IntoResponse for AppError {
    fn into_response(self) -> Response {
        let mut retry_after: Option<u64> = None;

        let (status, code, message) = match self {
            AppError::BadRequest(message) => (StatusCode::BAD_REQUEST, "bad_request", message),
            AppError::Unauthorized => (
                StatusCode::UNAUTHORIZED,
                "unauthorized",
                "no autenticado".to_string(),
            ),
            AppError::UnauthorizedWith(code) => (
                StatusCode::UNAUTHORIZED,
                code,
                "credencial invalida".to_string(),
            ),
            AppError::Forbidden(code, message) => (StatusCode::FORBIDDEN, code, message),
            AppError::NotFound => (
                StatusCode::NOT_FOUND,
                "not_found",
                "no encontrado".to_string(),
            ),
            AppError::Conflict(code, message) => (StatusCode::CONFLICT, code, message),
            AppError::Gone(code) => (
                StatusCode::GONE,
                code,
                "codigo caducado, genera uno nuevo".to_string(),
            ),
            AppError::RateLimited(wait) => {
                retry_after = Some(wait);
                (
                    StatusCode::TOO_MANY_REQUESTS,
                    "rate_limited",
                    format!("espera {wait} segundos"),
                )
            }
            AppError::Internal(message) => {
                tracing::error!("error interno: {message}");
                (
                    StatusCode::INTERNAL_SERVER_ERROR,
                    "internal",
                    "error interno".to_string(),
                )
            }
        };

        let mut response = (status, Json(json!({ "error": message, "code": code }))).into_response();
        response
            .headers_mut()
            .insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
        if let Some(wait) = retry_after {
            if let Ok(value) = HeaderValue::from_str(&wait.to_string()) {
                response.headers_mut().insert(header::RETRY_AFTER, value);
            }
        }
        response
    }
}

impl From<sqlx::Error> for AppError {
    fn from(err: sqlx::Error) -> Self {
        match &err {
            sqlx::Error::RowNotFound => AppError::NotFound,
            // 23505 = unique_violation
            sqlx::Error::Database(db) if db.code().as_deref() == Some("23505") => {
                AppError::Conflict("conflict", "ese valor ya esta en uso".to_string())
            }
            _ => AppError::Internal(err.to_string()),
        }
    }
}
