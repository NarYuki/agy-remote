# AGY Remote push relay

A tiny Cloudflare Worker that delivers notifications from `agy-connector` to the AGY Remote app
through Apple Push Notification service (APNs) on iOS and Firebase Cloud Messaging (FCM) on Android.

License: AGPL-3.0-only (see `../LICENSE`).

## What it can and cannot see

Notifications are **end-to-end encrypted** between your computer and your phone with
ChaCha20-Poly1305. The 256-bit key is created on the phone and handed to the connector over the
already-encrypted P2P link; the relay never receives it.

| The relay sees | The relay never sees |
| --- | --- |
| APNs device token or FCM registration token | chat titles, replies, commands, file names |
| connector's public key (to verify the signature) | your computer's name or address |
| time of each notification | which chat a notification belongs to (the thread id is a keyed hash) |
| a fixed-size (540-byte) ciphertext | |

Nothing is stored or logged. Each request is checked and forwarded to Apple or Google, then forgotten.
Android messages are data-only (no notification text at all): the app decrypts the blob and shows
the notification itself, so Google sees the same opaque ciphertext Apple does.

## Request format

`POST /v1/push`, signed with the connector's Ed25519 P2P identity:

```
X-Agy-Key:  base64url(public key)
X-Agy-Time: unix seconds            (rejected if over 60 s old or 5 minutes ahead)
X-Agy-Sig:  base64url(Ed25519 signature of "<time>\n<body>")

{"token": "<hex | FCM token>", "platform": "apns" | "fcm", "env": "production" | "sandbox",
 "collapse": "<opaque>", "c": "<base64>"}
```

`platform` is optional and defaults to `apns` (the connector leaves it out for iOS phones); `env`
only applies to APNs. A dead token (APNs `410`, FCM `UNREGISTERED`) is answered with `410`; any other
refusal is a plain `502 rejected` (the push service's reason is not passed on).

Requests are rate limited per connector key (60 per minute), per push token (30 per minute; an
APNs token counts once however its hex is spelled), per sender network (30 per minute; an IPv4
address or an IPv6 /64) and in all (600 per minute per Cloudflare location, `GLOBAL_LIMIT`). A signed request is accepted once; a repeat gets `409` (checked across all Cloudflare locations).

### Grants

Anyone can make a key and sign a request, so a signature alone doesn't say a phone wants it. A
phone that turns notifications on therefore asks the relay for a grant:

```
POST /v1/grant
{"token": "<hex | FCM token>", "platform": "apns" | "fcm", "env": "production" | "sandbox",
 "keys": ["<a connector's public key, as in X-Agy-Key>", …]}
```

One request names all of the phone's computers (up to 16 keys; `"key": "…"` for a single one).
The reply is only `{"ok": true}`. The grants go out as one push to that token (`g`, a JSON text
`{"expires", "grants": [{"key", "grant"}, …]}`), so only the phone that really has the token
receives them. On iOS it is a background push (`apns-push-type: background`, nothing shown); on
Android an FCM data message. Either way only the app sees it. The phone
gives each to its connector, which adds `"g": "<grant>", "gx": <expires>` to each push. A grant is
`HMAC-SHA256(GRANT_SECRET, "agy-grant-v1\n<platform>\n<token>\n<key>\n<expires>")`, good for 45
days; the relay stores nothing and checks it by computing it again. A push whose grant doesn't
check out gets `403 bad grant` (the connector then sends without it, and the phone asks anew).

Pushes with a grant are the only ones counted against the overall limit alone. Pushes without one
(apps and connectors from before grants) must first fit `UNGRANTED_LIMIT` (200 per minute for all
of them together), so made-up requests can't use up the budget of phones that have a grant. Asking
for a grant has limits of its own (`GRANT_IP_LIMIT` 5 per minute per sender network,
`GRANT_TOKEN_LIMIT` 3 per minute per phone, `GRANT_GLOBAL_LIMIT` 120 per minute in all), apart
from the ones for pushes. Without `GRANT_SECRET` the relay hands out no grants (`404`) and treats
every push as before. It isn't declared in `cloudflare.config.ts` (a declared secret has to be set
before every deploy); turn grants on by setting it on the deployed Worker to a long random text,
e.g. from `openssl rand -base64 48`.

## Self-hosting

APNs only delivers to apps signed by the same Apple Developer team as the key, so **the App Store
build of AGY Remote only works with the official relay**. Run your own relay if you build the app
yourself:

Configuration lives in `cloudflare.config.ts` (deployed with the official `cf` CLI). Put the
secrets in a JSON file **outside the repository** and pass it at deploy time:

```sh
npm install
npx cf auth login
# secrets.json: {"APNS_KEY_P8": "<contents of AuthKey_XXXXXXXXXX.p8>", "APNS_KEY_ID": "...", "APNS_TEAM_ID": "...",
#                "FCM_PROJECT_ID": "...", "FCM_CLIENT_EMAIL": "...@....iam.gserviceaccount.com",
#                "FCM_PRIVATE_KEY": "-----BEGIN PRIVATE KEY-----\n...\n-----END PRIVATE KEY-----\n"}
npx cf deploy --secrets-file /path/outside/repo/secrets.json
```

Change `APNS_TOPIC` in `cloudflare.config.ts` to your app's bundle id. The three `FCM_*` values
come from a Firebase service-account key (Project settings → Service accounts → Generate new
private key): `project_id`, `client_email` and `private_key`. They are optional; without them
Android pushes get `502 FCM not configured`. The OAuth access token minted from that key is
cached in memory for about 55 minutes. Setup for the Android app: [`../docs/push-android.md`](../docs/push-android.md).

Then point your connector at it:

```sh
agy-connector set-relay https://your-relay.example.workers.dev
```

## Tests

```sh
npm test
```
