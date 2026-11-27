// SPDX-License-Identifier: AGPL-3.0-only
//! Protocol v1 (`docs/protocol.md`) implemented on top of `agy acp`.
//!
//! One long-lived agy process runs turns and asks permission questions. Everything that only
//! reads (chat lists, opening a chat) goes through short-lived "reader" processes, because a
//! long-lived process never notices changes other agy processes make to a chat.

use std::collections::{BTreeSet, HashMap, HashSet};
use std::process::Stdio;
use std::fs::{self, File, TryLockError};
use std::path::{Component, Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex as StdMutex};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use data_encoding::HEXLOWER;
use crate::acp::{AcpProcess, Inbound, find_agy};
use crate::push::{Event, Pusher};
use crate::update;
use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};
use serde_json::{Map, Value, json};
use tokio::sync::{Mutex, broadcast, mpsc, oneshot};
use tracing::{info, warn};

/// Protocol version reported in `connector.info`.
pub const PROTOCOL: u64 = 1;

const CHAT_LIST_TTL: Duration = Duration::from_secs(4);
const LIMITS_TTL: Duration = Duration::from_secs(60);
const QUESTION_TIMEOUT: Duration = Duration::from_secs(10 * 60);
const READER_LOG_LIMIT: u64 = 4 * 1024 * 1024;
const RPC_TIMEOUT: Duration = Duration::from_secs(120);
const PROMPT_TIMEOUT: Duration = Duration::from_secs(7 * 24 * 60 * 60);
const MAX_FILE_HITS: usize = 200;
/// Largest file `files.read` returns in full; longer ones are cut here.
const MAX_READ_BYTES: usize = 1024 * 1024;
const PRINT_TIMEOUT: Duration = Duration::from_secs(60);
/// Folders `files.search` never enters (VCS data, dependencies, build output).
const IGNORED_DIRS: &str =
    ".git .hg .svn .jj node_modules target build .build DerivedData dist Pods .venv venv __pycache__ .gradle .next .turbo";

fn ignored_dir(name: &str) -> bool {
    IGNORED_DIRS.split(' ').any(|d| d == name)
}

// MARK: - Errors

/// A protocol error: a machine-readable code plus an English message for logs.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BridgeError {
    pub code: &'static str,
    pub message: String,
}

impl BridgeError {
    fn new(code: &'static str, message: impl Into<String>) -> Self {
        Self { code, message: message.into() }
    }

    fn to_json(&self) -> Value {
        json!({ "code": self.code, "message": self.message })
    }
}

type Outcome<T> = Result<T, BridgeError>;

fn bad(msg: impl Into<String>) -> BridgeError {
    BridgeError::new("bad_request", msg)
}

fn agy_down(msg: impl Into<String>) -> BridgeError {
    BridgeError::new("agy_unavailable", msg)
}

// MARK: - Chats open in the CLI

/// Letters, digits and `-` only, 1–128 bytes: safe to put into a file name.
pub fn valid_chat_id(id: &str) -> bool {
    (1..=128).contains(&id.len()) && id.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-')
}

fn presence_dir() -> PathBuf {
    let home = crate::home_dir().unwrap_or_else(|| PathBuf::from("."));
    home.join(".gemini").join("antigravity-cli").join("presence")
}

/// Whether another process holds an exclusive lock on `<dir>/<id>.lock`. Never reads the file.
pub fn lock_held_at(dir: &Path, id: &str) -> bool {
    if !valid_chat_id(id) {
        return false;
    }
    let Ok(handle) = File::open(dir.join(format!("{id}.lock"))) else { return false };
    match handle.try_lock_shared() {
        Ok(()) => {
            let _ = handle.unlock();
            false
        }
        Err(TryLockError::WouldBlock) => true,
        Err(_) => false,
    }
}

fn open_in_cli(id: &str) -> bool {
    lock_held_at(&presence_dir(), id)
}

fn chats_open_in_cli() -> HashSet<String> {
    let dir = presence_dir();
    let Ok(listing) = fs::read_dir(&dir) else { return HashSet::new() };
    let mut open = HashSet::new();
    for entry in listing.flatten() {
        let name = entry.file_name().to_string_lossy().into_owned();
        if let Some(id) = name.strip_suffix(".lock")
            && lock_held_at(&dir, id)
        {
            open.insert(id.to_owned());
        }
    }
    open
}

// MARK: - ACP update mapping

/// What one `session/update` means for the conversation.
#[derive(Debug, Clone, PartialEq)]
pub enum Mapped {
    User(String),
    Text(String),
    Thinking(String),
    Step(Value),
    Plan(Vec<Value>),
    /// agy's `ask_question` tool: the questions, written out as agy shows them (tool call id, text).
    Question(String, String),
    Context(u64, u64),
    Title(String),
    Commands(Vec<Value>),
    Config(Value),
    Mode(String),
    Other,
}

fn block_text(block: &Value) -> Option<String> {
    if s_at(block, "type")? != "text" {
        return None;
    }
    Some(s_at(block, "text").map(str::to_owned).unwrap_or_default())
}

fn step_kind(kind: &str) -> &'static str {
    match kind {
        "read" => "read",
        "edit" => "edit",
        "delete" => "delete",
        "move" => "move",
        "search" => "search",
        "execute" => "run",
        "fetch" => "fetch",
        "think" => "think",
        _ => "other",
    }
}

fn step_status(status: &str) -> &'static str {
    match status {
        "completed" => "done",
        "failed" => "failed",
        _ => "running",
    }
}

fn plan_status(status: &str) -> &'static str {
    match status {
        "completed" | "done" => "done",
        "in_progress" | "active" | "running" => "active",
        _ => "pending",
    }
}

fn clip(text: &str, max: usize) -> String {
    match text.char_indices().nth(max) {
        None => text.to_string(),
        Some((cut, _)) => format!("{}…", &text[..cut]),
    }
}

fn s_at<'a>(v: &'a Value, key: &str) -> Option<&'a str> {
    v.get(key).and_then(Value::as_str)
}

fn tool_detail(u: &Value) -> Option<String> {
    if let Some(out) = u.pointer("/rawOutput/output").and_then(Value::as_str) {
        return Some(clip(out, 4000));
    }
    let pieces: Vec<String> = u
        .get("content")
        .and_then(Value::as_array)
        .map(|items| items.iter().filter_map(|i| i.get("content").and_then(block_text)).collect())
        .unwrap_or_default();
    (!pieces.is_empty()).then(|| clip(&pieces.join("\n"), 4000))
}

/// Lines removed and added between `old` and `new`, counting only the changed middle.
fn line_delta(old: &str, new: &str) -> (usize, usize) {
    let a: Vec<&str> = old.lines().collect();
    let b: Vec<&str> = new.lines().collect();
    let head = a.iter().zip(&b).take_while(|(x, y)| x == y).count();
    let tail = a[head..].iter().rev().zip(b[head..].iter().rev()).take_while(|(x, y)| x == y).count();
    let (x, y) = (&a[head..a.len() - tail], &b[head..b.len() - tail]);
    if x.len() * y.len() > 4_000_000 {
        return (x.len(), y.len());
    }
    // Longest common subsequence of the middle: lines kept are neither removed nor added.
    let mut row = vec![0usize; y.len() + 1];
    for xi in x {
        let mut diag = 0;
        for (j, yj) in y.iter().enumerate() {
            let up = row[j + 1];
            row[j + 1] = if xi == yj { diag + 1 } else { up.max(row[j]) };
            diag = up;
        }
    }
    let kept = row[y.len()];
    (x.len() - kept, y.len() - kept)
}

/// The file a step touched, and for edits how many lines went and came.
fn step_file(u: &Value) -> (Option<String>, Option<(usize, usize)>) {
    let diffs: Vec<&Value> = u
        .get("content")
        .and_then(Value::as_array)
        .map(|items| items.iter().filter(|i| s_at(i, "type") == Some("diff")).collect())
        .unwrap_or_default();
    let path = diffs
        .first()
        .and_then(|d| s_at(d, "path"))
        .or_else(|| u.pointer("/locations/0/path").and_then(Value::as_str))
        .map(visible);
    let delta = (!diffs.is_empty()).then(|| {
        diffs.iter().fold((0, 0), |(r, a), d| {
            let (dr, da) = line_delta(s_at(d, "oldText").unwrap_or(""), s_at(d, "newText").unwrap_or(""));
            (r + dr, a + da)
        })
    });
    (path, delta)
}

fn partial_step(u: &Value) -> Value {
    let (path, delta) = step_file(u);
    let title = s_at(u, "title")
        .filter(|t| !t.is_empty())
        .or_else(|| u.pointer("/rawInput/toolSummary").and_then(Value::as_str))
        .or_else(|| s_at(u, "name"));
    json!({
        "id": s_at(u, "toolCallId").unwrap_or(""),
        "kind": s_at(u, "kind").map(step_kind),
        // Often the command itself: shown like an approval's, with hidden characters written out.
        "title": title.map(visible),
        "status": s_at(u, "status").map(step_status),
        "detail": tool_detail(u),
        "path": path,
        "removed": delta.map(|d| d.0),
        "added": delta.map(|d| d.1),
    })
}

fn plan_item(text: &str, status: &str) -> Value {
    json!({ "text": text, "status": plan_status(status) })
}

/// `ask_question` questions as agy writes them in its reply: the question, then `- [n] option`
/// lines (a blank line between questions).
pub fn question_text(questions: &[Value]) -> String {
    questions
        .iter()
        .map(|q| {
            let mut block = visible(s_at(q, "question").unwrap_or(""));
            for (i, o) in q.get("options").and_then(Value::as_array).into_iter().flatten().enumerate() {
                block.push_str(&format!("\n- [{}] {}", i + 1, visible(o.as_str().unwrap_or(""))));
            }
            block
        })
        .collect::<Vec<_>>()
        .join("\n\n")
}

/// Turns one ACP `session/update` into what it means for the conversation.
pub fn map_update(u: &Value) -> Mapped {
    let kind = s_at(u, "sessionUpdate").unwrap_or("");
    let chunk = || u.get("content").and_then(block_text);
    match kind {
        "user_message_chunk" => chunk().map_or(Mapped::Other, Mapped::User),
        "agent_message_chunk" => chunk().map_or(Mapped::Other, Mapped::Text),
        "agent_thought_chunk" => chunk().map_or(Mapped::Other, Mapped::Thinking),
        "tool_call" | "tool_call_update" => {
            if s_at(u, "name") == Some("ask_question") {
                let questions = u.pointer("/args/questions").or_else(|| u.pointer("/rawInput/questions"));
                return match questions.and_then(Value::as_array) {
                    Some(list) => Mapped::Question(s_at(u, "toolCallId").unwrap_or("").to_string(), question_text(list)),
                    None => Mapped::Other,
                };
            }
            if s_at(u, "name") != Some("update_steps") {
                return Mapped::Step(partial_step(u));
            }
            let list = u
                .pointer("/args/steps")
                .and_then(Value::as_array)
                .or_else(|| u.pointer("/rawInput/steps").and_then(Value::as_array));
            match list {
                Some(steps) => Mapped::Plan(
                    steps
                        .iter()
                        .map(|s| plan_item(s_at(s, "title").unwrap_or(""), s_at(s, "status").unwrap_or("")))
                        .collect(),
                ),
                None => Mapped::Other,
            }
        }
        "plan" => Mapped::Plan(
            u.get("entries")
                .and_then(Value::as_array)
                .map(|es| {
                    es.iter()
                        .map(|e| plan_item(s_at(e, "content").unwrap_or(""), s_at(e, "status").unwrap_or("")))
                        .collect()
                })
                .unwrap_or_default(),
        ),
        "usage_update" => Mapped::Context(
            u.get("used").and_then(Value::as_u64).unwrap_or(0),
            u.get("size").and_then(Value::as_u64).unwrap_or(0),
        ),
        "session_info_update" => s_at(u, "title").map_or(Mapped::Other, |t| Mapped::Title(t.into())),
        "available_commands_update" => Mapped::Commands(
            u.get("availableCommands").and_then(Value::as_array).cloned().unwrap_or_default(),
        ),
        "config_option_update" => Mapped::Config(u.get("configOptions").cloned().unwrap_or(Value::Null)),
        "current_mode_update" => s_at(u, "currentModeId").map_or(Mapped::Other, |m| Mapped::Mode(m.into())),
        _ => Mapped::Other,
    }
}

/// Merges a (partial) step into `steps` by id and returns the full step as it now stands.
/// A new step also records `at`: how much reply text (UTF-16 units) came before it, so the app can
/// show it between the right pieces of text.
pub fn merge_step(steps: &mut Vec<Value>, new: Value, at: usize) -> Value {
    let id = new.get("id").cloned().unwrap_or(Value::Null);
    if let Some(existing) = steps.iter_mut().find(|s| s.get("id") == Some(&id)) {
        if let (Some(target), Some(fields)) = (existing.as_object_mut(), new.as_object()) {
            for (k, v) in fields.iter().filter(|(_, v)| !v.is_null()) {
                target.insert(k.clone(), v.clone());
            }
        }
        return existing.clone();
    }
    let mut fresh = new;
    if let Some(obj) = fresh.as_object_mut() {
        obj.insert("at".into(), json!(at));
        for (key, fallback) in [("kind", "other"), ("title", "Tool"), ("status", "running")] {
            if obj.get(key).is_none_or(Value::is_null) {
                obj.insert(key.into(), json!(fallback));
            }
        }
    }
    steps.push(fresh.clone());
    fresh
}

// MARK: - Conversation

#[derive(Debug, Clone, Default)]
struct Msg {
    from_user: bool,
    text: String,
    attachments: Vec<Value>,
    thinking: String,
    steps: Vec<Value>,
    plan: Vec<Value>,
    approvals: Vec<Value>,
    seconds: Option<f64>,
    live: bool,
    /// Questions agy asked with `ask_question` (tool call id, text), and whether reply text came
    /// after them. Live, agy writes them into its reply; a replayed chat (`session/load`) has only
    /// the tool call, so the text is added from it there.
    questions: Vec<(String, String)>,
    text_after_question: bool,
}

#[derive(Debug, Clone, Default)]
pub struct Conversation {
    messages: Vec<Msg>,
    context: Option<(u64, u64)>,
    title: Option<String>,
}

impl Conversation {
    fn agent(&mut self) -> &mut Msg {
        if self.messages.last().is_none_or(|m| m.from_user) {
            self.messages.push(Msg::default());
        }
        self.messages.last_mut().expect("just ensured")
    }

    fn push_user(&mut self, text: String, attachments: Vec<Value>) {
        self.messages.push(Msg { from_user: true, text, attachments, ..Msg::default() });
    }

    pub fn user_count(&self) -> usize {
        self.messages.iter().filter(|m| m.from_user).count()
    }

    fn agent_count(&self) -> usize {
        self.messages.len() - self.user_count()
    }

    fn last_reply(&self) -> String {
        self.messages.iter().rev().find(|m| !m.from_user).map(|m| m.text.clone()).unwrap_or_default()
    }

    /// Applies one mapped update; returns the event to forward to the phone, if any.
    pub fn apply(&mut self, m: Mapped) -> Option<Value> {
        match m {
            Mapped::User(t) => {
                match self.messages.last_mut() {
                    Some(last) if last.from_user => last.text.push_str(&t),
                    _ => self.push_user(t, Vec::new()),
                }
                None
            }
            Mapped::Text(t) => {
                let msg = self.agent();
                if !msg.questions.is_empty() {
                    msg.text_after_question = true;
                }
                msg.text.push_str(&t);
                Some(json!({ "event": "turn.text", "text": t }))
            }
            Mapped::Thinking(t) => {
                self.agent().thinking.push_str(&t);
                Some(json!({ "event": "turn.thinking", "text": t }))
            }
            Mapped::Step(s) => {
                let msg = self.agent();
                let at = msg.text.encode_utf16().count();
                let merged = merge_step(&mut msg.steps, s, at);
                Some(json!({ "event": "turn.step", "step": merged }))
            }
            Mapped::Plan(p) => {
                self.agent().plan = p.clone();
                Some(json!({ "event": "turn.plan", "plan": p }))
            }
            Mapped::Context(used, size) => {
                self.context = Some((used, size));
                Some(json!({ "event": "turn.context", "used": used, "size": size }))
            }
            Mapped::Question(id, text) => {
                let msg = self.agent();
                // The same call comes again as `tool_call_update`: keep the first.
                if !msg.questions.iter().any(|(q, _)| *q == id) {
                    msg.questions.push((id, text));
                    msg.text_after_question = false;
                }
                None
            }
            Mapped::Title(t) => {
                self.title = Some(t.clone());
                Some(json!({ "event": "turn.title", "title": t }))
            }
            _ => None,
        }
    }

    fn messages_json(&self) -> Value {
        let list: Vec<Value> = self
            .messages
            .iter()
            .enumerate()
            .map(|(i, m)| {
                let id = format!("m{i}");
                if m.from_user {
                    json!({ "id": id, "from": "user", "text": m.text, "attachments": m.attachments })
                } else {
                    let mut text = m.text.clone();
                    if !m.questions.is_empty() && !m.text_after_question {
                        let asked = m.questions.iter().map(|(_, q)| q.as_str()).collect::<Vec<_>>().join("\n\n");
                        if !text.is_empty() {
                            text.push_str("\n\n");
                        }
                        text.push_str(&asked);
                    }
                    json!({
                        "id": id,
                        "from": "agent",
                        "text": text,
                        "thinking": (!m.thinking.is_empty()).then_some(&m.thinking),
                        "steps": m.steps,
                        "plan": m.plan,
                        "approvals": m.approvals,
                        "seconds": m.seconds,
                        "live": m.live,
                    })
                }
            })
            .collect();
        Value::Array(list)
    }

    fn context_json(&self) -> Value {
        self.context.map_or(Value::Null, |(used, size)| json!({ "used": used, "size": size }))
    }

    /// Re-attaches measured durations to agent replies by position.
    fn attach_seconds(&mut self, times: &[Option<f64>]) {
        let agents = self.messages.iter_mut().filter(|m| !m.from_user);
        for (msg, stored) in agents.zip(times.iter()) {
            if msg.seconds.is_none() {
                msg.seconds = *stored;
            }
        }
    }
}

// MARK: - Approvals

fn effect_of(kind: &str) -> &'static str {
    match kind {
        "allow_once" => "allow_once",
        "allow_always" => "allow_always",
        "reject_always" => "deny_always",
        _ => "deny_once",
    }
}

/// `text` with every character a person can't see (control characters other than newline and
/// tab, bidirectional overrides, zero-width and other invisible characters) written out as
/// `<U+XXXX>`. Approvals are shown with this, so what the phone shows is what will run: a command
/// can't hide a part of itself or show its characters in another order.
pub fn visible(text: &str) -> String {
    use std::fmt::Write;
    let mut out = String::with_capacity(text.len());
    for c in text.chars() {
        let hidden = (c.is_control() && c != '\n' && c != '\t')
            || matches!(c,
                '\u{00AD}' | '\u{034F}' | '\u{061C}' | '\u{115F}' | '\u{1160}' | '\u{17B4}' | '\u{17B5}' | '\u{180E}'
                | '\u{200B}'..='\u{200F}' | '\u{2028}'..='\u{202E}' | '\u{2060}'..='\u{206F}' | '\u{3164}'
                | '\u{FE00}'..='\u{FE0F}' | '\u{FEFF}' | '\u{FFA0}' | '\u{FFF9}'..='\u{FFFB}'
                | '\u{1D173}'..='\u{1D17A}' | '\u{E0000}'..='\u{E0FFF}');
        if hidden {
            let _ = write!(out, "<U+{:04X}>", c as u32);
        } else {
            out.push(c);
        }
    }
    out
}

/// What an approval notification says, and whether it says all of it. Only the request itself
/// (the command, else the file) is shown, never the agent's own summary of it, and runs of
/// spaces and line breaks become one space so nothing can be pushed out of view.
pub fn notification_text(approval: &Value) -> (String, bool) {
    let subject = ["command", "path", "title"]
        .iter()
        .find_map(|k| s_at(approval, k).filter(|v| !v.trim().is_empty()))
        .unwrap_or("");
    let shown = subject.split_whitespace().collect::<Vec<_>>().join(" ");
    let whole = shown.chars().count() <= crate::push::BODY_LIMIT;
    (shown, whole)
}

/// Builds the protocol's Approval from `session/request_permission` params.
pub fn build_approval(id: &str, params: &Value) -> Value {
    let empty = Value::Null;
    let tc = params.get("toolCall").unwrap_or(&empty);
    let name = s_at(tc, "name").unwrap_or("");
    let raw = tc.get("rawInput").or_else(|| tc.get("args")).unwrap_or(&empty);
    let what = match s_at(tc, "kind").unwrap_or("") {
        "execute" => "run",
        "edit" if name.contains("write") || name.contains("create") => "write",
        "edit" => "edit",
        _ if name.contains("write") => "write",
        _ => "other",
    };
    let command = s_at(raw, "CommandLine").or_else(|| s_at(raw, "command"));
    let diff = tc
        .get("content")
        .and_then(Value::as_array)
        .and_then(|items| items.iter().find(|c| s_at(c, "type") == Some("diff")));
    let path = diff
        .and_then(|d| s_at(d, "path"))
        .or_else(|| tc.pointer("/locations/0/path").and_then(Value::as_str))
        .or_else(|| s_at(raw, "TargetFile"))
        .or_else(|| s_at(raw, "path"));
    let title = tc
        .pointer("/rawInput/toolSummary")
        .and_then(Value::as_str)
        .or_else(|| s_at(tc, "title"))
        .unwrap_or("Permission needed");
    let mut choices = Vec::new();
    for o in params.get("options").and_then(Value::as_array).into_iter().flatten() {
        let label = s_at(o, "name").unwrap_or("");
        let effect = effect_of(s_at(o, "kind").unwrap_or(""));
        choices.push(json!({ "id": s_at(o, "optionId").unwrap_or(""), "label": label, "effect": effect }));
    }
    json!({
        "id": id,
        "what": what,
        "title": visible(title),
        "command": command.map(visible),
        "path": path.map(visible),
        "before": diff.and_then(|d| s_at(d, "oldText")).map(visible),
        "after": diff.and_then(|d| s_at(d, "newText")).map(visible),
        "choices": choices,
    })
}

/// The questions of an `elicitation/create` form as the phone shows them: an Approval with
/// `what: "question"`, no choices, and `questions`. agy names the fields `q1`, `q2`, … (a string
/// with `oneOf` for one answer, an array with `items.anyOf` for several) and `q1_other`, … for a
/// written answer. None if the form has nothing the phone can show.
pub fn build_question(id: &str, params: &Value) -> Option<Value> {
    let props = params.pointer("/requestedSchema/properties")?.as_object()?;
    let mut keys: Vec<(u64, &String)> = props
        .keys()
        .filter_map(|k| k.strip_prefix('q').and_then(|n| n.parse::<u64>().ok()).map(|n| (n, k)))
        .collect();
    keys.sort();
    let questions: Vec<Value> = keys
        .into_iter()
        .map(|(_, key)| {
            let field = &props[key.as_str()];
            let multi = s_at(field, "type") == Some("array");
            let list = if multi { field.pointer("/items/anyOf") } else { field.get("oneOf") };
            let options: Vec<Value> = list
                .and_then(Value::as_array)
                .into_iter()
                .flatten()
                .filter_map(|o| Some(json!({ "id": s_at(o, "const")?, "label": visible(s_at(o, "title").unwrap_or("")) })))
                .collect();
            let other = format!("{key}_other");
            json!({
                "key": key,
                "question": visible(s_at(field, "title").unwrap_or("")),
                "multi": multi,
                "options": options,
                "other": props.contains_key(&other).then_some(other),
            })
        })
        .collect();
    if questions.is_empty() {
        return None;
    }
    Some(json!({
        "id": id,
        "what": "question",
        "title": visible(s_at(params, "message").unwrap_or("")),
        "command": null, "path": null, "before": null, "after": null,
        "choices": [],
        "questions": questions,
    }))
}

/// The phone's answers to a question form, checked against it: only its fields, only offered
/// options, written answers as plain text. Returned as agy's form content.
pub fn checked_answers(form: &Value, answers: &Value) -> Result<Value, String> {
    let given = answers.as_object().ok_or("answers must be an object")?;
    let mut out = Map::new();
    for q in form["questions"].as_array().into_iter().flatten() {
        let key = s_at(q, "key").unwrap_or("");
        let offered: Vec<&str> = q["options"].as_array().into_iter().flatten().filter_map(|o| s_at(o, "id")).collect();
        if let Some(v) = given.get(key) {
            if q["multi"] == json!(true) {
                let picked: Vec<&str> = v.as_array().ok_or("a multiple-choice answer must be a list")?.iter().filter_map(Value::as_str).collect();
                if picked.iter().any(|p| !offered.contains(p)) {
                    return Err(format!("{key}: not one of the options"));
                }
                out.insert(key.into(), json!(picked));
            } else {
                let p = v.as_str().ok_or("an answer must be an option id")?;
                if !offered.contains(&p) {
                    return Err(format!("{key}: not one of the options"));
                }
                out.insert(key.into(), json!(p));
            }
        }
        if let Some(other) = s_at(q, "other")
            && let Some(text) = given.get(other).and_then(Value::as_str).map(str::trim).filter(|t| !t.is_empty())
        {
            out.insert(other.into(), json!(text.chars().take(2000).collect::<String>()));
        }
    }
    if given.keys().any(|k| !out.contains_key(k) && !given[k].is_null() && given[k] != json!("") && given[k] != json!([])) {
        return Err("answers name a field the question doesn't have".into());
    }
    Ok(Value::Object(out))
}

/// Resolves an `approval.answer` choice (`allow`, `deny` or a choice id) to agy's option id.
pub fn pick_choice(approval: &Value, answer: &str) -> Option<String> {
    let choices = approval.get("choices")?.as_array()?;
    let wanted_effect = match answer {
        "allow" => Some("allow_once"),
        "deny" => Some("deny_once"),
        _ => None,
    };
    let hit = choices.iter().find(|c| match wanted_effect {
        Some(effect) => s_at(c, "effect") == Some(effect),
        None => s_at(c, "id") == Some(answer),
    })?;
    s_at(hit, "id").map(String::from)
}

// MARK: - Folders

fn same_path(a: &str, b: &str) -> bool {
    if a == b {
        return true;
    }
    matches!((fs::canonicalize(a), fs::canonicalize(b)), (Ok(x), Ok(y)) if x == y)
}

/// The chat's folder: agy's value unless it is the connector's no-folder directory, else the
/// assigned folder, else none.
pub fn resolve_folder(agy_cwd: Option<&str>, assigned: Option<&str>, no_folder: &Path) -> Option<String> {
    let placeholder = no_folder.to_string_lossy();
    agy_cwd
        .filter(|c| !c.is_empty() && !same_path(c, &placeholder))
        .or(assigned)
        .map(String::from)
}

/// Where agy runs for a chat with no folder: an empty directory outside the connector's config,
/// so an agent listing `..` can't reach the identity key or the push keys next to it.
pub fn scratch_workspace() -> PathBuf {
    let home = crate::home_dir().unwrap_or_else(std::env::temp_dir);
    let cache = if cfg!(target_os = "macos") {
        home.join("Library").join("Caches")
    } else if cfg!(windows) {
        std::env::var_os("LOCALAPPDATA").map(PathBuf::from).filter(|p| p.is_absolute()).unwrap_or_else(|| home.join("AppData").join("Local"))
    } else {
        std::env::var_os("XDG_CACHE_HOME").map(PathBuf::from).filter(|p| p.is_absolute()).unwrap_or_else(|| home.join(".cache"))
    };
    let dir = cache.join("agy-connector").join("empty-workspace");
    let _ = fs::create_dir_all(&dir);
    dir
}

/// The home folder, `/`, or a folder above home: too much to offer for a chat just because agy
/// once ran there (`files.search` and attachments would reach every file the user has). Such a
/// folder is offered only if it was added on purpose with `agy-connector add-workspace`.
pub fn too_broad(folder: &str) -> bool {
    let Ok(path) = fs::canonicalize(folder) else { return false };
    if path.parent().is_none() {
        return true;
    }
    let home = crate::home_dir().and_then(|h| fs::canonicalize(h).ok());
    home.is_some_and(|h| h.starts_with(&path))
}

/// Where agy keeps the artifacts it writes for each chat (plans, walkthroughs, notes).
fn artifact_root() -> PathBuf {
    presence_dir().parent().map(|p| p.join("brain")).unwrap_or_default()
}

/// A link from an agent reply (`file:///abs/path#L3-L9`, `/abs/path` or `rel/path`) as a path,
/// with `%xx` escapes decoded; relative links are taken from the chat's folder.
pub fn link_path(link: &str, folder: Option<&str>) -> Option<PathBuf> {
    let no_anchor = link.split(['#', '?']).next().unwrap_or("");
    let raw = no_anchor.strip_prefix("file://").unwrap_or(no_anchor);
    let text = percent_decode(raw)?;
    if text.is_empty() || text.contains('\0') {
        return None;
    }
    // Windows would contact the named server just to look at such a path (and sign in to it with
    // the user's account) before any check here could refuse it. A reply can link anywhere.
    if cfg!(windows) && names_another_computer(&text) {
        return None;
    }
    let path = Path::new(&text);
    if path.is_absolute() {
        return Some(path.to_path_buf());
    }
    folder.map(|f| Path::new(f).join(path))
}

/// Whether a Windows path starts with two slashes of either kind: a network share
/// (`\\server\share`, `//server/share`) or a device path (`\\?\…`, `\\.\…`). Files the phone
/// may see are on this computer's drives, which are written `C:\…`.
pub fn names_another_computer(path: &str) -> bool {
    let mut first = path.chars().take(2);
    let slash = |c: Option<char>| matches!(c, Some('\\' | '/'));
    slash(first.next()) && slash(first.next())
}

/// `%xx` escapes decoded; None if one is malformed or the result isn't UTF-8.
fn percent_decode(raw: &str) -> Option<String> {
    let mut bytes = Vec::with_capacity(raw.len());
    let mut it = raw.bytes();
    while let Some(b) = it.next() {
        if b == b'%' {
            let hex: Vec<u8> = it.by_ref().take(2).collect();
            let v = std::str::from_utf8(&hex).ok().and_then(|h| u8::from_str_radix(h, 16).ok())?;
            bytes.push(v);
        } else {
            bytes.push(b);
        }
    }
    String::from_utf8(bytes).ok()
}

/// A chat's folder as agy reports it. agy writes some with URL escapes (`AdMob%20Reporter` for
/// `AdMob Reporter`); when the folder doesn't exist as written but does decoded, that's the one.
pub fn agy_folder(cwd: &str) -> String {
    if !Path::new(cwd).exists()
        && let Some(decoded) = percent_decode(cwd).filter(|d| Path::new(d).is_dir())
    {
        return decoded;
    }
    cwd.to_string()
}

/// Whether `path` may be shown on the phone: an existing regular file inside one of `allowed`
/// (after resolving symlinks and `..`), and never inside `private` (the connector's own folder,
/// with its keys). Returns the resolved path.
pub fn readable_file(path: &Path, allowed: &[PathBuf], private: &Path) -> Option<PathBuf> {
    let full = fs::canonicalize(path).ok()?;
    if !fs::metadata(&full).ok()?.is_file() {
        return None;
    }
    if fs::canonicalize(private).is_ok_and(|p| full.starts_with(p)) {
        return None;
    }
    allowed.iter().filter_map(|a| fs::canonicalize(a).ok()).any(|a| full.starts_with(a)).then_some(full)
}

pub fn is_known_folder(folder: &str, known: &[String]) -> bool {
    known.iter().any(|k| same_path(k, folder))
}

/// `folder/rel` if it exists and stays inside `folder`.
pub fn resolve_in_folder(folder: &str, rel: &str) -> Option<PathBuf> {
    let relative = Path::new(rel);
    if rel.is_empty() || relative.is_absolute() {
        return None;
    }
    if !relative.components().all(|c| matches!(c, Component::Normal(_) | Component::CurDir)) {
        return None;
    }
    let base = fs::canonicalize(folder).ok()?;
    let full = fs::canonicalize(base.join(relative)).ok()?;
    full.starts_with(&base).then_some(full)
}

fn folder_kind(dir: &Path) -> Value {
    let has = |name: &str| dir.join(name).exists();
    let xcode = fs::read_dir(dir).ok().is_some_and(|entries| {
        entries.flatten().any(|e| {
            let p = e.path();
            matches!(p.extension().and_then(|x| x.to_str()), Some("xcodeproj" | "xcworkspace"))
        })
    });
    let kind = if has("Package.swift") || xcode {
        "swift"
    } else if has("Cargo.toml") {
        "rust"
    } else if has("package.json") {
        "node"
    } else if ["pyproject.toml", "requirements.txt", "setup.py"].into_iter().any(has) {
        "python"
    } else if has("go.mod") {
        "go"
    } else {
        return Value::Null;
    };
    json!(kind)
}

fn search_files(root: &Path, query: &str) -> Vec<Value> {
    let needle = query.to_lowercase();
    let mut hits = Vec::new();
    let mut pending: Vec<PathBuf> = vec![root.to_path_buf()];
    while let Some(here) = pending.pop() {
        let Ok(meta) = fs::symlink_metadata(&here) else { continue };
        if meta.is_dir() {
            let skip = here != root
                && here.file_name().and_then(|n| n.to_str()).is_some_and(ignored_dir);
            if skip {
                continue;
            }
            let Ok(listing) = fs::read_dir(&here) else { continue };
            let mut children: Vec<PathBuf> = listing.flatten().map(|e| e.path()).collect();
            children.sort_by(|a, b| b.file_name().cmp(&a.file_name()));
            pending.extend(children);
        } else if meta.is_file() {
            let Ok(rel) = here.strip_prefix(root) else { continue };
            let rel = rel.to_string_lossy().to_string();
            if rel.to_lowercase().contains(&needle) {
                hits.push(json!({ "path": rel, "size": meta.len() }));
                if hits.len() >= MAX_FILE_HITS {
                    break;
                }
            }
        }
    }
    hits
}

// MARK: - Limits

/// Parses `agy -p /quota` and `agy -p /credits` output.
pub fn parse_limits(quota: &str, credits: &str) -> (Vec<Value>, Option<f64>) {
    let mut limits = Vec::new();
    for row in quota.lines() {
        let cols: Vec<&str> = row.split('\t').map(|c| c.trim()).collect();
        if cols.len() <= 2 {
            continue;
        }
        let Ok(remaining) = cols[2].trim_end_matches('%').trim().parse::<f64>() else { continue };
        let (group, label) = (cols[0], cols[1]);
        let lower = label.to_lowercase();
        let window = if lower.contains("week") {
            "week"
        } else if ["five hour", "5 hour", "5-hour"].iter().any(|w| lower.contains(w)) {
            "five_hours"
        } else {
            "other"
        };
        let id: String = format!("{group}-{window}")
            .to_lowercase()
            .chars()
            .map(|c| if c.is_ascii_alphanumeric() { c } else { '-' })
            .collect();
        // agy prints the Unix epoch for a limit with no reset time; that means "none", not "now".
        let resets = cols.get(3).filter(|r| !r.is_empty() && !r.starts_with("1970-01-01")).map(|r| r.to_string());
        limits.push(json!({
            "id": id, "group": group, "window": window, "label": label,
            "remaining": remaining, "resets": resets,
        }));
    }
    let balance = credits.lines().find_map(|line| {
        let (key, value) = line.split_once('\t')?;
        if !key.to_lowercase().contains("credit") {
            return None;
        }
        value.trim().parse::<f64>().ok()
    });
    (limits, balance)
}

// MARK: - Small helpers

fn rfc3339(t: SystemTime) -> String {
    let secs = t.duration_since(UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0);
    let (days, rem) = ((secs / 86_400) as i64, secs % 86_400);
    // Civil date from days since 1970-01-01 (Howard Hinnant's method, re-derived).
    let shifted = days + 719_468;
    let era = shifted.div_euclid(146_097);
    let doe = shifted - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = doy - (153 * mp + 2) / 5 + 1;
    let month = if mp < 10 { mp + 3 } else { mp - 9 };
    let year = yoe + era * 400 + i64::from(month <= 2);
    format!(
        "{year:04}-{month:02}-{day:02}T{:02}:{:02}:{:02}Z",
        rem / 3600,
        (rem / 60) % 60,
        rem % 60
    )
}

fn says_not_found(message: &str) -> bool {
    message.to_ascii_lowercase().contains("not found")
}

fn fresh_id() -> String {
    HEXLOWER.encode(&rand::random::<[u8; 12]>())
}

fn text_param(params: &Value, key: &str) -> Option<String> {
    params.get(key).and_then(Value::as_str).filter(|s| !s.is_empty()).map(String::from)
}

fn load_json<T: DeserializeOwned + Default>(path: &Path) -> T {
    fs::read(path).ok().and_then(|b| serde_json::from_slice(&b).ok()).unwrap_or_default()
}

fn store_json<T: Serialize>(path: &Path, value: &T) {
    let body = serde_json::to_vec_pretty(value).unwrap_or_default();
    if let Err(e) = crate::write_private_file(path, &body) {
        warn!("could not write {}: {e}", path.display());
    }
}

// MARK: - Options and catalog

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
struct Opts {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    model: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    effort: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    mode: Option<String>,
}

impl Opts {
    fn from_params(p: &Value) -> Self {
        Self { model: text_param(p, "model"), effort: text_param(p, "effort"), mode: text_param(p, "mode") }
    }

    fn overlay(&self, top: &Opts) -> Opts {
        Opts {
            model: top.model.clone().or_else(|| self.model.clone()),
            effort: top.effort.clone().or_else(|| self.effort.clone()),
            mode: top.mode.clone().or_else(|| self.mode.clone()),
        }
    }

    fn to_json(&self) -> Value {
        json!({ "model": self.model, "effort": self.effort, "mode": self.mode })
    }
}

fn config_entry<'a>(config: &'a Value, id: &str) -> Option<&'a Value> {
    config.as_array()?.iter().find(|o| s_at(o, "id") == Some(id))
}

fn current_options(config: &Value, reply: &Value) -> Opts {
    let value_of = |id: &str| config_entry(config, id).and_then(|o| s_at(o, "currentValue")).map(String::from);
    Opts {
        model: value_of("model")
            .or_else(|| reply.pointer("/models/currentModelId").and_then(Value::as_str).map(String::from)),
        effort: value_of("thought_level"),
        mode: value_of("mode")
            .or_else(|| reply.pointer("/modes/currentModeId").and_then(Value::as_str).map(String::from)),
    }
}

#[derive(Debug, Clone, Default)]
struct Catalog {
    models: Vec<Value>,
    efforts: Vec<Value>,
    modes: Vec<Value>,
    current: Opts,
    commands: Vec<Value>,
}

impl Catalog {
    fn absorb(&mut self, reply: &Value) {
        let config = reply.get("configOptions").cloned().unwrap_or_default();
        let choices_of = |id: &str| -> Vec<Value> {
            match config_entry(&config, id).map(|o| &o["options"]) {
                Some(Value::Array(list)) => list.clone(),
                _ => Vec::new(),
            }
        };
        let model_choices = choices_of("model");
        if !model_choices.is_empty() {
            self.efforts = choices_of("thought_level")
                .iter()
                .map(|e| json!({ "id": e.get("value"), "name": e.get("name") }))
                .collect();
            self.models = model_choices
                .iter()
                .map(|m| json!({ "id": m.get("value"), "name": m.get("name"), "efforts": self.efforts }))
                .collect();
        } else if let Some(list) = reply.pointer("/models/availableModels").and_then(Value::as_array) {
            self.models = list
                .iter()
                .map(|m| json!({ "id": m.get("modelId"), "name": m.get("name"), "efforts": [] }))
                .collect();
        }
        if let Some(list) = reply.pointer("/modes/availableModes").and_then(Value::as_array) {
            self.modes = list
                .iter()
                .map(|m| json!({ "id": m.get("id"), "name": m.get("name"), "description": m.get("description") }))
                .collect();
        }
        self.current = current_options(&config, reply);
    }

    fn modes_json(&self, turbo_allowed: bool) -> Value {
        const TURBO_ABOUT: &str = "Runs in agy's auto mode and allows every request once, without asking.";
        let mut turbo = Map::new();
        for (k, v) in [("id", "turbo"), ("name", "Turbo"), ("description", TURBO_ABOUT)] {
            turbo.insert(k.to_owned(), Value::from(v));
        }
        let turbo = Value::Object(turbo);
        let extra = turbo_allowed.then_some(turbo);
        Value::Array(self.modes.iter().cloned().chain(extra).collect())
    }
}

// MARK: - State

#[derive(Debug, Clone)]
struct ChatEntry {
    id: String,
    title: String,
    cwd: Option<String>,
    updated: Option<String>,
}

struct Turn {
    conv: Conversation,
    tx: Option<mpsc::UnboundedSender<Value>>,
    replaying: bool,
    stopped: bool,
}

struct Waiting {
    chat: String,
    approval: Value,
    /// A permission gets agy's option id (a string); a question its answers (an object), or
    /// null when the user skipped it. None: nobody answered.
    answer: oneshot::Sender<Option<Value>>,
}

#[derive(Default)]
struct State {
    turns: HashMap<String, Turn>,
    requests: HashMap<String, String>,
    approvals: HashMap<String, Waiting>,
    /// Chats the long-lived process has loaded → user messages it has seen.
    loaded: HashMap<String, usize>,
    /// agy's current options per chat (in the long-lived process).
    current: HashMap<String, Opts>,
    chat_cache: Option<(Instant, Vec<ChatEntry>)>,
    limits_cache: Option<(Instant, Value)>,
    catalog: Option<Catalog>,
    agy_info: Option<Value>,
    /// Options picked on the phone, per chat.
    chosen: HashMap<String, Opts>,
    /// Chats agy lists but can't load (Antigravity desktop app chats).
    desktop: HashSet<String>,
    /// Chats the phone tried to use while the CLI held them, and when. Only these get a
    /// "you can reply now" notification when the CLI lets go; one-off CLI runs stay silent.
    cli_waiters: HashMap<String, Instant>,
}

impl State {
    fn chat_state(&self, id: &str) -> &'static str {
        if self.turns.contains_key(id) {
            "running"
        } else if open_in_cli(id) {
            "open_in_cli"
        } else {
            "idle"
        }
    }

    /// The long-lived process is gone (or new): nothing is loaded in it any more.
    fn forget_main(&mut self) {
        self.current.clear();
        self.loaded.clear();
    }

    fn release_questions(&mut self, chat: &str) {
        let ids: Vec<String> =
            self.approvals.iter().filter(|(_, w)| w.chat == chat).map(|(k, _)| k.clone()).collect();
        for id in ids {
            if let Some(w) = self.approvals.remove(&id) {
                let _ = w.answer.send(None);
            }
        }
    }
}

#[derive(Default)]
struct Saved {
    folders: HashMap<String, String>,
    turbo: BTreeSet<String>,
    defaults: Opts,
    times: HashMap<String, Vec<Option<f64>>>,
}

struct ReadChat {
    conv: Conversation,
    current: Opts,
}

pub struct Bridge {
    computer: [String; 3],
    data_dir: PathBuf,
    pusher: Option<Arc<Pusher>>,
    update_note: StdMutex<Option<String>>,
    state: Mutex<State>,
    saved: StdMutex<Saved>,
    main: Mutex<Option<Arc<AcpProcess>>>,
    preparing: Mutex<()>,
}

impl Bridge {
    pub fn new(host: String, model: String, os: String, dir: PathBuf, pusher: Option<Arc<Pusher>>) -> Arc<Self> {
        let (host_name, hardware_model, os_version, data_dir) = (host, model, os, dir);
        let saved = Saved {
            folders: load_json(&data_dir.join("chat_folders.json")),
            turbo: load_json(&data_dir.join("turbo_chats.json")),
            defaults: load_json(&data_dir.join("defaults.json")),
            times: load_json(&data_dir.join("turn_times.json")),
        };
        let _ = fs::create_dir_all(scratch_workspace());
        Arc::new(Self {
            computer: [host_name, hardware_model, os_version],
            data_dir,
            pusher,
            update_note: StdMutex::new(None),
            state: Mutex::new(State::default()),
            saved: StdMutex::new(saved),
            main: Mutex::new(None),
            preparing: Mutex::new(()),
        })
    }

    fn no_folder(&self) -> PathBuf {
        scratch_workspace()
    }

    fn saved(&self) -> std::sync::MutexGuard<'_, Saved> {
        self.saved.lock().unwrap_or_else(|p| p.into_inner())
    }

    fn persist(&self, part: &str) {
        let saved = self.saved();
        let dir = &self.data_dir;
        match part {
            "folders" => store_json(&dir.join("chat_folders.json"), &saved.folders),
            "turbo" => store_json(&dir.join("turbo_chats.json"), &saved.turbo),
            "defaults" => store_json(&dir.join("defaults.json"), &saved.defaults),
            _ => store_json(&dir.join("turn_times.json"), &saved.times),
        }
    }

    /// Turbo (every request allowed without asking) can be turned off for this computer with
    /// `agy-connector set-turbo off` (`allow_turbo: false` in config.json). Then a lost phone
    /// can't switch a chat to it, and chats switched earlier ask again.
    fn turbo_allowed(&self) -> bool {
        let config: Value = load_json(&self.data_dir.join("config.json"));
        !matches!(config.get("allow_turbo"), Some(Value::Bool(false)))
    }

    fn is_turbo(&self, chat: &str) -> bool {
        self.turbo_allowed() && self.saved().turbo.contains(chat)
    }

    /// One request, one reply (every method except `turn.start`).
    pub async fn handle(self: &Arc<Self>, phone: &str, name: &str, args: &Value) -> Result<Value, String> {
        let (phone_id, params) = (phone, args);
        let result = match name {
            "connector.info" => self.info().await,
            "chats.list" => self.chats_list().await,
            "chat.open" => self.chat_open(params).await,
            "chat.set_folder" => self.set_folder(params).await,
            "chat.set_option" => self.set_option(params).await,
            "turn.stop" => self.turn_stop(params).await,
            "approval.answer" => self.answer(params).await,
            "question.answer" => self.answer_question(params).await,
            "files.search" => self.files_search(params).await,
            "files.read" => self.files_read(params).await,
            "limits.get" => self.limits(params).await,
            "notifications.register" => self.register_push(phone_id, params).await,
            "notifications.unregister" => {
                match self.pusher.as_ref() {
                    Some(pusher) => pusher.unregister(phone_id).await,
                    None => {}
                }
                Ok(Value::Object(Map::new()))
            }
            other => Err(bad(format!("unknown method {other}"))),
        };
        Ok(match result {
            Ok(mut v) => {
                if let Some(obj) = v.as_object_mut() {
                    obj.insert("ok".into(), Value::Bool(true));
                }
                v
            }
            Err(e) => json!({ "ok": false, "error": e.to_json() }),
        })
    }

    /// `turn.start`: runs the turn in the background and returns its events.
    pub fn start_prompt(self: &Arc<Self>, rid: String, body: Value) -> mpsc::UnboundedReceiver<Value> {
        let (events_in, events_out) = mpsc::unbounded_channel();
        tokio::spawn(Arc::clone(self).run_turn(rid, body, events_in));
        events_out
    }

    /// The phone stopped listening; the turn keeps going.
    pub async fn detach_request(&self, rid: &str) {
        let mut guard = self.state.lock().await;
        let Some(chat) = guard.requests.remove(rid) else { return };
        if let Some(turn) = guard.turns.get_mut(&chat) {
            turn.tx.take();
        }
    }

    /// Notifies phones when the CLI lets go of a chat.
    pub fn spawn_cli_watch(self: &Arc<Self>) {
        let bridge = Arc::clone(self);
        tokio::spawn(async move {
            let mut before: Option<HashSet<String>> = None;
            let mut tick = tokio::time::interval(Duration::from_secs(4));
            tick.tick().await;
            loop {
                tick.tick().await;
                let Some(pusher) = bridge.pusher.clone() else { break };
                if !pusher.has_devices().await {
                    before.take();
                    continue;
                }
                let now_open = chats_open_in_cli();
                if let Some(prev) = &before {
                    let released: Vec<String> = {
                        let mut st = bridge.state.lock().await;
                        // Forget waits older than two hours; the user has moved on.
                        st.cli_waiters.retain(|_, at| at.elapsed() < Duration::from_secs(2 * 60 * 60));
                        prev.difference(&now_open).filter(|id| st.cli_waiters.remove(*id).is_some()).cloned().collect()
                    };
                    if !released.is_empty() {
                        let titles = bridge.titles().await;
                        for id in &released {
                            let title = titles.get(id).map(String::as_str).unwrap_or("");
                            pusher.notify(Event::CliReleased { session_id: id, title }).await;
                        }
                    }
                }
                before = Some(now_open);
            }
        });
    }

    pub fn set_update_note(&self, note: Option<String>) {
        *self.update_note.lock().unwrap_or_else(|p| p.into_inner()) = note;
    }

    pub async fn is_idle(&self) -> bool {
        let guard = self.state.lock().await;
        guard.approvals.is_empty() && guard.turns.is_empty()
    }

    pub async fn stop_agy(&self) {
        let taken = self.main.lock().await.take();
        if let Some(p) = taken {
            p.kill().await;
        }
    }

    // MARK: agy processes

    async fn main_process(self: &Arc<Self>) -> Outcome<Arc<AcpProcess>> {
        let mut slot = self.main.lock().await;
        if let Some(alive) = slot.as_ref().filter(|p| p.is_alive()) {
            return Ok(Arc::clone(alive));
        }
        let proc = match AcpProcess::start(&self.no_folder(), None).await {
            Ok(p) => p,
            Err(e) => return Err(agy_down(e)),
        };
        {
            let mut guard = self.state.lock().await;
            guard.forget_main();
            guard.agy_info = Some(proc.agent_info());
        }
        let inbox = proc.subscribe();
        *slot = Some(proc.clone());
        let me = self.clone();
        let watched = proc.clone();
        tokio::spawn(async move { me.dispatch(watched, inbox).await });
        Ok(proc)
    }

    async fn restart_main(&self) {
        let old = self.main.lock().await.take();
        if let Some(p) = old {
            p.kill().await;
        }
        self.state.lock().await.forget_main();
    }

    async fn reader(&self) -> Outcome<Arc<AcpProcess>> {
        let log = self.data_dir.join("agy-reader.log");
        if fs::metadata(&log).is_ok_and(|m| m.len() > READER_LOG_LIMIT) {
            let _ = fs::remove_file(&log);
        }
        let proc = AcpProcess::start(&self.no_folder(), Some(&log)).await.map_err(agy_down)?;
        self.state.lock().await.agy_info = Some(proc.agent_info());
        Ok(proc)
    }

    async fn dispatch(self: Arc<Self>, proc: Arc<AcpProcess>, mut inbox: broadcast::Receiver<Inbound>) {
        loop {
            match inbox.recv().await {
                Ok(Inbound::Update { session_id, update }) => {
                    let mapped = map_update(&update);
                    let mut st = self.state.lock().await;
                    match &mapped {
                        Mapped::Commands(list) => {
                            st.catalog.get_or_insert_with(Catalog::default).commands = list.clone();
                        }
                        Mapped::Config(cfg) => {
                            st.current.insert(session_id.clone(), current_options(cfg, &Value::Null));
                        }
                        Mapped::Mode(m) => {
                            st.current.entry(session_id.clone()).or_default().mode = Some(m.clone());
                        }
                        _ => {}
                    }
                    if let Some(turn) = st.turns.get_mut(&session_id)
                        && let Some(event) = turn.conv.apply(mapped)
                        && !turn.replaying
                        && let Some(tx) = &turn.tx
                    {
                        let _ = tx.send(event);
                    }
                }
                Ok(Inbound::Request { id, method, params }) if method == "session/request_permission" => {
                    tokio::spawn(Arc::clone(&self).permission(Arc::clone(&proc), id, params));
                }
                Ok(Inbound::Request { id, method, params }) if method == "elicitation/create" => {
                    tokio::spawn(Arc::clone(&self).elicitation(Arc::clone(&proc), id, params));
                }
                Ok(Inbound::Request { id, method, .. }) => {
                    let _ = proc.respond_unsupported(id, &method).await;
                }
                Err(broadcast::error::RecvError::Lagged(skipped)) => warn!("agy acp: {skipped} messages lost"),
                Ok(Inbound::Exited) | Err(_) => break,
            }
        }
        {
            let mut slot = self.main.lock().await;
            if slot.as_ref().is_some_and(|p| Arc::ptr_eq(p, &proc)) {
                *slot = None;
            }
        }
        let mut guard = self.state.lock().await;
        guard.forget_main();
        guard.approvals.clear();
    }

    async fn permission(self: Arc<Self>, proc: Arc<AcpProcess>, rpc_id: Value, params: Value) {
        let chat = s_at(&params, "sessionId").unwrap_or("").to_string();
        let approval_id = fresh_id();
        let approval = build_approval(&approval_id, &params);
        let reply = |choice: Option<String>| {
            let mut inner = Map::new();
            match choice {
                Some(option) => {
                    inner.insert("outcome".into(), json!("selected"));
                    inner.insert("optionId".into(), json!(option));
                }
                None => {
                    inner.insert("outcome".into(), json!("cancelled"));
                }
            }
            json!({ "outcome": inner })
        };
        if self.is_turbo(&chat) {
            let _ = proc.respond(rpc_id, reply(pick_choice(&approval, "allow"))).await;
            return;
        }
        let (tx, rx) = oneshot::channel();
        let title = {
            let mut st = self.state.lock().await;
            let cached_title = st
                .chat_cache
                .as_ref()
                .and_then(|(_, list)| list.iter().find(|c| c.id == chat).map(|c| c.title.clone()));
            let Some(turn) = st.turns.get_mut(&chat) else {
                drop(st);
                let _ = proc.respond(rpc_id, reply(None)).await;
                return;
            };
            turn.conv.agent().approvals.push(approval.clone());
            if let Some(sender) = &turn.tx {
                let _ = sender.send(json!({ "event": "turn.approval", "approval": approval }));
            }
            let title = turn.conv.title.clone().or(cached_title).unwrap_or_default();
            st.approvals.insert(approval_id.clone(), Waiting { chat: chat.clone(), approval: approval.clone(), answer: tx });
            title
        };
        if let Some(pusher) = &self.pusher {
            // Allow / Deny buttons only when the notification shows the whole request; otherwise
            // it is answered in the app, where all of it can be read.
            let (what, whole) = notification_text(&approval);
            let quick = if whole { approval_id.as_str() } else { "" };
            pusher.notify(Event::Permission { session_id: &chat, title: &title, what: &what, approval_id: quick }).await;
        }
        let choice = tokio::time::timeout(QUESTION_TIMEOUT, rx)
            .await
            .ok()
            .and_then(Result::ok)
            .flatten()
            .and_then(|v| v.as_str().map(String::from));
        {
            let mut st = self.state.lock().await;
            st.approvals.remove(&approval_id);
            if let Some(turn) = st.turns.get_mut(&chat) {
                for m in &mut turn.conv.messages {
                    m.approvals.retain(|a| s_at(a, "id") != Some(approval_id.as_str()));
                }
            }
        }
        let _ = proc.respond(rpc_id, reply(choice)).await;
    }

    // MARK: chats and folders

    async fn list_chats(&self) -> Outcome<Vec<ChatEntry>> {
        if let Some((at, list)) = &self.state.lock().await.chat_cache
            && at.elapsed() < CHAT_LIST_TTL
        {
            return Ok(list.clone());
        }
        let reader = self.reader().await?;
        let paged = async {
            let mut found = Vec::new();
            let mut cursor: Option<String> = None;
            for _ in 0..100 {
                let params = match &cursor {
                    Some(c) => json!({ "cursor": c }),
                    None => json!({}),
                };
                let page = reader.call("session/list", params, RPC_TIMEOUT).await.map_err(agy_down)?;
                for s in page.get("sessions").and_then(Value::as_array).into_iter().flatten() {
                    let Some(id) = s_at(s, "sessionId") else { continue };
                    found.push(ChatEntry {
                        id: id.to_string(),
                        title: s_at(s, "title").unwrap_or("").to_string(),
                        cwd: s_at(s, "cwd").map(agy_folder),
                        updated: s_at(s, "updatedAt").filter(|u| !u.starts_with("0001-")).map(String::from),
                    });
                }
                cursor = s_at(&page, "nextCursor").filter(|c| !c.is_empty()).map(String::from);
                if cursor.is_none() {
                    break;
                }
            }
            Ok::<_, BridgeError>(found)
        };
        let outcome = paged.await;
        reader.kill().await;
        let mut list = outcome?;
        list.sort_by(|a, b| match (&a.updated, &b.updated) {
            (Some(x), Some(y)) => y.cmp(x),
            (Some(_), None) => std::cmp::Ordering::Less,
            (None, Some(_)) => std::cmp::Ordering::Greater,
            (None, None) => std::cmp::Ordering::Equal,
        });
        self.state.lock().await.chat_cache = Some((Instant::now(), list.clone()));
        Ok(list)
    }

    async fn titles(&self) -> HashMap<String, String> {
        self.list_chats()
            .await
            .map(|l| l.into_iter().map(|c| (c.id, c.title)).collect())
            .unwrap_or_default()
    }

    fn assigned_folder(&self, chat: &str) -> Option<String> {
        self.saved().folders.get(chat).cloned()
    }

    fn folder_for(&self, entry: Option<&ChatEntry>, chat: &str) -> Option<String> {
        let assigned = self.assigned_folder(chat);
        // A folder that no longer exists on this computer can't be worked in, so the chat is shown
        // without one (the app then asks for a folder) instead of failing every request there.
        resolve_folder(entry.and_then(|e| e.cwd.as_deref()), assigned.as_deref(), &self.no_folder())
            .filter(|f| Path::new(f).is_dir())
    }

    fn known_folders(&self, chats: &[ChatEntry]) -> Vec<String> {
        let config: Value = load_json(&self.data_dir.join("config.json"));
        let added: Vec<String> = config
            .get("workspaces")
            .and_then(Value::as_array)
            .map(|ws| ws.iter().filter_map(Value::as_str).map(String::from).collect())
            .unwrap_or_default();
        let mut candidates: Vec<String> = chats
            .iter()
            .filter_map(|c| resolve_folder(c.cwd.as_deref(), None, &self.no_folder()))
            .filter(|f| !too_broad(f))
            .collect();
        candidates.extend(added.iter().cloned());
        candidates.extend(
            self.saved().folders.values().filter(|f| !too_broad(f) || is_known_folder(f, &added)).cloned(),
        );
        let mut known: Vec<String> = Vec::new();
        for c in candidates {
            if Path::new(&c).is_dir() && !is_known_folder(&c, &known) {
                known.push(c);
            }
        }
        known
    }

    async fn all_known_folders(&self) -> Vec<String> {
        let chats = self.list_chats().await.unwrap_or_default();
        self.known_folders(&chats)
    }

    async fn read_chat(&self, chat: &str, folder: Option<&str>) -> Outcome<ReadChat> {
        let reader = self.reader().await?;
        let mut inbox = reader.subscribe();
        let cwd = folder.map(String::from).unwrap_or_else(|| self.no_folder().to_string_lossy().to_string());
        let loaded = reader
            .call("session/load", json!({ "sessionId": chat, "cwd": cwd, "mcpServers": [] }), RPC_TIMEOUT)
            .await;
        if loaded.is_ok() {
            tokio::time::sleep(Duration::from_millis(400)).await;
        }
        reader.kill().await;
        let reply = match loaded {
            Ok(r) => r,
            Err(e) if says_not_found(&e) => {
                self.state.lock().await.desktop.insert(chat.to_owned());
                return Err(BridgeError::new("not_found", e));
            }
            Err(e) => return Err(agy_down(e)),
        };
        let mut conv = Conversation::default();
        let mut config = reply.get("configOptions").cloned().unwrap_or(Value::Null);
        let mut commands: Option<Vec<Value>> = None;
        loop {
            match inbox.try_recv() {
                Ok(Inbound::Update { session_id, update }) if session_id == chat => match map_update(&update) {
                    Mapped::Commands(c) => commands = Some(c),
                    Mapped::Config(c) => config = c,
                    other => {
                        conv.apply(other);
                    }
                },
                Ok(_) | Err(broadcast::error::TryRecvError::Lagged(_)) => continue,
                Err(_) => break,
            }
        }
        {
            let mut st = self.state.lock().await;
            let catalog = st.catalog.get_or_insert_with(Catalog::default);
            catalog.absorb(&reply);
            if let Some(c) = commands {
                catalog.commands = c;
            }
        }
        Ok(ReadChat { conv, current: current_options(&config, &reply) })
    }

    // MARK: methods

    async fn info(&self) -> Outcome<Value> {
        let need_catalog = {
            let st = self.state.lock().await;
            st.catalog.as_ref().is_none_or(|c| c.models.is_empty())
        };
        let chats = self.list_chats().await.unwrap_or_default();
        if need_catalog {
            let desktop = self.state.lock().await.desktop.clone();
            for entry in chats.iter().filter(|c| !desktop.contains(&c.id)).take(3) {
                let folder = resolve_folder(entry.cwd.as_deref(), None, &self.no_folder());
                if self.read_chat(&entry.id, folder.as_deref()).await.is_ok() {
                    break;
                }
            }
        }
        if self.state.lock().await.agy_info.is_none()
            && let Ok(r) = self.reader().await
        {
            r.kill().await;
        }
        let folders: Vec<Value> = self
            .known_folders(&chats)
            .into_iter()
            .map(|path| {
                let p = Path::new(&path);
                let name = p.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_else(|| path.clone());
                json!({ "path": path, "name": name, "kind": folder_kind(p) })
            })
            .collect();
        let defaults = self.saved().defaults.clone();
        let note = self.update_note.lock().unwrap_or_else(|p| p.into_inner()).clone();
        let st = self.state.lock().await;
        let catalog = st.catalog.clone().unwrap_or_default();
        let agy = st.agy_info.clone().unwrap_or(Value::Null);
        let commands: Vec<Value> = catalog
            .commands
            .iter()
            .map(|c| json!({ "name": c.get("name"), "description": c.get("description") }))
            .collect();
        Ok(json!({
            "protocol": PROTOCOL,
            "computer": { "os": self.computer[2], "model": self.computer[1], "name": self.computer[0] },
            "agy": { "name": agy.get("name"), "version": agy.get("version") },
            "connector": {
                "version": update::CURRENT,
                "minAppVersion": update::MIN_APP_VERSION,
                "autoUpdate": update::auto_update_enabled(&self.data_dir),
                "updateNote": note,
                // Where notifications go through and under which key, for the phone to ask that
                // relay for a grant (see relay/src/index.js). Absent when notifications aren't available.
                "push": self.pusher.as_ref().map(|p| json!({ "relay": p.relay(), "key": p.relay_key() })),
            },
            "folders": folders,
            "models": catalog.models,
            "modes": catalog.modes_json(self.turbo_allowed()),
            "defaults": catalog.current.overlay(&defaults).to_json(),
            "commands": commands,
        }))
    }

    async fn chats_list(&self) -> Outcome<Value> {
        let chats = self.list_chats().await?;
        let st = self.state.lock().await;
        let rows: Vec<Value> = chats
            .iter()
            .map(|c| {
                json!({
                    "id": c.id,
                    "title": c.title,
                    "folder": self.folder_for(Some(c), &c.id),
                    "updated": c.updated,
                    "state": st.chat_state(&c.id),
                })
            })
            .collect();
        Ok(json!({ "chats": rows }))
    }

    fn chat_options(&self, st: &State, chat: &str, base: &Opts) -> Value {
        let mut merged = match st.chosen.get(chat) {
            Some(picked) => base.overlay(picked),
            None => base.clone(),
        };
        if self.is_turbo(chat) {
            merged.mode = Some("turbo".into());
        }
        merged.to_json()
    }

    async fn chat_open(&self, params: &Value) -> Outcome<Value> {
        let chat = text_param(params, "chatId").ok_or_else(|| bad("chatId missing"))?;
        if !valid_chat_id(&chat) {
            return Err(bad("invalid chatId"));
        }
        let times = self.saved().times.get(&chat).cloned().unwrap_or_default();
        {
            let st = self.state.lock().await;
            if let Some(turn) = st.turns.get(&chat) {
                let mut conv = turn.conv.clone();
                conv.attach_seconds(&times);
                let current = st.current.get(&chat).cloned().unwrap_or_default();
                return Ok(json!({
                    "chatId": chat,
                    "state": "running",
                    "readable": true,
                    "unreadableReason": null,
                    "context": conv.context_json(),
                    "options": self.chat_options(&st, &chat, &current),
                    "messages": conv.messages_json(),
                }));
            }
        }
        let locked = open_in_cli(&chat);
        if locked {
            self.state.lock().await.cli_waiters.insert(chat.clone(), Instant::now());
        }
        let state = if locked { "open_in_cli" } else { "idle" };
        let unreadable = |reason: &str| {
            json!({
                "chatId": chat,
                "state": state,
                "readable": false,
                "unreadableReason": reason,
                "context": null,
                "options": Opts::default().to_json(),
                "messages": [],
            })
        };
        if self.state.lock().await.desktop.contains(&chat) {
            return Ok(unreadable("desktop_app"));
        }
        let chats = self.list_chats().await?;
        let folder = self.folder_for(chats.iter().find(|c| c.id == chat), &chat);
        let mut read = match self.read_chat(&chat, folder.as_deref()).await {
            Ok(r) => r,
            Err(e) if e.code == "not_found" => return Ok(unreadable("desktop_app")),
            Err(e) => return Err(e),
        };
        if locked && read.conv.messages.is_empty() {
            return Ok(unreadable("cli_unsaved"));
        }
        read.conv.attach_seconds(&times);
        let st = self.state.lock().await;
        Ok(json!({
            "chatId": chat,
            "state": state,
            "readable": true,
            "unreadableReason": null,
            "context": read.conv.context_json(),
            "options": self.chat_options(&st, &chat, &read.current),
            "messages": read.conv.messages_json(),
        }))
    }

    async fn set_folder(&self, params: &Value) -> Outcome<Value> {
        let chat = text_param(params, "chatId").filter(|c| valid_chat_id(c)).ok_or_else(|| bad("chatId"))?;
        let folder = text_param(params, "folder").ok_or_else(|| bad("folder missing"))?;
        let known = self.all_known_folders().await;
        let spelling = known
            .into_iter()
            .find(|k| same_path(k, &folder))
            .ok_or_else(|| BridgeError::new("not_allowed", "not a known folder"))?;
        self.saved().folders.insert(chat, spelling);
        self.persist("folders");
        self.state.lock().await.chat_cache = None;
        Ok(json!({}))
    }

    async fn set_option(&self, params: &Value) -> Outcome<Value> {
        let option = text_param(params, "option").ok_or_else(|| bad("option missing"))?;
        let value = text_param(params, "value").ok_or_else(|| bad("value missing"))?;
        if option == "mode" && value == "turbo" && !self.turbo_allowed() {
            return Err(BridgeError::new("not_allowed", "Turbo is turned off on this computer"));
        }
        let assign = |opts: &mut Opts| -> Outcome<()> {
            let field = match option.as_str() {
                "model" => &mut opts.model,
                "effort" => &mut opts.effort,
                "mode" => &mut opts.mode,
                _ => return Err(bad("unknown option")),
            };
            *field = Some(value.clone());
            Ok(())
        };
        let Some(chat) = text_param(params, "chatId") else {
            let snapshot = {
                let mut saved = self.saved();
                assign(&mut saved.defaults)?;
                saved.defaults.to_json()
            };
            self.persist("defaults");
            return Ok(json!({ "options": snapshot }));
        };
        if !valid_chat_id(&chat) {
            return Err(bad("invalid chatId"));
        }
        let mut picked = self.state.lock().await.chosen.get(&chat).cloned().unwrap_or_default();
        assign(&mut picked)?;
        if option == "mode" {
            self.set_turbo(&chat, value == "turbo");
        }
        let mut st = self.state.lock().await;
        st.chosen.insert(chat.clone(), picked);
        let current = st.current.get(&chat).cloned().unwrap_or_default();
        Ok(json!({ "options": self.chat_options(&st, &chat, &current) }))
    }

    fn set_turbo(&self, chat: &str, on: bool) {
        let changed = {
            let mut saved = self.saved();
            if on { saved.turbo.insert(chat.to_string()) } else { saved.turbo.remove(chat) }
        };
        if changed {
            self.persist("turbo");
        }
    }

    async fn turn_stop(&self, params: &Value) -> Outcome<Value> {
        let chat = text_param(params, "chatId").ok_or_else(|| bad("chatId missing"))?;
        {
            let mut st = self.state.lock().await;
            let turn = st.turns.get_mut(&chat).ok_or_else(|| BridgeError::new("not_found", "no turn running"))?;
            turn.stopped = true;
            st.release_questions(&chat);
        }
        let proc = self.main.lock().await.clone();
        if let Some(p) = proc {
            p.notify("session/cancel", json!({ "sessionId": chat })).await.map_err(agy_down)?;
        }
        Ok(json!({}))
    }

    async fn answer(&self, params: &Value) -> Outcome<Value> {
        let id = text_param(params, "approvalId").ok_or_else(|| bad("approvalId missing"))?;
        let choice = text_param(params, "choice").ok_or_else(|| bad("choice missing"))?;
        let mut st = self.state.lock().await;
        let waiting = st
            .approvals
            .get(&id)
            .ok_or_else(|| BridgeError::new("not_found", "question is no longer waiting"))?;
        let option = pick_choice(&waiting.approval, &choice).ok_or_else(|| bad("unknown choice"))?;
        if let Some(w) = st.approvals.remove(&id) {
            let _ = w.answer.send(Some(json!(option)));
        }
        Ok(json!({}))
    }

    /// `question.answer`: answers to an `ask_question` form, or `skip: true`.
    async fn answer_question(&self, params: &Value) -> Outcome<Value> {
        let id = text_param(params, "approvalId").ok_or_else(|| bad("approvalId missing"))?;
        let mut st = self.state.lock().await;
        let waiting = st
            .approvals
            .get(&id)
            .ok_or_else(|| BridgeError::new("not_found", "question is no longer waiting"))?;
        let reply = if params.get("skip").and_then(Value::as_bool) == Some(true) {
            Value::Null
        } else {
            let answers = params.get("answers").ok_or_else(|| bad("answers missing"))?;
            checked_answers(&waiting.approval, answers).map_err(bad)?
        };
        if let Some(w) = st.approvals.remove(&id) {
            let _ = w.answer.send(Some(reply));
        }
        Ok(json!({}))
    }

    /// agy asks the user with `ask_question` (`elicitation/create`): shown on the phone like an
    /// approval, answered with `question.answer`.
    async fn elicitation(self: Arc<Self>, proc: Arc<AcpProcess>, rpc_id: Value, params: Value) {
        let chat = s_at(&params, "sessionId").unwrap_or("").to_string();
        let approval_id = fresh_id();
        let Some(approval) = build_question(&approval_id, &params) else {
            let _ = proc.respond(rpc_id, json!({ "action": "decline" })).await;
            return;
        };
        let (tx, rx) = oneshot::channel();
        let title = {
            let mut st = self.state.lock().await;
            let Some(turn) = st.turns.get_mut(&chat) else {
                drop(st);
                // Not a turn from the phone (the CLI has its own question form).
                let _ = proc.respond(rpc_id, json!({ "action": "decline" })).await;
                return;
            };
            turn.conv.agent().approvals.push(approval.clone());
            if let Some(sender) = &turn.tx {
                let _ = sender.send(json!({ "event": "turn.approval", "approval": approval }));
            }
            let title = turn.conv.title.clone().unwrap_or_default();
            st.approvals.insert(approval_id.clone(), Waiting { chat: chat.clone(), approval: approval.clone(), answer: tx });
            title
        };
        if let Some(pusher) = &self.pusher {
            let asked: Vec<&str> = approval["questions"]
                .as_array()
                .into_iter()
                .flatten()
                .filter_map(|q| s_at(q, "question"))
                .collect();
            // Answered in the app: no buttons on the notification.
            pusher.notify(Event::Question { session_id: &chat, title: &title, what: &asked.join(" / ") }).await;
        }
        let answer = tokio::time::timeout(QUESTION_TIMEOUT, rx).await.ok().and_then(Result::ok).flatten();
        {
            let mut st = self.state.lock().await;
            st.approvals.remove(&approval_id);
            if let Some(turn) = st.turns.get_mut(&chat) {
                for m in &mut turn.conv.messages {
                    m.approvals.retain(|a| s_at(a, "id") != Some(approval_id.as_str()));
                }
            }
        }
        let reply = match answer {
            Some(Value::Null) => json!({ "action": "decline" }),
            Some(content) => json!({ "action": "accept", "content": content }),
            None => json!({ "action": "cancel" }),
        };
        let _ = proc.respond(rpc_id, reply).await;
    }

    async fn files_search(&self, params: &Value) -> Outcome<Value> {
        let folder = text_param(params, "folder").ok_or_else(|| bad("folder missing"))?;
        let query = s_at(params, "query").unwrap_or("").to_string();
        if !is_known_folder(&folder, &self.all_known_folders().await) {
            return Err(BridgeError::new("not_allowed", "not a known folder"));
        }
        let private = fs::canonicalize(&self.data_dir).ok();
        let files = tokio::task::spawn_blocking(move || {
            let root = Path::new(&folder);
            let mut hits = search_files(root, &query);
            // A folder that contains the connector's own folder doesn't list its keys.
            if let (Some(p), Ok(base)) = (private, fs::canonicalize(root)) {
                hits.retain(|h| !s_at(h, "path").is_some_and(|rel| base.join(rel).starts_with(&p)));
            }
            hits
        })
            .await
            .map_err(|e| BridgeError::new("internal", e.to_string()))?;
        Ok(json!({ "files": files }))
    }

    /// `files.read`: a file an agent reply links to, for the phone to show. Only files in the
    /// computer's known folders or in agy's artifact folder, never the connector's own files.
    async fn files_read(&self, params: &Value) -> Outcome<Value> {
        let link = text_param(params, "path").ok_or_else(|| bad("path missing"))?;
        let chat = text_param(params, "chatId").filter(|c| valid_chat_id(c));
        let folder = match &chat {
            Some(c) => {
                let chats = self.list_chats().await.unwrap_or_default();
                self.folder_for(chats.iter().find(|e| &e.id == c), c)
            }
            None => None,
        };
        let path = link_path(&link, folder.as_deref()).ok_or_else(|| bad("not a file link"))?;
        let mut allowed: Vec<PathBuf> = self.all_known_folders().await.into_iter().map(PathBuf::from).collect();
        allowed.push(artifact_root());
        let private = self.data_dir.clone();
        let read = tokio::task::spawn_blocking(move || -> Outcome<Value> {
            let full = readable_file(&path, &allowed, &private)
                .ok_or_else(|| BridgeError::new("not_allowed", "not a file this computer shares"))?;
            let mut bytes = Vec::new();
            use std::io::Read;
            File::open(&full)
                .and_then(|f| f.take(MAX_READ_BYTES as u64 + 1).read_to_end(&mut bytes))
                .map_err(|e| BridgeError::new("not_found", e.to_string()))?;
            let truncated = bytes.len() > MAX_READ_BYTES;
            bytes.truncate(MAX_READ_BYTES);
            let text = match String::from_utf8(bytes) {
                Ok(t) => t,
                // Cut inside a character: drop the partial one.
                Err(e) if truncated && e.utf8_error().error_len().is_none() => {
                    let valid = e.utf8_error().valid_up_to();
                    String::from_utf8(e.into_bytes()[..valid].to_vec()).unwrap_or_default()
                }
                Err(_) => return Err(BridgeError::new("not_text", "not a text file")),
            };
            let name = full.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default();
            Ok(json!({ "path": full.to_string_lossy(), "name": name, "text": text, "truncated": truncated }))
        });
        read.await.map_err(|e| BridgeError::new("internal", e.to_string()))?
    }

    async fn limits(&self, params: &Value) -> Outcome<Value> {
        let refresh = params.get("refresh").and_then(Value::as_bool).unwrap_or(false);
        if !refresh
            && let Some((at, cached)) = &self.state.lock().await.limits_cache
            && at.elapsed() < LIMITS_TTL
        {
            return Ok(cached.clone());
        }
        let quota = self.agy_print("/quota").await?;
        let credits = self.agy_print("/credits").await.unwrap_or_default();
        let (limits, balance) = parse_limits(&quota, &credits);
        if limits.is_empty() {
            return Err(agy_down(format!("could not read limits: {}", clip(quota.trim(), 200))));
        }
        let result = json!({ "fetched": rfc3339(SystemTime::now()), "limits": limits, "credits": balance });
        self.state.lock().await.limits_cache = Some((Instant::now(), result.clone()));
        Ok(result)
    }

    async fn agy_print(&self, command: &str) -> Outcome<String> {
        let Some(binary) = find_agy() else { return Err(agy_down("agy CLI not found")) };
        let log = self.data_dir.join("agy-reader.log");
        let mut cmd = tokio::process::Command::new(binary);
        cmd.arg("--log-file").args([log.as_os_str(), "-p".as_ref(), command.as_ref()]);
        cmd.kill_on_drop(true).current_dir(self.no_folder());
        #[cfg(windows)]
        cmd.creation_flags(crate::acp::CREATE_NO_WINDOW);
        cmd.stdout(Stdio::piped()).stderr(Stdio::null()).stdin(Stdio::null());
        let child = cmd.spawn().map_err(|e| agy_down(format!("{e}")))?;
        match tokio::time::timeout(PRINT_TIMEOUT, child.wait_with_output()).await {
            Err(_) => Err(agy_down(format!("agy -p {command} timed out"))),
            Ok(Err(e)) => Err(agy_down(e.to_string())),
            Ok(Ok(out)) => Ok(String::from_utf8_lossy(&out.stdout).into_owned()),
        }
    }

    async fn register_push(&self, phone_id: &str, params: &Value) -> Outcome<Value> {
        let pusher = self
            .pusher
            .as_ref()
            .ok_or_else(|| BridgeError::new("internal", "notifications are not available"))?;
        let mut reg = Map::new();
        reg.insert("token".into(), params.get("token").cloned().unwrap_or(Value::Null));
        reg.insert("platform".into(), json!(s_at(params, "platform").unwrap_or("apns")));
        reg.insert("env".into(), json!(s_at(params, "environment").unwrap_or("production")));
        reg.insert("key".into(), params.get("key").cloned().unwrap_or(Value::Null));
        for field in ["grant", "grantExp"] {
            if let Some(v) = params.get(field) {
                reg.insert(field.into(), v.clone());
            }
        }
        if let Some(kinds) = params.get("kinds").and_then(Value::as_array) {
            let wire: Vec<&str> = kinds
                .iter()
                .filter_map(Value::as_str)
                .filter_map(|k| match k {
                    "finished" => Some("turn_complete"),
                    "failed" => Some("turn_failed"),
                    "approval" => Some("permission"),
                    "cli_released" => Some("cli_released"),
                    _ => None,
                })
                .collect();
            reg.insert("kinds".into(), json!(wire));
        }
        let grant_refused = pusher.register(phone_id, &Value::Object(reg)).await.map_err(|e| {
            eprintln!("notifications: phone {}… could not register: {e}", &phone_id[..phone_id.len().min(8)]);
            bad(e)
        })?;
        Ok(if grant_refused { json!({ "grantRefused": true }) } else { json!({}) })
    }

    // MARK: turns

    async fn emit(&self, chat: &str, event: Value) {
        let st = self.state.lock().await;
        if let Some(tx) = st.turns.get(chat).and_then(|t| t.tx.as_ref()) {
            let _ = tx.send(event);
        }
    }

    async fn run_turn(self: Arc<Self>, request_id: String, params: Value, tx: mpsc::UnboundedSender<Value>) {
        let mut chat: Option<String> = None;
        let outcome = self.turn_inner(&request_id, &params, &tx, &mut chat).await;
        if let Err(e) = outcome {
            let failed = json!({ "error": e.to_json(), "event": "turn.failed" });
            let _ = tx.send(failed);
            if let (Some(pusher), Some(id)) = (&self.pusher, &chat) {
                let title = {
                    let st = self.state.lock().await;
                    st.turns.get(id).and_then(|t| t.conv.title.clone()).unwrap_or_default()
                };
                pusher.notify(Event::TurnFailed { session_id: id, title: &title, code: e.code, error: &e.message }).await;
            }
        }
        let mut st = self.state.lock().await;
        st.requests.remove(&request_id);
        if let Some(id) = &chat {
            st.turns.remove(id);
            st.release_questions(id);
        }
        st.chat_cache = None;
    }

    async fn turn_inner(
        self: &Arc<Self>,
        request_id: &str,
        params: &Value,
        tx: &mpsc::UnboundedSender<Value>,
        chat_out: &mut Option<String>,
    ) -> Outcome<()> {
        let text = s_at(params, "text").unwrap_or("").to_string();
        let attachments: Vec<Value> = match params.get("attachments") {
            Some(Value::Array(list)) => list.clone(),
            _ => Vec::new(),
        };
        let asked = params.get("options").map(Opts::from_params).unwrap_or_default();
        if text.trim().is_empty() && attachments.is_empty() {
            return Err(bad("empty message"));
        }
        let prep = self.preparing.lock().await;

        let (chat, folder, desired, proc) = match text_param(params, "chatId") {
            Some(chat) => {
                if !valid_chat_id(&chat) {
                    return Err(bad("invalid chatId"));
                }
                if open_in_cli(&chat) {
                    self.state.lock().await.cli_waiters.insert(chat.clone(), Instant::now());
                    return Err(BridgeError::new("open_in_cli", "the agy CLI has this chat open"));
                }
                let (running_here, on_desktop, seen_before, others_running) = {
                    let st = self.state.lock().await;
                    (
                        st.turns.contains_key(&chat),
                        st.desktop.contains(&chat),
                        st.loaded.get(&chat).copied(),
                        !st.turns.is_empty(),
                    )
                };
                if running_here {
                    return Err(BridgeError::new("busy", "a turn is already running in this chat"));
                }
                if on_desktop {
                    return Err(BridgeError::new("not_found", "agy can't open this chat"));
                }
                let chats = self.list_chats().await?;
                let folder = self
                    .folder_for(chats.iter().find(|c| c.id == chat), &chat)
                    .ok_or_else(|| BridgeError::new("folder_required", "chat has no folder"))?;
                let mut base: Option<Conversation> = None;
                if let Some(seen) = seen_before {
                    if !others_running {
                        self.restart_main().await;
                    } else {
                        let fresh = self.read_chat(&chat, Some(&folder)).await?;
                        if fresh.conv.user_count() != seen {
                            return Err(BridgeError::new(
                                "busy",
                                "chat changed elsewhere; try again when the other turn ends",
                            ));
                        }
                        base = Some(fresh.conv);
                    }
                }
                let have_base = base.is_some();
                self.state.lock().await.turns.insert(
                    chat.clone(),
                    Turn { conv: base.unwrap_or_default(), tx: None, replaying: true, stopped: false },
                );
                *chat_out = Some(chat.clone());
                let proc = self.main_process().await?;
                let still_loaded = self.state.lock().await.loaded.contains_key(&chat);
                if !have_base || !still_loaded {
                    let reply = proc
                        .call("session/load", json!({ "sessionId": chat, "cwd": folder, "mcpServers": [] }), RPC_TIMEOUT)
                        .await
                        .map_err(|e| {
                            if says_not_found(&e) {
                                BridgeError::new("not_found", e)
                            } else {
                                agy_down(e)
                            }
                        })?;
                    let mut st = self.state.lock().await;
                    let cfg = reply.get("configOptions").cloned().unwrap_or(Value::Null);
                    st.current.insert(chat.clone(), current_options(&cfg, &reply));
                    st.catalog.get_or_insert_with(Catalog::default).absorb(&reply);
                    let users = st.turns.get(&chat).map(|t| t.conv.user_count()).unwrap_or(0);
                    st.loaded.insert(chat.clone(), users);
                }
                let picked = self.state.lock().await.chosen.get(&chat).cloned().unwrap_or_default();
                (chat, folder, picked.overlay(&asked), proc)
            }
            None => {
                let wanted = text_param(params, "folder")
                    .ok_or_else(|| BridgeError::new("folder_required", "new chat needs a folder"))?;
                let folder = self
                    .all_known_folders()
                    .await
                    .into_iter()
                    .find(|k| same_path(k, &wanted))
                    .ok_or_else(|| BridgeError::new("not_allowed", "not a known folder"))?;
                let proc = self.main_process().await?;
                let reply = proc
                    .call("session/new", json!({ "cwd": folder, "mcpServers": [] }), RPC_TIMEOUT)
                    .await
                    .map_err(agy_down)?;
                let chat = s_at(&reply, "sessionId")
                    .map(String::from)
                    .ok_or_else(|| agy_down("session/new returned no sessionId"))?;
                {
                    let mut st = self.state.lock().await;
                    let cfg = reply.get("configOptions").cloned().unwrap_or(Value::Null);
                    st.current.insert(chat.clone(), current_options(&cfg, &reply));
                    st.catalog.get_or_insert_with(Catalog::default).absorb(&reply);
                    st.loaded.insert(chat.clone(), 0);
                    st.turns.insert(
                        chat.clone(),
                        Turn { conv: Conversation::default(), tx: None, replaying: true, stopped: false },
                    );
                }
                *chat_out = Some(chat.clone());
                let defaults = self.saved().defaults.clone();
                (chat, folder, defaults.overlay(&asked), proc)
            }
        };

        // Options: send only what differs from agy's current values.
        if desired.mode.as_deref() == Some("turbo") && !self.turbo_allowed() {
            return Err(BridgeError::new("not_allowed", "Turbo is turned off on this computer"));
        }
        if let Some(m) = &desired.mode {
            self.set_turbo(&chat, m == "turbo");
        }
        let now = self.state.lock().await.current.get(&chat).cloned().unwrap_or_default();
        if let Some(model) = &desired.model
            && now.model.as_ref() != Some(model)
        {
            proc.call("session/set_model", json!({ "sessionId": chat, "modelId": model }), RPC_TIMEOUT)
                .await
                .map_err(|e| bad(format!("model: {e}")))?;
        }
        if let Some(effort) = &desired.effort
            && now.effort.as_ref() != Some(effort)
        {
            let args = json!({ "sessionId": chat, "configId": "thought_level", "value": effort });
            proc.call("session/set_config_option", args, RPC_TIMEOUT)
                .await
                .map_err(|e| bad(format!("effort: {e}")))?;
        }
        let agy_mode = desired.mode.as_ref().map(|m| if m == "turbo" { "auto".to_string() } else { m.clone() });
        if let Some(mode) = &agy_mode
            && now.mode.as_ref() != Some(mode)
        {
            proc.call("session/set_mode", json!({ "sessionId": chat, "modeId": mode }), RPC_TIMEOUT)
                .await
                .map_err(|e| bad(format!("mode: {e}")))?;
        }
        {
            let mut st = self.state.lock().await;
            let applied = Opts { mode: agy_mode.clone(), ..desired.clone() };
            st.current.insert(chat.clone(), now.overlay(&applied));
            st.chosen.insert(chat.clone(), desired.clone());
        }

        // Prompt content.
        let mut content = vec![json!({ "type": "text", "text": text })];
        let mut shown = Vec::new();
        for a in &attachments {
            match s_at(a, "kind") {
                Some("image") => {
                    let Some(data) = s_at(a, "data") else { return Err(bad("image without data")) };
                    let mime = s_at(a, "mime").unwrap_or("image/png");
                    content.push(json!({ "mimeType": mime, "data": data, "type": "image" }));
                    shown.push(json!({ "kind": "image", "name": s_at(a, "name").unwrap_or("image") }));
                }
                Some("file") => {
                    let rel = s_at(a, "path").unwrap_or("");
                    // A chat agy ran in the home folder (or above) works there, but files are only
                    // attached from a folder that could be offered (see `too_broad`).
                    if too_broad(&folder) {
                        return Err(BridgeError::new("not_allowed", "files can't be attached in this folder"));
                    }
                    let full = resolve_in_folder(&folder, rel)
                        .filter(|f| !fs::canonicalize(&self.data_dir).is_ok_and(|p| f.starts_with(p)))
                        .ok_or_else(|| {
                            BridgeError::new("not_allowed", format!("{rel} is not inside the chat's folder"))
                        })?;
                    let name = full.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default();
                    content.push(json!({
                        "type": "resource_link",
                        "uri": format!("file://{}", full.display()),
                        "name": name,
                    }));
                    shown.push(json!({ "kind": "file", "name": rel }));
                }
                _ => return Err(bad("unknown attachment kind")),
            }
        }

        {
            let mut st = self.state.lock().await;
            st.requests.insert(request_id.to_string(), chat.clone());
            let turn = st.turns.get_mut(&chat).ok_or_else(|| BridgeError::new("internal", "turn vanished"))?;
            turn.replaying = false;
            turn.tx = Some(tx.clone());
            turn.conv.push_user(text.clone(), shown);
            turn.conv.messages.push(Msg { live: true, ..Msg::default() });
        }
        drop(prep);
        self.emit(&chat, json!({ "event": "turn.chat", "chatId": chat, "folder": folder })).await;

        let touched = Arc::new(AtomicBool::new(false));
        let watcher = {
            let flag = touched.clone();
            let id = chat.clone();
            tokio::spawn(async move {
                loop {
                    if open_in_cli(&id) {
                        flag.store(true, Ordering::SeqCst);
                    }
                    tokio::time::sleep(Duration::from_secs(1)).await;
                }
            })
        };
        let began = Instant::now();
        let result = proc
            .call("session/prompt", json!({ "sessionId": chat, "prompt": content }), PROMPT_TIMEOUT)
            .await;
        watcher.abort();
        let seconds = (began.elapsed().as_secs_f64() * 10.0).round() / 10.0;

        let (stopped, title, reply_text, slot) = {
            let mut st = self.state.lock().await;
            let mut info = (false, String::new(), String::new(), None, 0usize);
            if let Some(turn) = st.turns.get_mut(&chat) {
                if let Some(last) = turn.conv.messages.last_mut() {
                    last.live = false;
                    last.seconds = Some(seconds);
                    last.approvals.clear();
                }
                let agents = turn.conv.agent_count();
                info = (
                    turn.stopped,
                    turn.conv.title.clone().unwrap_or_default(),
                    turn.conv.last_reply(),
                    agents.checked_sub(1),
                    turn.conv.user_count(),
                );
            }
            st.loaded.insert(chat.clone(), info.4);
            (info.0, info.1, info.2, info.3)
        };
        if let Some(index) = slot {
            {
                let mut saved = self.saved();
                let list = saved.times.entry(chat.clone()).or_default();
                if list.len() <= index {
                    list.resize(index + 1, None);
                }
                list[index] = Some(seconds);
            }
            self.persist("times");
        }
        if touched.load(Ordering::SeqCst) {
            self.emit(&chat, json!({ "event": "turn.notice", "code": "cli_touched" })).await;
        }
        match result {
            Ok(reply) => {
                let reason = s_at(&reply, "stopReason").unwrap_or("end_turn");
                if stopped || reason == "cancelled" {
                    self.emit(&chat, json!({ "event": "turn.stopped" })).await;
                } else {
                    self.emit(&chat, json!({ "event": "turn.finished", "seconds": seconds })).await;
                    if let Some(pusher) = &self.pusher {
                        if reason == "end_turn" {
                            // The agent finished on its own: the only "done" notification.
                            let preview: String = reply_text.chars().take(180).collect();
                            pusher.notify(Event::TurnComplete { session_id: &chat, title: &title, preview: &preview }).await;
                        } else {
                            // Cut short: the reply hit its length limit (`max_tokens`), the turn its
                            // step limit (`max_turn_requests`), or the model declined (`refusal`).
                            let detail = format!("agy stopped the turn early ({reason})");
                            pusher.notify(Event::TurnFailed { session_id: &chat, title: &title, code: reason, error: &detail }).await;
                        }
                    }
                }
                info!("turn in {chat} ended after {seconds}s");
                Ok(())
            }
            Err(e) if !proc.is_alive() => Err(agy_down(e)),
            Err(e) => Err(BridgeError::new("internal", e)),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn scratch(tag: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("agy-bridge-{tag}-{}", fresh_id()));
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    /// A `session/update` of `kind` with extra fields merged in.
    fn upd(kind: &str, fields: Value) -> Value {
        let mut obj = fields.as_object().cloned().unwrap_or_default();
        obj.insert("sessionUpdate".to_owned(), Value::from(kind));
        Value::Object(obj)
    }

    fn text_block(t: &str) -> Value {
        let mut b = Map::new();
        b.insert("type".to_owned(), "text".into());
        b.insert("text".to_owned(), t.into());
        Value::Object(b)
    }

    fn chunk(kind: &str, t: &str) -> Mapped {
        let mut fields = Map::new();
        fields.insert("content".to_owned(), text_block(t));
        map_update(&upd(kind, Value::Object(fields)))
    }

    fn event_name(ev: &Option<Value>) -> &str {
        ev.as_ref().and_then(|e| e["event"].as_str()).unwrap_or("")
    }

    #[test]
    fn lock_probe_reports_only_exclusive_holders() {
        let dir = scratch("lock");
        let file = dir.join("abc-123.lock");
        fs::write(&file, b"").unwrap();
        assert!(!lock_held_at(&dir, "abc-123"), "nobody holds it");
        let holder = File::open(&file).unwrap();
        holder.lock().unwrap();
        assert!(lock_held_at(&dir, "abc-123"), "held exclusively elsewhere");
        holder.unlock().unwrap();
        assert!(!lock_held_at(&dir, "abc-123"));
        for bad_id in ["missing", "../abc-123", "", "a b"] {
            assert!(!lock_held_at(&dir, bad_id), "{bad_id:?}");
        }
        assert!(!valid_chat_id(&"x".repeat(129)));
        assert!(valid_chat_id("Zz-09"));
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn updates_become_events() {
        let mut conv = Conversation::default();
        let ev = conv.apply(chunk("agent_thought_chunk", "hm"));
        assert_eq!(event_name(&ev), "turn.thinking");
        assert_eq!(ev.unwrap()["text"], "hm");
        let ev = conv.apply(chunk("agent_message_chunk", "Hi"));
        assert_eq!(event_name(&ev), "turn.text");

        let ev = conv.apply(map_update(&upd("usage_update", serde_json::from_str(r#"{"used":5,"size":10}"#).unwrap())));
        assert_eq!(event_name(&ev), "turn.context");
        assert_eq!((ev.as_ref().unwrap()["used"].as_u64(), ev.as_ref().unwrap()["size"].as_u64()), (Some(5), Some(10)));

        let ev = conv.apply(Mapped::Title("Fix".into()));
        assert_eq!(event_name(&ev), "turn.title");
        assert_eq!(conv.title.as_deref(), Some("Fix"));

        let tool: Value = serde_json::from_str(r#"{"toolCallId":"t1","kind":"execute","status":"pending","title":"ls"}"#).unwrap();
        let ev = conv.apply(map_update(&upd("tool_call", tool))).unwrap();
        assert_eq!(ev["event"], "turn.step");
        assert_eq!((ev["step"]["kind"].as_str(), ev["step"]["status"].as_str()), (Some("run"), Some("running")));

        let entries: Value = serde_json::from_str(
            r#"{"entries":[{"content":"a","status":"completed"},{"content":"b","status":"in_progress"},{"content":"c","status":"later"}]}"#,
        )
        .unwrap();
        let Mapped::Plan(plan) = map_update(&upd("plan", entries)) else { panic!("not a plan") };
        let statuses: Vec<&str> = plan.iter().filter_map(|p| p["status"].as_str()).collect();
        assert_eq!(statuses, ["done", "active", "pending"]);

        let picture: Value = serde_json::from_str(r#"{"content":{"type":"image","data":"x"}}"#).unwrap();
        assert_eq!(map_update(&upd("user_message_chunk", picture)), Mapped::Other);
        assert_eq!(map_update(&upd("brand_new_kind", Value::Null)), Mapped::Other);
    }

    #[test]
    fn chunks_build_two_messages() {
        let mut conv = Conversation::default();
        conv.apply(chunk("user_message_chunk", "hi"));
        conv.apply(chunk("agent_thought_chunk", "hm"));
        conv.apply(chunk("agent_thought_chunk", "m"));
        conv.apply(chunk("agent_message_chunk", "Hel"));
        conv.apply(chunk("agent_message_chunk", "lo"));
        let all = conv.messages_json();
        let list = all.as_array().unwrap();
        assert_eq!(list.len(), 2);
        assert_eq!(list[0]["from"], "user");
        assert_eq!((list[1]["text"].as_str(), list[1]["thinking"].as_str()), (Some("Hello"), Some("hmm")));
        assert_eq!(list[1]["id"], "m1");
        assert_eq!(conv.user_count(), 1);
    }

    #[test]
    fn ask_question_survives_a_replay_without_showing_twice_live() {
        let ask: Value = serde_json::from_str(
            r#"{"toolCallId":"q1","name":"ask_question","kind":"other","title":"Run ask_question","status":"in_progress",
                "args":{"questions":[{"question":"Which colour?","options":["red","blue"],"is_multi_select":false}]}}"#,
        )
        .unwrap();
        let asked = "Which colour?\n- [1] red\n- [2] blue";
        // Replay (session/load): only the tool call, then status updates. The text is added.
        let mut replay = Conversation::default();
        replay.apply(chunk("user_message_chunk", "pick one"));
        assert_eq!(replay.apply(map_update(&upd("tool_call", ask.clone()))), None, "no step row for it");
        replay.apply(map_update(&upd("tool_call_update", ask.clone())));
        let list = replay.messages_json();
        assert_eq!(list[1]["text"], asked);
        assert_eq!(list[1]["steps"].as_array().unwrap().len(), 0);
        // Live: agy writes the question itself after the call. Shown once.
        let mut live = Conversation::default();
        live.apply(chunk("user_message_chunk", "pick one"));
        live.apply(map_update(&upd("tool_call", ask)));
        live.apply(chunk("agent_message_chunk", asked));
        assert_eq!(live.messages_json()[1]["text"], asked);
    }

    #[test]
    fn update_steps_tool_is_a_plan() {
        let from_args: Value =
            serde_json::from_str(r#"{"name":"update_steps","args":{"steps":[{"title":"one","status":"done"},{"title":"two"}]}}"#)
                .unwrap();
        let Mapped::Plan(plan) = map_update(&upd("tool_call", from_args)) else { panic!("expected a plan") };
        assert_eq!(plan.len(), 2);
        assert_eq!((plan[0]["text"].as_str(), plan[0]["status"].as_str()), (Some("one"), Some("done")));
        assert_eq!(plan[1]["status"], "pending");
        let from_raw: Value = serde_json::from_str(r#"{"name":"update_steps","rawInput":{"steps":[]}}"#).unwrap();
        assert_eq!(map_update(&upd("tool_call_update", from_raw)), Mapped::Plan(Vec::new()));
        let neither: Value = serde_json::from_str(r#"{"name":"update_steps"}"#).unwrap();
        assert_eq!(map_update(&upd("tool_call", neither)), Mapped::Other);
    }

    #[test]
    fn steps_merge_by_id() {
        let partial = |id: &str, kind: Option<&str>, title: Option<&str>, status: Option<&str>| {
            let mut m = Map::new();
            m.insert("id".into(), id.into());
            m.insert("kind".into(), kind.map_or(Value::Null, Value::from));
            m.insert("title".into(), title.map_or(Value::Null, Value::from));
            m.insert("status".into(), status.map_or(Value::Null, Value::from));
            Value::Object(m)
        };
        let mut steps = Vec::new();
        let first = merge_step(&mut steps, partial("a", None, None, None), 0);
        assert_eq!(
            (first["kind"].as_str(), first["title"].as_str(), first["status"].as_str()),
            (Some("other"), Some("Tool"), Some("running"))
        );
        merge_step(&mut steps, partial("b", Some("read"), Some("Read"), None), 0);
        let merged = merge_step(&mut steps, partial("b", None, None, Some("done")), 0);
        assert_eq!(merged["title"], "Read");
        assert_eq!(merged["kind"], "read");
        assert_eq!(merged["status"], "done");
        assert_eq!(steps.len(), 2);
    }

    #[test]
    fn plan_is_replaced_and_seconds_reattached() {
        let entry = |t: &str| plan_item(t, "pending");
        let mut conv = Conversation::default();
        conv.apply(Mapped::Plan(vec![entry("a"), entry("b")]));
        conv.apply(Mapped::Plan(vec![entry("c")]));
        assert_eq!(conv.messages[0].plan, vec![entry("c")]);

        let mut c = Conversation::default();
        for round in ["1", "2"] {
            c.push_user(format!("q{round}"), Vec::new());
            c.apply(Mapped::Text(format!("a{round}")));
        }
        c.attach_seconds(&[Some(1.5), None, Some(9.0)]);
        assert_eq!(c.messages[1].seconds, Some(1.5));
        assert_eq!(c.messages[3].seconds, None);
    }

    #[test]
    fn file_links_resolve_and_stay_inside_shared_folders() {
        let root = scratch("read");
        let proj = root.join("proj");
        let secret = root.join("proj").join(".config-agy");
        fs::create_dir_all(proj.join("src")).unwrap();
        fs::create_dir_all(&secret).unwrap();
        fs::write(proj.join("src").join("a b.rs"), b"fn main() {}").unwrap();
        fs::write(secret.join("identity.json"), b"{}").unwrap();
        fs::write(root.join("outside.txt"), b"no").unwrap();
        #[cfg(unix)]
        std::os::unix::fs::symlink(root.join("outside.txt"), proj.join("link.txt")).unwrap();
        let allowed = vec![proj.clone()];
        let p = proj.to_string_lossy().to_string();

        let link = format!("file://{}/src/a%20b.rs#L1-L2", p);
        let path = link_path(&link, None).unwrap();
        assert!(readable_file(&path, &allowed, &secret).is_some(), "absolute file:// link with an anchor");
        let rel = link_path("src/a%20b.rs", Some(&p)).unwrap();
        assert!(readable_file(&rel, &allowed, &secret).is_some(), "relative to the chat's folder");
        assert!(link_path("src/x.rs", None).is_none(), "relative without a folder");

        let deny = |l: &str| readable_file(&link_path(l, Some(&p)).unwrap(), &allowed, &secret).is_none();
        assert!(deny("../outside.txt"), "no climbing out");
        assert!(deny(&format!("{p}/../outside.txt")));
        assert!(deny("link.txt"), "no symlink out");
        assert!(deny(".config-agy/identity.json"), "never the connector's folder");
        assert!(deny("src"), "folders aren't files");
        assert!(deny("/etc/hosts"), "nothing outside shared folders");
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn network_and_device_paths_are_recognised() {
        for p in ["\\\\evil\\share\\a.txt", "//evil/share/a.txt", "\\/evil/share", "/\\evil\\share", "\\\\?\\C:\\a", "\\\\.\\pipe\\x"] {
            assert!(names_another_computer(p), "{p}");
        }
        for p in ["C:\\Users\\me\\a.txt", "/Users/me/a.txt", "src/a.rs", "\\src\\a.rs", "a//b", ""] {
            assert!(!names_another_computer(p), "{p}");
        }
        // `file:////server/share/x` is how a link spells a share: what is left after `file://`.
        assert!(names_another_computer("file:////evil/share/x".strip_prefix("file://").unwrap()));
    }

    #[test]
    fn escaped_folders_from_agy_are_decoded() {
        let root = scratch("escaped");
        let real = root.join("AdMob Reporter");
        fs::create_dir_all(&real).unwrap();
        let escaped = format!("{}/AdMob%20Reporter", root.display());
        assert_eq!(agy_folder(&escaped), real.to_string_lossy());
        let plain = root.join("100%done");
        fs::create_dir_all(&plain).unwrap();
        assert_eq!(agy_folder(&plain.to_string_lossy()), plain.to_string_lossy(), "an existing path is kept as it is");
        assert_eq!(agy_folder("/no/such%20place"), "/no/such%20place", "nothing to find: unchanged");
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn home_and_root_are_too_broad() {
        let home = std::env::var("HOME").unwrap();
        assert!(too_broad("/"));
        assert!(too_broad(&home));
        if let Some(parent) = Path::new(&home).parent() {
            assert!(too_broad(&parent.to_string_lossy()));
        }
        let proj = scratch("broad");
        assert!(!too_broad(&proj.to_string_lossy()), "an ordinary project folder is fine");
        assert!(!too_broad("/does/not/exist"));
        let _ = fs::remove_dir_all(&proj);
    }

    #[test]
    fn folder_rules() {
        let root = scratch("folders");
        let placeholder = root.join("no-folder");
        fs::create_dir_all(&placeholder).unwrap();
        let ph = placeholder.to_string_lossy().into_owned();
        assert_eq!(resolve_folder(Some("/work"), Some("/x"), &placeholder).as_deref(), Some("/work"));
        assert_eq!(resolve_folder(Some(ph.as_str()), Some("/x"), &placeholder).as_deref(), Some("/x"));
        assert!(resolve_folder(Some(""), None, &placeholder).is_none());
        assert!(resolve_folder(None, None, &placeholder).is_none());
        let proj = root.join("proj");
        fs::create_dir_all(proj.join("src")).unwrap();
        fs::write(proj.join("src").join("main.rs"), b"fn main() {}").unwrap();
        let base = proj.to_string_lossy().into_owned();
        let ok = |rel: &str| resolve_in_folder(&base, rel).is_some();
        assert!(ok("src/main.rs") && ok("./src/main.rs"));
        assert!(!ok("../proj/src/main.rs") && !ok("/etc/passwd") && !ok("") && !ok("src/nope.rs"));
        let known = vec![base.clone()];
        assert!(is_known_folder(&format!("{base}/src/.."), &known));
        assert!(!is_known_folder("/etc", &known));

        assert!(folder_kind(&proj).is_null());
        let steps = [("go.mod", "go"), ("package.json", "node"), ("Cargo.toml", "rust"), ("Package.swift", "swift")];
        for (marker, expected) in steps {
            fs::write(proj.join(marker), b"").unwrap();
            assert_eq!(folder_kind(&proj).as_str(), Some(expected), "{marker}");
        }

        fs::create_dir_all(proj.join("target")).unwrap();
        fs::write(proj.join("target").join("main.rs"), b"").unwrap();
        let hits = search_files(&proj, "MAIN");
        assert_eq!(hits.len(), 1);
        assert_eq!((hits[0]["path"].as_str(), hits[0]["size"].as_u64()), (Some("src/main.rs"), Some(12)));
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn approvals_and_choices() {
        let params: Value = serde_json::from_str(
            r#"{"options":[{"kind":"allow_once","optionId":"o1","name":"Allow"},
                           {"kind":"allow_always","optionId":"o2","name":"Always"},
                           {"kind":"reject_once","optionId":"o3","name":"No"}],
                "toolCall":{"rawInput":{"toolSummary":"Run tests","CommandLine":"cargo test"},"kind":"execute","title":"Run"}}"#,
        )
        .unwrap();
        let a = build_approval("q1", &params);
        assert_eq!(a["what"], "run");
        assert_eq!(a["command"], "cargo test");
        assert_eq!(a["title"], "Run tests");
        assert_eq!(a["choices"][2]["effect"], "deny_once");
        assert!(a["path"].is_null());
        let picks: Vec<Option<String>> = ["allow", "deny", "o2", "nope"].iter().map(|x| pick_choice(&a, x)).collect();
        assert_eq!(picks, [Some("o1".into()), Some("o3".into()), Some("o2".into()), None]);

        let edit: Value = serde_json::from_str(
            r#"{"toolCall":{"kind":"edit","name":"write_to_file","content":[{"type":"diff","path":"/a.txt","oldText":"x","newText":"y"}]}}"#,
        )
        .unwrap();
        let e = build_approval("q2", &edit);
        assert_eq!(notification_text(&a), ("cargo test".to_string(), true), "the command, not the agent's summary");
        assert_eq!((e["what"].as_str(), e["path"].as_str()), (Some("write"), Some("/a.txt")));
        assert_eq!((e["before"].as_str(), e["after"].as_str()), (Some("x"), Some("y")));
        assert_eq!(e["title"], "Permission needed");
    }

    #[test]
    fn approvals_show_hidden_characters_and_long_requests_lose_quick_answers() {
        let tool = |cmd: &str| -> Value {
            serde_json::from_value(json!({ "toolCall": { "kind": "execute", "rawInput": { "CommandLine": cmd, "toolSummary": "Read README" } } })).unwrap()
        };
        // Right-to-left override, zero-width space, escape and carriage return are written out.
        let a = build_approval("q", &tool("ls \u{202E}fdp.exe\u{200B} \u{1b}[2K\rok\n\tnext"));
        let step = partial_step(&json!({ "toolCallId": "t", "title": "rm \u{202E}x" }));
        let edit = partial_step(&json!({ "toolCallId": "e", "kind": "edit", "content": [{ "type": "diff",
            "path": "/p/a.txt", "oldText": "l1\nl2\nl3\n", "newText": "l1\nsecond\nl3\nl4\n" }] }));
        assert_eq!((edit["path"].as_str(), edit["removed"].as_u64(), edit["added"].as_u64()), (Some("/p/a.txt"), Some(1), Some(2)));
        assert_eq!(step["title"], "rm <U+202E>x", "a step shows the command the same way");
        assert_eq!(a["command"], "ls <U+202E>fdp.exe<U+200B> <U+001B>[2K<U+000D>ok\n\tnext");
        assert_eq!(visible("日本語 ok"), "日本語 ok", "ordinary text is untouched");

        let (shown, whole) = notification_text(&build_approval("q", &tool("echo hi\n\n      rm -rf ~/x")));
        assert_eq!((shown.as_str(), whole), ("echo hi rm -rf ~/x", true), "padding can't push a part out of view");
        let long = format!("echo {}; rm -rf ~/x", "a".repeat(200));
        assert!(!notification_text(&build_approval("q", &tool(&long))).1, "too long to show: no Allow in the notification");
    }

    #[test]
    fn question_forms_are_shown_and_answers_checked() {
        let params: Value = serde_json::from_str(r#"{"sessionId":"s","toolCallId":"t","mode":"form","message":"The agent has 2 questions.",
            "requestedSchema":{"type":"object","properties":{
              "q10":{"type":"string","title":"Tenth?","oneOf":[{"const":"1","title":"Yes"}]},
              "q1":{"type":"string","title":"Which colour?","oneOf":[{"const":"1","title":"Red"},{"const":"2","title":"Blue"}]},
              "q1_other":{"type":"string","title":"Other"},
              "q2":{"type":"array","title":"Which fruits?","items":{"anyOf":[{"const":"1","title":"Apple"},{"const":"2","title":"Banana"}]}}}}}"#).unwrap();
        let form = build_question("a1", &params).unwrap();
        assert_eq!(form["what"], "question");
        let keys: Vec<&str> = form["questions"].as_array().unwrap().iter().map(|q| q["key"].as_str().unwrap()).collect();
        assert_eq!(keys, ["q1", "q2", "q10"], "in number order");
        assert_eq!(form["questions"][0]["other"], "q1_other");
        assert_eq!(form["questions"][1]["multi"], true);
        assert_eq!(form["questions"][1]["options"][1]["label"], "Banana");

        let ok = checked_answers(&form, &json!({ "q1": "2", "q2": ["1", "2"], "q1_other": "  teal  " })).unwrap();
        assert_eq!(ok, json!({ "q1": "2", "q2": ["1", "2"], "q1_other": "teal" }));
        assert!(checked_answers(&form, &json!({ "q1": "9" })).is_err(), "not an offered option");
        assert!(checked_answers(&form, &json!({ "q2": ["1", "7"] })).is_err());
        assert!(checked_answers(&form, &json!({ "q2": "1" })).is_err(), "a list is needed");
        assert!(checked_answers(&form, &json!({ "q3": "1" })).is_err(), "no such field");
        assert_eq!(checked_answers(&form, &json!({ "q1": "1", "q2": [] })).unwrap(), json!({ "q1": "1", "q2": [] }));
        assert!(build_question("a", &json!({ "requestedSchema": { "properties": {} } })).is_none());
    }

    #[test]
    fn limits_are_parsed() {
        let quota = [
            "Gemini Pro\tWeekly limit\t97.0%\tMonday",
            "Gemini Pro\t5-hour window\t40\t",
            "header only",
            "bad\trow\tx%",
        ]
        .join("\n");
        let (limits, credits) = parse_limits(&quota, "Plan\tfree\nAI credits\t 12.5 \n");
        assert_eq!(limits.len(), 2);
        let week = &limits[0];
        assert_eq!((week["window"].as_str(), week["remaining"].as_f64()), (Some("week"), Some(97.0)));
        assert_eq!((week["id"].as_str(), week["resets"].as_str()), (Some("gemini-pro-week"), Some("Monday")));
        assert_eq!(limits[1]["window"], "five_hours");
        assert!(limits[1]["resets"].is_null());
        assert_eq!(credits, Some(12.5));
    }

    #[test]
    fn rfc3339_formatting() {
        assert_eq!(rfc3339(UNIX_EPOCH + Duration::from_secs(1_764_000_000)), "2025-11-24T16:00:00Z");
        assert_eq!(rfc3339(UNIX_EPOCH), "1970-01-01T00:00:00Z");
        assert_eq!(rfc3339(UNIX_EPOCH + Duration::from_secs(951_782_400)), "2000-02-29T00:00:00Z");
    }
}
