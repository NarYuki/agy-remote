// SPDX-License-Identifier: AGPL-3.0-only
//! A minimal JSON-RPC 2.0 client for `agy acp` (the Agent Client Protocol over stdio).
//!
//! Requests we make are matched to replies by numeric id. Everything agy sends on its own
//! (session updates and requests such as permission prompts) is fanned out on a broadcast
//! channel so several listeners can follow one process.

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex as StdMutex, OnceLock};
use std::time::Duration;

use serde_json::{Value, json};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::process::{Child, ChildStdin, Command};
use tokio::sync::{Mutex, broadcast, oneshot};

/// Something agy sent that is not a reply to one of our calls.
#[derive(Clone, Debug)]
pub enum Inbound {
    /// A `session/update` notification.
    Update { session_id: String, update: Value },
    /// agy asks us something (for example `session/request_permission`).
    Request { id: Value, method: String, params: Value },
    /// The process closed its stdout.
    Exited,
}

type Waiter = oneshot::Sender<Result<Value, String>>;

pub struct AcpProcess {
    writer: Mutex<ChildStdin>,
    waiting: StdMutex<HashMap<u64, Waiter>>,
    counter: AtomicU64,
    events: broadcast::Sender<Inbound>,
    child: Mutex<Option<Child>>,
    running: AtomicBool,
    info: OnceLock<Value>,
}

/// A JSON-RPC 2.0 message with the given members.
fn rpc<const N: usize>(members: [(&str, Value); N]) -> Value {
    let mut obj = serde_json::Map::new();
    obj.insert("jsonrpc".to_owned(), Value::from("2.0"));
    obj.extend(members.into_iter().map(|(k, v)| (k.to_owned(), v)));
    Value::Object(obj)
}

/// Where the agy binary lives: `~/.gemini/bin/agy`, else the first `agy` on `PATH`.
pub fn find_agy() -> Option<PathBuf> {
    // Windows looks for agy.exe (then .cmd/.bat shims); Unix for plain `agy`.
    let names: &[&str] = if cfg!(windows) { &["agy.exe", "agy.cmd", "agy.bat"] } else { &["agy"] };
    let home = crate::home_dir();
    let mut dirs: Vec<PathBuf> = home.iter().map(|h| h.join(".gemini").join("bin")).collect();
    dirs.extend(std::env::var_os("PATH").into_iter().flat_map(|v| std::env::split_paths(&v).collect::<Vec<_>>()));
    // Services start with a bare PATH, so also try the usual per-user and system spots.
    if let Some(h) = &home {
        dirs.push(h.join(".local").join("bin"));
    }
    if cfg!(windows) {
        if let Some(local) = std::env::var_os("LOCALAPPDATA").map(PathBuf::from) {
            dirs.push(local.join("agy").join("bin"));
            dirs.push(local.join("Programs").join("agy"));
            dirs.push(local.join("Programs").join("Antigravity").join("bin"));
        }
        if let Some(appdata) = std::env::var_os("APPDATA").map(PathBuf::from) {
            dirs.push(appdata.join("npm"));
        }
    } else {
        dirs.extend(["/usr/local/bin", "/opt/homebrew/bin", "/usr/bin"].map(PathBuf::from));
    }
    dirs.iter().flat_map(|d| names.iter().map(move |n| d.join(n))).find(|p| runnable(p))
}

/// Keeps console programs started from the background connector from opening a window.
#[cfg(windows)]
pub const CREATE_NO_WINDOW: u32 = 0x0800_0000;

/// A regular file with some execute bit set (any regular file off Unix).
fn runnable(p: &Path) -> bool {
    match std::fs::metadata(p) {
        Ok(m) if m.is_file() => has_exec_bit(&m),
        _ => false,
    }
}

#[cfg(unix)]
fn has_exec_bit(m: &std::fs::Metadata) -> bool {
    std::os::unix::fs::PermissionsExt::mode(&m.permissions()) & 0o111 > 0
}

#[cfg(not(unix))]
fn has_exec_bit(_: &std::fs::Metadata) -> bool {
    true
}

impl AcpProcess {
    /// Starts `agy [--log-file <path>] acp` in `cwd` and performs the `initialize` handshake.
    pub async fn start(cwd: &Path, log_file: Option<&Path>) -> Result<Arc<Self>, String> {
        let binary = find_agy().ok_or("agy CLI not found: install Antigravity's agy CLI (looked in ~/.gemini/bin, PATH and ~/.local/bin)")?;
        let mut cmd = Command::new(&binary);
        if let Some(log) = log_file {
            cmd.arg("--log-file").arg(log);
        }
        cmd.arg("acp")
            .current_dir(cwd)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::null())
            .kill_on_drop(true);
        #[cfg(windows)]
        cmd.creation_flags(CREATE_NO_WINDOW);
        let mut child = match cmd.spawn() {
            Ok(c) => c,
            Err(e) => return Err(format!("failed to start {}: {e}", binary.display())),
        };
        let stdin = child.stdin.take().ok_or("agy acp has no stdin")?;
        let stdout = child.stdout.take().ok_or("agy acp has no stdout")?;
        let (events, _) = broadcast::channel(4096);
        let proc = Arc::new(Self {
            writer: Mutex::new(stdin),
            waiting: StdMutex::new(HashMap::new()),
            counter: AtomicU64::new(1),
            events,
            child: Mutex::new(Some(child)),
            running: AtomicBool::new(true),
            info: OnceLock::new(),
        });

        let reader_side = Arc::clone(&proc);
        let mut lines = BufReader::with_capacity(1024 * 1024, stdout).lines();
        tokio::spawn(async move {
            while let Ok(Some(line)) = lines.next_line().await {
                if let Ok(msg) = serde_json::from_str::<Value>(&line) {
                    reader_side.route(msg);
                }
            }
            reader_side.running.store(false, Ordering::SeqCst);
            let orphans: Vec<Waiter> = match reader_side.waiting.lock() {
                Ok(mut map) => map.drain().map(|(_, w)| w).collect(),
                Err(_) => Vec::new(),
            };
            for w in orphans {
                let _ = w.send(Err("agy acp exited".into()));
            }
            let _ = reader_side.events.send(Inbound::Exited);
        });

        // Form questions: agy sends `ask_question` as `elicitation/create` (answered on the phone)
        // instead of writing the questions into its reply and ending the turn.
        let hello = json!({ "protocolVersion": 1, "clientCapabilities": { "elicitation": { "form": {} } } });
        let reply = proc.call("initialize", hello, Duration::from_secs(30)).await?;
        let agent = reply.get("agentInfo").cloned().unwrap_or_default();
        proc.info.get_or_init(|| agent);
        Ok(proc)
    }

    /// What agy said about itself in `initialize` (`name`, `version`, …), or null.
    pub fn agent_info(&self) -> Value {
        self.info.get().cloned().unwrap_or(Value::Null)
    }

    fn route(&self, msg: Value) {
        let method = msg.get("method").and_then(Value::as_str);
        let id = msg.get("id");
        match (method, id) {
            (None, Some(id)) => {
                let Some(num) = id.as_u64() else { return };
                let waiter = self.waiting.lock().ok().and_then(|mut m| m.remove(&num));
                let Some(waiter) = waiter else { return };
                let outcome = match msg.get("error") {
                    Some(e) => Err(e
                        .get("message")
                        .and_then(Value::as_str)
                        .unwrap_or("agent error")
                        .to_string()),
                    None => Ok(msg.get("result").cloned().unwrap_or(Value::Null)),
                };
                let _ = waiter.send(outcome);
            }
            (Some(m), Some(id)) => {
                let _ = self.events.send(Inbound::Request {
                    id: id.clone(),
                    method: m.to_string(),
                    params: msg.get("params").cloned().unwrap_or_default(),
                });
            }
            (Some("session/update"), None) => {
                let body = &msg["params"];
                let update = body["update"].clone();
                let session_id = body["sessionId"].as_str().map(str::to_owned).unwrap_or_default();
                let _ = self.events.send(Inbound::Update { session_id, update });
            }
            _ => {}
        }
    }

    async fn send_line(&self, msg: &Value) -> Result<(), String> {
        let mut bytes = msg.to_string().into_bytes();
        bytes.push(b'\n');
        let mut w = self.writer.lock().await;
        if let Err(e) = w.write_all(&bytes).await {
            return Err(format!("agy acp write: {e}"));
        }
        match w.flush().await {
            Ok(()) => Ok(()),
            Err(e) => Err(format!("agy acp flush: {e}")),
        }
    }

    /// Sends a request and waits for its reply (or `timeout`).
    pub async fn call(&self, method: &str, params: Value, timeout: Duration) -> Result<Value, String> {
        let id = self.counter.fetch_add(1, Ordering::SeqCst);
        let (done, reply) = oneshot::channel();
        if let Ok(mut m) = self.waiting.lock() {
            m.insert(id, done);
        }
        let forget = || {
            if let Ok(mut m) = self.waiting.lock() {
                m.remove(&id);
            }
        };
        let frame = rpc([("id", json!(id)), ("method", json!(method)), ("params", params)]);
        if let Err(e) = self.send_line(&frame).await {
            forget();
            return Err(e);
        }
        match tokio::time::timeout(timeout, reply).await {
            Ok(Ok(outcome)) => outcome,
            Ok(Err(_)) => Err("agy acp dropped the request".into()),
            Err(_) => {
                forget();
                Err(format!("{method} timed out"))
            }
        }
    }

    pub async fn notify(&self, method: &str, params: Value) -> Result<(), String> {
        self.send_line(&rpc([("method", json!(method)), ("params", params)])).await
    }

    pub async fn respond(&self, id: Value, result: Value) -> Result<(), String> {
        self.send_line(&rpc([("id", id), ("result", result)])).await
    }

    pub async fn respond_unsupported(&self, id: Value, method: &str) -> Result<(), String> {
        let message = format!("unsupported: {method}");
        self.send_line(&rpc([("id", id), ("error", json!({ "message": message, "code": -32601 }))])).await
    }

    pub fn subscribe(&self) -> broadcast::Receiver<Inbound> {
        self.events.subscribe()
    }

    pub async fn kill(&self) {
        self.running.store(false, Ordering::SeqCst);
        if let Some(mut child) = self.child.lock().await.take() {
            let _ = child.kill().await;
        }
    }

    pub fn is_alive(&self) -> bool {
        self.running.load(Ordering::SeqCst)
    }
}
