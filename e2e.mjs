// Prueba de humo contra la API local (que debe estar apuntando a Neon).
// Uso: desde social/, con la API arrancada:  node e2e.mjs
import { createHmac, randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";

const base = "http://127.0.0.1:8080";
const secret =
  readFileSync(new URL(".env", import.meta.url), "utf8").match(
    /^LAUNCHER_HMAC_SECRET=(.*)$/m,
  )?.[1].trim() ?? "";
const suffix = String(Date.now()).slice(-6);
const name = `E2E${suffix}`;
const email = `e2e${suffix}@nebula.test`;
const pass = "SuperSecret123";

async function call(method, path, body, token) {
  const res = await fetch(base + path, {
    method,
    headers: {
      ...(body ? { "content-type": "application/json" } : {}),
      ...(token ? { authorization: `Bearer ${token}` } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  let json;
  try {
    json = JSON.parse(text);
  } catch {
    json = text;
  }
  return { status: res.status, body: json };
}

const results = [];
function check(label, cond, extra) {
  results.push({ label, ok: !!cond });
  console.log(
    `${cond ? "PASS" : "FAIL"} — ${label}${extra !== undefined ? " :: " + JSON.stringify(extra) : ""}`,
  );
}

// 1. registro de cuenta Nebula
let r = await call("POST", "/api/accounts", { email, password: pass });
check("registro cuenta Nebula devuelve 201 + dev_code", r.status === 201 && !!r.body.dev_code, {
  status: r.status,
});
const code = r.body.dev_code;

// 2. verificacion de email
r = await call("POST", "/api/accounts/verify", { email, code });
check("verificar email devuelve 204", r.status === 204, r.status);

// 3. login web
r = await call("POST", "/api/accounts/login", { email, password: pass });
check("login web devuelve token", r.status === 200 && !!r.body.token, {
  status: r.status,
  email_verified: r.body.account?.email_verified,
});
const webToken = r.body.token;

// 4. codigo de dispositivo
r = await call("POST", "/api/auth/device", { mc_name: name });
check("crear codigo de dispositivo devuelve 201", r.status === 201 && !!r.body.code, r.body);
const devCode = r.body.code;

// 5. polling sin aprobar
r = await call("POST", "/api/auth/device/token", { code: devCode });
check("polling sin aprobar devuelve 202 (pending)", r.status === 202, r.body);

// 6. aprobar con la cuenta web
r = await call("POST", "/api/auth/device/approve", { code: devCode }, webToken);
check("aprobar codigo crea identidad offline", r.status === 200 && r.body.identity?.kind === "offline", r.body);

// 7. token de juego
r = await call("POST", "/api/auth/device/token", { code: devCode });
check("recoger token de juego devuelve 200", r.status === 200 && !!r.body.token, {
  status: r.status,
  user: r.body.user,
});
const gameToken = r.body.token;

// 8. codigo de un solo uso
r = await call("POST", "/api/auth/device/token", { code: devCode });
check("reusar el codigo devuelve 410", r.status === 410, r.body.code);

// 9. crear post
r = await call("POST", "/api/posts", { body: "Hola desde el E2E de Nebula" }, gameToken);
check("crear post devuelve 201 con el autor", r.status === 201 && r.body.author?.username === name, r.body.author);
const postId = r.body.id;

// 10. feed global
r = await call("GET", "/api/feed?limit=5");
check("el feed global incluye el post", r.status === 200 && r.body.posts?.some((p) => p.id === postId), {
  count: r.body.posts?.length,
});

// 11. like
r = await call("POST", `/api/posts/${postId}/like`, null, gameToken);
check("like devuelve 204", r.status === 204, r.status);

// 12. sin token
r = await call("POST", "/api/posts", { body: "sin token" });
check("post sin token devuelve 401", r.status === 401, r.status);

// 13. perfil
r = await call("GET", `/api/users/${name}`);
check("perfil por nombre devuelve el conteo de posts", r.status === 200 && r.body.posts === 1, r.body);

// 14. busqueda FTS
r = await call("GET", "/api/search?q=E2E");
check("busqueda FTS encuentra el post", r.status === 200 && Array.isArray(r.body) && r.body.length >= 1, {
  hits: r.body?.length,
});

// 15. notificaciones
r = await call("GET", "/api/notifications", null, gameToken);
check("notificaciones devuelve unread numerico", r.status === 200 && typeof r.body.unread === "number", r.body);

// 16. eligibility
r = await call("GET", `/api/auth/eligibility?mc_name=${name}`);
check("eligibility marca al jugador como vinculado", r.status === 200 && r.body.allowed === true, r.body);

// 17. otra cuenta intenta el mismo nombre
const email2 = `e2e2${suffix}@nebula.test`;
r = await call("POST", "/api/accounts", { email: email2, password: pass });
const code2 = r.body.dev_code;
await call("POST", "/api/accounts/verify", { email: email2, code: code2 });
r = await call("POST", "/api/accounts/login", { email: email2, password: pass });
const web2 = r.body.token;
r = await call("POST", "/api/auth/device", { mc_name: name });
const dev2 = r.body.code;
r = await call("POST", "/api/auth/device/approve", { code: dev2 }, web2);
check("el nombre ya reclamado devuelve 409 name_reserved", r.status === 409 && r.body.code === "name_reserved", r.body);

// 18. rate limit (3 posts por minuto; ya hay 1)
const codes = [];
for (let i = 0; i < 4; i++) {
  const rr = await call("POST", "/api/posts", { body: `spam ${i}` }, gameToken);
  codes.push(rr.status);
}
check("rate limit corta el 4o post con 429", codes[0] === 201 && codes[1] === 201 && codes[2] === 429, codes);

// 19. borrar post propio
r = await call("DELETE", `/api/posts/${postId}`, null, gameToken);
check("borrar post propio devuelve 204", r.status === 204, r.status);

// 20. ya no esta en el feed
r = await call("GET", "/api/feed?limit=5");
check("el post borrado sale del feed", !r.body.posts?.some((p) => p.id === postId), {
  count: r.body.posts?.length,
});

// ---------------------------------------------------------------------------
// Premium: exactamente el payload que escribe el launcher (sin account_id)
// ---------------------------------------------------------------------------
const premiumName = `Prem${suffix}`;
const premiumUuid = randomUUID();
const issuedAt = Math.floor(Date.now() / 1000);
const proof = (ts) =>
  createHmac("sha256", secret).update(`${premiumUuid}|${premiumName}|${ts}`).digest("hex");

// 21. vínculo premium
r = await call("POST", "/api/auth/link/premium", {
  mc_uuid: premiumUuid,
  mc_name: premiumName,
  issued_at: issuedAt,
  proof: proof(issuedAt),
});
check("launcher premium vincula sin account_id", r.status === 200 && r.body.user?.kind === "premium", {
  status: r.status,
  user: r.body.user,
  code: r.body.code,
});
const premiumToken = r.body.token;

// 22. eligibility premium
r = await call("GET", `/api/auth/eligibility?mc_name=${premiumName}&mc_uuid=${premiumUuid}`);
check("eligibility premium => allowed/premium", r.status === 200 && r.body.allowed === true && r.body.kind === "premium", {
  kind: r.body.kind,
  reason: r.body.reason,
});

// 23. un offline no puede reclamar ese nombre
r = await call("POST", "/api/auth/device", { mc_name: premiumName });
check("offline choca con nombre premium (409 name_reserved)", r.status === 409 && r.body.code === "name_reserved", r.body);

// 24. el premium publica
r = await call("POST", "/api/posts", { body: "Hola desde una cuenta premium" }, premiumToken);
check("premium puede publicar", r.status === 201 && r.body.author?.kind === "premium", r.body.author);

// 25. proof invalido
r = await call("POST", "/api/auth/link/premium", {
  mc_uuid: premiumUuid,
  mc_name: premiumName,
  issued_at: issuedAt,
  proof: "00".repeat(32),
});
check("proof invalido devuelve 401", r.status === 401, r.status);

// 26. proof caducado (1 hora)
const oldIssued = issuedAt - 3600;
r = await call("POST", "/api/auth/link/premium", {
  mc_uuid: premiumUuid,
  mc_name: premiumName,
  issued_at: oldIssued,
  proof: proof(oldIssued),
});
check("proof de hace 1 hora devuelve 401", r.status === 401, r.status);

const failed = results.filter((x) => !x.ok).length;
console.log(`\n${results.length - failed}/${results.length} checks OK`);
process.exit(failed === 0 ? 0 : 1);
