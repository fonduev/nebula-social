-- Nebula Social - esquema inicial (PostgreSQL 14+)
-- Se aplica automaticamente al arrancar la API (sqlx::migrate!).
--
-- Modelo de identidad:
--   accounts    -> cuenta Nebula (email + contrasena). Es la que prueba quien eres
--                  cuando eres offline.
--   identities  -> jugador de Minecraft (uuid + nombre). Solo dos tipos con acceso:
--                  premium (uuid de Mojang verificado) y offline (uuid v3 del nombre,
--                  vinculado por una cuenta Nebula con email verificado).
--   name_claims -> un nombre de MC pertenece a UNA identidad en toda la red.
--                  El claim premium manda sobre el offline.

-- gen_random_uuid() es nativo desde PostgreSQL 13: no hace falta pgcrypto.


create table accounts (
    id                    uuid primary key default gen_random_uuid(),
    email                 text not null,
    password_hash         text not null,
    email_verified        boolean not null default false,
    -- sha256 del codigo de 6 digitos que se envia por correo (nunca en claro).
    verify_code_hash      text,
    verify_code_expires_at timestamptz,
    created_at            timestamptz not null default now()
);

create unique index accounts_email_lower_idx on accounts (lower(email));

create table identities (
    id            uuid primary key default gen_random_uuid(),
    account_id    uuid not null references accounts(id) on delete cascade,
    mc_uuid       uuid not null,
    mc_name       text not null,
    kind          text not null check (kind in ('premium', 'offline')),
    verified      boolean not null default false,
    bio           text not null default '',
    -- true cuando un premium verifico el nombre y este claim offline fue revocado.
    -- Sus posts dejan de mostrarse en el feed publico.
    claim_revoked boolean not null default false,
    created_at    timestamptz not null default now()
);

create unique index identities_uuid_idx on identities (mc_uuid);
create index identities_account_idx on identities (account_id);
create index identities_name_idx on identities (lower(mc_name));

create table name_claims (
    name_key    text primary key,
    mc_name     text not null,
    identity_id uuid not null unique references identities(id) on delete cascade,
    premium     boolean not null default false,
    claimed_at  timestamptz not null default now()
);

create table sessions (
    token_hash  text primary key,
    account_id  uuid not null references accounts(id) on delete cascade,
    -- null = sesion web sin jugador vinculado todavia.
    identity_id uuid references identities(id) on delete cascade,
    scope       text not null check (scope in ('web', 'game')),
    created_at  timestamptz not null default now(),
    expires_at  timestamptz not null
);

create index sessions_account_idx on sessions (account_id);
create index sessions_identity_idx on sessions (identity_id);

-- Codigo de dispositivo: lo crea el juego, lo aprueba la web con la cuenta Nebula.
create table device_codes (
    code        text primary key,
    mc_name     text not null,
    mc_uuid     uuid not null,
    account_id  uuid references accounts(id) on delete cascade,
    identity_id uuid references identities(id) on delete set null,
    approved_at timestamptz,
    consumed_at timestamptz,
    expires_at  timestamptz not null,
    created_at  timestamptz not null default now()
);

create index device_codes_uuid_idx on device_codes (mc_uuid);

create table posts (
    id         uuid primary key default gen_random_uuid(),
    author_id  uuid not null references identities(id) on delete cascade,
    body       text not null check (char_length(body) between 1 and 280),
    created_at timestamptz not null default now(),
    search     tsvector generated always as (to_tsvector('simple', body)) stored
);

create index posts_created_idx on posts (created_at desc, id desc);
create index posts_author_created_idx on posts (author_id, created_at desc);
create index posts_search_idx on posts using gin (search);

create table follows (
    follower_id uuid not null references identities(id) on delete cascade,
    followee_id uuid not null references identities(id) on delete cascade,
    created_at  timestamptz not null default now(),
    primary key (follower_id, followee_id),
    check (follower_id <> followee_id)
);

create index follows_followee_idx on follows (followee_id);

create table likes (
    identity_id uuid not null references identities(id) on delete cascade,
    post_id     uuid not null references posts(id) on delete cascade,
    created_at  timestamptz not null default now(),
    primary key (identity_id, post_id)
);

create index likes_post_idx on likes (post_id);

create table comments (
    id         uuid primary key default gen_random_uuid(),
    post_id    uuid not null references posts(id) on delete cascade,
    author_id  uuid not null references identities(id) on delete cascade,
    body       text not null check (char_length(body) between 1 and 300),
    created_at timestamptz not null default now()
);

create index comments_post_idx on comments (post_id, created_at);

create table notifications (
    id           uuid primary key default gen_random_uuid(),
    recipient_id uuid not null references identities(id) on delete cascade,
    actor_id     uuid not null references identities(id) on delete cascade,
    kind         text not null check (kind in ('like', 'comment', 'follow')),
    post_id      uuid references posts(id) on delete cascade,
    created_at   timestamptz not null default now(),
    read_at      timestamptz
);

create index notifications_recipient_idx on notifications (recipient_id, created_at desc);
