// SPDX-License-Identifier: AGPL-3.0-only
import { test } from "node:test";
import assert from "node:assert/strict";
import worker, { senderNetwork } from "../src/index.js";

const b64url = (bytes) => Buffer.from(bytes).toString("base64url");

async function setup() {
  const sender = await crypto.subtle.generateKey({ name: "Ed25519" }, true, ["sign", "verify"]);
  const apns = await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, ["sign"]);
  const p8 = Buffer.from(await crypto.subtle.exportKey("pkcs8", apns.privateKey)).toString("base64");
  const env = {
    APNS_KEY_P8: `-----BEGIN PRIVATE KEY-----\n${p8}\n-----END PRIVATE KEY-----`,
    APNS_KEY_ID: "ABCDE12345",
    APNS_TEAM_ID: "TEAM123456",
    APNS_TOPIC: "moe.n4tsu.agyremote",
  };
  const pub = b64url(await crypto.subtle.exportKey("raw", sender.publicKey));
  async function request(bodyObj, { time = Math.floor(Date.now() / 1000), tamper = false } = {}) {
    const body = JSON.stringify(bodyObj);
    const sig = await crypto.subtle.sign("Ed25519", sender.privateKey, new TextEncoder().encode(`${time}\n${body}`));
    return new Request("https://relay.test/v1/push", {
      method: "POST",
      headers: { "X-Agy-Key": pub, "X-Agy-Time": String(time), "X-Agy-Sig": b64url(new Uint8Array(sig)) },
      body: tamper ? body.replace("abc", "abd") : body,
    });
  }
  return { env, request, pub };
}

const token = "a".repeat(64);

test("forwards a signed push to APNs without touching the ciphertext", async () => {
  const { env, request } = await setup();
  let seen;
  globalThis.fetch = async (url, init) => {
    seen = { url, init };
    return new Response("", { status: 200 });
  };
  const res = await worker.fetch(await request({ token, env: "sandbox", collapse: "t1", c: "abc" }), env);
  assert.equal(res.status, 200);
  assert.equal(seen.url, `https://api.sandbox.push.apple.com/3/device/${token}`);
  assert.equal(seen.init.headers["apns-topic"], "moe.n4tsu.agyremote");
  assert.match(seen.init.headers.authorization, /^bearer [\w-]+\.[\w-]+\.[\w-]+$/);
  const payload = JSON.parse(seen.init.body);
  assert.equal(payload.c, "abc");
  assert.equal(payload.aps["mutable-content"], 1);
});

test("rejects a tampered body", async () => {
  const { env, request } = await setup();
  globalThis.fetch = async () => assert.fail("must not reach APNs");
  const res = await worker.fetch(await request({ token, c: "abc" }, { tamper: true }), env);
  assert.equal(res.status, 401);
});

test("rejects a stale request", async () => {
  const { env, request } = await setup();
  globalThis.fetch = async () => assert.fail("must not reach APNs");
  const res = await worker.fetch(await request({ token, c: "abc" }, { time: 1000 }), env);
  assert.equal(res.status, 401);
});

test("reports a dead token as 410", async () => {
  const { env, request } = await setup();
  globalThis.fetch = async () => new Response(JSON.stringify({ reason: "Unregistered" }), { status: 410 });
  const res = await worker.fetch(await request({ token, c: "abc" }), env);
  assert.equal(res.status, 410);
  assert.equal((await res.json()).error, "unregistered");
});

async function fcmEnv(env) {
  const rsa = await crypto.subtle.generateKey(
    { name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" },
    true,
    ["sign", "verify"],
  );
  const pk = Buffer.from(await crypto.subtle.exportKey("pkcs8", rsa.privateKey)).toString("base64");
  return {
    ...env,
    FCM_PROJECT_ID: "agy-test",
    // As pasted from the service-account JSON: literal "\n" escapes.
    FCM_CLIENT_EMAIL: `relay-${Math.random()}@agy-test.iam.gserviceaccount.com`,
    FCM_PRIVATE_KEY: `-----BEGIN PRIVATE KEY-----\\n${pk}\\n-----END PRIVATE KEY-----\\n`,
    rsa,
  };
}

const fcmToken = "dQw4w9WgXcQ:APA91bH-Ab_cdEFG1234567890abcdefghijklmnopqrstuvwxyz";

test("forwards an FCM push as a data-only high-priority message and caches the OAuth token", async () => {
  const base = await setup();
  const env = await fcmEnv(base.env);
  const calls = [];
  globalThis.fetch = async (url, init) => {
    calls.push({ url, init });
    if (url === "https://oauth2.googleapis.com/token") {
      const form = new URLSearchParams(init.body);
      assert.equal(form.get("grant_type"), "urn:ietf:params:oauth:grant-type:jwt-bearer");
      const [h, c, s] = form.get("assertion").split(".");
      const claims = JSON.parse(Buffer.from(c, "base64url").toString());
      assert.equal(claims.iss, env.FCM_CLIENT_EMAIL);
      assert.equal(claims.scope, "https://www.googleapis.com/auth/firebase.messaging");
      const ok = await crypto.subtle.verify(
        "RSASSA-PKCS1-v1_5",
        env.rsa.publicKey,
        Buffer.from(s, "base64url"),
        new TextEncoder().encode(`${h}.${c}`),
      );
      assert.ok(ok, "JWT must be signed with the service-account key");
      return new Response(JSON.stringify({ access_token: "ya29.test", expires_in: 3599 }), { status: 200 });
    }
    return new Response("{}", { status: 200 });
  };
  for (let i = 0; i < 2; i++) {
    const res = await worker.fetch(await base.request({ token: fcmToken, platform: "fcm", collapse: "t1", c: `abc${i}` }), env);
    assert.equal(res.status, 200);
  }
  const oauth = calls.filter((x) => x.url === "https://oauth2.googleapis.com/token");
  assert.equal(oauth.length, 1, "access token is cached");
  const sends = calls.filter((x) => x.url === "https://fcm.googleapis.com/v1/projects/agy-test/messages:send");
  assert.equal(sends.length, 2);
  assert.equal(sends[0].init.headers.authorization, "Bearer ya29.test");
  const { message } = JSON.parse(sends[0].init.body);
  assert.equal(message.token, fcmToken);
  assert.deepEqual(message.data, { c: "abc0", collapse: "t1" });
  assert.equal(message.notification, undefined, "data-only: nothing readable for Google to display");
  assert.equal(message.android.priority, "HIGH");
});

test("reports an unregistered FCM token as 410", async () => {
  const base = await setup();
  const env = await fcmEnv(base.env);
  globalThis.fetch = async (url) =>
    url.startsWith("https://oauth2")
      ? new Response(JSON.stringify({ access_token: "t", expires_in: 3600 }))
      : new Response(
          JSON.stringify({ error: { status: "NOT_FOUND", details: [{ errorCode: "UNREGISTERED" }] } }),
          { status: 404 },
        );
  const res = await worker.fetch(await base.request({ token: fcmToken, platform: "fcm", c: "abc" }), env);
  assert.equal(res.status, 410);
  assert.equal((await res.json()).error, "unregistered");
});

test("requests without a platform still go to APNs, and unknown platforms are refused", async () => {
  const { env, request } = await setup();
  let url;
  globalThis.fetch = async (u) => {
    url = u;
    return new Response("", { status: 200 });
  };
  assert.equal((await worker.fetch(await request({ token, c: "abc" }), env)).status, 200);
  assert.match(url, /^https:\/\/api\.push\.apple\.com\/3\/device\//);
  globalThis.fetch = async () => assert.fail("must not send");
  assert.equal((await worker.fetch(await request({ token, platform: "webpush", c: "abc" }), env)).status, 400);
  assert.equal((await worker.fetch(await request({ token: "bad token!", platform: "fcm", c: "abc" }), env)).status, 400);
});

test("a signed request is delivered only once", async () => {
  const { env, request } = await setup();
  let calls = 0;
  globalThis.fetch = async () => (calls++, new Response("", { status: 200 }));
  const req = await request({ token, c: "replay-me" });
  const again = req.clone();
  assert.equal((await worker.fetch(req, env)).status, 200);
  assert.equal((await worker.fetch(again, env)).status, 409);
  assert.equal(calls, 1);
});

test("the per-phone limit holds even with a new key for every request", async () => {
  const counts = new Map();
  const limiter = (max) => ({
    limit: async ({ key }) => {
      const n = (counts.get(key) || 0) + 1;
      counts.set(key, n);
      return { success: n <= max };
    },
  });
  globalThis.fetch = async () => new Response("", { status: 200 });
  let delivered = 0;
  for (let i = 0; i < 50; i++) {
    const { env, request } = await setup(); // fresh sender key each time
    Object.assign(env, { SENDER_LIMIT: limiter(60), TOKEN_LIMIT: limiter(30), IP_LIMIT: limiter(1000) });
    if ((await worker.fetch(await request({ token, c: `n${i}` }), env)).status === 200) delivered++;
  }
  assert.equal(delivered, 30);
});

test("Apple's reasons are not passed on", async () => {
  const { env, request } = await setup();
  globalThis.fetch = async () => new Response(JSON.stringify({ reason: "BadDeviceToken" }), { status: 400 });
  const res = await worker.fetch(await request({ token, c: "abc" }), env);
  assert.equal(res.status, 502);
  assert.equal((await res.json()).error, "rejected");
});

test("a replay sent to another isolate is caught by the shared limiter", async () => {
  const { env, request } = await setup();
  const used = new Set();
  env.REPLAY_LIMIT = { limit: async ({ key }) => ({ success: !used.has(key) && !!used.add(key) }) };
  globalThis.fetch = async () => new Response("", { status: 200 });
  const req = await request({ token, c: "elsewhere" });
  const copy = req.clone();
  assert.equal((await worker.fetch(req, env)).status, 200);
  // A fresh isolate has an empty in-memory cache; simulate it by re-importing the module.
  const other = (await import(`../src/index.js?isolate=${Date.now()}`)).default;
  assert.equal((await other.fetch(copy, env)).status, 409);
});

test("requests signed over a minute ago are refused; a fast clock is tolerated", async () => {
  const { env, request } = await setup();
  globalThis.fetch = async () => new Response("", { status: 200 });
  const now = Math.floor(Date.now() / 1000);
  assert.equal((await worker.fetch(await request({ token, c: "old" }, { time: now - 90 }), env)).status, 401);
  assert.equal((await worker.fetch(await request({ token, c: "ahead" }, { time: now + 120 }), env)).status, 200);
});

const countingLimiter = (max, counts = new Map()) => ({
  limit: async ({ key }) => {
    const n = (counts.get(key) || 0) + 1;
    counts.set(key, n);
    return { success: n <= max };
  },
});

test("the per-phone limit counts an APNs token once, however its hex is spelled", async () => {
  const limiter = countingLimiter(30);
  const seen = new Set();
  globalThis.fetch = async (url) => (seen.add(url), new Response("", { status: 200 }));
  let delivered = 0;
  for (let i = 0; i < 50; i++) {
    const { env, request } = await setup();
    env.TOKEN_LIMIT = limiter;
    // The same token with a different mix of upper and lower case each time.
    const spelled = [...token].map((ch, j) => ((i >> j % 6) & 1 ? ch.toUpperCase() : ch)).join("");
    if ((await worker.fetch(await request({ token: spelled, c: `n${i}` }), env)).status === 200) delivered++;
  }
  assert.equal(delivered, 30);
  assert.deepEqual([...seen], [`https://api.push.apple.com/3/device/${token}`], "Apple gets the lowercase token");
});

test("addresses in one IPv6 /64 share a limit; IPv4 addresses don't", async () => {
  assert.equal(senderNetwork("2001:db8:1:2::1"), senderNetwork("2001:0DB8:0001:0002:ffff:ffff:ffff:ffff"));
  assert.equal(senderNetwork("2001:db8:1:2::1"), "ip6:2001:db8:1:2");
  assert.equal(senderNetwork("2001:db8::1"), "ip6:2001:db8:0:0");
  assert.equal(senderNetwork("::1"), "ip6:0:0:0:0");
  assert.notEqual(senderNetwork("2001:db8:1:2::1"), senderNetwork("2001:db8:1:3::1"));
  assert.notEqual(senderNetwork("203.0.113.9"), senderNetwork("203.0.113.10"));
  assert.equal(senderNetwork(""), "");

  const limiter = countingLimiter(120);
  globalThis.fetch = async () => new Response("", { status: 200 });
  let delivered = 0;
  for (let i = 0; i < 150; i++) {
    const { env, request } = await setup();
    env.IP_LIMIT = limiter;
    const req = await request({ token: i.toString(16).padStart(64, "0"), c: "x" });
    req.headers.set("CF-Connecting-IP", `2001:db8:1:2::${i.toString(16)}`);
    if ((await worker.fetch(req, env)).status === 200) delivered++;
  }
  assert.equal(delivered, 120);
});

test("the overall limit stops a flood from many keys, phones and addresses", async () => {
  const limiter = countingLimiter(5);
  globalThis.fetch = async () => new Response("", { status: 200 });
  const statuses = [];
  for (let i = 0; i < 7; i++) {
    const { env, request } = await setup();
    env.GLOBAL_LIMIT = limiter;
    const req = await request({ token: i.toString(16).padStart(64, "0"), c: "x" });
    req.headers.set("CF-Connecting-IP", `198.51.100.${i}`);
    statuses.push((await worker.fetch(req, env)).status);
  }
  assert.deepEqual(statuses, [200, 200, 200, 200, 200, 429, 429]);
});

test("a replay with the signature spelled differently is still a replay", async () => {
  const { env, request } = await setup();
  let calls = 0;
  globalThis.fetch = async () => (calls++, new Response("", { status: 200 }));
  const first = await request({ token, c: "respell" });
  const sig = first.headers.get("X-Agy-Sig");
  const body = await first.clone().text();
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
  // 64 bytes end with 4 unused bits in the last character: other values there decode the same.
  const spare = alphabet[(alphabet.indexOf(sig.at(-1)) & 0b110000) | 0b000101];
  const spellings = [sig + "=", sig + "==", sig.replace(/-/g, "+").replace(/_/g, "/"), sig.slice(0, -1) + spare];
  assert.equal((await worker.fetch(first, env)).status, 200);
  for (const respelled of spellings) {
    const headers = new Headers(first.headers);
    headers.set("X-Agy-Sig", respelled);
    const again = new Request(first.url, { method: "POST", headers, body });
    assert.equal((await worker.fetch(again, env)).status, 409, respelled);
  }
  assert.equal(calls, 1);
});

test("a collapse id that isn't a plain id is left out instead of breaking the request", async () => {
  const { env, request } = await setup();
  for (const collapse of ["a\r\nx-evil: 1", "\u3042", "x".repeat(65), 7]) {
    let seen;
    globalThis.fetch = async (url, init) => {
      new Headers(init.headers); // throws on a value no HTTP header can carry
      seen = init;
      return new Response("", { status: 200 });
    };
    const res = await worker.fetch(await request({ token, collapse, c: `c${String(collapse).length}` }), env);
    assert.equal(res.status, 200);
    assert.equal(seen.headers["apns-collapse-id"], undefined);
    assert.equal(JSON.parse(seen.body).aps["thread-id"], undefined);
  }
});

test("an oversized body is refused without being read to the end", async () => {
  const { env } = await setup();
  globalThis.fetch = async () => assert.fail("must not send");
  // Declared too large: not read at all.
  const declared = { method: "POST", url: "https://relay.test/v1/push", headers: new Headers({ "Content-Length": "5000" }) };
  Object.defineProperty(declared, "body", { get: () => assert.fail("must not read the body") });
  assert.equal((await worker.fetch(declared, env)).status, 413);
  // Not declared (streamed): reading stops at the limit.
  let pulled = 0;
  const endless = new ReadableStream({ pull: (c) => (pulled++, c.enqueue(new Uint8Array(1024))) }, { highWaterMark: 0 });
  const streamed = new Request("https://relay.test/v1/push", { method: "POST", body: endless, duplex: "half" });
  assert.equal((await worker.fetch(streamed, env)).status, 413);
  assert.ok(pulled <= 6, `read ${pulled} KiB of an endless body`);
});

test("the sender-network limit is checked before the signature", async () => {
  const { env } = await setup();
  env.IP_LIMIT = countingLimiter(2);
  globalThis.fetch = async () => assert.fail("must not send");
  const statuses = [];
  for (let i = 0; i < 4; i++) {
    const req = new Request("https://relay.test/v1/push", {
      method: "POST",
      body: "{}",
      headers: { "CF-Connecting-IP": "203.0.113.7", "X-Agy-Time": "1", "X-Agy-Key": "x", "X-Agy-Sig": "y" },
    });
    statuses.push((await worker.fetch(req, env)).status);
  }
  assert.deepEqual(statuses, [401, 401, 429, 429]);
});

test("JSON that isn't an object, and an unreachable Apple, get an answer instead of a crash", async () => {
  const { env, request } = await setup();
  globalThis.fetch = async () => assert.fail("must not reach APNs");
  for (const body of [null, 5, "text"]) {
    const res = await worker.fetch(await request(body), env);
    assert.equal(res.status, 400);
  }
  globalThis.fetch = async () => {
    throw new Error("network down");
  };
  const res = await worker.fetch(await request({ token, c: "abc" }), env);
  assert.equal(res.status, 502);
  assert.equal((await res.json()).error, "APNs unavailable");
});

/** The one grant in a grant push (`g`): `{ grant, key, expires }`. */
function onlyGrant(payload) {
  const g = JSON.parse(payload.g);
  assert.equal(g.grants.length, 1);
  return { ...g.grants[0], expires: g.expires };
}

/** Asks for a grant the way a phone does and returns what the relay pushed to the phone. */
async function askGrant(env, body, ip = "198.51.100.1") {
  let pushed = null;
  globalThis.fetch = async (url, init) => {
    pushed = { url: String(url), init, payload: JSON.parse(init.body) };
    return new Response("", { status: 200 });
  };
  const res = await worker.fetch(
    new Request("https://relay.test/v1/grant", { method: "POST", headers: { "CF-Connecting-IP": ip }, body: JSON.stringify(body) }),
    env,
  );
  return { res, pushed, text: await res.text() };
}

const upTo = (limit) => {
  const seen = new Map();
  return {
    limit: async ({ key }) => {
      seen.set(key, (seen.get(key) || 0) + 1);
      return { success: seen.get(key) <= limit };
    },
  };
};

test("a grant goes to the phone as a push, never in the reply, and lets its connector send", async () => {
  const { env, request, pub } = await setup();
  env.GRANT_SECRET = "relay secret for tests";
  const asked = await askGrant(env, { token: token.toUpperCase(), key: pub });
  assert.equal(asked.res.status, 200);
  assert.equal(asked.text, JSON.stringify({ ok: true }), "the reply carries no grant");
  assert.equal(asked.pushed.url, `https://api.push.apple.com/3/device/${token}`);
  // A background push: nothing shown, nothing for the notification extension.
  assert.deepEqual(asked.pushed.payload.aps, { "content-available": 1 });
  assert.equal(asked.pushed.init.headers["apns-push-type"], "background");
  assert.equal(asked.pushed.init.headers["apns-priority"], "5");
  const g = onlyGrant(asked.pushed.payload);
  assert.equal(g.key, pub);
  assert.ok(g.expires > Date.now() / 1000 + 40 * 86400);

  // Requests without a grant have no budget left; with the grant it still goes through.
  env.UNGRANTED_LIMIT = upTo(0);
  let delivered = 0;
  globalThis.fetch = async () => {
    delivered++;
    return new Response("", { status: 200 });
  };
  assert.equal((await worker.fetch(await request({ token, c: "plain" }), env)).status, 429);
  assert.equal((await worker.fetch(await request({ token, c: "granted", g: g.grant, gx: g.expires }), env)).status, 200);
  assert.equal(delivered, 1);
});

test("a grant only works for its own phone, connector and time", async () => {
  const { env, request, pub } = await setup();
  env.GRANT_SECRET = "relay secret for tests";
  const g = onlyGrant((await askGrant(env, { token, key: pub })).pushed.payload);
  globalThis.fetch = async () => assert.fail("must not reach APNs");
  const refused = async (body) => assert.equal((await worker.fetch(await request(body), env)).status, 403, JSON.stringify(body).slice(0, 60));
  await refused({ token: "b".repeat(64), c: "1", g: g.grant, gx: g.expires }); // another phone
  await refused({ token, platform: "fcm", c: "2", g: g.grant, gx: g.expires }); // another push service
  await refused({ token, c: "3", g: g.grant, gx: g.expires + 1 }); // a later expiry than was granted
  await refused({ token, c: "4", g: g.grant.slice(0, -2) + "AA", gx: g.expires });
  await refused({ token, c: "5", g: "", gx: g.expires });
  await refused({ token, c: "6", g: g.grant, gx: String(g.expires) });
  await refused({ token, c: "7", g: g.grant }); // no expiry
  await refused({ token, c: "8", g: { length: 43 }, gx: g.expires });

  // Another connector with this phone's grant.
  const other = await setup();
  other.env.GRANT_SECRET = env.GRANT_SECRET;
  assert.equal((await worker.fetch(await other.request({ token, c: "9", g: g.grant, gx: g.expires }), other.env)).status, 403);

  // A relay with another secret (or after the secret was changed).
  env.GRANT_SECRET = "changed";
  await refused({ token, c: "10", g: g.grant, gx: g.expires });

  // Expired: a grant made for the past, signed with the right secret, is still refused.
  const realNow = Date.now;
  Date.now = () => realNow() - 50 * 86400 * 1000;
  const old = onlyGrant((await askGrant(env, { token, key: pub })).pushed.payload);
  Date.now = realNow;
  globalThis.fetch = async () => assert.fail("must not reach APNs");
  await refused({ token, c: "11", g: old.grant, gx: old.expires });
});

test("asking for grants has limits of its own and can't use up the notification budget", async () => {
  const { env, request, pub } = await setup();
  Object.assign(env, { GRANT_SECRET: "s", GRANT_IP_LIMIT: upTo(5), GRANT_TOKEN_LIMIT: upTo(3), GRANT_GLOBAL_LIMIT: upTo(120), GLOBAL_LIMIT: upTo(600), IP_LIMIT: upTo(30) });
  let sent = 0;
  for (let ip = 0; ip < 60; ip++) {
    for (let i = 0; i < 10; i++) {
      const junk = (ip * 100 + i).toString(16).padStart(64, "0");
      const r = await askGrant(env, { token: junk, key: pub }, `203.0.113.${ip}`);
      if (r.res.status === 200) sent++;
    }
  }
  assert.equal(sent, 120, "600 requests from 60 addresses: capped by the overall grant limit");
  // One phone can't be pestered with grant pushes either.
  Object.assign(env, { GRANT_GLOBAL_LIMIT: upTo(1000) });
  let toVictim = 0;
  for (let ip = 0; ip < 10; ip++) if ((await askGrant(env, { token, key: pub }, `192.0.2.${ip}`)).res.status === 200) toVictim++;
  assert.equal(toVictim, 3);
  // Notifications still go out.
  globalThis.fetch = async () => new Response("", { status: 200 });
  assert.equal((await worker.fetch(await request({ token: "c".repeat(64), c: "abc" }), env)).status, 200);
});

test("grant requests are checked like pushes, and a relay without a secret hands out none", async () => {
  const { env, pub } = await setup();
  assert.equal((await askGrant(env, { token, key: pub })).res.status, 404);
  env.GRANT_SECRET = "s";
  const tooMany = Array.from({ length: 17 }, (_, i) => b64url(new Uint8Array(32).fill(i)));
  for (const body of [null, { token: "../x", key: pub }, { token, key: "short" }, { token, key: 5 }, { token, key: pub, platform: "sms" }, { key: pub },
    { token, keys: [] }, { token, keys: [pub, "short"] }, { token, keys: tooMany }, { token, keys: pub }]) {
    const r = await askGrant(env, body);
    assert.equal(r.res.status, 400, JSON.stringify(body));
    assert.equal(r.pushed, null);
  }
});

test("without a secret, a request that carries a grant is treated like any other", async () => {
  const { env, request } = await setup();
  globalThis.fetch = async () => new Response("", { status: 200 });
  assert.equal((await worker.fetch(await request({ token, c: "abc", g: "anything", gx: 1 }), env)).status, 200);
});

test("one request gets grants for all of a phone's computers, each good only for its own", async () => {
  const { env, request, pub } = await setup();
  const second = await setup();
  Object.assign(env, { GRANT_SECRET: "s", GRANT_TOKEN_LIMIT: upTo(3) });
  second.env.GRANT_SECRET = "s";
  const asked = await askGrant(env, { token, keys: [pub, second.pub, pub] });
  assert.equal(asked.res.status, 200);
  const g = JSON.parse(asked.pushed.payload.g);
  assert.deepEqual(g.grants.map((x) => x.key), [pub, second.pub], "duplicates dropped, order kept");
  globalThis.fetch = async () => new Response("", { status: 200 });
  const mine = g.grants[0].grant, theirs = g.grants[1].grant;
  assert.equal((await worker.fetch(await request({ token, c: "1", g: mine, gx: g.expires }), env)).status, 200);
  assert.equal((await worker.fetch(await second.request({ token, c: "2", g: theirs, gx: g.expires }), second.env)).status, 200);
  assert.equal((await worker.fetch(await request({ token, c: "3", g: theirs, gx: g.expires }), env)).status, 403);
  // Sixteen computers still take one request, so the per-phone limit isn't touched by many computers.
  const many = Array.from({ length: 16 }, (_, i) => b64url(new Uint8Array(32).fill(i + 1)));
  const big = await askGrant(env, { token: "d".repeat(64), keys: many });
  assert.equal(big.res.status, 200);
  assert.equal(JSON.parse(big.pushed.payload.g).grants.length, 16);
  assert.ok(Buffer.byteLength(big.pushed.init.body) < 4096, "fits in one push");
});

test("an FCM grant push is data-only and short-lived", async () => {
  const base = await setup();
  const { pub } = base;
  const env = { ...(await fcmEnv(base.env)), GRANT_SECRET: "s" };
  const sent = [];
  globalThis.fetch = async (url, init) => {
    sent.push({ url: String(url), init });
    if (String(url).includes("oauth2")) return new Response(JSON.stringify({ access_token: "at", expires_in: 3600 }), { status: 200 });
    return new Response("{}", { status: 200 });
  };
  const res = await worker.fetch(
    new Request("https://relay.test/v1/grant", { method: "POST", body: JSON.stringify({ token: fcmToken, platform: "fcm", keys: [pub] }) }),
    env,
  );
  assert.equal(res.status, 200);
  const message = JSON.parse(sent.find((s) => s.url.includes("fcm.googleapis")).init.body).message;
  assert.equal(message.notification, undefined);
  assert.equal(message.android.ttl, "60s");
  assert.equal(JSON.parse(message.data.g).grants[0].key, pub);
});
