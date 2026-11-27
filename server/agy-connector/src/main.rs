// SPDX-License-Identifier: AGPL-3.0-only
//! `agy-connector`: lets the AGY Remote app drive the agy CLI on this computer over iroh QUIC.

mod acp;
mod bridge;
mod pairing;
mod push;
mod update;

use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use agy_p2p_common::{
    AGY_ALPN, ClientEnvelope, DhtEndpointRecord, MAX_FRAME_BYTES, PairingTicket, prioritize_addr_strings,
    publish_dht_endpoint, read_raw_frame_max, write_json_frame,
};
use clap::{Parser, Subcommand, ValueEnum};
use data_encoding::HEXLOWER;
use iroh::endpoint::{Connection, presets};
use iroh::{Endpoint, RelayMode, SecretKey};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use tracing::{info, warn};

use crate::bridge::Bridge;
use crate::push::Pusher;

#[derive(Parser)]
#[command(name = "agy-connector", version, about = "Use the agy CLI on this computer from AGY Remote")]
struct Cli {
    /// Log what is happening (to stderr).
    #[arg(short, long, global = true)]
    verbose: bool,
    #[command(subcommand)]
    command: Option<Command>,
}

#[derive(Subcommand)]
enum Command {
    /// Start the connector in the background (the default). It shows no pairing code.
    Up {
        /// Stay in this terminal instead (Ctrl+C stops it).
        #[arg(long)]
        foreground: bool,
    },
    /// Stop the connector running in the background.
    Down,
    /// Run the connector in this process (used by `up` and the login service).
    #[command(hide = true)]
    Run,
    /// Pair a phone: shows a one-time code to scan, then asks for the number the phone shows.
    Pair,
    /// List the phones paired with this computer.
    Devices,
    /// Unpair one phone (its id from `devices`).
    Unpair { id: String },
    /// Make a new pairing code and unpair every phone.
    ResetPairing,
    /// Check that agy can be reached and the protocol methods work.
    Check,
    /// Use your own push relay.
    SetRelay { url: String },
    /// Offer a folder for new chats.
    AddWorkspace { path: PathBuf },
    /// Look for a new release now and install it.
    Update,
    /// Turn automatic updates on or off.
    SetAutoUpdate { state: Toggle },
    /// Allow or forbid Turbo mode (every request allowed without asking) from the phone.
    SetTurbo { state: Toggle },
}

#[derive(Clone, Copy, ValueEnum)]
enum Toggle {
    On,
    Off,
}

#[derive(Serialize, Deserialize)]
struct Identity {
    secret_key_hex: String,
    #[serde(default)]
    host_name: String,
}

fn config_dir() -> PathBuf {
    if let Some(custom) = std::env::var_os("AGY_CONNECTOR_HOME") {
        return PathBuf::from(custom);
    }
    // Linux follows the XDG base-dir spec; a relative XDG_CONFIG_HOME is ignored, as the spec says.
    if cfg!(target_os = "linux")
        && let Some(xdg) = std::env::var_os("XDG_CONFIG_HOME").map(PathBuf::from).filter(|p| p.is_absolute())
    {
        return xdg.join("agy-connector");
    }
    if cfg!(windows)
        && let Some(appdata) = std::env::var_os("APPDATA").map(PathBuf::from).filter(|p| p.is_absolute())
    {
        return appdata.join("agy-connector");
    }
    home_dir().unwrap_or_else(|| PathBuf::from(".")).join(".config").join("agy-connector")
}

/// The user's home folder: `HOME`, else `USERPROFILE` (Windows).
pub(crate) fn home_dir() -> Option<PathBuf> {
    std::env::var_os("HOME")
        .filter(|h| !h.is_empty())
        .or_else(|| std::env::var_os("USERPROFILE").filter(|h| !h.is_empty()))
        .map(PathBuf::from)
}

/// First non-empty, trimmed line of a small text file.
fn first_line(path: &str) -> Option<String> {
    let raw = std::fs::read_to_string(path).ok()?;
    raw.lines().map(str::trim).find(|l| !l.is_empty()).map(String::from)
}

/// Writes a file only the current user can read (mode 0600 on Unix).
pub(crate) fn write_private_file(path: &Path, content: &[u8]) -> Result<(), String> {
    if let Some(parent) = path.parent() {
        let mut folder = std::fs::DirBuilder::new();
        folder.recursive(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::DirBuilderExt;
            // A folder made here is private from the start (an existing one keeps its mode).
            folder.mode(0o700);
        }
        folder.create(parent).map_err(|e| format!("{}: {e}", parent.display()))?;
    }
    use std::io::Write;
    let mut opts = std::fs::OpenOptions::new();
    opts.write(true).create(true).truncate(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        // Created private, so the content is never readable by others even for a moment.
        opts.mode(0o600);
    }
    let fail = |e: std::io::Error| format!("{}: {e}", path.display());
    let mut file = opts.open(path).map_err(fail)?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        // An existing file keeps its old mode on open; tighten it before writing.
        file.set_permissions(std::fs::Permissions::from_mode(0o600)).map_err(fail)?;
    }
    file.write_all(content).map_err(fail)?;
    Ok(())
}

/// Makes the configuration folder and everything in it private (0700 / 0600).
fn harden_config_dir(dir: &Path) {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let private = |p: &Path, mode: u32| {
            let _ = std::fs::set_permissions(p, std::fs::Permissions::from_mode(mode));
        };
        private(dir, 0o700);
        for entry in std::fs::read_dir(dir).into_iter().flatten().flatten() {
            if entry.file_type().is_ok_and(|t| t.is_file()) {
                private(&entry.path(), 0o600);
            }
        }
        let logs = dir.join("logs");
        if logs.is_dir() {
            private(&logs, 0o700);
        }
        for entry in std::fs::read_dir(&logs).into_iter().flatten().flatten() {
            let path = entry.path();
            if path.is_file() {
                private(&path, 0o600);
            }
        }
    }
    // Windows: %APPDATA% is already private to the user.
    #[cfg(not(unix))]
    let _ = dir;
}

fn run_text(program: &str, args: &[&str]) -> Option<String> {
    let mut cmd = std::process::Command::new(program);
    cmd.args(args);
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        cmd.creation_flags(acp::CREATE_NO_WINDOW);
    }
    let out = cmd.output().ok()?;
    let text = String::from_utf8_lossy(&out.stdout).trim().to_string();
    (out.status.success() && !text.is_empty()).then_some(text)
}

fn machine_name() -> String {
    if cfg!(windows)
        && let Some(n) = std::env::var("COMPUTERNAME").ok().filter(|n| !n.is_empty())
    {
        return n;
    }
    if cfg!(target_os = "macos")
        && let Some(n) = run_text("scutil", &["--get", "ComputerName"])
    {
        return n;
    }
    // Minimal containers often lack the `hostname` tool, so read the kernel's copy first.
    first_line("/proc/sys/kernel/hostname")
        .or_else(|| first_line("/etc/hostname"))
        .or_else(|| run_text("hostname", &[]))
        .unwrap_or_else(|| "Computer".into())
}

fn hardware_model() -> String {
    if cfg!(windows) {
        return run_text("powershell", &["-NoProfile", "-Command", "(Get-CimInstance Win32_ComputerSystem).Model"])
            .filter(|m| !m.to_ascii_lowercase().contains("o.e.m"))
            .unwrap_or_default();
    }
    if cfg!(target_os = "macos") {
        return run_text("sysctl", &["-n", "hw.model"]).unwrap_or_default();
    }
    // DMI is x86-centric; ARM boards usually describe themselves in the device tree instead.
    // Firmware placeholders such as "To Be Filled By O.E.M." are not worth showing.
    const SOURCES: [&str; 3] =
        ["/sys/devices/virtual/dmi/id/product_name", "/sys/firmware/devicetree/base/model", "/proc/device-tree/model"];
    for source in SOURCES {
        let Some(line) = first_line(source) else { continue };
        let model = line.replace('\0', "");
        if !model.is_empty() && !model.to_ascii_lowercase().contains("o.e.m") {
            return model;
        }
    }
    String::new()
}

fn os_version() -> String {
    if cfg!(windows) {
        return run_text("powershell", &["-NoProfile", "-Command", "(Get-CimInstance Win32_OperatingSystem).Caption"])
            .map(|v| v.trim_start_matches("Microsoft ").to_string())
            .unwrap_or_else(|| "Windows".into());
    }
    if cfg!(target_os = "macos") {
        return run_text("sw_vers", &["-productVersion"]).map(|v| format!("macOS {v}")).unwrap_or_else(|| "macOS".into());
    }
    // os-release(5): /etc first, then the vendor copy in /usr/lib.
    std::fs::read_to_string("/etc/os-release")
        .or_else(|_| std::fs::read_to_string("/usr/lib/os-release"))
        .ok()
        .and_then(|t| {
            t.lines()
                .find_map(|l| l.strip_prefix("PRETTY_NAME=").map(|v| v.trim_matches('"').to_string()))
        })
        .unwrap_or_else(|| std::env::consts::OS.to_string())
}

fn load_identity(dir: &Path) -> Result<Identity, String> {
    let path = dir.join("identity.json");
    if let Ok(raw) = std::fs::read(&path)
        && let Ok(mut id) = serde_json::from_slice::<Identity>(&raw)
    {
        if id.host_name.is_empty() {
            id.host_name = machine_name();
        }
        return Ok(id);
    }
    let id = Identity {
        secret_key_hex: HEXLOWER.encode(&rand::random::<[u8; 32]>()),
        host_name: machine_name(),
    };
    let body = serde_json::to_vec_pretty(&id).map_err(|e| e.to_string())?;
    write_private_file(&path, &body)?;
    Ok(id)
}

fn secret_of(id: &Identity) -> Result<[u8; 32], String> {
    HEXLOWER
        .decode(id.secret_key_hex.trim().as_bytes())
        .ok()
        .and_then(|v| v.try_into().ok())
        .ok_or_else(|| "identity.json has a bad secret key".to_string())
}

fn print_qr(text: &str) {
    match qrcode::QrCode::new(text.as_bytes()) {
        Ok(code) => {
            let art = code
                .render::<qrcode::render::unicode::Dense1x2>()
                .dark_color(qrcode::render::unicode::Dense1x2::Light)
                .light_color(qrcode::render::unicode::Dense1x2::Dark)
                .quiet_zone(true)
                .build();
            println!("{art}");
        }
        Err(e) => eprintln!("(could not draw the QR code: {e})"),
    }
}

fn read_config_file(dir: &Path) -> Value {
    std::fs::read(dir.join("config.json"))
        .ok()
        .and_then(|b| serde_json::from_slice::<Value>(&b).ok())
        .filter(Value::is_object)
        .unwrap_or_else(|| json!({}))
}

fn write_config_file(dir: &Path, cfg: &Value) -> Result<(), String> {
    let body = serde_json::to_vec_pretty(cfg).map_err(|e| e.to_string())?;
    write_private_file(&dir.join("config.json"), &body)
}

/// Largest request a phone that is not paired yet may send (enough for any request but
/// `turn.start` with images, which only paired phones send). Keeps strangers from making the
/// connector hold 16 MiB per stream.
const UNPAIRED_FRAME_BYTES: usize = 64 * 1024;
/// A request must arrive within this long once its stream is open. Paired phones get much
/// longer: a `turn.start` with photos can be several MiB over a slow relayed link.
const UNPAIRED_READ_TIMEOUT: Duration = Duration::from_secs(30);
const PAIRED_READ_TIMEOUT: Duration = Duration::from_secs(10 * 60);
/// Streams handled at once per connection.
const STREAMS_PER_CONNECTION: usize = 32;
/// Connections from phones that aren't paired, open at once. More are closed straight away;
/// paired phones are never counted or turned away.
const UNPAIRED_CONNECTIONS: usize = 16;

/// One reply frame, then the stream is closed.
async fn reply_once(send: &mut iroh::endpoint::SendStream, reply: &Value) {
    let _ = write_json_frame(send, reply).await;
    let _ = send.finish();
}

fn refusal(code: &str, message: &str) -> Value {
    json!({ "ok": false, "error": { "code": code, "message": message } })
}

/// `pair.begin` / `pair.confirm`: the only requests served without a token (see pairing.rs).
fn handle_pairing(dir: &Path, computer: &str, phone: &str, method: &str, params: &Value) -> Value {
    let secret = params.get("secret").and_then(Value::as_str).unwrap_or("");
    let outcome = if method == "pair.begin" {
        pairing::begin(dir, computer, phone, secret).map(|code| {
            let name = read_ticket(dir).map(|t| t.host_name).unwrap_or_default();
            // The phone shows `code`; the user types it into `agy-connector pair` on this computer.
            json!({ "ok": true, "computer": name, "code": code })
        })
    } else {
        pairing::confirm(dir, phone, secret).map(|token| {
            // The phone keeps this ticket from now on: the computer's address and its own token.
            let mut ticket = read_ticket(dir).unwrap_or_else(|_| PairingTicket {
                endpoint_id: computer.to_string(),
                auth_token: String::new(),
                pairing_secret: None,
                host_name: String::new(),
                hardware_model: String::new(),
                os_version: String::new(),
                relay_url: None,
                addrs: Vec::new(),
            });
            ticket.auth_token = token;
            ticket.pairing_secret = None;
            info!("paired a new phone: {phone}");
            json!({ "ok": true, "ticket": ticket.to_uri() })
        })
    };
    outcome.unwrap_or_else(|e| {
        if e != pairing::PairError::Pending {
            warn!("pairing request from {phone} refused: {}", e.message());
        }
        refusal(e.code(), &e.message())
    })
}

async fn handle_connection(
    conn: Connection,
    bridge: Arc<Bridge>,
    dir: PathBuf,
    computer: String,
    unpaired: Arc<tokio::sync::Semaphore>,
) {
    let phone = conn.remote_id().to_string();
    let _unpaired_slot = if pairing::is_paired(&dir, &phone) {
        None
    } else {
        match unpaired.try_acquire_owned() {
            Ok(slot) => Some(slot),
            Err(_) => {
                conn.close(0u32.into(), b"busy");
                return;
            }
        }
    };
    let slots = Arc::new(tokio::sync::Semaphore::new(STREAMS_PER_CONNECTION));
    loop {
        // A stranger is someone who is not paired while no pairing is going on. They get one
        // request (answered `not_paired`, so an unpaired phone learns why) and are then
        // disconnected; they can't keep connections or many streams open.
        let paired = pairing::is_paired(&dir, &phone);
        let stranger = !paired && !pairing::session_open(&dir);
        // Nobody unpaired may sit on a connection without asking anything: there are only a few
        // places for unpaired phones, and one taken while a pairing code was open would otherwise
        // stay taken after it closed, keeping every new phone out. A phone that is pairing asks
        // about once a second.
        let accepted = if paired {
            conn.accept_bi().await.ok()
        } else {
            tokio::time::timeout(UNPAIRED_READ_TIMEOUT, conn.accept_bi()).await.ok().and_then(Result::ok)
        };
        let Some((mut send, mut recv)) = accepted else { break };
        let Ok(slot) = slots.clone().acquire_owned().await else { break };
        let bridge = bridge.clone();
        let phone = phone.clone();
        let dir = dir.clone();
        let computer = computer.clone();
        let request = tokio::spawn(async move {
            let _slot = slot;
            let (limit, wait) = if pairing::is_paired(&dir, &phone) {
                (MAX_FRAME_BYTES, PAIRED_READ_TIMEOUT)
            } else {
                (UNPAIRED_FRAME_BYTES, UNPAIRED_READ_TIMEOUT)
            };
            let read = tokio::time::timeout(wait, read_raw_frame_max(&mut recv, limit)).await;
            let Ok(Ok(Some(raw))) = read else { return };
            let Ok(envelope) = serde_json::from_slice::<ClientEnvelope>(&raw) else {
                return reply_once(&mut send, &json!({ "ok": false, "error": "Malformed ClientEnvelope" })).await;
            };
            if matches!(envelope.method.as_str(), "pair.begin" | "pair.confirm") {
                let reply = handle_pairing(&dir, &computer, &phone, &envelope.method, &envelope.params);
                return reply_once(&mut send, &reply).await;
            }
            if !pairing::check(&dir, &phone, &envelope.auth_token) {
                warn!("rejected {phone}: not paired, or not its token");
                let message = "This phone isn't paired. Run `agy-connector pair` on the computer and scan the code.";
                return reply_once(&mut send, &refusal("not_paired", message)).await;
            }
            if envelope.method == "turn.start" {
                let mut events = bridge.start_prompt(envelope.request_id.clone(), envelope.params);
                let mut checked = std::time::Instant::now();
                while let Some(ev) = events.recv().await {
                    // A phone unpaired while its turn runs stops hearing about it (the turn goes on).
                    let unpaired = checked.elapsed() >= Duration::from_secs(2) && {
                        checked = std::time::Instant::now();
                        !pairing::is_paired(&dir, &phone)
                    };
                    if unpaired || write_json_frame(&mut send, &ev).await.is_err() {
                        bridge.detach_request(&envelope.request_id).await;
                        break;
                    }
                }
                let _ = send.finish();
            } else {
                let reply = bridge
                    .handle(&phone, &envelope.method, &envelope.params)
                    .await
                    .unwrap_or_else(|m| json!({ "ok": false, "error": { "code": "internal", "message": m } }));
                reply_once(&mut send, &reply).await;
            }
        });
        if stranger {
            let _ = request.await;
            // Leave the answer a moment to arrive, then let go of the connection.
            let _ = tokio::time::timeout(Duration::from_secs(2), conn.closed()).await;
            break;
        }
    }
    conn.close(0u32.into(), b"");
}

fn unix_now() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0)
}

fn current_addrs(endpoint: &Endpoint) -> (Option<String>, Vec<String>) {
    let addr = endpoint.addr();
    let relay = addr.relay_urls().next().map(|r| r.to_string());
    let ips: Vec<String> = addr.ip_addrs().map(|a| a.to_string()).collect();
    (relay, prioritize_addr_strings(&ips, 8))
}

fn show_check(what: &str, reply: &Value) -> Result<(), String> {
    if reply["ok"] == json!(true) {
        return Ok(());
    }
    let code = reply["error"]["code"].as_str().unwrap_or("?");
    let message = reply["error"]["message"].as_str().unwrap_or("");
    Err(format!("{what}: {code} ({message})"))
}

async fn run_check(dir: PathBuf) -> Result<(), String> {
    let bridge = Bridge::new(machine_name(), hardware_model(), os_version(), dir, None);
    let ask = |method: &'static str, params: Value| {
        let b = bridge.clone();
        async move { b.handle("check", method, &params).await.unwrap_or_else(|e| json!({ "ok": false, "error": { "code": "internal", "message": e } })) }
    };
    let count = |v: &Value| v.as_array().map_or(0, Vec::len);

    let info = ask("connector.info", json!({})).await;
    show_check("connector.info", &info)?;
    println!("protocol: {}", info["protocol"]);
    println!(
        "agy: {} {}",
        info["agy"]["name"].as_str().unwrap_or("?"),
        info["agy"]["version"].as_str().unwrap_or("?")
    );
    println!(
        "folders: {}, models: {}, modes: {}, commands: {}",
        count(&info["folders"]),
        count(&info["models"]),
        count(&info["modes"]),
        count(&info["commands"])
    );

    let list = ask("chats.list", json!({})).await;
    show_check("chats.list", &list)?;
    let chats = list["chats"].as_array().cloned().unwrap_or_default();
    println!("chats: {}", chats.len());
    for chat in chats.iter().take(10) {
        let opened = ask("chat.open", json!({ "chatId": chat["id"] })).await;
        if opened["readable"] != json!(true) {
            continue;
        }
        println!(
            "first readable chat: {} ({} messages, {})",
            chat["title"].as_str().unwrap_or(""),
            count(&opened["messages"]),
            opened["state"].as_str().unwrap_or("?")
        );
        if let Some(folder) = chat["folder"].as_str() {
            let found = ask("files.search", json!({ "folder": folder, "query": "" })).await;
            println!("files in {folder}: {}", count(&found["files"]));
        }
        break;
    }

    let limits = ask("limits.get", json!({ "refresh": true })).await;
    if limits["ok"] == json!(true) {
        for l in limits["limits"].as_array().into_iter().flatten() {
            println!(
                "limit: {} {} {}% left, resets {}",
                l["group"].as_str().unwrap_or(""),
                l["window"].as_str().unwrap_or(""),
                l["remaining"],
                l["resets"].as_str().unwrap_or("-")
            );
        }
        println!("credits: {}", limits["credits"]);
    } else {
        println!("limits: unavailable ({})", limits["error"]["message"].as_str().unwrap_or(""));
    }

    let outside = ask("files.search", json!({ "folder": "/etc", "query": "" })).await;
    if outside["error"]["code"] == json!("not_allowed") {
        println!("files.search outside known folders: refused (ok)");
    } else {
        println!("files.search outside known folders: ALLOWED (bad)");
    }
    bridge.stop_agy().await;
    Ok(())
}

fn spawn_auto_update(dir: PathBuf, bridge: Arc<Bridge>) {
    tokio::spawn(async move {
        tokio::time::sleep(Duration::from_secs(5 * 60)).await;
        loop {
            if update::auto_update_enabled(&dir) {
                match update::check_and_install().await {
                    Ok(update::Outcome::Installed(v)) => {
                        info!("installed agy-connector {v}; restarting when idle");
                        bridge.set_update_note(Some(format!("Updating to {v}")));
                        while !bridge.is_idle().await {
                            tokio::time::sleep(Duration::from_secs(20)).await;
                        }
                        bridge.stop_agy().await;
                        restart_self();
                    }
                    Ok(update::Outcome::Available { version, .. }) => bridge.set_update_note(Some(version)),
                    Ok(update::Outcome::UpToDate) => bridge.set_update_note(None),
                    Err(e) => warn!("update check failed: {e}"),
                }
            }
            tokio::time::sleep(update::CHECK_INTERVAL).await;
        }
    });
}

/// Resolves on SIGTERM (`agy-connector down`, launchd, systemd).
async fn terminate() {
    #[cfg(unix)]
    {
        if let Ok(mut s) = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate()) {
            s.recv().await;
            return;
        }
    }
    #[cfg(windows)]
    {
        // Windows has no SIGTERM: `agy-connector down` drops a `stop` file into the config folder.
        let stop = config_dir().join("stop");
        let _ = std::fs::remove_file(&stop);
        loop {
            tokio::time::sleep(Duration::from_millis(500)).await;
            if stop.exists() {
                let _ = std::fs::remove_file(&stop);
                return;
            }
        }
    }
    #[allow(unreachable_code)]
    std::future::pending::<()>().await
}

fn restart_self() {
    let Ok(exe) = std::env::current_exe() else { return };
    let args: Vec<String> = std::env::args().skip(1).collect();
    #[cfg(unix)]
    {
        use std::os::unix::process::CommandExt;
        let e = std::process::Command::new(&exe).args(&args).exec();
        warn!("could not restart: {e}");
    }
    #[cfg(not(unix))]
    {
        let _ = std::process::Command::new(&exe).args(&args).spawn();
        std::process::exit(0);
    }
}

/// Binds the network endpoint on the UDP port the last run used (else any free port), so
/// phones that knew the old address reach a restarted connector at once instead of waiting
/// for a DHT lookup. The port is kept in `udp_port` in the configuration folder.
async fn bind_endpoint(dir: &Path, secret: &SecretKey) -> Result<Endpoint, String> {
    let port_file = dir.join("udp_port");
    let builder = || {
        Endpoint::builder(presets::N0)
            .secret_key(secret.clone())
            .alpns(vec![AGY_ALPN.to_vec()])
            .relay_mode(RelayMode::Default)
    };
    let saved = std::fs::read_to_string(&port_file).ok().and_then(|s| s.trim().parse::<u16>().ok()).filter(|p| *p != 0);
    let mut endpoint = None;
    if let Some(port) = saved
        && let Ok(b) = builder().bind_addr(std::net::SocketAddr::from(([0, 0, 0, 0], port)))
    {
        endpoint = b.bind().await.ok();
    }
    let endpoint = match endpoint {
        Some(e) => e,
        None => builder().bind().await.map_err(|e| format!("could not start the network endpoint: {e}"))?,
    };
    if let Some(port) = endpoint.bound_sockets().iter().find(|a| a.is_ipv4()).map(|a| a.port())
        && saved != Some(port)
    {
        let _ = std::fs::write(&port_file, port.to_string());
    }
    Ok(endpoint)
}

/// Publishes the current addresses to the DHT every 8 minutes, and keeps live_ticket.json (what
/// `agy-connector pair` hands out) up to date with them.
fn spawn_dht_publisher(endpoint: Endpoint, secret: [u8; 32], dir: PathBuf, about: [String; 3]) {
    tokio::spawn(async move {
        let dht = n0_mainline::Dht::client().ok();
        if dht.is_none() {
            warn!("Mainline DHT unavailable; not publishing addresses");
        }
        let mut seq = 0i64;
        loop {
            let ticket = live_ticket(&endpoint, &about[0], &about[1], &about[2]);
            if let Ok(body) = serde_json::to_vec_pretty(&ticket) {
                let _ = write_private_file(&dir.join("live_ticket.json"), &body);
            }
            let Some(dht) = &dht else {
                tokio::time::sleep(Duration::from_secs(480)).await;
                continue;
            };
            let (relay, addrs) = current_addrs(&endpoint);
            // The DHT is public: anyone with the endpoint id can read this record. The computer's
            // name stays out of it (phones get it from the pairing code); only addresses go in.
            let record = DhtEndpointRecord { relay_url: relay, addrs, updated_at: unix_now() };
            match publish_dht_endpoint(dht, &secret, &record, &mut seq).await {
                Ok(s) => info!("published addresses to the DHT (seq {s})"),
                Err(e) => warn!("{e}"),
            }
            tokio::time::sleep(Duration::from_secs(480)).await;
        }
    });
}

async fn run_up(dir: PathBuf, verbose: bool) -> Result<(), String> {
    // One connector per configuration folder: a second copy would answer the same phones and
    // send every notification twice (and with a stale key once a phone re-registers).
    std::fs::create_dir_all(&dir).map_err(|e| format!("{}: {e}", dir.display()))?;
    let lock_path = dir.join("connector.lock");
    let instance_lock = std::fs::OpenOptions::new()
        .create(true)
        .truncate(false)
        .write(true)
        .open(&lock_path)
        .map_err(|e| format!("{}: {e}", lock_path.display()))?;
    if instance_lock.try_lock().is_err() {
        return Err("agy-connector is already running on this computer (another `agy-connector up` \
                    or the background service). Stop it with `agy-connector down` first."
            .into());
    }
    let _ = std::fs::write(dir.join("connector.pid"), std::process::id().to_string());
    harden_config_dir(&dir);
    let identity = load_identity(&dir)?;
    let secret_bytes = secret_of(&identity)?;
    let secret = SecretKey::from_bytes(&secret_bytes);
    let endpoint = bind_endpoint(&dir, &secret).await?;
    let _ = tokio::time::timeout(Duration::from_secs(6), endpoint.online()).await;

    let (hw, os) = (hardware_model(), os_version());
    // The computer's address for `agy-connector pair`; no token in it (each phone gets its own).
    let ticket = live_ticket(&endpoint, &identity.host_name, &hw, &os);
    let ticket_json = serde_json::to_vec_pretty(&ticket).map_err(|e| e.to_string())?;
    write_private_file(&dir.join("live_ticket.json"), &ticket_json)?;

    let pusher = Pusher::new(dir.clone(), identity.host_name.clone(), secret);
    let bridge = Bridge::new(identity.host_name.clone(), hw.clone(), os.clone(), dir.clone(), Some(pusher));
    bridge.spawn_cli_watch();
    spawn_auto_update(dir.clone(), bridge.clone());
    spawn_dht_publisher(endpoint.clone(), secret_bytes, dir.clone(), [identity.host_name.clone(), hw, os]);

    if verbose {
        println!("endpoint id: {}", ticket.endpoint_id);
        println!("relay: {}", ticket.relay_url.as_deref().unwrap_or("none"));
        println!("addresses: {}", ticket.addrs.join(", "));
        println!("data: {}", dir.display());
    }
    println!("agy-connector is running on {}. Pair a phone with `agy-connector pair`.", ticket.host_name);

    let computer = ticket.endpoint_id.clone();
    let server = endpoint.clone();
    let conn_dir = dir.clone();
    let unpaired = Arc::new(tokio::sync::Semaphore::new(UNPAIRED_CONNECTIONS));
    let accept_loop = async move {
        while let Some(incoming) = server.accept().await {
            let unpaired = unpaired.clone();
            let bridge = bridge.clone();
            let dir = conn_dir.clone();
            let computer = computer.clone();
            tokio::spawn(async move {
                match incoming.await {
                    Ok(conn) => handle_connection(conn, bridge, dir, computer, unpaired).await,
                    Err(e) => warn!("incoming connection failed: {e}"),
                }
            });
        }
    };
    tokio::select! {
        _ = accept_loop => {}
        _ = tokio::signal::ctrl_c() => info!("stopping"),
        _ = terminate() => info!("stopping"),
    }
    endpoint.close().await;
    Ok(())
}

fn live_ticket(endpoint: &Endpoint, host: &str, hw: &str, os: &str) -> PairingTicket {
    let (relay, addrs) = current_addrs(endpoint);
    PairingTicket {
        endpoint_id: endpoint.id().to_string(),
        auth_token: String::new(),
        pairing_secret: None,
        host_name: host.to_string(),
        hardware_model: hw.to_string(),
        os_version: os.to_string(),
        relay_url: relay,
        addrs,
    }
}

/// Whether a connector already holds this folder's instance lock.
fn connector_running(dir: &Path) -> bool {
    let Ok(f) = std::fs::OpenOptions::new().create(true).truncate(false).write(true).open(dir.join("connector.lock")) else {
        return false;
    };
    let busy = f.try_lock().is_err();
    if !busy {
        let _ = f.unlock();
    }
    busy
}

/// `agy-connector up`: start `agy-connector run` detached from this terminal, wait until it has
/// written a fresh pairing ticket, show it, and hand the prompt back.
async fn start_background(dir: &Path, verbose: bool) -> Result<(), String> {
    std::fs::create_dir_all(dir).map_err(|e| format!("{}: {e}", dir.display()))?;
    if connector_running(dir) {
        println!("agy-connector is already running in the background. Pair a phone with `agy-connector pair`.");
        return Ok(());
    }
    let logs = dir.join("logs");
    std::fs::create_dir_all(&logs).map_err(|e| format!("{}: {e}", logs.display()))?;
    harden_config_dir(dir);
    let log_path = logs.join("connector.log");
    let mut opts = std::fs::OpenOptions::new();
    opts.create(true).append(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        opts.mode(0o600);
    }
    let log = opts.open(&log_path).map_err(|e| e.to_string())?;
    let ticket_path = dir.join("live_ticket.json");
    let before = std::fs::metadata(&ticket_path).and_then(|m| m.modified()).ok();
    let exe = std::env::current_exe().map_err(|e| e.to_string())?;
    let mut cmd = std::process::Command::new(exe);
    cmd.arg("run");
    if verbose {
        cmd.arg("--verbose");
    }
    cmd.stdin(std::process::Stdio::null())
        .stdout(log.try_clone().map_err(|e| e.to_string())?)
        .stderr(log);
    #[cfg(unix)]
    {
        use std::os::unix::process::CommandExt;
        unsafe extern "C" {
            fn setsid() -> i32;
        }
        // Its own session: closing the terminal or pressing Ctrl+C here doesn't reach it.
        unsafe {
            cmd.pre_exec(|| {
                setsid();
                Ok(())
            });
        }
    }
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        // No console window, own process group: closing this terminal or Ctrl+C doesn't reach it.
        const CREATE_NEW_PROCESS_GROUP: u32 = 0x0000_0200;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        cmd.creation_flags(CREATE_NEW_PROCESS_GROUP | CREATE_NO_WINDOW);
    }
    let mut child = cmd.spawn().map_err(|e| format!("could not start agy-connector: {e}"))?;
    for _ in 0..120 {
        tokio::time::sleep(Duration::from_millis(250)).await;
        if let Ok(Some(status)) = child.try_wait() {
            let tail = std::fs::read_to_string(&log_path).unwrap_or_default();
            let last: Vec<&str> = tail.lines().rev().take(5).collect();
            return Err(format!(
                "agy-connector stopped right away ({status}).\n{}",
                last.into_iter().rev().collect::<Vec<_>>().join("\n")
            ));
        }
        let now = std::fs::metadata(&ticket_path).and_then(|m| m.modified()).ok();
        if now.is_some() && now != before {
            println!("agy-connector is running in the background (log: {}).", log_path.display());
            println!("Pair a phone with `agy-connector pair`. Stop it with `agy-connector down`.");
            return Ok(());
        }
    }
    Err(format!("agy-connector is starting but isn't online yet; see {}", log_path.display()))
}

/// `agy-connector down`: stop the background connector.
fn stop_background(dir: &Path) -> Result<(), String> {
    if !connector_running(dir) {
        println!("agy-connector isn't running.");
        return Ok(());
    }
    let pid = std::fs::read_to_string(dir.join("connector.pid"))
        .ok()
        .and_then(|s| s.trim().parse::<i32>().ok())
        .ok_or("agy-connector is running but its process id is unknown")?;
    #[cfg(unix)]
    {
        unsafe extern "C" {
            fn kill(pid: i32, sig: i32) -> i32;
        }
        if unsafe { kill(pid, 15) } != 0 {
            return Err(format!("could not stop process {pid}"));
        }
    }
    #[cfg(windows)]
    std::fs::write(dir.join("stop"), b"").map_err(|e| format!("could not stop process {pid}: {e}"))?;
    for _ in 0..40 {
        std::thread::sleep(Duration::from_millis(250));
        if !connector_running(dir) {
            println!("agy-connector stopped.");
            return Ok(());
        }
    }
    #[cfg(windows)]
    {
        let _ = std::process::Command::new("taskkill").args(["/PID", &pid.to_string(), "/T", "/F"]).output();
        std::thread::sleep(Duration::from_millis(500));
        if !connector_running(dir) {
            let _ = std::fs::remove_file(dir.join("stop"));
            println!("agy-connector stopped.");
            return Ok(());
        }
    }
    Err(format!("agy-connector (process {pid}) didn't stop; if it runs as a login service, it restarts on its own"))
}

fn read_ticket(dir: &Path) -> Result<PairingTicket, String> {
    let path = dir.join("live_ticket.json");
    let raw = std::fs::read(&path).map_err(|_| "agy-connector isn't running. Start it with `agy-connector up`.".to_string())?;
    serde_json::from_slice(&raw).map_err(|e| format!("{}: {e}", path.display()))
}

/// Lines typed into this terminal, read on a thread of their own so waiting for one doesn't block
/// the rest. The channel closes when the terminal does (or there never was one).
fn typed_lines() -> tokio::sync::mpsc::UnboundedReceiver<String> {
    let (tx, rx) = tokio::sync::mpsc::unbounded_channel();
    std::thread::spawn(move || {
        for line in std::io::stdin().lines() {
            let Ok(line) = line else { break };
            if tx.send(line).is_err() {
                break;
            }
        }
    });
    rx
}

/// `agy-connector pair`: shows a one-time pairing code, then asks for the number shown by the
/// phone that scanned it, and waits until that phone is paired (see pairing.rs). The number is
/// typed here and never printed, so watching this terminal is not enough to pair a phone.
async fn pair_phone(dir: &Path) -> Result<(), String> {
    if !connector_running(dir) {
        start_background(dir, false).await?;
        println!();
    }
    let mut ticket = read_ticket(dir)?;
    let computer = ticket.endpoint_id.clone();
    let secret = pairing::start_session(dir)?;
    ticket.auth_token.clear();
    ticket.pairing_secret = Some(secret.clone());
    let uri = ticket.to_uri();
    print_qr(&uri);
    println!("Scan this with AGY Remote on {}, or paste this pairing code into the app:", ticket.host_name);
    println!("{uri}");
    let minutes = pairing::SESSION_LENGTH.as_secs() / 60;
    println!();
    println!("It works once, for {minutes} minutes. Waiting for a phone… (Ctrl+C to cancel)");

    let mut typed = typed_lines();
    let mut asked_for: Option<String> = None;
    let mut approved = false;
    let ask = || {
        use std::io::Write;
        print!("Number on the phone: ");
        let _ = std::io::stdout().flush();
    };
    let outcome = loop {
        let line = tokio::select! {
            _ = tokio::time::sleep(Duration::from_millis(400)) => None,
            _ = tokio::signal::ctrl_c() => break Err("Pairing cancelled.".to_string()),
            line = typed.recv(), if !approved => match line {
                Some(l) => Some(l),
                None => break Err("Pairing needs a terminal: the number the phone shows is typed here.".to_string()),
            },
        };
        let Some(s) = pairing::session(dir).filter(|s| s.matches(&secret)) else {
            return Err("Pairing was cancelled: another `agy-connector pair` started.".into());
        };
        if s.done {
            break Ok(());
        }
        if s.failed {
            break Err("Too many wrong numbers were typed. Run `agy-connector pair` again.".into());
        }
        if s.expired() {
            break Err(format!("Nobody paired within {minutes} minutes. Run `agy-connector pair` again."));
        }
        if let Some(phone) = s.phone.clone()
            && asked_for.as_ref() != Some(&phone)
        {
            println!();
            println!("A phone scanned the code (id {}…). It now shows a 6-digit number.", &phone[..phone.len().min(10)]);
            println!("Type that number here. If your phone shows no number, press Ctrl+C: another phone has the code.");
            ask();
            asked_for = Some(phone);
            // Anything typed before a phone scanned is not an answer to this.
            continue;
        }
        let Some(line) = line.filter(|l| !l.trim().is_empty()) else { continue };
        if asked_for.is_none() {
            continue;
        }
        match pairing::approve(dir, &computer, &secret, &line) {
            Ok(()) => {
                approved = true;
                println!("That's the number. Finishing on the phone…");
            }
            Err(pairing::PairError::WrongCode { left }) => {
                println!("That isn't the number on the phone. {left} tries left.");
                ask();
            }
            Err(pairing::PairError::TooManyTries) => {
                break Err("Too many wrong numbers were typed. Run `agy-connector pair` again.".into());
            }
            Err(e) => break Err(e.message()),
        }
    };
    pairing::end_session(dir);
    outcome?;
    println!();
    println!("Paired. The phone is now connected to {}.", ticket.host_name);
    Ok(())
}

fn list_devices(dir: &Path) {
    let list = pairing::devices(dir);
    if list.is_empty() {
        println!("No phones are paired. Pair one with `agy-connector pair`.");
        return;
    }
    for (id, d) in list {
        let when = SystemTime::UNIX_EPOCH + Duration::from_secs(d.paired);
        let ago = SystemTime::now().duration_since(when).map(|d| d.as_secs() / 86_400).unwrap_or(0);
        println!("{id}  (paired {ago} days ago)");
    }
}

fn unpair(dir: &Path, id: &str) -> Result<(), String> {
    if !pairing::remove(dir, id)? {
        return Err(format!("No paired phone has the id {id}. See `agy-connector devices`."));
    }
    // Its notification key goes too; the running connector checks the list before each push.
    println!("Unpaired {id}. It can't connect any more, and gets no notifications.");
    Ok(())
}

/// Unpairs every phone. Every phone has to pair again.
fn reset_pairing(dir: &Path) -> Result<(), String> {
    pairing::clear(dir)?;
    let _ = std::fs::remove_file(dir.join("push_devices.json"));
    println!("Every phone was unpaired. Pair one again with `agy-connector pair`.");
    Ok(())
}

async fn run(cli: Cli) -> Result<(), String> {
    let dir = config_dir();
    match cli.command.unwrap_or(Command::Up { foreground: false }) {
        Command::Up { foreground: true } | Command::Run => {
            run_up(dir, cli.verbose).await
        }
        Command::Up { foreground: false } => start_background(&dir, cli.verbose).await,
        Command::Down => stop_background(&dir),
        Command::Pair => pair_phone(&dir).await,
        Command::Devices => {
            list_devices(&dir);
            Ok(())
        }
        Command::Unpair { id } => unpair(&dir, id.trim()),
        Command::ResetPairing => reset_pairing(&dir),
        Command::Check => run_check(dir).await,
        Command::SetRelay { url } => {
            let url = url.trim().trim_end_matches('/').to_string();
            if !url.starts_with("https://") || !push::allowed_url(&url) {
                return Err("The relay URL must start with https://".into());
            }
            let mut cfg = read_config_file(&dir);
            cfg["push_relay"] = json!(url);
            write_config_file(&dir, &cfg)?;
            println!("Push relay set to {url}");
            Ok(())
        }
        Command::AddWorkspace { path } => {
            let full = std::fs::canonicalize(&path).map_err(|e| format!("{}: {e}", path.display()))?;
            let text = full.to_string_lossy().to_string();
            let mut cfg = read_config_file(&dir);
            if !cfg["workspaces"].is_array() {
                cfg["workspaces"] = json!([]);
            }
            let list = cfg["workspaces"].as_array_mut().expect("array");
            if !list.iter().any(|w| w.as_str() == Some(text.as_str())) {
                list.push(json!(text));
            }
            write_config_file(&dir, &cfg)?;
            println!("Added workspace: {text}");
            Ok(())
        }
        Command::Update => {
            match update::check_and_install().await? {
                update::Outcome::UpToDate => println!("agy-connector {} is up to date.", update::CURRENT),
                update::Outcome::Installed(v) => println!("Updated to {v}. Restart agy-connector to use it."),
                update::Outcome::Available { version, how } => {
                    println!("agy-connector {version} is available. Update with {how}.")
                }
            }
            Ok(())
        }
        Command::SetAutoUpdate { state } => {
            let on = matches!(state, Toggle::On);
            let mut cfg = read_config_file(&dir);
            cfg["auto_update"] = json!(on);
            write_config_file(&dir, &cfg)?;
            println!("Automatic updates {}.", if on { "on" } else { "off" });
            Ok(())
        }
        Command::SetTurbo { state } => {
            let on = matches!(state, Toggle::On);
            let mut cfg = read_config_file(&dir);
            cfg["allow_turbo"] = json!(on);
            write_config_file(&dir, &cfg)?;
            if on {
                println!("Turbo mode can be picked on the phone.");
            } else {
                println!("Turbo mode is off: every request asks first, also in chats that used Turbo.");
            }
            Ok(())
        }
    }
}

#[tokio::main]
async fn main() {
    let cli = Cli::parse();
    let filter = if std::env::var_os("RUST_LOG").is_some() {
        tracing_subscriber::EnvFilter::from_default_env()
    } else if cli.verbose {
        tracing_subscriber::EnvFilter::new("info")
    } else {
        tracing_subscriber::EnvFilter::new("off")
    };
    tracing_subscriber::fmt().with_env_filter(filter).with_writer(std::io::stderr).init();
    if let Err(e) = run(cli).await {
        eprintln!("{e}");
        std::process::exit(1);
    }
}
