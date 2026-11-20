-- El launcher premium no conoce cuentas Nebula: manda solo { mc_uuid, mc_name, issued_at, proof }.
-- La cuenta se crea en la API sin email ni contrasena; su prueba de identidad es el proof
-- HMAC del launcher, no un login web. Si mas adelante el jugador quiere cuenta con email,
-- se le asocia (queda pendiente el flujo de "reclamar cuenta" desde la web).
alter table accounts alter column email drop not null;
alter table accounts alter column password_hash drop not null;
