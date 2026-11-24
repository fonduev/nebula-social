-- Jugadores conectados con Nebula Launcher / NebulaPlus.
-- Alimenta el badge estrella en nametags / Tab list y vincula UUID <-> perfil de cosmeticos.
CREATE TABLE IF NOT EXISTS nebula_players (
    uuid          TEXT PRIMARY KEY,
    username      TEXT NOT NULL,
    badge_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    last_seen     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS nebula_players_name_idx ON nebula_players (lower(username));
CREATE INDEX IF NOT EXISTS nebula_players_seen_idx ON nebula_players (last_seen DESC);
