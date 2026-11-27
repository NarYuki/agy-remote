// AGY Remote push relay — Cloudflare configuration (cf CLI).
// SPDX-License-Identifier: AGPL-3.0-only
import { bindings, defineConfig } from "cf/config";

export default defineConfig({
  worker: {
    name: "agy-remote-push-relay",
    entrypoint: "./src/index.js",
    compatibilityDate: "2026-11-01",
    // Official relay address. Self-hosters: put your own domain here (or turn workersDev on instead).
    domains: ["noti.agy.yutorinatsu.moe"],
    workersDev: false,
    env: {
      // Bundle id of the app that receives notifications.
      APNS_TOPIC: bindings.text("moe.n4tsu.agyremote"),
      // Per-connector rate limit.
      SENDER_LIMIT: bindings.rateLimit({ namespace: "1001", simple: { limit: 60, period: 60 } }),
      // Per phone (push token) and per sender network (an IPv4 address or an IPv6 /64): a new key
      // per request gets around the limit above.
      TOKEN_LIMIT: bindings.rateLimit({ namespace: "1002", simple: { limit: 30, period: 60 } }),
      // A connector sends a handful a minute, so 30 is ample for a home or office address, and it
      // takes 20 addresses (not 5) of made-up requests to use up the overall limit below.
      IP_LIMIT: bindings.rateLimit({ namespace: "1003", simple: { limit: 30, period: 60 } }),
      // Everything this relay forwards, per Cloudflare location: a flood from many addresses can't
      // run up rejected sends with Apple or Google. Raise it if real traffic gets near.
      GLOBAL_LIMIT: bindings.rateLimit({ namespace: "1005", simple: { limit: 600, period: 60 } }),
      // Each signature once per minute, everywhere (requests older than a minute are refused anyway).
      REPLAY_LIMIT: bindings.rateLimit({ namespace: "1004", simple: { limit: 1, period: 60 } }),
      // Requests without a grant (apps and connectors from before grants): a budget of their own,
      // checked before the overall one, so they can't fill it. Lower it as old versions disappear.
      UNGRANTED_LIMIT: bindings.rateLimit({ namespace: "1006", simple: { limit: 200, period: 60 } }),
      // Asking for a grant (POST /v1/grant) is rare: per sender network, per phone, and in all.
      // These are separate from everything above, so a flood of asking can't stop notifications.
      GRANT_IP_LIMIT: bindings.rateLimit({ namespace: "1007", simple: { limit: 5, period: 60 } }),
      GRANT_TOKEN_LIMIT: bindings.rateLimit({ namespace: "1008", simple: { limit: 3, period: 60 } }),
      GRANT_GLOBAL_LIMIT: bindings.rateLimit({ namespace: "1009", simple: { limit: 120, period: 60 } }),
      // GRANT_SECRET, which signs grants, is optional and so not declared here (a declared secret
      // must be set before every deploy). Without it the relay hands out no grants and treats every
      // push as before. Turn grants on by setting it on the Worker to a long random text, e.g. from
      // `openssl rand -base64 48`. Changing it makes every grant invalid; phones get new ones on
      // their own.
      // Set with `cf workers secrets put` (or `cf deploy --secrets-file`); never committed.
      APNS_KEY_P8: bindings.secret(),
      APNS_KEY_ID: bindings.secret(),
      APNS_TEAM_ID: bindings.secret(),
      // Firebase Cloud Messaging (Android): from the Firebase service-account JSON key
      // (project_id, client_email, private_key). Optional; without them only iOS is served.
      FCM_PROJECT_ID: bindings.secret(),
      FCM_CLIENT_EMAIL: bindings.secret(),
      FCM_PRIVATE_KEY: bindings.secret(),
    },
  },
});
