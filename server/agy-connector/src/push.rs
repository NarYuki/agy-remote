// SPDX-License-Identifier: AGPL-3.0-only
//! Push notifications to paired phones, end-to-end encrypted.
//!
//! Nothing here runs until a phone turns notifications on: the relay is contacted only after the
//! first `push.register` arrives (or, after a restart, if a phone registered earlier).
//!
//! Each notification is a small JSON document, padded to a fixed size and sealed with
//! ChaCha20-Poly1305 under a key only the phone and this computer know. The relay (and Apple) see
//! the device token, the time, and an opaque thread id — never the content.

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};

use chacha20poly1305::{
    ChaCha20Poly1305, KeyInit, Nonce,
    aead::{Aead, Payload},
};
use data_encoding::{BASE64, BASE64URL_NOPAD, HEXLOWER};
use iroh::SecretKey;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use tokio::sync::Mutex;
use tracing::{info, warn};

/// Official relay used by App Store builds. Override with `agy-connector set-relay <url>`.
pub const DEFAULT_RELAY: &str = "https://noti.agy.yutorinatsu.moe";
/// Characters of a notification's body that are sent (longer ones are cut, ending in "…").
pub const BODY_LIMIT: usize = 180;
/// Every plaintext is padded to this many bytes so ciphertext length reveals nothing.
const PADDED_LEN: usize = 512;
const DEVICES_FILE: &str = "push_devices.json";

#[derive(Clone, Serialize, Deserialize)]
struct Device {
    /// Push token: an APNs device token (hex) or an FCM registration token.
    token: String,
    /// "apns" (iOS) or "fcm" (Android). Absent in registrations saved before Android support.
    #[serde(default = "default_platform", skip_serializing_if = "is_apns")]
    platform: String,
    /// "production" or "sandbox".
    env: String,
    /// 32-byte ChaCha20-Poly1305 key (hex), created on the phone.
    key: String,
    /// Which kinds of notification the phone wants; absent means all.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    kinds: Option<Vec<String>>,
    /// The relay's word that this connector may send to this token, which the phone got from
    /// the relay and passed on, and when it stops being good (unix seconds). Absent with apps
    /// and relays from before grants; the relay then serves the phone from a smaller budget.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    grant: Option<Grant>,
}

#[derive(Clone, PartialEq, Debug, Serialize, Deserialize)]
struct Grant {
    value: String,
    expires: u64,
}

impl Grant {
    /// What the relay hands out is a short base64url text; anything else is not sent on.
    fn from_params(params: &Value) -> Option<Self> {
        let value = params.get("grant").and_then(Value::as_str)?;
        let expires = params.get("grantExp").and_then(Value::as_u64)?;
        let plain = (16..=128).contains(&value.len()) && value.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_');
        plain.then(|| Self { value: value.to_string(), expires })
    }
}

fn default_platform() -> String {
    "apns".into()
}

fn is_apns(p: &String) -> bool {
    p == "apns"
}

/// Whether `token` looks like a push token for `platform`.
fn valid_token(platform: &str, token: &str) -> bool {
    match platform {
        // FCM registration tokens: URL-safe base64 plus ':' (usually ~160 chars).
        "fcm" => (32..=4096).contains(&token.len()) && token.bytes().all(|b| b.is_ascii_alphanumeric() || b"-_:".contains(&b)),
        _ => token.len() >= 64 && token.chars().all(|c| c.is_ascii_hexdigit()),
    }
}

/// What a notification is about.
pub enum Event<'a> {
    TurnComplete { session_id: &'a str, title: &'a str, preview: &'a str },
    /// `code`: why (a protocol error code, or agy's stop reason such as `max_tokens`), for the
    /// phone to say in its own language; `error` is the English detail.
    TurnFailed { session_id: &'a str, title: &'a str, code: &'a str, error: &'a str },
    Permission { session_id: &'a str, title: &'a str, what: &'a str, approval_id: &'a str },
    /// agy asks the user a question (`ask_question`); answered in the app.
    Question { session_id: &'a str, title: &'a str, what: &'a str },
    /// The agy CLI closed a chat it had open, so it can be continued from the phone.
    CliReleased { session_id: &'a str, title: &'a str },
}

pub struct Pusher {
    data_dir: PathBuf,
    host_name: String,
    signing_key: SecretKey,
    /// Phone's P2P endpoint id → its registration.
    devices: Mutex<HashMap<String, Device>>,
    /// Grants the relay refused (it no longer honours them), so a phone offering one again is told.
    refused: Mutex<std::collections::HashSet<String>>,
    /// HTTP client, created on first use so an unused connector never opens connections.
    client: Mutex<Option<reqwest::Client>>,
}

impl Pusher {
    pub fn new(data_dir: PathBuf, host_name: String, signing_key: SecretKey) -> Arc<Self> {
        let devices = load_devices(&data_dir);
        if !devices.is_empty() {
            info!("push: {} phone(s) registered for notifications", devices.len());
        }
        Arc::new(Self { data_dir, host_name, signing_key, devices: Mutex::new(devices), refused: Mutex::new(Default::default()), client: Mutex::new(None) })
    }

    /// Stores (or replaces) a phone's registration. Called when the app turns notifications on.
    /// Returns whether the grant it offered is one the relay refused: the phone then asks the
    /// relay for a new one.
    pub async fn register(&self, phone_id: &str, params: &Value) -> Result<bool, String> {
        let field = |name: &str| params.get(name).and_then(Value::as_str);
        let platform = if field("platform") == Some("fcm") { "fcm" } else { "apns" };
        let mut token = field("token").unwrap_or("").trim().to_string();
        if platform == "apns" {
            token = token.to_lowercase();
        }
        if !valid_token(platform, &token) {
            return Err("Invalid device token".into());
        }
        let key = field("key").unwrap_or("").to_lowercase();
        if HEXLOWER.decode(key.as_bytes()).map(|k| k.len()) != Ok(32) {
            return Err("Invalid notification key".into());
        }
        let env = if field("env") == Some("sandbox") { "sandbox" } else { "production" };
        let kinds = params.get("kinds").and_then(Value::as_array).map(|list| {
            list.iter().filter_map(Value::as_str).map(str::to_string).collect::<Vec<_>>()
        });
        let mut record = Device { token, platform: platform.into(), env: env.into(), key, kinds, grant: Grant::from_params(params) };
        let grant_refused = match &record.grant {
            Some(g) => self.refused.lock().await.contains(&g.value),
            None => false,
        };
        if grant_refused {
            record.grant = None;
        }
        let mut devices = self.devices.lock().await;
        let was_empty = devices.is_empty();
        // A registration without a grant (the phone is still waiting for it) doesn't lose the
        // one this phone gave earlier for the same token.
        if record.grant.is_none()
            && let Some(before) = devices.get(phone_id).filter(|d| d.token == record.token && d.platform == record.platform)
        {
            record.grant = before.grant.clone();
        }
        // Same push token under another id = this phone after a reset or reinstall. Its old entry holds
        // a key the phone no longer has, so anything sent with it would arrive undecryptable.
        devices.retain(|id, d| id == phone_id || d.token != record.token);
        eprintln!("notifications: phone {}… registered ({} {})", &phone_id[..phone_id.len().min(8)], record.platform, record.env);
        devices.insert(phone_id.to_string(), record);
        save_devices(&self.data_dir, &devices);
        if was_empty {
            info!("push: notifications go through {}", self.relay_url());
        }
        Ok(grant_refused)
    }

    /// Whether any phone wants notifications (nothing is watched or sent otherwise).
    pub async fn has_devices(&self) -> bool {
        !self.devices.lock().await.is_empty()
    }

    pub async fn unregister(&self, phone_id: &str) {
        let mut devices = self.devices.lock().await;
        if devices.remove(phone_id).is_some() {
            eprintln!("notifications: phone {}… unregistered", &phone_id[..phone_id.len().min(8)]);
            save_devices(&self.data_dir, &devices);
        }
    }

    /// Sends `event` to every registered phone. Does nothing (and contacts nobody) if none are.
    pub async fn notify(self: &Arc<Self>, event: Event<'_>) {
        let devices: Vec<(String, Device)> = self.devices.lock().await.iter().map(|(k, v)| (k.clone(), v.clone())).collect();
        if devices.is_empty() {
            return;
        }
        let (kind, session_id, title, body, approval, code) = match event {
            Event::TurnComplete { session_id, title, preview } => ("turn_complete", session_id, title, preview, "", ""),
            Event::TurnFailed { session_id, title, code, error } => ("turn_failed", session_id, title, error, "", code),
            Event::Permission { session_id, title, what, approval_id } => ("permission", session_id, title, what, approval_id, ""),
            Event::Question { session_id, title, what } => ("question", session_id, title, what, "", ""),
            Event::CliReleased { session_id, title } => ("cli_released", session_id, title, "", "", ""),
        };
        // A question is switched on and off with approvals (the phone's "approval" setting).
        let setting = if kind == "question" { "permission" } else { kind };
        let doc = fitted_doc(kind, session_id, approval, title, body, code, &self.host_name, now());
        for (phone_id, device) in devices {
            // A phone removed with `agy-connector unpair` gets nothing more, and its push token
            // and notification key are forgotten.
            if !crate::pairing::is_paired(&self.data_dir, &phone_id) {
                self.unregister(&phone_id).await;
                continue;
            }
            if device.kinds.as_ref().is_some_and(|k| !k.iter().any(|x| x == setting)) {
                continue;
            }
            let me = self.clone();
            let doc = doc.clone();
            tokio::spawn(async move {
                if let Err(e) = me.send_one(&phone_id, &device, &doc).await {
                    warn!("push to {phone_id}: {e}");
                    // Always in the log (not only with -v): otherwise a phone that stops getting
                    // notifications leaves no trace of why.
                    eprintln!("notification to phone {}… failed: {e}", &phone_id[..phone_id.len().min(8)]);
                }
            });
        }
    }

    async fn send_one(&self, phone_id: &str, device: &Device, doc: &Value) -> Result<(), String> {
        let sealed = seal(&device.key, doc)?;
        let session = doc.get("sessionId").and_then(Value::as_str).unwrap_or("");
        let mut payload = json!({
            "token": device.token,
            "env": device.env,
            "collapse": thread_id(&device.key, session),
            "c": sealed,
        });
        if device.platform != "apns" {
            payload["platform"] = json!(device.platform);
        }
        let grant = device.grant.as_ref().filter(|g| g.expires > now());
        if let Some(g) = grant {
            payload["g"] = json!(g.value);
            payload["gx"] = json!(g.expires);
        }
        let mut reply = self.post(&payload).await?;
        if reply.0 == 403 && grant.is_some() {
            // The relay no longer honours this grant (its secret changed, or it is another relay
            // now). Send without it; the phone gets a new one the next time its app is open.
            if let Some(obj) = payload.as_object_mut() {
                obj.remove("g");
                obj.remove("gx");
            }
            reply = self.post(&payload).await?;
            if let Some(g) = grant {
                let mut refused = self.refused.lock().await;
                if refused.len() >= 256 {
                    refused.clear();
                }
                refused.insert(g.value.clone());
            }
            let mut devices = self.devices.lock().await;
            if let Some(d) = devices.get_mut(phone_id).filter(|d| d.token == device.token) {
                d.grant = None;
                // Saved too: after a restart it would otherwise be tried, and refused, again.
                save_devices(&self.data_dir, &devices);
            }
        }
        match reply {
            (200, _) => Ok(()),
            (410, _) => {
                self.unregister(phone_id).await;
                Err("device unregistered; forgot it".into())
            }
            (code, text) => Err(format!("relay returned {code}: {text}")),
        }
    }

    /// One signed request to the relay: its status and what it said.
    async fn post(&self, payload: &Value) -> Result<(u16, String), String> {
        let body = payload.to_string();
        let stamp = now().to_string();
        let signed = format!("{stamp}\n{body}");
        let sig = self.signing_key.sign(signed.as_bytes());
        let endpoint = format!("{}/v1/push", self.relay_url());
        let resp = self
            .client()
            .await
            .post(endpoint)
            .header("content-type", "application/json")
            .header("X-Agy-Key", self.relay_key())
            .header("X-Agy-Time", stamp)
            .header("X-Agy-Sig", BASE64URL_NOPAD.encode(&sig.to_bytes()))
            .body(body)
            .send()
            .await
            .map_err(|e| format!("relay unreachable: {e}"))?;
        let code = resp.status().as_u16();
        Ok((code, if code == 200 { String::new() } else { resp.text().await.unwrap_or_default() }))
    }

    /// This connector's key as the relay knows it (`X-Agy-Key`). A phone names it when it asks
    /// the relay for a grant.
    pub fn relay_key(&self) -> String {
        BASE64URL_NOPAD.encode(self.signing_key.public().as_bytes())
    }

    /// The relay this connector sends through.
    pub fn relay(&self) -> String {
        self.relay_url()
    }

    async fn client(&self) -> reqwest::Client {
        let mut slot = self.client.lock().await;
        slot.get_or_insert_with(|| {
            reqwest::Client::builder()
                .timeout(std::time::Duration::from_secs(15))
                .user_agent("agy-connector")
                .build()
                .expect("HTTP client")
        })
        .clone()
    }

    fn relay_url(&self) -> String {
        relay_url(&self.data_dir)
    }
}

/// The relay URL from `config.json` (`push_relay`), or the official one.
pub fn relay_url(data_dir: &Path) -> String {
    let configured = std::fs::read(data_dir.join("config.json"))
        .ok()
        .and_then(|b| serde_json::from_slice::<Value>(&b).ok())
        .and_then(|cfg| cfg.get("push_relay").and_then(Value::as_str).map(str::to_string));
    let chosen = match configured {
        Some(u) if allowed_url(&u) => u,
        _ => DEFAULT_RELAY.to_string(),
    };
    chosen.trim_end_matches('/').to_string()
}

/// Pads the JSON document to `PADDED_LEN` and seals it: base64(nonce ‖ ciphertext ‖ tag).
fn seal(key_hex: &str, doc: &Value) -> Result<String, String> {
    seal_with_nonce(key_hex, doc, rand::random())
}

fn seal_with_nonce(key_hex: &str, doc: &Value, nonce_bytes: [u8; 12]) -> Result<String, String> {
    let key = HEXLOWER.decode(key_hex.as_bytes()).map_err(|_| "bad key".to_string())?;
    let cipher = ChaCha20Poly1305::new_from_slice(&key).map_err(|_| "bad key length".to_string())?;
    let mut plain = serde_json::to_vec(doc).map_err(|e| e.to_string())?;
    if plain.len() > PADDED_LEN {
        return Err("notification too long".into());
    }
    plain.resize(PADDED_LEN, b' ');
    let sealed = cipher
        .encrypt(Nonce::from_slice(&nonce_bytes), Payload { msg: &plain, aad: b"agy-push-v1" })
        .map_err(|_| "encryption failed".to_string())?;
    let mut blob = nonce_bytes.to_vec();
    blob.extend_from_slice(&sealed);
    Ok(BASE64.encode(&blob))
}

/// `https://` with a host; nothing else (no plain HTTP, not even to this computer).
pub fn allowed_url(u: &str) -> bool {
    let Ok(url) = url::Url::parse(u) else { return false };
    match url.scheme() {
        "https" => url.host_str().is_some_and(|h| !h.is_empty()),
        _ => false,
    }
}

/// 16 hex chars, stable per chat and meaningless without the key: HMAC-SHA256 under a key
/// derived from the notification key for this purpose only, so the message key is never used
/// with a fixed nonce.
fn thread_id(key_hex: &str, session_id: &str) -> String {
    use sha2::{Digest, Sha256};
    let Ok(key) = HEXLOWER.decode(key_hex.as_bytes()) else { return String::new() };
    let hmac = |k: &[u8], msg: &[u8]| -> [u8; 32] {
        let mut block = [0u8; 64];
        block[..k.len()].copy_from_slice(k);
        let pad = |x: u8| block.iter().map(|b| b ^ x).collect::<Vec<u8>>();
        let inner = Sha256::new().chain_update(pad(0x36)).chain_update(msg).finalize();
        Sha256::new().chain_update(pad(0x5c)).chain_update(inner).finalize().into()
    };
    let thread_key = hmac(&key, b"agy-push-thread-v1");
    HEXLOWER.encode(&hmac(&thread_key, session_id.as_bytes())[..8])
}

fn truncate(s: &str, max: usize) -> String {
    let t: String = s.chars().take(max).collect();
    if t.chars().count() < s.chars().count() { format!("{}…", t.trim_end()) } else { t }
}

/// The notification document, with the body (then the title) shortened until it fits in
/// `PADDED_LEN` bytes. Limits are in characters, but Japanese takes 3 bytes a character.
#[allow(clippy::too_many_arguments)]
fn fitted_doc(kind: &str, session_id: &str, approval: &str, title: &str, body: &str, code: &str, host: &str, ts: u64) -> Value {
    let build = |t: usize, b: usize| {
        json!({
            "v": 1,
            "kind": kind,
            "sessionId": session_id,
            "approvalId": approval,
            "title": truncate(title, t),
            "body": truncate(body, b),
            "code": code,
            "host": truncate(host, 40),
            "ts": ts,
        })
    };
    let fits = |d: &Value| serde_json::to_vec(d).map(|v| v.len() <= PADDED_LEN).unwrap_or(false);
    let (mut t, mut b) = (80, BODY_LIMIT);
    loop {
        let doc = build(t, b);
        if fits(&doc) || (t == 0 && b == 0) {
            return doc;
        }
        if b > 0 { b = b.saturating_sub(10) } else { t = t.saturating_sub(10) }
    }
}

fn now() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0)
}

fn load_devices(data_dir: &Path) -> HashMap<String, Device> {
    std::fs::read(data_dir.join(DEVICES_FILE))
        .ok()
        .and_then(|raw| serde_json::from_slice(&raw).ok())
        .unwrap_or_default()
}

fn save_devices(data_dir: &Path, devices: &HashMap<String, Device>) {
    let encoded = serde_json::to_vec(devices).unwrap_or_default();
    if let Err(e) = crate::write_private_file(&data_dir.join(DEVICES_FILE), &encoded) {
        warn!("push: could not save registrations: {e}");
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sealed_payload_is_fixed_size_and_round_trips() {
        let key = HEXLOWER.encode(&[7u8; 32]);
        let short = seal(&key, &json!({ "a": 1 })).unwrap();
        let long = seal(&key, &json!({ "a": "x".repeat(300) })).unwrap();
        assert_eq!(short.len(), long.len(), "lengths must not leak content size");

        let raw = BASE64.decode(short.as_bytes()).unwrap();
        let cipher = ChaCha20Poly1305::new_from_slice(&[7u8; 32]).unwrap();
        let plain = cipher
            .decrypt(Nonce::from_slice(&raw[..12]), Payload { msg: &raw[12..], aad: b"agy-push-v1" })
            .unwrap();
        let v: Value = serde_json::from_str(String::from_utf8(plain).unwrap().trim_end()).unwrap();
        assert_eq!(v["a"], 1);
    }

    /// Shared with the Android app's unit test (PushCryptoTest.kt), which must open this exact blob.
    const VECTOR_KEY: [u8; 32] = [7u8; 32];
    const VECTOR_NONCE: [u8; 12] = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12];
    const VECTOR_SEALED: &str = include_str!("../tests/push_vector.txt");

    fn vector_doc() -> Value {
        json!({
            "v": 1,
            "kind": "permission",
            "sessionId": "chat-1",
            "approvalId": "appr-9",
            "title": "Fix the build",
            "body": "Run ./gradlew test?",
            "host": "studio",
            "ts": 1700000000,
        })
    }

    #[test]
    fn sealed_test_vector_matches_android() {
        let sealed = seal_with_nonce(&HEXLOWER.encode(&VECTOR_KEY), &vector_doc(), VECTOR_NONCE).unwrap();
        if std::env::var_os("AGY_WRITE_PUSH_VECTOR").is_some() {
            std::fs::write(concat!(env!("CARGO_MANIFEST_DIR"), "/tests/push_vector.txt"), &sealed).unwrap();
        }
        assert_eq!(sealed, VECTOR_SEALED.trim());
    }

    #[test]
    fn fcm_registration_keeps_case_and_old_files_default_to_apns() {
        assert!(valid_token("fcm", "dQw4w9WgXcQ:APA91bH-Ab_cdEFG1234567890abcdefghijklmnop"));
        assert!(!valid_token("fcm", "has spaces in it which are not allowed in tokens at all"));
        assert!(!valid_token("apns", "dQw4w9WgXcQ:APA91bH-Ab_cdEFG1234567890abcdefghijklmnop"));
        let old: Device = serde_json::from_str(r#"{"token":"ab","env":"production","key":"00"}"#).unwrap();
        assert_eq!(old.platform, "apns");
        // An APNs registration is saved exactly as before (no platform field).
        assert!(!serde_json::to_string(&old).unwrap().contains("platform"));
    }

    #[test]
    fn grants_are_kept_only_in_the_relays_spelling() {
        let ok = json!({ "grant": "abcDEF012-_abcDEF012-_", "grantExp": 1_900_000_000u64 });
        assert_eq!(Grant::from_params(&ok), Some(Grant { value: "abcDEF012-_abcDEF012-_".into(), expires: 1_900_000_000 }));
        for bad in [
            json!({ "grant": "abcDEF012-_abcDEF012-_" }),
            json!({ "grant": "short", "grantExp": 1 }),
            json!({ "grant": "has a space in it, which no grant has", "grantExp": 1 }),
            json!({ "grant": "quote\"and,json{chars}:not-allowed", "grantExp": 1 }),
            json!({ "grant": "x".repeat(129), "grantExp": 1 }),
            json!({ "grant": 5, "grantExp": 1 }),
            json!({ "grant": "abcDEF012-_abcDEF012-_", "grantExp": "soon" }),
        ] {
            assert_eq!(Grant::from_params(&bad), None, "{bad}");
        }
        // Registrations saved before grants still load.
        let old: Device = serde_json::from_str(r#"{"token":"ab","env":"production","key":"00"}"#).unwrap();
        assert!(old.grant.is_none());
        assert!(!serde_json::to_string(&old).unwrap().contains("grant"));
    }

    #[test]
    fn relay_urls_are_checked_by_host() {
        assert!(allowed_url("https://noti.example.com"));
        assert!(!allowed_url("http://localhost:8787"));
        assert!(!allowed_url("http://127.0.0.1:8787/"));
        assert!(!allowed_url("http://localhost.evil.com"));
        assert!(!allowed_url("http://127.0.0.1.evil.com"));
        assert!(!allowed_url("http://example.com"));
        assert!(!allowed_url("ftp://localhost"));
    }

    #[test]
    fn thread_id_matches_hmac_sha256() {
        // HMAC-SHA256(HMAC-SHA256(key, "agy-push-thread-v1"), "s"), key = 32 × 0x01, first 8 bytes.
        let k1 = HEXLOWER.encode(&[1u8; 32]);
        assert_eq!(thread_id(&k1, "s").len(), 16);
        assert_eq!(thread_id(&k1, "s"), include_str!("../tests/thread_id_vector.txt").trim());
    }

    #[test]
    fn thread_id_is_stable_and_keyed() {
        let k1 = HEXLOWER.encode(&[1u8; 32]);
        let k2 = HEXLOWER.encode(&[2u8; 32]);
        assert_eq!(thread_id(&k1, "s"), thread_id(&k1, "s"));
        assert_ne!(thread_id(&k1, "s"), thread_id(&k2, "s"));
        assert_ne!(thread_id(&k1, "s"), thread_id(&k1, "t"));
    }

    #[test]
    fn long_japanese_notifications_are_shortened_to_fit() {
        let jp = "日本語の長い文章です。".repeat(40);
        let doc = fitted_doc("permission", &"s".repeat(36), &"a".repeat(36), &jp, &jp, "", "ホスト名", now());
        let key = HEXLOWER.encode(&[7u8; 32]);
        assert!(seal(&key, &doc).is_ok());
        assert!(doc["body"].as_str().unwrap().ends_with('…'));
        // Short ASCII text is left alone.
        let doc = fitted_doc("turn_complete", "s", "", "title", "done", "", "mac", 1);
        assert_eq!(doc["body"], "done");
        assert_eq!(doc["title"], "title");
    }
}
