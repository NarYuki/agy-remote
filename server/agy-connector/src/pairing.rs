// SPDX-License-Identifier: AGPL-3.0-only
//! Which phones may use this connector, and how a new one is paired.
//!
//! Every paired phone is on the list in `paired_devices.json` under its P2P endpoint id (a key the
//! phone keeps), with its own token, of which only a SHA-256 hash is stored. A request is served
//! only if the phone is on the list and presents its own token.
//!
//! Pairing (`agy-connector pair`):
//! 1. The computer shows a pairing code (QR or text): its address plus a one-time secret, valid
//!    for 10 minutes. No token is in it.
//! 2. The phone connects (the QUIC handshake proves the computer's key, which is in the code) and
//!    sends `pair.begin` with the secret. The first phone to do so holds the session.
//! 3. That phone shows a 6-digit number derived from the secret and both endpoint ids, and the
//!    user types it into `agy-connector pair` on the computer. Three wrong numbers end the session.
//! 4. Only then is the phone added: its next `pair.confirm` gets its token (until then the answer
//!    is `pair_pending`).
//!
//! The number goes from the phone to the computer's keyboard, never the other way: someone who
//! only sees the computer's screen (a screen share, a camera) can scan the code first, but can't
//! type on the computer, and the owner's phone then shows no number to type. Nothing the computer
//! displays is enough to pair.
//!
//! The files are read on every check, so `agy-connector unpair` takes effect at once in a
//! running connector.

use std::collections::BTreeMap;
use std::path::Path;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use data_encoding::HEXLOWER;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

const DEVICES_FILE: &str = "paired_devices.json";
const SESSION_FILE: &str = "pairing_session.json";
/// How long a pairing code works.
pub const SESSION_LENGTH: Duration = Duration::from_secs(10 * 60);
/// Wrong numbers allowed before the pairing code stops working.
const MAX_ATTEMPTS: u8 = 3;

fn now() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0)
}

fn sha256_hex(text: &str) -> String {
    HEXLOWER.encode(&Sha256::digest(text.as_bytes()))
}

fn same(a: &str, b: &str) -> bool {
    a.len() == b.len() && a.bytes().zip(b.bytes()).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Device {
    /// Unix time it was paired.
    pub paired: u64,
    /// SHA-256 (hex) of the phone's own token.
    token_sha256: String,
}

/// Paired phones by endpoint id.
pub fn devices(dir: &Path) -> BTreeMap<String, Device> {
    std::fs::read(dir.join(DEVICES_FILE)).ok().and_then(|b| serde_json::from_slice(&b).ok()).unwrap_or_default()
}

/// Holds an exclusive lock on `paired_devices.lock` while the list or the session is read and
/// rewritten, so concurrent requests (or `agy-connector unpair` beside a running connector)
/// can't drop an entry. Released when dropped.
struct ListLock(Option<std::fs::File>);

impl ListLock {
    fn take(dir: &Path) -> Self {
        let file = std::fs::OpenOptions::new()
            .create(true)
            .truncate(false)
            .write(true)
            .open(dir.join("paired_devices.lock"))
            .ok();
        Self(file.filter(|f| f.lock().is_ok()))
    }
}

impl Drop for ListLock {
    fn drop(&mut self) {
        if let Some(f) = &self.0 {
            let _ = f.unlock();
        }
    }
}

/// Writes next to the target and renames, so a reader never sees a half-written file.
fn save_json<T: Serialize>(dir: &Path, name: &str, value: &T) -> Result<(), String> {
    let body = serde_json::to_vec_pretty(value).map_err(|e| e.to_string())?;
    let staged = dir.join(format!("{name}.new"));
    crate::write_private_file(&staged, &body)?;
    std::fs::rename(&staged, dir.join(name)).map_err(|e| format!("{name}: {e}"))
}

fn save(dir: &Path, list: &BTreeMap<String, Device>) -> Result<(), String> {
    save_json(dir, DEVICES_FILE, list)
}

pub fn is_paired(dir: &Path, phone: &str) -> bool {
    devices(dir).contains_key(phone)
}

/// Whether `phone` is paired and `token` is its own.
pub fn check(dir: &Path, phone: &str, token: &str) -> bool {
    devices(dir).remove(phone).is_some_and(|d| same(&sha256_hex(token), &d.token_sha256))
}

/// Removes one phone. Returns whether it was on the list.
pub fn remove(dir: &Path, phone: &str) -> Result<bool, String> {
    let _lock = ListLock::take(dir);
    let mut list = devices(dir);
    let found = list.remove(phone).is_some();
    if found {
        save(dir, &list)?;
    }
    Ok(found)
}

/// Forgets every phone and ends any pairing in progress.
pub fn clear(dir: &Path) -> Result<(), String> {
    let _lock = ListLock::take(dir);
    let _ = std::fs::remove_file(dir.join(SESSION_FILE));
    save(dir, &BTreeMap::new())
}

// MARK: - Pairing sessions

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct Session {
    /// One-time secret in the pairing code (hex).
    secret: String,
    expires: u64,
    /// The phone that sent `pair.begin` first; only it can confirm.
    #[serde(default)]
    pub phone: Option<String>,
    #[serde(default)]
    attempts: u8,
    /// Set once the number that phone shows was typed on the computer.
    #[serde(default)]
    pub approved: bool,
    /// Set once the phone was added.
    #[serde(default)]
    pub done: bool,
    /// Set when too many wrong numbers ended it.
    #[serde(default)]
    pub failed: bool,
}

impl Session {
    /// Whether this is the session started with `secret` (and not a newer one).
    pub fn matches(&self, secret: &str) -> bool {
        same(&self.secret, secret)
    }

    pub fn expired(&self) -> bool {
        now() >= self.expires
    }
}

/// 6 digits from HMAC-SHA256(secret, "agy-pair-v1" | computer id | phone id). Bound to both keys
/// so it only works for the phone the computer saw.
pub fn check_code(secret: &str, computer: &str, phone: &str) -> String {
    let key = HEXLOWER.decode(secret.as_bytes()).unwrap_or_default();
    let mut block = [0u8; 64];
    let k: Vec<u8> = if key.len() > 64 { Sha256::digest(&key).to_vec() } else { key };
    block[..k.len()].copy_from_slice(&k);
    let pad = |x: u8| block.iter().map(|b| b ^ x).collect::<Vec<u8>>();
    let msg = format!("agy-pair-v1|{computer}|{phone}");
    let inner = Sha256::new().chain_update(pad(0x36)).chain_update(msg.as_bytes()).finalize();
    let mac = Sha256::new().chain_update(pad(0x5c)).chain_update(inner).finalize();
    let n = u64::from_be_bytes(mac[..8].try_into().expect("8 bytes")) % 1_000_000;
    format!("{n:06}")
}

pub fn session(dir: &Path) -> Option<Session> {
    std::fs::read(dir.join(SESSION_FILE)).ok().and_then(|b| serde_json::from_slice(&b).ok())
}

/// Whether `agy-connector pair` is waiting for a phone right now.
pub fn session_open(dir: &Path) -> bool {
    session(dir).is_some_and(|s| !s.expired() && !s.done && !s.failed)
}

/// Starts a pairing session (replacing any other) and returns its secret.
pub fn start_session(dir: &Path) -> Result<String, String> {
    let _lock = ListLock::take(dir);
    let secret = HEXLOWER.encode(&rand::random::<[u8; 16]>());
    let s = Session { secret: secret.clone(), expires: now() + SESSION_LENGTH.as_secs(), ..Session::default() };
    save_json(dir, SESSION_FILE, &s)?;
    Ok(secret)
}

pub fn end_session(dir: &Path) {
    let _lock = ListLock::take(dir);
    let _ = std::fs::remove_file(dir.join(SESSION_FILE));
}

/// Why `pair.begin` / `pair.confirm` was refused. `code()` is the protocol error code.
#[derive(Debug, PartialEq, Eq)]
pub enum PairError {
    /// No pairing in progress, expired, or the secret doesn't match.
    NoSession,
    /// Another phone is already pairing with this code.
    Busy,
    WrongCode { left: u8 },
    TooManyTries,
    /// The number hasn't been typed on the computer yet.
    Pending,
}

impl PairError {
    pub fn code(&self) -> &'static str {
        match self {
            Self::NoSession => "pairing_closed",
            Self::Busy => "busy",
            Self::WrongCode { .. } => "wrong_code",
            Self::TooManyTries => "pairing_closed",
            Self::Pending => "pair_pending",
        }
    }

    pub fn message(&self) -> String {
        match self {
            Self::NoSession => "No pairing code is open. Run `agy-connector pair` on the computer.".into(),
            Self::Busy => "Another phone is pairing with this code.".into(),
            Self::WrongCode { left } => format!("Wrong number. {left} tries left."),
            Self::TooManyTries => "Too many wrong numbers. Run `agy-connector pair` again.".into(),
            Self::Pending => "Type the number this phone shows into `agy-connector pair` on the computer.".into(),
        }
    }
}

fn open_session(dir: &Path, secret: &str) -> Result<Session, PairError> {
    match session(dir) {
        Some(s) if !s.expired() && !s.done && !s.failed && same(&s.secret, secret) => Ok(s),
        _ => Err(PairError::NoSession),
    }
}

/// `pair.begin`: the phone proves it has the pairing code and becomes the one phone this code
/// can pair. Returns the number it shows, for the user to type on the computer.
pub fn begin(dir: &Path, computer: &str, phone: &str, secret: &str) -> Result<String, PairError> {
    let _lock = ListLock::take(dir);
    let mut s = open_session(dir, secret)?;
    match s.phone.as_deref() {
        Some(p) if p != phone => return Err(PairError::Busy),
        Some(_) => return Ok(check_code(&s.secret, computer, phone)),
        None => {}
    }
    s.phone = Some(phone.to_string());
    save_json(dir, SESSION_FILE, &s).map_err(|_| PairError::NoSession)?;
    Ok(check_code(&s.secret, computer, phone))
}

/// The number typed into `agy-connector pair` on the computer: if it is the one the phone that
/// scanned shows, that phone may finish pairing. Three wrong numbers end the session.
pub fn approve(dir: &Path, computer: &str, secret: &str, typed: &str) -> Result<(), PairError> {
    let _lock = ListLock::take(dir);
    let mut s = open_session(dir, secret)?;
    let Some(phone) = s.phone.clone() else { return Err(PairError::NoSession) };
    let digits: String = typed.chars().filter(char::is_ascii_digit).collect();
    if !same(&digits, &check_code(&s.secret, computer, &phone)) {
        s.attempts += 1;
        let left = MAX_ATTEMPTS.saturating_sub(s.attempts);
        if left == 0 {
            s.failed = true;
        }
        let _ = save_json(dir, SESSION_FILE, &s);
        return Err(if left == 0 { PairError::TooManyTries } else { PairError::WrongCode { left } });
    }
    s.approved = true;
    save_json(dir, SESSION_FILE, &s).map_err(|_| PairError::NoSession)
}

/// `pair.confirm`: once the phone's number was typed on the computer, adds the phone and returns
/// its new token. `Pending` until then; the phone asks again.
pub fn confirm(dir: &Path, phone: &str, secret: &str) -> Result<String, PairError> {
    let _lock = ListLock::take(dir);
    let mut s = open_session(dir, secret)?;
    if s.phone.as_deref() != Some(phone) {
        return Err(if s.phone.is_some() { PairError::Busy } else { PairError::NoSession });
    }
    if !s.approved {
        return Err(PairError::Pending);
    }
    let token = HEXLOWER.encode(&rand::random::<[u8; 32]>());
    let mut list = devices(dir);
    list.insert(phone.to_string(), Device { paired: now(), token_sha256: sha256_hex(&token) });
    save(dir, &list).map_err(|_| PairError::NoSession)?;
    s.done = true;
    let _ = save_json(dir, SESSION_FILE, &s);
    Ok(token)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn scratch() -> std::path::PathBuf {
        let d = std::env::temp_dir().join(format!("agy-pairing-{}", rand::random::<u64>()));
        std::fs::create_dir_all(&d).unwrap();
        d
    }

    #[test]
    fn pairing_needs_the_secret_and_the_number_typed_on_the_computer() {
        let dir = scratch();
        clear(&dir).unwrap();
        assert_eq!(begin(&dir, "pc", "phone", "00"), Err(PairError::NoSession), "nothing open");
        let secret = start_session(&dir).unwrap();
        assert_eq!(begin(&dir, "pc", "phone", "beef"), Err(PairError::NoSession), "wrong secret");
        assert_eq!(approve(&dir, "pc", &secret, "000000"), Err(PairError::NoSession), "no phone yet");
        let shown = begin(&dir, "pc", "phone", &secret).unwrap();
        assert_eq!(shown, check_code(&secret, "pc", "phone"));
        assert_eq!(begin(&dir, "pc", "phone", &secret).unwrap(), shown, "asking again shows the same number");
        assert_eq!(begin(&dir, "pc", "other", &secret), Err(PairError::Busy), "one phone per code");
        assert_ne!(shown, check_code(&secret, "pc", "other"), "the number belongs to one phone");
        assert_eq!(confirm(&dir, "phone", &secret), Err(PairError::Pending), "not before the computer agrees");
        assert!(!is_paired(&dir, "phone"));
        let wrong = if shown == "000000" { "000001" } else { "000000" };
        assert_eq!(approve(&dir, "pc", &secret, wrong), Err(PairError::WrongCode { left: 2 }));
        assert_eq!(confirm(&dir, "phone", &secret), Err(PairError::Pending));
        approve(&dir, "pc", &secret, &format!("{} {}", &shown[..3], &shown[3..])).unwrap();
        assert_eq!(confirm(&dir, "other", &secret), Err(PairError::Busy), "only the phone that scanned");
        let token = confirm(&dir, "phone", &secret).unwrap();
        assert!(check(&dir, "phone", &token));
        assert!(!check(&dir, "phone", "guess"), "only its own token");
        assert!(!check(&dir, "phone", ""), "never an empty one");
        assert!(!check(&dir, "other", &token), "a token is bound to its phone");
        assert_eq!(confirm(&dir, "phone", &secret), Err(PairError::NoSession), "single use");
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// Someone who only sees the computer's screen scans the code before the owner does.
    #[test]
    fn scanning_first_pairs_nothing_without_the_computers_keyboard() {
        let dir = scratch();
        let secret = start_session(&dir).unwrap();
        begin(&dir, "pc", "stranger", &secret).unwrap();
        // The owner's phone gets no number to type, and the stranger can't type theirs.
        assert_eq!(begin(&dir, "pc", "owner", &secret), Err(PairError::Busy));
        for _ in 0..20 {
            assert_eq!(confirm(&dir, "stranger", &secret), Err(PairError::Pending));
        }
        assert!(!is_paired(&dir, "stranger"));
        // Whatever the owner types instead (their own phone's number, had it shown one) is wrong.
        let owners = check_code(&secret, "pc", "owner");
        assert!(matches!(approve(&dir, "pc", &secret, &owners), Err(PairError::WrongCode { .. })));
        assert!(!is_paired(&dir, "stranger"));
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_session_is_open_only_while_pair_is_waiting() {
        let dir = scratch();
        assert!(!session_open(&dir), "never started");
        let secret = start_session(&dir).unwrap();
        assert!(session_open(&dir));
        let code = begin(&dir, "pc", "p", &secret).unwrap();
        approve(&dir, "pc", &secret, &code).unwrap();
        assert!(session_open(&dir), "the phone still has to collect its token");
        confirm(&dir, "p", &secret).unwrap();
        assert!(!session_open(&dir), "used");
        start_session(&dir).unwrap();
        end_session(&dir);
        assert!(!session_open(&dir), "cancelled");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn three_wrong_numbers_close_the_code() {
        let dir = scratch();
        let secret = start_session(&dir).unwrap();
        let right = begin(&dir, "pc", "p", &secret).unwrap();
        let wrong = if right == "123456" { "654321" } else { "123456" };
        approve(&dir, "pc", &secret, wrong).unwrap_err();
        approve(&dir, "pc", &secret, wrong).unwrap_err();
        assert_eq!(approve(&dir, "pc", &secret, wrong), Err(PairError::TooManyTries));
        assert_eq!(approve(&dir, "pc", &secret, &right), Err(PairError::NoSession));
        assert_eq!(confirm(&dir, "p", &secret), Err(PairError::NoSession));
        assert!(!is_paired(&dir, "p"));
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn phones_pairing_at_once_are_all_kept() {
        let dir = scratch();
        clear(&dir).unwrap();
        // Sessions replace each other, so not every phone gets in; every one that was told it
        // paired must be on the list, while other threads rewrite it at the same time.
        let workers: Vec<_> = (0..16)
            .map(|i| {
                let d = dir.clone();
                std::thread::spawn(move || {
                    let phone = format!("phone-{i}");
                    let secret = start_session(&d).unwrap();
                    let code = begin(&d, "pc", &phone, &secret).ok()?;
                    approve(&d, "pc", &secret, &code).ok()?;
                    confirm(&d, &phone, &secret).ok().map(|_| phone)
                })
            })
            .collect();
        let paired: Vec<String> = workers.into_iter().filter_map(|w| w.join().unwrap()).collect();
        let list = devices(&dir);
        for phone in &paired {
            assert!(list.contains_key(phone), "{phone} was told it paired but isn't on the list");
        }
        assert_eq!(list.len(), paired.len());
        let _ = std::fs::remove_dir_all(&dir);
    }
}
