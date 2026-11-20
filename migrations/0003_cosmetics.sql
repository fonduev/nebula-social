-- Lo que cada jugador lleva puesto en el wardrobe.
-- Se guarda como JSON (`{"hat": 10001, "cape": 10042}`) para no crear una fila por slot.
CREATE TABLE IF NOT EXISTS cosmetic_equipped (
    profile TEXT PRIMARY KEY,
    equipped TEXT NOT NULL DEFAULT '{}',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
