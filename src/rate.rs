use std::{
    collections::{HashMap, VecDeque},
    sync::Mutex,
    time::{Duration, Instant},
};

use uuid::Uuid;

/// Limitador de ventana deslizante por identidad **y accion**, en memoria.
///
/// La accion forma parte de la clave: un like no puede gastar la cuota de posts.
/// Sirve para una sola instancia de la API (que es como se arranca hoy). Si algun
/// dia hay varias instancias detras de un balanceador, esto se mueve a Redis o a
/// una tabla de Postgres: el contrato con el cliente no cambia (429 + Retry-After).
pub struct RateLimiter {
    buckets: Mutex<HashMap<(Uuid, &'static str), VecDeque<Instant>>>,
}

impl RateLimiter {
    pub fn new() -> Self {
        Self {
            buckets: Mutex::new(HashMap::new()),
        }
    }

    /// `Ok(())` si la accion entra en la ventana; `Err(segundos)` si hay que esperar.
    pub fn check(
        &self,
        identity: Uuid,
        action: &'static str,
        max: usize,
        window: Duration,
    ) -> Result<(), u64> {
        let key = (identity, action);
        let now = Instant::now();
        let mut buckets = match self.buckets.lock() {
            Ok(buckets) => buckets,
            // Si un hilo quedo envenenado, preferimos no tumbar la API.
            Err(poisoned) => poisoned.into_inner(),
        };

        let bucket = buckets.entry(key).or_default();
        while let Some(front) = bucket.front().copied() {
            if now.duration_since(front) > window {
                bucket.pop_front();
            } else {
                break;
            }
        }

        if bucket.len() >= max {
            let oldest = bucket.front().copied().unwrap_or(now);
            let wait = window.saturating_sub(now.duration_since(oldest)).as_secs();
            return Err(wait.max(1));
        }

        bucket.push_back(now);

        // Poda barata: si se acumulan muchas identidades, limpiamos las vacias.
        if buckets.len() > 4096 {
            buckets.retain(|_, queue| !queue.is_empty());
        }

        Ok(())
    }
}
