# The connector bridge — requirements

`server/agy-connector/src/bridge.rs` is missing and must be written. It implements `docs/protocol.md`
on top of agy's Agent Client Protocol, using the pieces that already exist:

- `acp.rs` — runs `agy acp` and speaks JSON-RPC to it (`AcpProcess::start`, `call`, `notify`,
  `respond`, `subscribe`, `kill`, `is_alive`, `agent_info`).
- `push.rs` — sends end-to-end encrypted notifications (`Pusher`).
- `update.rs` — versions (`CURRENT`, `MIN_APP_VERSION`, `auto_update_enabled`).

Use only agy's public interfaces: ACP over `agy acp`, and `agy -p` for one-off commands. Do not read
or write agy's data files. The single exception is described under "Chats open in the CLI".

## Interface `main.rs` needs

```rust
pub struct Bridge { … }
impl Bridge {
    pub fn new(host_name: String, hardware_model: String, os_version: String,
               data_dir: PathBuf, pusher: Option<Arc<Pusher>>) -> Arc<Self>;
    /// One request → one reply. `phone_id` identifies the paired phone.
    pub async fn handle(self: &Arc<Self>, phone_id: &str, method: &str, params: &Value) -> Result<Value, String>;
    /// `turn.start`: returns the event stream.
    pub fn start_prompt(self: &Arc<Self>, request_id: String, params: Value) -> mpsc::UnboundedReceiver<Value>;
    /// The phone stopped listening; the turn must keep going.
    pub async fn detach_request(&self, request_id: &str);
    pub fn spawn_cli_watch(self: &Arc<Self>);
    pub fn set_update_note(&self, note: Option<String>);
    pub async fn is_idle(&self) -> bool;     // no turn running, no question waiting
    pub async fn stop_agy(&self);            // stop agy processes before the connector restarts
}
```

You may change `main.rs` to match the new protocol: the streamed method is now `turn.start`, and the
`check` command should call the new method names. Keep `main.rs`'s transport and auth as they are.

## Behaviour

### agy processes
- One long-lived `agy acp` process runs turns and answers agy's permission requests. Start it on
  first use; if it dies, start a new one and fail any running turns with `agy_unavailable`.
- Start every `agy acp` process with its working directory set to an empty folder the connector
  owns (`<data_dir>/no-folder`). agy reports this folder as the folder of chats whose real folder it
  doesn't know; treat that value as "no folder".
- **An `agy acp` process keeps the first copy of a chat it loads and never sees later changes made
  by other agy processes (such as the CLI). `session/list` in a long-lived process is stale too.**
  Therefore:
  - `chats.list`: list chats from a fresh, short-lived `agy acp` process (then stop it). Reuse the
    result for up to 4 seconds.
  - `chat.open`: if the chat has a turn running here, answer from memory. Otherwise load it in a
    fresh short-lived process, rebuild the conversation from the replayed updates, stop the process.
  - Before a turn in an existing chat: if the long-lived process has loaded that chat before, it may
    hold a stale copy. If no turn is running, restart the long-lived process. If another turn is
    running, read the chat fresh and compare user-message counts with what the long-lived process
    last saw; if they differ, fail with `busy`.
- Start short-lived processes with `agy --log-file <data_dir>/agy-reader.log acp` (one shared file,
  delete it when over 4 MB) so they don't add a log file per run to agy's log folder.

### Folders
- A chat's folder is agy's value, or one assigned with `chat.set_folder` (saved in
  `<data_dir>/chat_folders.json`, private file), or none.
- Known folders = folders of listed chats + folders configured in `<data_dir>/config.json`
  `workspaces` (array of paths; `agy-connector add-workspace` writes it) + assigned folders.
- `turn.start` in a chat with no folder fails with `folder_required`. Never run a turn in a guessed
  folder. Load the chat with its real folder before the turn (agy works in the folder given to
  `session/load`).

### Chats open in the CLI
- While the agy CLI has a chat open it holds an exclusive lock on
  `~/.gemini/antigravity-cli/presence/<chatId>.lock`. Detect this by trying a non-blocking *shared*
  lock on that file (and releasing it at once). Never read the file. Any error means "not open".
  Validate the ID (letters, digits, `-`) before building the path.
- Report `open_in_cli` in `chats.list` and `chat.open`. Refuse `turn.start` with `open_in_cli`:
  two agy processes writing one chat lose each other's turns.
- During a turn, check the lock about once a second; if the CLI opens the chat, send
  `turn.notice` `cli_touched` before the final event.
- `spawn_cli_watch`: every 4 seconds, while at least one phone is registered for notifications,
  list the lock files and find chats that were open in the CLI and no longer are; send a
  `cli_released` notification for each (with the chat title).
- If a chat is open in the CLI and loading it returns no messages, agy couldn't save it where other
  processes can read it: `readable: false`, `unreadableReason: cli_unsaved`.

### Turns
- A turn runs to completion even if the phone disconnects. Keep the in-progress conversation and
  any waiting questions in memory so `chat.open` can show them.
- New chat: `session/new` with the folder, apply the chat's default options, then prompt.
- Options: model via `session/set_model`, mode via `session/set_mode`, effort and other options via
  `session/set_config_option`. Only send a change when it differs from the current value.
- **Turbo** is a connector mode, not an agy mode: run the chat in agy's `auto` mode and answer every
  permission request with its one-time allow choice. Remember per chat which chats are in Turbo
  (`<data_dir>/turbo_chats.json`), so it survives restarts.
- Map ACP `session/update` kinds to events: message chunks → `turn.text`; thought chunks →
  `turn.thinking`; tool calls and their updates → `turn.step`; plans → `turn.plan`; usage →
  `turn.context`; session info title → `turn.title`. Use only text content for user messages.
- Measure how long each turn takes. agy's replay has no timings, so keep the measured seconds and
  re-attach them to replies by position when a chat is reloaded.
- Attachments: images as ACP image content; workspace files as resource links resolved inside the
  chat's folder (refuse paths outside it).

### Questions (permission requests)
- Forward `session/request_permission` as `turn.approval`, with the choices agy offers. Wait up to
  10 minutes for `approval.answer`; on timeout answer "cancelled" — never allow something the user
  didn't approve (except in Turbo).
- `allow` / `deny` in `approval.answer` pick the choice whose effect is one-time allow / deny.
- Notify phones (`approval` kind) when a question is asked.

### Chats agy can't open
- agy lists chats from the Antigravity desktop app but `session/load` fails with "not found":
  `readable: false`, `unreadableReason: desktop_app`. Remember these so they aren't loaded again.

### Limits
- `limits.get`: run `agy -p /quota` and `agy -p /credits` (no conversation is started) and parse the
  text output into limits. Cache for 60 seconds unless `refresh`.

### Notifications
- On turn end, notify `finished` (title + first 180 characters of the reply) or `failed` (error).

### Updates and versions
- `connector.info` includes `update::CURRENT`, `update::MIN_APP_VERSION`,
  `update::auto_update_enabled(data_dir)` and the current update note.

### Files
- Private files (`chat_folders.json`, `turbo_chats.json`) are written with `crate::write_private_file`.

## Tests
Add unit tests for: the lock probe (lock held by another file handle → open; free → not open;
bad ID → not open), ACP update → event mapping, plan/step merging, and folder rules.
