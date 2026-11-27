// SPDX-License-Identifier: AGPL-3.0-only
//! Self-update for installs from the release archive (`install.sh`). Homebrew installs are left
//! to Homebrew.
//!
//! A release publishes `manifest.json` and `manifest.json.sig` (an Ed25519 signature of the
//! manifest's exact bytes). The connector only accepts a manifest signed by the key built into
//! it, then checks each download against the manifest's SHA-256 before replacing itself.
//!
//! ```json
//! { "version": "0.3.0",
//!   "minAppVersion": "1.1",
//!   "assets": { "aarch64-macos": { "url": "https://…/agy-connector", "sha256": "…" } } }
//! ```

use std::{
    path::{Path, PathBuf},
    time::Duration,
};

use data_encoding::{BASE64, HEXLOWER};
use iroh::{PublicKey, Signature};
use serde::Deserialize;
use serde_json::Value;
use sha2::{Digest, Sha256};

/// Version of this build.
pub const CURRENT: &str = env!("CARGO_PKG_VERSION");
/// Oldest AGY Remote app this connector works with; the app tells the user to update if older.
pub const MIN_APP_VERSION: &str = "1.1";

/// Where releases are published (the newest GitHub Release, which carries the signed manifest).
const MANIFEST_URL: &str = "https://github.com/NarYuki/agy-remote/releases/latest/download/manifest.json";
/// Release signing key (public half). The private half signs releases (`agy-release sign`, a
/// maintainer tool that is not part of this program) and never ships.
const RELEASE_PUBLIC_KEY: &str = "dc6a32855d2b8e7c1c9d98bba13da775e78471bbe0364febe5bff02fe75ba7d0";

/// How often a running connector looks for a new release.
pub const CHECK_INTERVAL: Duration = Duration::from_secs(6 * 60 * 60);

#[derive(Deserialize)]
struct Manifest {
    version: String,
    #[serde(default, rename = "minAppVersion")]
    _min_app_version: Option<String>,
    assets: std::collections::HashMap<String, Asset>,
}

#[derive(Deserialize)]
struct Asset {
    url: String,
    sha256: String,
}

/// What happened when looking for an update.
pub enum Outcome {
    UpToDate,
    /// The new binary is in place; restart to run it.
    Installed(String),
    /// A newer version exists but this install updates another way.
    Available { version: String, how: &'static str },
}

/// This machine's asset name, e.g. `aarch64-macos`.
pub fn platform() -> String {
    format!("{}-{}", std::env::consts::ARCH, std::env::consts::OS)
}

/// How this copy was installed, if it can't replace itself.
pub fn managed_by(exe: &Path) -> Option<&'static str> {
    let p = exe.to_string_lossy().replace('\\', "/");
    if p.contains("/Cellar/") || p.contains("/homebrew/") || p.contains("/linuxbrew/") {
        Some("brew upgrade agy-connector")
    } else if p.contains("/target/debug/") || p.contains("/target/release/") {
        Some("a development build (rebuild from source)")
    } else {
        None
    }
}

/// Whether automatic updates are on (`auto_update` in config.json; on unless set to false).
pub fn auto_update_enabled(data_dir: &Path) -> bool {
    !matches!(read_config(data_dir).get("auto_update"), Some(Value::Bool(false)))
}

fn read_config(data_dir: &Path) -> Value {
    let Ok(text) = std::fs::read_to_string(data_dir.join("config.json")) else { return Value::Null };
    serde_json::from_str(&text).unwrap_or_default()
}

/// Looks for a newer release and, if this install can update itself, installs it.
pub async fn check_and_install() -> Result<Outcome, String> {
    let http = match reqwest::Client::builder().user_agent("agy-connector").timeout(Duration::from_secs(60)).build() {
        Ok(c) => c,
        Err(e) => return Err(format!("{e}")),
    };
    let source = MANIFEST_URL;
    let manifest_bytes = fetch(&http, &source).await?;
    let signature = fetch(&http, &format!("{source}.sig")).await?;
    verify(&manifest_bytes, &signature)?;
    let manifest: Manifest =
        serde_json::from_slice(&manifest_bytes).map_err(|e| format!("bad manifest: {e}"))?;
    if !is_newer(&manifest.version, CURRENT) {
        return Ok(Outcome::UpToDate);
    }
    let exe: PathBuf = std::env::current_exe().map_err(|e| e.to_string())?;
    let exe = exe.canonicalize().unwrap_or(exe);
    if let Some(how) = managed_by(&exe) {
        return Ok(Outcome::Available { version: manifest.version, how });
    }
    let target = platform();
    let asset = manifest.assets.get(&target).ok_or_else(|| format!("no release for {target}"))?;
    if !is_allowed_url(&asset.url) {
        return Err("release URL must be https".into());
    }
    let binary = fetch(&http, &asset.url).await?;
    let digest = HEXLOWER.encode(&Sha256::digest(&binary));
    if digest != asset.sha256.to_lowercase() {
        return Err("download doesn't match the signed checksum".into());
    }
    replace_executable(&exe, &binary)?;
    Ok(Outcome::Installed(manifest.version))
}

async fn fetch(client: &reqwest::Client, url: &str) -> Result<Vec<u8>, String> {
    let failed = |e: reqwest::Error| format!("{url}: {e}");
    let resp = client.get(url).send().await.map_err(failed)?;
    match resp.status() {
        ok if ok.is_success() => Ok(resp.bytes().await.map_err(failed)?.to_vec()),
        status => Err(format!("{url}: HTTP {status}")),
    }
}

/// Checks the base64 Ed25519 signature `sig` of `message` against the release key.
fn verify(message: &[u8], sig: &[u8]) -> Result<(), String> {
    let key_bytes: [u8; 32] = HEXLOWER
        .decode(RELEASE_PUBLIC_KEY.as_bytes())
        .ok()
        .and_then(|v| v.try_into().ok())
        .ok_or("this build has no release key")?;
    let key = PublicKey::from_bytes(&key_bytes).map_err(|_| "bad release key")?;
    let sig_text = String::from_utf8_lossy(sig);
    let sig_bytes: [u8; 64] = BASE64
        .decode(sig_text.trim().as_bytes())
        .ok()
        .and_then(|v| v.try_into().ok())
        .ok_or("bad signature file")?;
    key.verify(message, &Signature::from_bytes(&sig_bytes))
        .map_err(|_| "release signature doesn't match".to_string())
}

/// Writes the new binary next to the old one and renames it into place (atomic on one disk).
fn replace_executable(exe: &Path, binary: &[u8]) -> Result<(), String> {
    let Some(folder) = exe.parent() else { return Err("no install folder".into()) };
    let staged = folder.join(".agy-connector.update");
    if let Err(e) = std::fs::write(&staged, binary) {
        return Err(format!("can't write update: {e}"));
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt as _;
        let mut perms = std::fs::metadata(&staged).map(|m| m.permissions()).map_err(|e| e.to_string())?;
        perms.set_mode(0o755);
        let _ = std::fs::set_permissions(&staged, perms);
    }
    // Windows can't overwrite a running .exe, but it can rename it out of the way.
    #[cfg(windows)]
    {
        let old = folder.join(".agy-connector.old.exe");
        let _ = std::fs::remove_file(&old);
        std::fs::rename(exe, &old).map_err(|e| {
            let _ = std::fs::remove_file(&staged);
            format!("can't move {} aside: {e}", exe.display())
        })?;
    }
    std::fs::rename(&staged, exe).map_err(|e| {
        let _ = std::fs::remove_file(&staged);
        format!("can't replace {}: {e}", exe.display())
    })
}

/// HTTPS only, except a local server for testing.
fn is_allowed_url(u: &str) -> bool {
    crate::push::allowed_url(u)
}

/// `a > b` for dotted versions ("0.10.0" > "0.9.3"); missing parts count as 0.
pub fn is_newer(a: &str, b: &str) -> bool {
    let parse = |v: &str| -> Vec<u64> { v.trim().trim_start_matches('v').split('.').map(|p| p.parse().unwrap_or(0)).collect() };
    let (a, b) = (parse(a), parse(b));
    for i in 0..a.len().max(b.len()) {
        let (x, y) = (a.get(i).copied().unwrap_or(0), b.get(i).copied().unwrap_or(0));
        if x != y {
            return x > y;
        }
    }
    false
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn version_order() {
        assert!(is_newer("0.3.0", "0.2.9"));
        assert!(is_newer("0.10.0", "0.9.0"));
        assert!(is_newer("1.0", "0.99.99"));
        assert!(!is_newer("0.2.0", "0.2.0"));
        assert!(!is_newer("0.2", "0.2.0"));
        assert!(is_newer("v1.2.1", "1.2"));
    }

    #[test]
    fn homebrew_installs_are_left_alone() {
        assert!(managed_by(Path::new("/opt/homebrew/Cellar/agy-connector/0.2.0/bin/agy-connector")).is_some());
        assert!(managed_by(Path::new("/Users/me/.local/bin/agy-connector")).is_none());
    }
}
