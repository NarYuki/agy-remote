# AGY Remote protocol, version 1

How the iOS app talks to `agy-connector`. Transport, pairing and authentication are already
implemented (`server/agy-p2p-common`, `server/agy-connector/src/main.rs`): each request is one QUIC
bi-directional stream carrying one length-prefixed JSON frame from the app:

```json
{ "auth_token": "…", "request_id": "uuid", "method": "chats.list", "params": { } }
```

For ordinary methods the connector writes one JSON frame back and closes the stream. For
`turn.start` it writes a sequence of event frames, the last being `turn.finished`, `turn.stopped`
or `turn.failed`.

## Conventions

- Field names are `camelCase`. Times are RFC 3339 strings. IDs are agy's own where they exist.
- Every reply has `"ok": true` or `"ok": false, "error": { "code": "…", "message": "…" }`.
  `message` is English for logs; the app shows its own text based on `code`.
- Error codes: `bad_request`, `not_found`, `folder_required`, `open_in_cli`, `busy`,
  `agy_unavailable`, `not_allowed`, `not_paired`, `not_text`, `pair_pending`, `pairing_closed`,
  `internal`.
- `auth_token` is the phone's own token, issued by `pair.confirm`. A request is served only if
  the phone's P2P endpoint id is on the computer's list of paired phones and the token is that
  phone's (`not_paired` otherwise). Until a phone is paired, its requests are limited to 64 KiB and
  only `pair.begin` / `pair.confirm` are answered.
- Unknown fields are ignored on both sides, so either side can add fields without breaking the other.
- `protocol` in `connector.info` is the version number below; the app refuses connectors whose
  `protocol` it doesn't support and says which side to update.

## Methods

### Pairing: `pair.begin` / `pair.confirm`
The only methods served without a token. `agy-connector pair` shows a code
`agy-rem://pair?id=<computer>&ps=<one-time secret>&name=…&addrs=…` (no `token`), valid once for ten
minutes.

1. `pair.begin` `{ "secret": "<ps>" }` → `{ "ok": true, "computer": "<name>", "code": "123456" }`.
   The first phone to send it holds the code (`busy` for any other). `code` is
   HMAC-SHA256(secret, `agy-pair-v1|<computer id>|<phone id>`) mod 10⁶; the phone shows it.
2. The user types that number into `agy-connector pair` on the computer, which never displays it:
   seeing the computer's screen is not enough to pair. Three wrong numbers close the code.
3. Meanwhile the phone asks `pair.confirm` `{ "secret": "<ps>" }` about once a second. Until the
   number was typed the answer is `pair_pending`; then
   `{ "ok": true, "ticket": "agy-rem://pair?id=…&token=<this phone's token>&…" }`, the ticket the
   phone keeps. Once used, expired or closed: `pairing_closed`.

An `agy-rem://pair` link opened from outside the app (a web page, another app) contacts nobody
until the user agrees; a scanned or pasted code starts at once.

### `connector.info`
No params. Returns the computer and agy, plus app-wide settings.

```json
{ "ok": true, "protocol": 1,
  "computer": { "name": "Mac Pro", "model": "MacPro7,1", "os": "macOS 26.4" },
  "agy": { "name": "…", "version": "…" },
  "connector": { "version": "0.3.0", "minAppVersion": "1.1", "autoUpdate": true, "updateNote": null },
  "folders": [ { "path": "/Users/me/app", "name": "app", "kind": "swift" } ],
  "models": [ { "id": "…", "name": "…", "efforts": [ { "id": "high", "name": "High" } ] } ],
  "modes": [ { "id": "default", "name": "Default", "description": "…" }, { "id": "turbo", "name": "Turbo" } ],
  "defaults": { "model": "…", "effort": "…", "mode": "default" },
  "commands": [ { "name": "context", "description": "…" } ] }
```

`connector.push` (`{ "relay": "https://…", "key": "<base64url>" }`, absent when notifications aren't
available) is the push relay this computer sends through and the key it signs with there. The app
asks that relay for a grant with them (see `notifications.register`). The app only contacts an
`https://` address.

`folders` leaves out the home folder, `/` and folders above home, even if agy ran there, unless
they were added with `agy-connector add-workspace`.

`kind` is a short hint for an icon (`swift`, `rust`, `node`, `python`, `go`, or `null`).

### `chats.list`
No params. Returns every chat agy knows, newest first.

```json
{ "ok": true, "chats": [ {
    "id": "…", "title": "…", "folder": "/Users/me/app" | null, "updated": "…",
    "state": "idle" | "running" | "open_in_cli" } ] }
```

`folder` is `null` when agy doesn't report one (chats started in the interactive CLI) and none has
been assigned with `chat.set_folder`. `running` means a turn started from the app is in progress.

### `chat.open`
`{ "chatId": "…" }`. Returns the conversation as it is now (read fresh from agy, see bridge.md).

```json
{ "ok": true, "chatId": "…", "state": "idle" | "running" | "open_in_cli",
  "readable": true | false, "unreadableReason": "desktop_app" | "cli_unsaved" | null,
  "context": { "used": 51234, "size": 1048576 } | null,
  "options": { "model": "…", "effort": "…", "mode": "…" },
  "messages": [ Message, … ] }
```

`Message`:

```json
{ "id": "…", "from": "user" | "agent",
  "text": "…",                         // Markdown for agent messages
  "attachments": [ { "kind": "image" | "file", "name": "…" } ],   // user messages
  "thinking": "…" | null,               // agent: summary of its reasoning
  "steps": [ Step, … ],                  // agent: tool calls, in order
  "plan": [ { "text": "…", "status": "pending" | "active" | "done" } ],
  "approvals": [ Approval, … ],          // agent: questions still waiting for an answer
  "seconds": 12.4 | null,                // agent: how long the turn took, if known
  "live": true | false }                 // agent: the turn is still running
```

`Step`: `{ "id": "…", "kind": "read" | "edit" | "delete" | "move" | "search" | "run" | "fetch" | "think" | "other", "title": "…", "status": "running" | "done" | "failed", "detail": "…" | null }`.

`Approval`:

```json
{ "id": "…", "what": "run" | "write" | "edit" | "other", "title": "…",
  "command": "…" | null, "path": "…" | null, "before": "…" | null, "after": "…" | null,
  "choices": [ { "id": "…", "label": "…", "effect": "allow_once" | "allow_always" | "deny_once" | "deny_always" } ] }
```

### `chat.set_folder`
`{ "chatId": "…", "folder": "/path" }`. Records which folder a chat without one works in. The folder
must be one of `connector.info` `folders`. Returns `{ "ok": true }`.

### `chat.set_option`
`{ "chatId": "…" | null, "option": "model" | "effort" | "mode", "value": "…" }`. With `chatId`
null, the value becomes the default for the next new chat. Returns `{ "ok": true, "options": {…} }`.
`mode: "turbo"` is `not_allowed` (and Turbo is missing from `connector.info` `modes`) when the
computer has turned it off with `agy-connector set-turbo off`.

### `turn.start` (streamed)
```json
{ "chatId": "…" | null, "folder": "/path" | null, "text": "…",
  "attachments": [ { "kind": "image", "name": "a.png", "mime": "image/png", "data": "base64…" },
                   { "kind": "file", "path": "relative/in/folder.swift" } ],
  "options": { "model": "…", "effort": "…", "mode": "…" } }
```

`chatId` null starts a new chat in `folder`. Events, each a frame:

| `event` | Fields |
| --- | --- |
| `turn.chat` | `chatId`, `folder` — sent first; tells a new chat its ID |
| `turn.thinking` | `text` (append) |
| `turn.text` | `text` (append to the reply) |
| `turn.step` | `step` (Step; same `id` again means an update) |
| `turn.plan` | `plan` (replaces the whole plan) |
| `turn.context` | `used`, `size` |
| `turn.title` | `title` |
| `turn.approval` | `approval` (Approval) |
| `turn.notice` | `code` — currently `cli_touched`: the agy CLI opened this chat during the turn |
| `turn.finished` | `seconds` |
| `turn.stopped` | — (stopped with `turn.stop`) |
| `turn.failed` | `error` (`code`, `message`) |

If the stream breaks (the phone goes away), the turn keeps running on the computer; the app sees
it again with `chat.open` (`state: running`, last message `live: true`, any waiting `approvals`).

### `turn.stop`
`{ "chatId": "…" }`. Stops the running turn. Returns `{ "ok": true }`.

### `approval.answer`
`{ "approvalId": "…", "choice": "<choice id>" | "allow" | "deny" }`. `allow` and `deny` pick the
one-time allow or deny choice (used by notification buttons, which don't know the choice IDs).
Returns `{ "ok": true }`; `not_found` if the question is no longer waiting.

### `files.search`
`{ "folder": "/path", "query": "text" }`. Up to 200 files under `folder` whose relative path
contains `query` (case-insensitive), skipping VCS and build folders. `folder` must be a known folder;
anything else is `not_allowed`. Returns `{ "ok": true, "files": [ { "path": "rel/path", "size": 123 } ] }`.

### `files.read`
`{ "path": "file:///abs/path#L3-L9" | "/abs/path" | "rel/path", "chatId": "…" }`. A file an agent
reply links to, for the app to show (it never hands such links to the system). Relative paths
are taken from the chat's folder. Only regular files inside the known folders or agy's artifact
folder (`~/.gemini/antigravity-cli/brain`), after resolving symlinks and `..`, and never inside the
connector's own folder (`not_allowed` otherwise). Returns
`{ "ok": true, "path": "…", "name": "…", "text": "…", "truncated": false }`, at most 1 MiB;
`not_text` for files that aren't UTF-8.

### `limits.get`
`{ "refresh": false }`. Plan limits from agy.

```json
{ "ok": true, "fetched": "…",
  "limits": [ { "id": "…", "group": "…", "window": "week" | "five_hours" | "other", "label": "…",
                "remaining": 82.0, "resets": "…" | null } ],
  "credits": 12.5 | null }
```

### `notifications.register` / `notifications.unregister`
Register: `{ "token": "<APNs hex | FCM token>", "platform": "apns" | "fcm", "environment": "production" | "sandbox", "key": "<64 hex>", "kinds": ["finished", "failed", "approval", "cli_released"] }`.
The app gives every computer its own `key`: a computer can't make notifications that look like
another's, and the key that opens a notification tells the phone which computer to answer.
`platform` is optional and defaults to `apns` (iOS); Android sends `fcm` with its Firebase
registration token, and `environment` is then ignored.
`grant` and `grantExp` (optional) are the relay's grant for this computer and push token, and its
expiry in unix seconds. The app gets them by asking the computer's relay (`POST <relay>/v1/grant`
with `{ "token", "platform", "env", "keys": [<connector.push.key>, …] }`, one request for all
computers on that relay); the relay replies only `{ "ok": true }` and sends
`{ "expires", "grants": [{ "key", "grant" }, …] }` as one push to that token (`g`, a JSON text; a
background push on iOS, a data message on Android, so nothing is shown), and only the phone that
has the token receives it. The app keeps a grant only if it asked for it in
the last ten minutes, registers again with it, and asks anew ten days before it expires. The
computer adds the grant to every notification; the relay serves notifications without one from a
smaller budget. A registration without `grant` keeps the one given earlier for the same token.
The reply has `"grantRefused": true` when the relay refused this grant the last time the computer
used it (the relay's secret changed): the app forgets it and asks again.
Maps onto `push.rs` (`Pusher::register` / `unregister`), translating kind names to the ones
`push.rs` uses. Unregister takes no params.
