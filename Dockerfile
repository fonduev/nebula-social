# Imagen de despliegue de nebula-social: API axum + sqlx contra Postgres (Neon).
# No necesita `DATABASE_URL` para compilar: no se usan macros de sqlx.

# Etapa de build: imagen completa (incluye gcc/ld, que la variante slim no siempre trae).
FROM rust:1-bookworm AS build
WORKDIR /app
COPY Cargo.toml Cargo.lock ./
COPY migrations ./migrations
COPY src ./src
# El builder de Render tiene RAM limitada: compilar en paralelo lo tumba.
ENV CARGO_BUILD_JOBS=2
RUN cargo build --release --locked

FROM debian:bookworm-slim
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /app/target/release/nebula-social /usr/local/bin/nebula-social
# El host inyecta PORT; main.rs escucha en 0.0.0.0:PORT si BIND_ADDR viene vacio.
ENV RUST_LOG=info
EXPOSE 8080
CMD ["nebula-social"]
