//! Catalogo de cosmeticos del wardrobe.
//!
//! El cliente del mod pide tres cosas a esta API:
//!   GET  /cosmetics          -> grupos con sus variantes (lo que lista el wardrobe)
//!   GET  /cosmetics/player   -> lo que el jugador tiene y lleva puesto
//!   PUT  /cosmetics/player   -> guarda lo equipado
//! y descarga cada cosmetic como un zip (`url`) con el `.geo.json`, la animacion y el `.png`.
//!
//! Los assets viven en `assets/cosmetics` (los genera `tools/wardrobe/GenerateWardrobeAssets.java`):
//! un `catalog.json` con una entrada por cosmetic y las texturas ya coloreadas por paleta.

use std::{
    collections::HashMap,
    path::{Path as FsPath, PathBuf},
    sync::OnceLock,
};

use axum::{
    extract::{Path, State},
    http::{header, HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    routing::{get, put},
    Json, Router,
};
use serde::Deserialize;
use serde_json::{json, Map, Value};
use sha2::{Digest, Sha256};

use crate::AppState;

#[derive(Deserialize, Clone)]
struct CatalogEntry {
    slug: String,
    #[allow(dead_code)]
    base: String,
    name: String,
    #[serde(rename = "type")]
    kind: String,
    slots: Vec<String>,
    model: Option<String>,
    animation: Option<String>,
    texture: String,
}

#[derive(Deserialize)]
struct CatalogFile {
    items: Vec<CatalogEntry>,
}

struct Item {
    id: u32,
    kind: String,
    name: String,
    slots: Vec<String>,
    /// El zip que descarga el cliente (geometria + animacion + textura).
    archive: Vec<u8>,
    hash: String,
    /// Textura suelta, para la miniatura (`cover_url`).
    cover: Vec<u8>,
}

#[derive(Default)]
struct Catalog {
    items: Vec<Item>,
    by_id: HashMap<u32, usize>,
}

const FIRST_ID: u32 = 10_000;

fn catalog_dir() -> PathBuf {
    std::env::var("COSMETICS_DIR")
        .ok()
        .map(|value| value.trim().to_string())
        .filter(|value| !value.is_empty())
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("assets/cosmetics"))
}

fn catalog() -> &'static Catalog {
    static CATALOG: OnceLock<Catalog> = OnceLock::new();
    CATALOG.get_or_init(|| {
        let catalog = load_catalog(&catalog_dir());
        tracing::info!(
            "wardrobe: {} cosmeticos cargados desde {}",
            catalog.items.len(),
            catalog_dir().display()
        );
        catalog
    })
}

fn load_catalog(root: &FsPath) -> Catalog {
    let raw = match std::fs::read(root.join("catalog.json")) {
        Ok(bytes) => bytes,
        Err(error) => {
            tracing::warn!(
                "wardrobe: no se pudo leer {}: {error}",
                root.join("catalog.json").display()
            );
            return Catalog::default();
        }
    };
    let parsed: CatalogFile = match serde_json::from_slice(&raw) {
        Ok(parsed) => parsed,
        Err(error) => {
            tracing::error!("wardrobe: catalog.json invalido: {error}");
            return Catalog::default();
        }
    };

    let mut catalog = Catalog::default();
    for entry in &parsed.items {
        let cover = match std::fs::read(root.join(&entry.texture)) {
            Ok(bytes) => bytes,
            Err(error) => {
                tracing::warn!(
                    "wardrobe: falta la textura {}: {error}",
                    root.join(&entry.texture).display()
                );
                continue;
            }
        };

        let mut files: Vec<(String, Vec<u8>)> = Vec::with_capacity(4);
        if let Some(model) = &entry.model {
            match std::fs::read(root.join(model)) {
                Ok(bytes) => files.push((format!("{}.geo.json", entry.slug), bytes)),
                Err(error) => {
                    tracing::warn!(
                        "wardrobe: falta el modelo {}: {error}",
                        root.join(model).display()
                    );
                    continue;
                }
            }
        }
        if let Some(animation) = &entry.animation {
            if let Ok(bytes) = std::fs::read(root.join(animation)) {
                files.push((format!("{}.animation.json", entry.slug), bytes));
            }
        }
        if entry.kind == "pet" {
            // El cliente resuelve las mascotas con un manifiesto; "shoulder" las ancla al cuerpo.
            files.push((
                "pet.json".to_string(),
                br#"{"archetype":"shoulder","scale":1.0}"#.to_vec(),
            ));
        }
        files.push((format!("{}.png", entry.slug), cover.clone()));

        let archive = zip_stored(&files);
        let hash = hex::encode(Sha256::digest(&archive));

        let id = FIRST_ID + catalog.items.len() as u32;
        catalog.by_id.insert(id, catalog.items.len());
        catalog.items.push(Item {
            id,
            kind: entry.kind.clone(),
            name: entry.name.clone(),
            slots: entry.slots.clone(),
            archive,
            hash,
            cover,
        });
    }
    catalog
}

/// Toma el esquema y el host reales de la peticion (detras del proxy del host el
/// original llega en `x-forwarded-proto`), asi las URLs del catalogo no dependen de
/// una variable de entorno que se pueda quedar desactualizada.
fn request_origin(headers: &HeaderMap) -> String {
    let proto = headers
        .get("x-forwarded-proto")
        .and_then(|value| value.to_str().ok())
        .map(|value| value.split(',').next().unwrap_or("https").trim().to_string())
        .filter(|value| !value.is_empty())
        .unwrap_or_else(|| "http".to_string());
    let host = headers
        .get(header::HOST)
        .and_then(|value| value.to_str().ok())
        .unwrap_or("127.0.0.1:8080");
    format!("{proto}://{host}")
}

fn group_json(item: &Item, origin: &str) -> Value {
    json!({
        "id": item.id,
        "type": item.kind,
        "name": item.name,
        "allowed_slots": item.slots,
        "variants": [{
            "id": item.id,
            "name": item.name,
            "model": null,
            "allowed_slots": item.slots,
            "url": format!("{origin}/cosmetics/assets/{}.zip", item.id),
            "hash": item.hash,
            "cover_url": format!("{origin}/cosmetics/cover/{}.png", item.id),
        }],
    })
}

fn groups_json(origin: &str) -> Vec<Value> {
    catalog()
        .items
        .iter()
        .map(|item| group_json(item, origin))
        .collect()
}

async fn list_catalog(headers: HeaderMap) -> Response {
    let origin = request_origin(&headers);
    Json(json!({ "cosmetics": groups_json(&origin) })).into_response()
}

async fn view_cosmetic(Path(id): Path<u32>, headers: HeaderMap) -> Response {
    let origin = request_origin(&headers);
    match catalog()
        .by_id
        .get(&id)
        .and_then(|index| catalog().items.get(*index))
    {
        Some(item) => Json(group_json(item, &origin)).into_response(),
        None => (StatusCode::NOT_FOUND, "cosmetico desconocido").into_response(),
    }
}

async fn cosmetic_archive(Path(file): Path<String>) -> Response {
    let id: u32 = file.trim_end_matches(".zip").parse().unwrap_or(0);
    match catalog()
        .by_id
        .get(&id)
        .and_then(|index| catalog().items.get(*index))
    {
        Some(item) => (
            [
                (header::CONTENT_TYPE, "application/zip"),
                (header::CACHE_CONTROL, "public, max-age=86400"),
            ],
            item.archive.clone(),
        )
            .into_response(),
        None => (StatusCode::NOT_FOUND, "cosmetico desconocido").into_response(),
    }
}

async fn cosmetic_cover(Path(file): Path<String>) -> Response {
    let id: u32 = file.trim_end_matches(".png").parse().unwrap_or(0);
    match catalog()
        .by_id
        .get(&id)
        .and_then(|index| catalog().items.get(*index))
    {
        Some(item) => (
            [
                (header::CONTENT_TYPE, "image/png"),
                (header::CACHE_CONTROL, "public, max-age=86400"),
            ],
            item.cover.clone(),
        )
            .into_response(),
        None => (StatusCode::NOT_FOUND, "cosmetico desconocido").into_response(),
    }
}

/// El token que emite `/account/login`: `nebula.<hex del nombre>`. No da permisos,
/// solo identifica al jugador para guardar lo que lleva puesto.
fn profile_key(headers: &HeaderMap) -> String {
    let token = headers
        .get(header::AUTHORIZATION)
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.strip_prefix("Bearer ").or_else(|| value.strip_prefix("bearer ")))
        .map(str::trim)
        .unwrap_or_default();
    let Some(encoded) = token.strip_prefix("nebula.") else {
        return "default".to_string();
    };
    match hex::decode(encoded) {
        Ok(bytes) => String::from_utf8(bytes)
            .ok()
            .filter(|name| !name.is_empty())
            .unwrap_or_else(|| "default".to_string()),
        Err(_) => "default".to_string(),
    }
}

async fn load_equipped(state: &AppState, profile: &str) -> Value {
    let row = sqlx::query_as::<_, (String,)>(
        "SELECT equipped FROM cosmetic_equipped WHERE profile = $1",
    )
    .bind(profile)
    .fetch_optional(&state.db)
    .await;
    match row {
        Ok(Some((text,))) => serde_json::from_str(&text).unwrap_or_else(|_| json!({})),
        Ok(None) => json!({}),
        Err(error) => {
            tracing::warn!("wardrobe: no se pudo leer lo equipado: {error}");
            json!({})
        }
    }
}

async fn player_cosmetics(State(state): State<AppState>, headers: HeaderMap) -> Response {
    let origin = request_origin(&headers);
    let profile = profile_key(&headers);
    let equipped = load_equipped(&state, &profile).await;
    // Todo el catalogo esta disponible: no hay tienda ni compras.
    Json(json!({
        "cosmetics": groups_json(&origin),
        "emotes": [],
        "equipped": equipped,
        "particle_color": null,
    }))
    .into_response()
}

async fn set_player_cosmetics(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(body): Json<Value>,
) -> Response {
    let catalog = catalog();
    let mut equipped = Map::new();
    if let Some(entries) = body.get("equipped").and_then(Value::as_object) {
        for (slot, value) in entries {
            if value.is_null() {
                continue;
            }
            let Some(id) = value.as_u64() else {
                continue;
            };
            if !catalog.by_id.contains_key(&(id as u32)) {
                return (StatusCode::BAD_REQUEST, "cosmetico desconocido").into_response();
            }
            equipped.insert(slot.clone(), Value::from(id));
        }
    }

    let profile = profile_key(&headers);
    let text = Value::Object(equipped.clone()).to_string();
    let result = sqlx::query(
        "INSERT INTO cosmetic_equipped (profile, equipped, updated_at) VALUES ($1, $2, now()) \
         ON CONFLICT (profile) DO UPDATE SET equipped = EXCLUDED.equipped, updated_at = now()",
    )
    .bind(&profile)
    .bind(&text)
    .execute(&state.db)
    .await;
    if let Err(error) = result {
        tracing::error!("wardrobe: no se pudo guardar lo equipado: {error}");
        return (StatusCode::INTERNAL_SERVER_ERROR, "no se pudo guardar").into_response();
    }

    Json(json!({ "equipped": equipped })).into_response()
}

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/cosmetics", get(list_catalog))
        .route("/cosmetics/view/{id}", get(view_cosmetic))
        .route("/cosmetics/player", get(player_cosmetics))
        .route("/cosmetics/player", put(set_player_cosmetics))
        .route("/cosmetics/assets/{file}", get(cosmetic_archive))
        .route("/cosmetics/cover/{file}", get(cosmetic_cover))
}

/// ZIP sin compresion (metodo "stored"): `ZipInputStream` del cliente lo lee igual y
/// no hace falta arrastrar un compresor al binario.
fn zip_stored(files: &[(String, Vec<u8>)]) -> Vec<u8> {
    let mut out: Vec<u8> = Vec::new();
    let mut central: Vec<u8> = Vec::new();
    let mut offset: u32 = 0;

    for (name, data) in files {
        let crc = crc32(data);
        let name_bytes = name.as_bytes();
        let size = data.len() as u32;

        out.extend_from_slice(&0x0403_4b50u32.to_le_bytes());
        out.extend_from_slice(&20u16.to_le_bytes()); // version necesaria
        out.extend_from_slice(&0u16.to_le_bytes()); // flags
        out.extend_from_slice(&0u16.to_le_bytes()); // metodo: stored
        out.extend_from_slice(&0u16.to_le_bytes()); // hora
        out.extend_from_slice(&33u16.to_le_bytes()); // fecha (1980-01-01)
        out.extend_from_slice(&crc.to_le_bytes());
        out.extend_from_slice(&size.to_le_bytes());
        out.extend_from_slice(&size.to_le_bytes());
        out.extend_from_slice(&(name_bytes.len() as u16).to_le_bytes());
        out.extend_from_slice(&0u16.to_le_bytes()); // extra
        out.extend_from_slice(name_bytes);
        out.extend_from_slice(data);

        central.extend_from_slice(&0x0201_4b50u32.to_le_bytes());
        central.extend_from_slice(&20u16.to_le_bytes()); // version que lo creo
        central.extend_from_slice(&20u16.to_le_bytes()); // version necesaria
        central.extend_from_slice(&0u16.to_le_bytes());
        central.extend_from_slice(&0u16.to_le_bytes());
        central.extend_from_slice(&0u16.to_le_bytes());
        central.extend_from_slice(&33u16.to_le_bytes());
        central.extend_from_slice(&crc.to_le_bytes());
        central.extend_from_slice(&size.to_le_bytes());
        central.extend_from_slice(&size.to_le_bytes());
        central.extend_from_slice(&(name_bytes.len() as u16).to_le_bytes());
        central.extend_from_slice(&0u16.to_le_bytes()); // extra
        central.extend_from_slice(&0u16.to_le_bytes()); // comentario
        central.extend_from_slice(&0u16.to_le_bytes()); // disco
        central.extend_from_slice(&0u16.to_le_bytes()); // atributos internos
        central.extend_from_slice(&0u32.to_le_bytes()); // atributos externos
        central.extend_from_slice(&offset.to_le_bytes());
        central.extend_from_slice(name_bytes);

        offset += 30 + name_bytes.len() as u32 + size;
    }

    let central_offset = out.len() as u32;
    out.extend_from_slice(&central);
    let central_size = out.len() as u32 - central_offset;

    out.extend_from_slice(&0x0605_4b50u32.to_le_bytes());
    out.extend_from_slice(&0u16.to_le_bytes()); // disco
    out.extend_from_slice(&0u16.to_le_bytes()); // disco del indice
    out.extend_from_slice(&(files.len() as u16).to_le_bytes());
    out.extend_from_slice(&(files.len() as u16).to_le_bytes());
    out.extend_from_slice(&central_size.to_le_bytes());
    out.extend_from_slice(&central_offset.to_le_bytes());
    out.extend_from_slice(&0u16.to_le_bytes()); // comentario
    out
}

fn crc32(data: &[u8]) -> u32 {
    static TABLE: OnceLock<[u32; 256]> = OnceLock::new();
    let table = TABLE.get_or_init(|| {
        let mut table = [0u32; 256];
        for (index, slot) in table.iter_mut().enumerate() {
            let mut value = index as u32;
            for _ in 0..8 {
                value = if value & 1 == 1 {
                    0xEDB8_8320 ^ (value >> 1)
                } else {
                    value >> 1
                };
            }
            *slot = value;
        }
        table
    });

    let mut crc = 0xFFFF_FFFFu32;
    for byte in data {
        crc = table[((crc ^ *byte as u32) & 0xFF) as usize] ^ (crc >> 8);
    }
    crc ^ 0xFFFF_FFFF
}
