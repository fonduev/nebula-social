use axum::{
    http::header,
    response::{Html, IntoResponse},
};

/// Página de vinculación: sirve para aprobar el código de 6 caracteres que muestra el juego.
/// Todo va en un solo HTML sin dependencias: es la única superficie web del proyecto.
pub async fn link_page() -> impl IntoResponse {
    ([(header::CACHE_CONTROL, "no-store")], Html(PAGE))
}

const PAGE: &str = r##"<!doctype html>
<html lang="es">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1" />
<title>Nebula Social - vincular jugador</title>
<style>
  :root { color-scheme: dark; }
  * { box-sizing: border-box; }
  body {
    margin: 0; min-height: 100vh; display: flex; align-items: center; justify-content: center;
    padding: 24px; background: radial-gradient(1200px 600px at 50% -10%, #1b1533 0%, #0a0a0f 60%);
    color: #e8e8f2; font: 15px/1.5 system-ui, -apple-system, "Segoe UI", sans-serif;
  }
  .wrap { width: 100%; max-width: 460px; }
  h1 { font-size: 22px; margin: 0 0 4px; letter-spacing: .2px; }
  h1 span { color: #a78bfa; }
  .sub { margin: 0 0 18px; color: #9a9ab4; font-size: 13px; }
  .card {
    background: #12121a; border: 1px solid #23233a; border-radius: 14px; padding: 20px;
    box-shadow: 0 20px 50px rgba(0,0,0,.45);
  }
  label { display: block; font-size: 12px; text-transform: uppercase; letter-spacing: .6px; color: #8b8ba7; margin: 14px 0 6px; }
  label:first-of-type { margin-top: 0; }
  input {
    width: 100%; padding: 11px 12px; border-radius: 9px; border: 1px solid #2a2a42;
    background: #0c0c14; color: #e8e8f2; font-size: 15px; outline: none;
  }
  input:focus { border-color: #7c5cff; box-shadow: 0 0 0 3px rgba(124,92,255,.18); }
  #code { text-transform: uppercase; letter-spacing: 6px; font-size: 20px; text-align: center; font-weight: 600; }
  .hint { margin: 8px 0 0; color: #7e7e99; font-size: 12px; }
  button {
    width: 100%; margin-top: 16px; padding: 11px 14px; border-radius: 9px; border: 0;
    font-size: 14px; font-weight: 600; cursor: pointer; transition: filter .15s, opacity .15s;
  }
  button:hover { filter: brightness(1.12); }
  button:disabled { opacity: .55; cursor: default; }
  .primary { background: linear-gradient(135deg, #7c5cff, #5b8cff); color: #fff; }
  .ghost { background: #1b1b2b; color: #c9c9e0; border: 1px solid #2a2a42; }
  .tabs { display: flex; gap: 8px; margin: 20px 0 4px; }
  .tab {
    width: auto; margin: 0; padding: 7px 14px; font-size: 13px;
    background: #17172a; color: #9a9ab4; border: 1px solid #26263c;
  }
  .tab.active { background: #241d47; color: #e8e8f2; border-color: #4c3bb0; }
  .status { margin-top: 16px; padding: 11px 12px; border-radius: 9px; font-size: 13px; border: 1px solid; }
  .status.info { background: #151a2e; border-color: #2b3a66; color: #b9c8ee; }
  .status.ok { background: #12241a; border-color: #2c5a3c; color: #a8e6bd; }
  .status.warn { background: #2a2213; border-color: #5c4a22; color: #eed9a8; }
  .status.error { background: #2a1418; border-color: #5c2530; color: #f0b3bd; }
  .hidden { display: none !important; }
  #who { margin: 0 0 4px; color: #b7b7d0; font-size: 13px; }
  .foot { margin: 16px 0 0; text-align: center; color: #6d6d86; font-size: 12px; }
</style>
</head>
<body>
<div class="wrap">
  <h1>Nebula <span>Social</span></h1>
  <p class="sub">Vincula tu jugador de Minecraft a tu cuenta Nebula.</p>

  <div class="card">
    <label for="code">Codigo del juego</label>
    <input id="code" maxlength="6" autocomplete="off" spellcheck="false" placeholder="ABC123" />
    <p class="hint">Aparece en el juego: pantalla Social, pestana Feed, cuando el nombre no esta vinculado.</p>

    <div id="authBoxes">
      <div class="tabs">
        <button id="tabLogin" class="tab active" type="button">Entrar</button>
        <button id="tabRegister" class="tab" type="button">Crear cuenta</button>
      </div>

      <form id="loginForm">
        <label for="email">Email</label>
        <input id="email" type="email" autocomplete="username" required />
        <label for="password">Contrasena</label>
        <input id="password" type="password" autocomplete="current-password" required />
        <button class="primary" type="submit">Entrar</button>
      </form>

      <form id="registerForm" class="hidden">
        <label for="email2">Email</label>
        <input id="email2" type="email" autocomplete="username" required />
        <label for="password2">Contrasena (minimo 8 caracteres)</label>
        <input id="password2" type="password" autocomplete="new-password" required />
        <button class="primary" type="submit">Crear cuenta</button>
        <div id="verifyBox" class="hidden">
          <label for="verify">Codigo de verificacion del correo</label>
          <input id="verify" maxlength="6" autocomplete="one-time-code" />
          <button id="verifyBtn" class="ghost" type="button">Verificar y entrar</button>
        </div>
      </form>
    </div>

    <div id="session" class="hidden">
      <p id="who"></p>
      <button id="approveBtn" class="primary" type="button">Vincular a Minecraft</button>
      <button id="logoutBtn" class="ghost" type="button">Salir</button>
    </div>

    <div id="status" class="status hidden"></div>
  </div>

  <p class="foot">NebulaPlus - la red social se juega dentro del cliente.</p>
</div>

<script>
const $ = (id) => document.getElementById(id);
let token = "";
let pendingEmail = "";

const MESSAGES = {
  email_no_verificado: "Verifica tu correo antes de vincular un jugador.",
  name_reserved: "Ese nombre pertenece a otra cuenta (premium verificada u otra cuenta Nebula).",
  already_linked: "Ese jugador ya esta vinculado a otra cuenta Nebula.",
  code_expired: "El codigo caduco. Genera otro en el juego.",
  code_used: "Ese codigo ya se uso. Genera otro en el juego.",
  not_found: "No encuentro ese codigo. Revisa que sea el que muestra el juego.",
  unauthorized: "Email o contrasena incorrectos.",
  email_taken: "Ese email ya tiene una cuenta Nebula.",
  bad_request: "Revisa los datos e intentalo otra vez."
};

function show(el, on) { el.classList.toggle("hidden", !on); }

function say(kind, text) {
  const el = $("status");
  el.className = "status " + kind;
  el.textContent = text;
}

function explain(data, fallback) {
  if (data && data.code && MESSAGES[data.code]) return MESSAGES[data.code];
  if (data && data.error) return data.error;
  return fallback;
}

async function api(method, path, body, bearer) {
  const res = await fetch(path, {
    method,
    headers: Object.assign(
      {},
      body ? { "content-type": "application/json" } : {},
      bearer ? { authorization: "Bearer " + bearer } : {}
    ),
    body: body ? JSON.stringify(body) : undefined,
  });
  let data = null;
  try { data = await res.json(); } catch (e) { data = null; }
  return { ok: res.ok, status: res.status, data: data || {} };
}

const initial = new URLSearchParams(location.search).get("code");
if (initial) $("code").value = initial.toUpperCase();

$("tabLogin").addEventListener("click", () => {
  $("tabLogin").classList.add("active");
  $("tabRegister").classList.remove("active");
  show($("loginForm"), true);
  show($("registerForm"), false);
});

$("tabRegister").addEventListener("click", () => {
  $("tabRegister").classList.add("active");
  $("tabLogin").classList.remove("active");
  show($("registerForm"), true);
  show($("loginForm"), false);
});

function enterSession(account, email) {
  const verified = account && account.email_verified;
  $("who").textContent = "Sesion: " + (email || (account && account.email) || "") + (verified ? "" : " (correo sin verificar)");
  show($("authBoxes"), false);
  show($("session"), true);
  if (!verified) {
    say("warn", "Tu correo no esta verificado: los jugadores offline no se pueden vincular hasta que lo verifiques.");
  } else {
    say("info", "Pega el codigo del juego y pulsa Vincular a Minecraft.");
  }
}

$("loginForm").addEventListener("submit", async (event) => {
  event.preventDefault();
  const email = $("email").value.trim();
  const password = $("password").value;
  if (!email || !password) { say("warn", "Escribe email y contrasena."); return; }

  const res = await api("POST", "/api/accounts/login", { email: email, password: password });
  if (!res.ok) { say("error", explain(res.data, "No se pudo entrar.")); return; }

  token = res.data.token;
  enterSession(res.data.account, email);
});

$("registerForm").addEventListener("submit", async (event) => {
  event.preventDefault();
  const email = $("email2").value.trim();
  const password = $("password2").value;
  if (password.length < 8) { say("warn", "La contrasena necesita 8 caracteres o mas."); return; }

  const res = await api("POST", "/api/accounts", { email: email, password: password });
  if (!res.ok) { say("error", explain(res.data, "No se pudo crear la cuenta.")); return; }

  pendingEmail = email;
  show($("verifyBox"), true);
  if (res.data.dev_code) {
    $("verify").value = res.data.dev_code;
    say("info", "Modo desarrollo: codigo rellenado automaticamente.");
  } else {
    say("info", "Te enviamos un codigo por correo. Escribelo aqui.");
  }
});

$("verifyBtn").addEventListener("click", async () => {
  const code = $("verify").value.trim();
  if (!pendingEmail) { say("warn", "Primero crea la cuenta."); return; }

  const res = await api("POST", "/api/accounts/verify", { email: pendingEmail, code: code });
  if (!res.ok) { say("error", explain(res.data, "Codigo incorrecto o caducado.")); return; }

  const login = await api("POST", "/api/accounts/login", {
    email: pendingEmail,
    password: $("password2").value,
  });
  if (!login.ok) { say("warn", "Correo verificado. Ahora entra con tu contrasena."); return; }

  token = login.data.token;
  enterSession(login.data.account, pendingEmail);
});

$("approveBtn").addEventListener("click", async () => {
  const code = $("code").value.trim().toUpperCase();
  if (code.length !== 6) { say("warn", "El codigo tiene 6 caracteres."); return; }

  const res = await api("POST", "/api/auth/device/approve", { code: code }, token);
  if (res.ok) {
    const identity = res.data.identity || {};
    say("ok", "Listo: " + (identity.username || "jugador") + " vinculado. Vuelve al juego, se conecta solo.");
    $("approveBtn").disabled = true;
    return;
  }
  if (res.status === 401) {
    token = "";
    show($("session"), false);
    show($("authBoxes"), true);
    say("error", "La sesion caduco. Vuelve a entrar.");
    return;
  }
  say("error", explain(res.data, "No se pudo vincular."));
});

$("logoutBtn").addEventListener("click", async () => {
  await api("POST", "/api/auth/logout", null, token);
  token = "";
  $("approveBtn").disabled = false;
  show($("session"), false);
  show($("authBoxes"), true);
  say("info", "Sesion cerrada.");
});
</script>
</body>
</html>"##;
