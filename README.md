# nebula-social

Backend de la red social in-game de NebulaPlus: Rust (axum + sqlx) sobre PostgreSQL.

El contrato de la API y el reparto con el cliente estan en
`../scratch/external_mods_out/SOCIAL_INGAME_PLAN.md`. Este README solo explica como levantarlo.

## 1. Base de datos (Neon)

1. Crea un proyecto en <https://neon.tech> (free tier) y copia la cadena de conexion.
2. Anade `?sslmode=require` si no viene ya.
3. `cp .env.example .env` y pega la cadena en `DATABASE_URL`.

No hay paso manual de migraciones: `sqlx::migrate!` aplica `migrations/0001_init.sql` al arrancar.

## 2. Arrancar

```bash
cargo run
# curl http://127.0.0.1:8080/health
# -> {"status":"ok","db":true}
```

Variables (`social/.env`):

| Variable | Para que |
|---|---|
| `DATABASE_URL` | Postgres de Neon, con `sslmode=require` |
| `BIND_ADDR` | Por defecto `127.0.0.1:8080` |
| `LINK_BASE_URL` | Pagina web donde se aprueba el codigo de vinculacion |
| `LAUNCHER_HMAC_SECRET` | Secreto con el launcher para firmar el proof premium |
| `DEV_ECHO_VERIFY_CODE` | `1` solo en desarrollo: el codigo de email vuelve en la respuesta |

## 3. Quien puede entrar

| Tipo | Prueba | Acceso |
|---|---|---|
| Premium (Microsoft/Mojang) | `POST /api/auth/link/premium` con proof HMAC del launcher | Si |
| Offline sin cuenta Nebula | ninguna posible | No (`403 cuenta_no_vinculada`) |
| Offline con cuenta Nebula | cuenta con email verificado + codigo de dispositivo | Si |

La clave siempre es el **UUID**, nunca el nombre. Un nombre de Minecraft pertenece a una sola
identidad en toda la red (`name_claims`): el claim premium manda y, si aparece, revoca el claim
offline (sus posts dejan de salir en el feed publico).

### Flujo offline con cuenta Nebula

```
POST /api/accounts                    { email, password }   -> codigo de verificacion (log/DEV)
POST /api/accounts/verify             { email, code }
POST /api/accounts/login              { email, password }   -> token web
POST /api/auth/device                 { mc_name }           -> { code, verify_url }
POST /api/auth/device/approve         { code }              (Bearer web, email verificado)
POST /api/auth/device/token           { code }              -> 202 pendiente | 200 { token, user }
```

### Flujo premium (launcher)

El launcher escribe `config/nebula/social_launch` con
`{ mc_uuid, mc_name, issued_at, proof, mc_access_token }` y el cliente lo manda a
`POST /api/auth/link/premium`. La API prueba la identidad en dos niveles:

1. **`mc_access_token`** (preferido): llama a `GET https://api.minecraftservices.com/minecraft/profile`
   con ese token y comprueba que el UUID y el nombre que devuelve Mojang coinciden con el payload.
   Es prueba de propiedad real: no hay secreto que robar ni suplantar. El nombre canonico pasa a ser
   el que devuelve Mojang.
2. **`proof`** (respaldo si no hay token): HMAC-SHA256 en hex de `"{mc_uuid}|{mc_name}|{issued_at}"`
   con `LAUNCHER_HMAC_SECRET`. Ojo: si el secreto viaja dentro de un launcher distribuido, deja de
   ser un secreto.

Codigos de error del 401: `token_invalid` (Mojang rechaza el token), `token_mismatch` (el token es de
otro jugador), `proof_invalid` (firma mal calculada / secreto distinto) y `proof_expired` (archivo de
mas de 5 minutos; regenerar en vez de reintentar).

**El `mc_access_token` es una credencial viva**: el cliente debe borrar el archivo en cuanto lo lee y
nunca escribirlo en logs. Esta API tampoco lo registra.

En el primer vinculo la cuenta se crea sin email ni contrasena (solo agrupa identidades); el jugador
puede tener luego cuenta con correo, pero ese flujo de "reclamar cuenta" esta pendiente. Si el nombre
estaba reclamado por un offline de otra identidad, su claim se revoca y sus posts salen del feed
publico (`claim_revoked`).

## 4. Pagina de vinculacion (`GET /link`)

La API sirve su propia pagina para aprobar el codigo de 6 caracteres (no hay frontend aparte):

```
http://127.0.0.1:8080/link?code=ABC123      (alias: /device)
```

Trae el codigo relleno desde el query, permite entrar con la cuenta Nebula o crear una (con
verificacion de correo) y al pulsar **Vincular a Minecraft** hace `POST /api/auth/device/approve`.
En cuanto aprueba, el juego recoge el token con su polling y entra solo. Toda la pagina es un unico
HTML sin dependencias dentro de `src/link_page.rs`; el token de sesion vive en memoria, no en
`localStorage`.

## 5. Prueba de humo

Con la API arrancada y `DEV_ECHO_VERIFY_CODE=1` en `.env`:

```bash
node e2e.mjs
```

Recorre el flujo completo (registro → verificación → código de dispositivo → token de juego → post →
feed → like → perfil → búsqueda → notificaciones → nombre duplicado → rate limit → borrado) y sale
con código 0 solo si pasan los 20 checks. Crea cuentas `e2e*@nebula.test` de usar y tirar.

## 6. Limitaciones conocidas

- Envio de correo **no implementado**: el codigo de verificacion sale en el log del servidor. Hay que
  conectar un proveedor (Resend, Postmark, SES) antes de abrir el registro al publico.
- El limitador (3 posts/min, 10 likes/min, 5 comentarios/min) vive en memoria: vale para una sola
  instancia. Con varias hace falta Redis o una tabla.
- Sin moderacion todavia: solo el autor puede borrar su post.
- `cargo check` no toca la base: sin un Postgres real no se probo ninguna query contra el servidor.
