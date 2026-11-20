mod auth;
mod cosmetics;
mod error;
mod link_page;
mod oneclient;
mod rate;
mod social;

use std::{net::SocketAddr, sync::Arc};

use axum::{
    routing::{delete, get, post},
    Router,
};
use sqlx::postgres::PgPoolOptions;
use tower_http::{cors::CorsLayer, trace::TraceLayer};

use crate::rate::RateLimiter;

#[derive(Clone)]
pub struct AppState {
    pub db: sqlx::PgPool,
    /// Limitador en memoria: una instancia. Ver `rate.rs`.
    pub limiter: Arc<RateLimiter>,
    /// Pagina web donde se aprueba el codigo de vinculacion.
    pub link_base_url: Arc<String>,
    /// Secreto compartido con el launcher para firmar el proof premium.
    /// Si falta, `/api/auth/link/premium` responde 500 y el resto sigue vivo.
    pub hmac_secret: Option<Arc<String>>,
    /// Solo para desarrollo: devuelve el codigo de verificacion en la respuesta.
    pub echo_verify_code: bool,
    pub oneclient: oneclient::OneClientStore,
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    dotenvy::dotenv().ok();
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "info,tower_http=info".into()),
        )
        .init();

    let database_url = std::env::var("DATABASE_URL")
        .map_err(|_| anyhow::anyhow!("falta DATABASE_URL (copia social/.env.example a social/.env)"))?;
    let max_connections: u32 = std::env::var("DB_MAX_CONNECTIONS")
        .ok()
        .and_then(|value| value.parse().ok())
        .unwrap_or(10);

    let db = PgPoolOptions::new()
        .max_connections(max_connections)
        .connect(&database_url)
        .await?;

    // El esquema se aplica al arrancar: no hay paso manual de migraciones.
    sqlx::migrate!("./migrations").run(&db).await?;

    // La propia API sirve la pagina de vinculacion, asi que el default apunta aqui.
    // En un host publico se deduce del dominio que asigna el propio host (Render).
    let link_base_url = std::env::var("LINK_BASE_URL")
        .ok()
        .map(|value| value.trim().to_string())
        .filter(|value| !value.is_empty())
        .or_else(|| {
            std::env::var("RENDER_EXTERNAL_URL")
                .ok()
                .map(|base| format!("{}/link", base.trim_end_matches('/')))
        })
        .unwrap_or_else(|| "http://127.0.0.1:8080/link".to_string());
    let hmac_secret = std::env::var("LAUNCHER_HMAC_SECRET")
        .ok()
        .filter(|secret| !secret.trim().is_empty())
        .map(Arc::new);
    if hmac_secret.is_none() {
        tracing::warn!(
            "LAUNCHER_HMAC_SECRET sin configurar: la vinculacion premium por launcher queda apagada"
        );
    }
    let echo_verify_code = std::env::var("DEV_ECHO_VERIFY_CODE")
        .map(|value| value == "1" || value.eq_ignore_ascii_case("true"))
        .unwrap_or(false);

    let state = AppState {
        db,
        limiter: Arc::new(RateLimiter::new()),
        link_base_url: Arc::new(link_base_url),
        hmac_secret,
        echo_verify_code,
        oneclient: oneclient::OneClientStore::default(),
    };

    let app = Router::new()
        .merge(oneclient::router())
        .merge(cosmetics::router())
        .route("/health", get(social::health))
        // Pagina web para aprobar el codigo de vinculacion (la unica superficie web)
        .route("/link", get(link_page::link_page))
        .route("/device", get(link_page::link_page))
        // Cuenta Nebula (web)
        .route("/api/accounts", post(auth::register))
        .route("/api/accounts/verify", post(auth::verify_email))
        .route("/api/accounts/login", post(auth::login))
        // Vinculacion
        .route("/api/auth/device", post(auth::device_create))
        .route("/api/auth/device/approve", post(auth::device_approve))
        .route("/api/auth/device/token", post(auth::device_token))
        .route("/api/auth/link/premium", post(auth::link_premium))
        .route("/api/auth/eligibility", get(auth::eligibility))
        .route("/api/auth/logout", post(auth::logout))
        .route("/api/me", get(auth::me))
        // Social
        .route("/api/feed", get(social::feed))
        .route("/api/posts", post(social::create_post))
        .route("/api/posts/{id}", get(social::get_post))
        .route("/api/posts/{id}", delete(social::delete_post))
        .route("/api/posts/{id}/like", post(social::like))
        .route("/api/posts/{id}/like", delete(social::unlike))
        .route("/api/posts/{id}/comments", get(social::list_comments))
        .route("/api/posts/{id}/comments", post(social::create_comment))
        .route("/api/users/{username}", get(social::profile))
        .route("/api/users/{username}/follow", post(social::follow))
        .route("/api/users/{username}/follow", delete(social::unfollow))
        .route("/api/search", get(social::search))
        .route("/api/notifications", get(social::notifications))
        .layer(TraceLayer::new_for_http())
        .layer(CorsLayer::permissive())
        .with_state(state);

    // En un host gestionado el puerto llega en PORT y hay que escuchar en 0.0.0.0,
    // o el health check del host nunca alcanza la API.
    let bind = std::env::var("BIND_ADDR")
        .ok()
        .filter(|value| !value.trim().is_empty())
        .or_else(|| {
            std::env::var("PORT")
                .ok()
                .map(|port| format!("0.0.0.0:{}", port.trim()))
        })
        .unwrap_or_else(|| "127.0.0.1:8080".to_string());
    let addr: SocketAddr = bind.parse()?;

    let listener = tokio::net::TcpListener::bind(addr).await?;
    tracing::info!("nebula-social escuchando en http://{addr}");
    axum::serve(listener, app).await?;

    Ok(())
}
