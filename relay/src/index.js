// AGY Remote push relay.
// SPDX-License-Identifier: AGPL-3.0-only
//
// What this does: receives a notification from an agy-connector and forwards it to Apple Push
// Notification service (iOS) or Firebase Cloud Messaging (Android). The notification body is end-to-end encrypted between the connector and
// the phone (ChaCha20-Poly1305); this relay cannot read it and stores nothing.
//
// Request:  POST /v1/push
//   Headers  X-Agy-Key:  base64url Ed25519 public key of the connector (its P2P identity)
//            X-Agy-Time: unix seconds when the request was signed
//            X-Agy-Sig:  base64url Ed25519 signature over `${time}\n${body}`
//   Body     {"token": "<APNs device token, hex | FCM registration token>",
//             "platform": "apns"|"fcm" (optional, default "apns"),
//             "env": "production"|"sandbox" (APNs only), "collapse": "<opaque id>",
//             "c": "<base64 ciphertext>"}
//
// A phone that turns notifications on asks for a grant first:
//
// Request:  POST /v1/grant
//   Body     {"token": …, "platform": …, "env": … (as above), "keys": ["<a connector's key, as in X-Agy-Key>", …]}
//
// One request covers all of a phone's computers: "keys" lists their keys (up to 16; "key" for
// just one), and one push carries a grant for each. A grant says "this connector may send to
// this push token" for 45 days. It is never in the reply: it is sent as a push to that token, so
// only the phone that really has the token gets it. On iOS it is a background push: nothing is
// shown, and only the app receives it.
// The phone hands it to its connector, which adds it to each push as "g" (and its expiry "gx").
// The relay keeps nothing: a grant is an HMAC it can check again. Pushes with a grant are the
// only ones counted against the main budget; ones without (older apps and connectors) share a
// small budget of their own, so made-up requests can't use up everyone's.
//
// Anyone can run their own copy, but APNs only accepts notifications for an app signed with the
// same Apple Developer team, so the App Store build of AGY Remote only works with the official
// relay. Self-builds can point the connector at their own relay (`agy-connector set-relay`).

const MAX_BODY = 4096;
// A request may be signed up to 60 s ago (the replay check below remembers signatures for that
// long), or up to 5 minutes "in the future" for a computer whose clock runs fast.
const MAX_AGE_SECONDS = 60;
const MAX_SKEW_SECONDS = 300;
const GRANT_LIFETIME_SECONDS = 45 * 24 * 60 * 60;
/** Most computers one grant request may name. */
const MAX_GRANT_KEYS = 16;
const APNS_HOSTS = {
  production: "https://api.push.apple.com",
  sandbox: "https://api.sandbox.push.apple.com",
};
// Apple asks providers to refresh the auth token no more than every 20 minutes; it is valid for 60.
const JWT_LIFETIME_MS = 40 * 60 * 1000;

let cachedJwt = null; // { token, createdAt }

const FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
const GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token";
let cachedFcmToken = null; // { token, expiresAt, email }

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "GET" && url.pathname === "/") {
      return text("AGY Remote push relay. Source: https://github.com/NarYuki/agy-remote (AGPL-3.0)\n");
    }
    if (request.method === "POST" && url.pathname === "/v1/grant") return issueGrant(request, env);
    if (request.method !== "POST" || url.pathname !== "/v1/push") {
      return text("Not found\n", 404);
    }

    // Cheap checks first, before anything is verified: the sender's network, then the size (the
    // body is read only up to the limit, so nothing large is ever held).
    const network = senderNetwork(request.headers.get("CF-Connecting-IP") || "");
    if (env.IP_LIMIT && network && !(await env.IP_LIMIT.limit({ key: network })).success) {
      return json({ error: "rate limited" }, 429);
    }
    const body = await readBody(request, MAX_BODY);
    if (body === null) return json({ error: "too large" }, 413);

    // The key and signature are used in their one canonical spelling from here on. Base64 has
    // several spellings for the same bytes, and each would otherwise count as a new signature
    // (a replay) or a new sender (its own rate limit).
    const key = canonical(request.headers.get("X-Agy-Key") || "", 32);
    const time = request.headers.get("X-Agy-Time") || "";
    const sig = canonical(request.headers.get("X-Agy-Sig") || "", 64);
    if (!key || !sig) return json({ error: "bad signature" }, 401);
    const now = Math.floor(Date.now() / 1000);
    const age = now - Number(time);
    if (!/^\d+$/.test(time) || age > MAX_AGE_SECONDS || -age > MAX_SKEW_SECONDS) {
      return json({ error: "stale request" }, 401);
    }
    if (!(await verifySignature(key, sig, `${time}\n${body}`))) {
      return json({ error: "bad signature" }, 401);
    }

    // A signature is only good once: remembered in this isolate, and across all of Cloudflare
    // through a limiter that allows each signature one use per minute.
    if (seenSignature(sig, now)) return json({ error: "replayed" }, 409);
    if (env.REPLAY_LIMIT && !(await env.REPLAY_LIMIT.limit({ key: `s:${sig}` })).success) {
      return json({ error: "replayed" }, 409);
    }

    let msg;
    try {
      msg = JSON.parse(body);
    } catch {
      return json({ error: "bad json" }, 400);
    }

    // Valid JSON that isn't an object (`null`, a number) has no fields to read.
    if (msg === null || typeof msg !== "object") return json({ error: "bad json" }, 400);
    const { c } = msg;
    const target = pushTarget(msg);
    if (target.error) return json({ error: target.error }, 400);
    const { platform, token } = target;
    if (typeof c !== "string" || c.length > 2048) return json({ error: "bad payload" }, 400);

    // Anyone can make a key, so the limits that matter are per phone and per sender network (that
    // one is checked first, above); the per-key limit stays for a misbehaving connector, and the
    // last one caps what this relay sends to Apple and Google in all, whoever asks.
    // A grant proves a phone with this token asked for this connector's notifications. One that
    // doesn't check out (forged, expired, another connector's or phone's) is refused outright.
    let granted = false;
    if (env.GRANT_SECRET && msg.g !== undefined) {
      if (!(await grantValid(env, platform, token, key, msg.g, msg.gx, now))) return json({ error: "bad grant" }, 403);
      granted = true;
    }
    // Without a grant (an app or connector from before grants) the request first has to fit a
    // small budget shared by all such requests, so it never reaches the main one when that is full.
    const limits = [
      [env.SENDER_LIMIT, key],
      [env.TOKEN_LIMIT, `t:${token}`],
      ...(env.GRANT_SECRET && !granted ? [[env.UNGRANTED_LIMIT, "all"]] : []),
      [env.GLOBAL_LIMIT, "all"],
    ];
    for (const [limiter, id] of limits) {
      if (!limiter || !id) continue;
      const { success } = await limiter.limit({ key: id });
      if (!success) return json({ error: "rate limited" }, 429);
    }
    // Goes into an HTTP header: only plain id characters, or it is left out.
    const collapse = typeof msg.collapse === "string" && /^[\w-]{1,64}$/.test(msg.collapse) ? msg.collapse : "";
    if (platform === "fcm") return sendFcm(env, token, { c, ...(collapse ? { collapse } : {}) }, collapse, "86400s");
    const apnsEnv = msg.env === "sandbox" ? "sandbox" : "production";

    const payload = {
      aps: {
        // Shown only if the phone's notification extension cannot decrypt `c`.
        // loc-key: the phone shows this in its own language if the extension can't decrypt.
        alert: { title: "AGY Remote", "loc-key": "New activity" },
        "mutable-content": 1,
        sound: "default",
        ...(collapse ? { "thread-id": collapse } : {}),
      },
      c,
    };

    let res;
    try {
      res = await fetch(`${APNS_HOSTS[apnsEnv]}/3/device/${token}`, {
        method: "POST",
        headers: {
          authorization: `bearer ${await apnsJwt(env)}`,
          "apns-topic": env.APNS_TOPIC,
          "apns-push-type": "alert",
          "apns-priority": "10",
          ...(collapse ? { "apns-collapse-id": collapse } : {}),
          "content-type": "application/json",
        },
        body: JSON.stringify(payload),
      });
    } catch {
      // Apple can't be reached (or the signing key is unusable): say so, like the FCM path does.
      return json({ error: "APNs unavailable" }, 502);
    }

    if (res.ok) return json({ ok: true });
    // 410 tells the connector to forget a dead token. Apple's other reasons are not passed on.
    // Nothing is logged or kept.
    return res.status === 410 ? json({ error: "unregistered" }, 410) : json({ error: "rejected" }, 502);
  },
};

/**
 * Where a request wants a push sent: `{ platform, token }`, or `{ error }`. An APNs token is hex,
 * and Apple takes it in either case, so it is lowercased (else each spelling would count as
 * another phone for the limits and the grants).
 */
function pushTarget(msg) {
  const platform = msg.platform === undefined || msg.platform === "apns" ? "apns" : msg.platform;
  if (platform !== "apns" && platform !== "fcm") return { error: "bad platform" };
  const token = msg.token;
  const shape = platform === "fcm" ? /^[\w:-]{32,4096}$/ : /^[0-9a-f]{64,200}$/i;
  if (typeof token !== "string" || !shape.test(token)) return { error: "bad token" };
  return { platform, token: platform === "apns" ? token.toLowerCase() : token };
}

async function grantKey(env) {
  return crypto.subtle.importKey("raw", new TextEncoder().encode(env.GRANT_SECRET), { name: "HMAC", hash: "SHA-256" }, false, ["sign", "verify"]);
}

const grantText = (platform, token, key, expires) => new TextEncoder().encode(`agy-grant-v1\n${platform}\n${token}\n${key}\n${expires}`);

/** The grant for `key` (a connector) to send to `token` until `expires` (unix seconds). */
async function grantFor(env, platform, token, key, expires) {
  return b64urlEncode(new Uint8Array(await crypto.subtle.sign("HMAC", await grantKey(env), grantText(platform, token, key, expires))));
}

async function grantValid(env, platform, token, key, grant, expires, now) {
  if (typeof grant !== "string" || !Number.isSafeInteger(expires) || expires <= now) return false;
  try {
    return await crypto.subtle.verify("HMAC", await grantKey(env), b64urlDecode(grant), grantText(platform, token, key, expires));
  } catch {
    return false;
  }
}

/**
 * `POST /v1/grant`: sends the phone its grant as a push. Nothing here is signed (a phone has no
 * key the relay knows), so what protects it is where the answer goes, and limits of its own:
 * asking is rare (once per computer every few weeks), and a flood of asking can't touch the
 * budget notifications are sent from.
 */
async function issueGrant(request, env) {
  if (!env.GRANT_SECRET) return json({ error: "grants not available" }, 404);
  const network = senderNetwork(request.headers.get("CF-Connecting-IP") || "");
  if (env.GRANT_IP_LIMIT && network && !(await env.GRANT_IP_LIMIT.limit({ key: network })).success) {
    return json({ error: "rate limited" }, 429);
  }
  const body = await readBody(request, MAX_BODY);
  if (body === null) return json({ error: "too large" }, 413);
  let msg;
  try {
    msg = JSON.parse(body);
  } catch {
    return json({ error: "bad json" }, 400);
  }
  if (msg === null || typeof msg !== "object") return json({ error: "bad json" }, 400);
  const target = pushTarget(msg);
  if (target.error) return json({ error: target.error }, 400);
  const { platform, token } = target;
  const asked = Array.isArray(msg.keys) ? msg.keys : msg.key !== undefined ? [msg.key] : [];
  if (asked.length === 0 || asked.length > MAX_GRANT_KEYS) return json({ error: "bad key" }, 400);
  const keys = [];
  for (const k of asked) {
    const key = canonical(typeof k === "string" ? k : "", 32);
    if (!key) return json({ error: "bad key" }, 400);
    if (!keys.includes(key)) keys.push(key);
  }
  for (const [limiter, id] of [[env.GRANT_TOKEN_LIMIT, `t:${token}`], [env.GRANT_GLOBAL_LIMIT, "all"]]) {
    if (limiter && !(await limiter.limit({ key: id })).success) return json({ error: "rate limited" }, 429);
  }
  const now = Math.floor(Date.now() / 1000);
  const expires = now + GRANT_LIFETIME_SECONDS;
  const grants = [];
  for (const key of keys) grants.push({ key, grant: await grantFor(env, platform, token, key, expires) });
  const g = JSON.stringify({ expires, grants });
  // The phone asks while its app is open and waits for this; a copy arriving minutes later is no use.
  if (platform === "fcm") return sendFcm(env, token, { g }, "", "60s");
  let res;
  try {
    res = await fetch(`${APNS_HOSTS[msg.env === "sandbox" ? "sandbox" : "production"]}/3/device/${token}`, {
      method: "POST",
      headers: {
        authorization: `bearer ${await apnsJwt(env)}`,
        "apns-topic": env.APNS_TOPIC,
        // A background push: iOS shows nothing and hands `g` to the app. Apple wants priority 5
        // for these.
        "apns-push-type": "background",
        "apns-priority": "5",
        "apns-expiration": String(now + 60),
        "content-type": "application/json",
      },
      body: JSON.stringify({ aps: { "content-available": 1 }, g }),
    });
  } catch {
    return json({ error: "APNs unavailable" }, 502);
  }
  if (res.ok) return json({ ok: true });
  return res.status === 410 ? json({ error: "unregistered" }, 410) : json({ error: "rejected" }, 502);
}

/**
 * The limiter key for a sender address. An IPv6 customer is given a whole /64 (2^64 addresses),
 * so those are counted together; an IPv4 address counts on its own.
 */
export function senderNetwork(ip) {
  if (!ip) return "";
  if (!ip.includes(":")) return `ip:${ip}`;
  const [head, tail = ""] = ip.toLowerCase().split("::");
  const left = head ? head.split(":") : [];
  const right = tail ? tail.split(":") : [];
  // "::" stands for the groups that are missing from the eight.
  const groups = ip.includes("::") ? [...left, ...Array(Math.max(0, 8 - left.length - right.length)).fill("0"), ...right] : left;
  return `ip6:${groups.slice(0, 4).map((g) => g.replace(/^0+(?=.)/, "")).join(":")}`;
}

const recentSignatures = new Map(); // signature → unix time it expires

function seenSignature(sig, now) {
  if (recentSignatures.size > 10000) {
    for (const [s, until] of recentSignatures) if (until < now) recentSignatures.delete(s);
  }
  if (recentSignatures.has(sig)) return true;
  recentSignatures.set(sig, now + MAX_AGE_SECONDS + MAX_SKEW_SECONDS);
  return false;
}

// Firebase Cloud Messaging (HTTP v1). The message is data-only, so Android hands it to the app's
// FirebaseMessagingService, which decrypts `c` and shows the notification itself; Google only ever
// sees the same opaque blob Apple does.
async function sendFcm(env, token, data, collapse, ttl) {
  if (!env.FCM_PROJECT_ID || !env.FCM_CLIENT_EMAIL || !env.FCM_PRIVATE_KEY) {
    return json({ error: "FCM not configured" }, 502);
  }
  const message = {
    token,
    data,
    android: {
      priority: "HIGH",
      ttl,
      ...(collapse ? { collapse_key: collapse } : {}),
    },
  };
  const send = async (accessToken) =>
    fetch(`https://fcm.googleapis.com/v1/projects/${encodeURIComponent(env.FCM_PROJECT_ID)}/messages:send`, {
      method: "POST",
      headers: { authorization: `Bearer ${accessToken}`, "content-type": "application/json" },
      body: JSON.stringify({ message }),
    });
  let res;
  try {
    res = await send(await fcmAccessToken(env));
    if (res.status === 401) {
      // The cached access token was revoked or expired early: get a new one once.
      cachedFcmToken = null;
      res = await send(await fcmAccessToken(env));
    }
  } catch (e) {
    return json({ error: "FCM unavailable" }, 502);
  }
  if (res.ok) return json({ ok: true });
  const err = (await res.json().catch(() => ({}))).error || {};
  const code = (err.details || []).map((d) => d.errorCode).find(Boolean) || err.status || `HTTP ${res.status}`;
  // UNREGISTERED (404): the app was uninstalled or the token was deleted. Reported as 410 like
  // APNs, so the connector forgets the registration.
  const gone = code === "UNREGISTERED" || res.status === 404;
  return gone ? json({ error: "unregistered" }, 410) : json({ error: "rejected" }, 502);
}

/** OAuth access token for FCM from the service-account key, cached until shortly before it expires. */
async function fcmAccessToken(env) {
  if (cachedFcmToken && cachedFcmToken.email === env.FCM_CLIENT_EMAIL && Date.now() < cachedFcmToken.expiresAt) {
    return cachedFcmToken.token;
  }
  // Secrets pasted from the JSON key keep "\n" escapes; accept both forms.
  const pem = env.FCM_PRIVATE_KEY.replace(/\\n/g, "\n").replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
  const key = await crypto.subtle.importKey(
    "pkcs8",
    Uint8Array.from(atob(pem), (ch) => ch.charCodeAt(0)),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const iat = Math.floor(Date.now() / 1000);
  const header = b64urlEncode(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claims = b64urlEncode(
    JSON.stringify({ iss: env.FCM_CLIENT_EMAIL, scope: FCM_SCOPE, aud: GOOGLE_TOKEN_URL, iat, exp: iat + 3600 }),
  );
  const signature = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(`${header}.${claims}`));
  const assertion = `${header}.${claims}.${b64urlEncode(new Uint8Array(signature))}`;
  const res = await fetch(GOOGLE_TOKEN_URL, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion }).toString(),
  });
  const body = await res.json().catch(() => ({}));
  if (!res.ok || typeof body.access_token !== "string") throw new Error(`Google OAuth failed: HTTP ${res.status}`);
  const lifetime = Number(body.expires_in) > 0 ? Number(body.expires_in) : 3600;
  // Refresh five minutes early.
  cachedFcmToken = { token: body.access_token, expiresAt: Date.now() + (lifetime - 300) * 1000, email: env.FCM_CLIENT_EMAIL };
  return body.access_token;
}

async function verifySignature(keyB64, sigB64, message) {
  try {
    const key = await crypto.subtle.importKey("raw", b64urlDecode(keyB64), { name: "Ed25519" }, false, ["verify"]);
    return await crypto.subtle.verify("Ed25519", key, b64urlDecode(sigB64), new TextEncoder().encode(message));
  } catch {
    return false;
  }
}

async function apnsJwt(env) {
  if (cachedJwt && Date.now() - cachedJwt.createdAt < JWT_LIFETIME_MS) return cachedJwt.token;
  const pem = env.APNS_KEY_P8.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
  const key = await crypto.subtle.importKey(
    "pkcs8",
    Uint8Array.from(atob(pem), (ch) => ch.charCodeAt(0)),
    { name: "ECDSA", namedCurve: "P-256" },
    false,
    ["sign"],
  );
  const header = b64urlEncode(JSON.stringify({ alg: "ES256", kid: env.APNS_KEY_ID }));
  const claims = b64urlEncode(JSON.stringify({ iss: env.APNS_TEAM_ID, iat: Math.floor(Date.now() / 1000) }));
  const signature = await crypto.subtle.sign(
    { name: "ECDSA", hash: "SHA-256" },
    key,
    new TextEncoder().encode(`${header}.${claims}`),
  );
  const token = `${header}.${claims}.${b64urlEncode(new Uint8Array(signature))}`;
  cachedJwt = { token, createdAt: Date.now() };
  return token;
}

function b64urlEncode(input) {
  const bytes = typeof input === "string" ? new TextEncoder().encode(input) : input;
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** The request body as text, or null once it is longer than `max` bytes (the rest is not read). */
async function readBody(request, max) {
  if (Number(request.headers.get("Content-Length")) > max) return null;
  if (!request.body) return "";
  const reader = request.body.getReader();
  const bytes = new Uint8Array(max);
  let size = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    if (size + value.length > max) {
      await reader.cancel();
      return null;
    }
    bytes.set(value, size);
    size += value.length;
  }
  return new TextDecoder().decode(bytes.subarray(0, size));
}

/** `s` re-encoded as unpadded base64url if it is base64 of exactly `length` bytes, else "". */
function canonical(s, length) {
  try {
    const bytes = b64urlDecode(s);
    return bytes.length === length ? b64urlEncode(bytes) : "";
  } catch {
    return "";
  }
}

function b64urlDecode(s) {
  const b64 = s.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((s.length + 3) % 4);
  return Uint8Array.from(atob(b64), (ch) => ch.charCodeAt(0));
}

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), { status, headers: { "content-type": "application/json" } });
}

function text(s, status = 200) {
  return new Response(s, { status, headers: { "content-type": "text/plain; charset=utf-8" } });
}
