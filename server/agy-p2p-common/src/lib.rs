use std::{
    net::SocketAddr,
    str::FromStr,
    time::{SystemTime, UNIX_EPOCH},
};

use iroh::{
    EndpointAddr, EndpointId, RelayUrl, TransportAddr,
    endpoint::{RecvStream, SendStream},
};
use n0_mainline::{Dht, MutableItem, SigningKey};
use serde::{Deserialize, Serialize};
use tokio::io::AsyncWriteExt;
use url::Url;

/// Custom ALPN protocol identifier for Antigravity Serverless P2P Bridge.
pub const AGY_ALPN: &[u8] = b"agy-remote/p2p/v1";

/// BEP-44 salt scoping Mainline DHT mutable records to Antigravity P2P v1.
pub const DHT_SALT: &[u8] = b"agy-p2p-v1";

/// Maximum frame payload size (16 MiB).
pub const MAX_FRAME_BYTES: usize = 16 * 1024 * 1024;

/// Compact signed endpoint record (< 900 bytes) published to BitTorrent Mainline DHT (BEP-44).
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DhtEndpointRecord {
    /// Optional relay URL for NAT holepunching coordination.
    #[serde(rename = "r", skip_serializing_if = "Option::is_none")]
    pub relay_url: Option<String>,
    /// Direct UDP socket addresses (LAN, STUN reflexive, Tailscale, IPv6).
    #[serde(rename = "a", default)]
    pub addrs: Vec<String>,
    /// Unix timestamp (seconds) of publication.
    #[serde(rename = "t", default)]
    pub updated_at: u64,
}

/// Pairing ticket shared once between PC Connector and iOS App (via QR code or URI).
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PairingTicket {
    pub endpoint_id: String,
    /// This phone's own token, issued once pairing is confirmed. Empty in a pairing code.
    #[serde(default)]
    pub auth_token: String,
    /// One-time secret of a pairing code (`agy-connector pair`); the phone proves it saw the
    /// code with it, then types the number the computer shows. Absent in a stored ticket.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub pairing_secret: Option<String>,
    pub host_name: String,
    #[serde(default)]
    pub hardware_model: String,
    #[serde(default)]
    pub os_version: String,
    #[serde(default)]
    pub relay_url: Option<String>,
    #[serde(default)]
    pub addrs: Vec<String>,
}

impl PairingTicket {
    /// Encodes the pairing ticket as a human-readable & deep-linkable `agy-rem://pair?...` URI.
    pub fn to_uri(&self) -> String {
        let mut url = Url::parse("agy-rem://pair").expect("valid static base url");
        {
            let mut qp = url.query_pairs_mut();
            qp.append_pair("id", &self.endpoint_id);
            if !self.auth_token.is_empty() {
                qp.append_pair("token", &self.auth_token);
            }
            if let Some(ps) = self.pairing_secret.as_deref().filter(|p| !p.is_empty()) {
                qp.append_pair("ps", ps);
            }
            qp.append_pair("name", &self.host_name);
            if !self.hardware_model.is_empty() {
                qp.append_pair("hw", &self.hardware_model);
            }
            if !self.os_version.is_empty() {
                qp.append_pair("os", &self.os_version);
            }
            if let Some(relay) = &self.relay_url
                && !relay.is_empty()
            {
                qp.append_pair("relay", relay);
            }
            if !self.addrs.is_empty() {
                // Keep top 8 addresses so QR code stays compact and fast to scan.
                let prioritized = prioritize_addr_strings(&self.addrs, 8);
                qp.append_pair("addrs", &prioritized.join(","));
            }
        }
        url.to_string()
    }

    /// Parses an `agy-rem://pair?...` code (with `ps`) or a stored ticket (with `token`).
    pub fn parse_any(input: &str) -> Result<Self, String> {
        let raw = input.trim();
        if !raw.starts_with("agy-rem://") {
            return Err("Unsupported pairing code (expected agy-rem://pair?...)".into());
        }
        let parsed = Url::parse(raw).map_err(|e| format!("Invalid pairing URI: {e}"))?;
        let mut ticket = PairingTicket {
            endpoint_id: String::new(),
            auth_token: String::new(),
            pairing_secret: None,
            host_name: "Antigravity Host".into(),
            hardware_model: String::new(),
            os_version: String::new(),
            relay_url: None,
            addrs: Vec::new(),
        };
        let (mut have_id, mut have_token) = (false, false);
        for (key, val) in parsed.query_pairs() {
            let val = val.into_owned();
            match key.as_ref() {
                "id" => {
                    ticket.endpoint_id = val;
                    have_id = true;
                }
                "token" => {
                    ticket.auth_token = val;
                    have_token = true;
                }
                "ps" if !val.is_empty() => {
                    ticket.pairing_secret = Some(val);
                    have_token = true;
                }
                "name" => ticket.host_name = val,
                "hw" => ticket.hardware_model = val,
                "os" => ticket.os_version = val,
                "relay" if !val.is_empty() => ticket.relay_url = Some(val),
                "addrs" => {
                    ticket.addrs = val
                        .split(',')
                        .map(str::trim)
                        .filter(|a| !a.is_empty())
                        .map(String::from)
                        .collect();
                }
                _ => {}
            }
        }
        if !have_id || !have_token {
            return Err("Pairing URI missing 'id' or 'token'/'ps'".into());
        }
        if let Err(e) = EndpointId::from_str(&ticket.endpoint_id) {
            return Err(format!("Invalid EndpointId '{}': {e}", ticket.endpoint_id));
        }
        Ok(ticket)
    }

    pub fn parsed_endpoint_id(&self) -> Result<EndpointId, String> {
        EndpointId::from_str(&self.endpoint_id)
            .map_err(|e| format!("Invalid EndpointId: {e}"))
    }

    /// Builds an `iroh::EndpointAddr` combining ticket hints and any fresh Mainline DHT record.
    /// Addresses to dial. The relay is included only when `with_relay` is set (the user agreed
    /// to use it).
    pub fn to_endpoint_addr(
        &self,
        dht_record: Option<&DhtEndpointRecord>,
        with_relay: bool,
    ) -> Result<EndpointAddr, String> {
        let id = self.parsed_endpoint_id()?;
        let mut transport_addrs: Vec<TransportAddr> = Vec::new();
        let mut seen_ips = Vec::new();

        // Fresh DHT addresses take priority, followed by cached ticket addresses.
        let combined_ips = dht_record
            .map(|r| r.addrs.as_slice())
            .unwrap_or(&[])
            .iter()
            .chain(self.addrs.iter());

        for addr_str in combined_ips {
            if let Ok(sock) = SocketAddr::from_str(addr_str)
                && !seen_ips.contains(&sock)
            {
                seen_ips.push(sock);
                transport_addrs.push(TransportAddr::Ip(sock));
            }
        }

        let relay_candidate = dht_record
            .and_then(|r| r.relay_url.as_deref())
            .or(self.relay_url.as_deref());

        if with_relay
            && let Some(r_str) = relay_candidate
            && let Ok(r_url) = RelayUrl::from_str(r_str)
        {
            transport_addrs.push(TransportAddr::Relay(r_url));
        }

        if transport_addrs.is_empty() {
            Ok(EndpointAddr::new(id))
        } else {
            Ok(EndpointAddr::from_parts(id, transport_addrs))
        }
    }
}

/// Prioritizes IPv4 LAN / Tailscale CGNAT / public IPv4 before IPv6 link-local addresses,
/// keeping QR tickets compact while preserving direct local reachability.
pub fn prioritize_addr_strings(addrs: &[String], max_count: usize) -> Vec<String> {
    let mut parsed: Vec<(u8, String)> = Vec::new();
    for s in addrs {
        if let Ok(sa) = SocketAddr::from_str(s) {
            let rank = match sa {
                SocketAddr::V4(v4) => {
                    let oct = v4.ip().octets();
                    if oct[0] == 100 && (oct[1] & 0b1100_0000) == 0b0100_0000 {
                        0 // Tailscale / CGNAT mesh IP
                    } else if v4.ip().is_private() {
                        1 // Private LAN IPv4
                    } else if !v4.ip().is_loopback() && !v4.ip().is_unspecified() {
                        2 // Public IPv4
                    } else {
                        9
                    }
                }
                SocketAddr::V6(v6) => {
                    if v6.ip().is_loopback() || v6.ip().is_unspecified() {
                        9
                    } else if (v6.ip().segments()[0] & 0xffc0) == 0xfe80 {
                        5 // Link-local IPv6
                    } else {
                        3 // Global / ULA IPv6
                    }
                }
            };
            if rank < 9 {
                parsed.push((rank, sa.to_string()));
            }
        }
    }
    parsed.sort_by(|a, b| a.0.cmp(&b.0).then_with(|| a.1.cmp(&b.1)));
    parsed.dedup_by(|a, b| a.1 == b.1);
    parsed.into_iter().take(max_count).map(|(_, s)| s).collect()
}

/// Publishes a signed BEP-44 record to the global BitTorrent Mainline DHT.
pub async fn publish_dht_endpoint(
    dht: &Dht,
    secret_bytes: &[u8; 32],
    record: &DhtEndpointRecord,
    last_seq: &mut i64,
) -> Result<i64, String> {
    let mut slim = record.clone();
    slim.addrs = prioritize_addr_strings(&record.addrs, 8);
    let mut payload = serde_json::to_vec(&slim).map_err(|e| e.to_string())?;
    if payload.len() > 950 {
        slim.addrs.truncate(4);
        payload = serde_json::to_vec(&slim).map_err(|e| e.to_string())?;
    }
    let micros = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_micros() as i64)
        .unwrap_or(0);
    let seq = micros.max(*last_seq + 1);
    *last_seq = seq;
    let signer = SigningKey::from_bytes(secret_bytes);
    let item = MutableItem::new(&signer, &payload, seq, Some(DHT_SALT));
    dht.put_mutable(item, None)
        .await
        .map_err(|e| format!("Mainline DHT put_mutable error: {e}"))?;
    Ok(seq)
}

/// Resolves the newest Ed25519-verified endpoint record from the global Mainline DHT.
pub async fn resolve_dht_endpoint(
    dht: &Dht,
    endpoint_id: &EndpointId,
) -> Option<DhtEndpointRecord> {
    let pub_bytes = endpoint_id.as_bytes();
    let item = dht
        .get_mutable_most_recent(pub_bytes, Some(DHT_SALT))
        .await
        .ok()??;
    serde_json::from_slice(item.value()).ok()
}

/// Writes a length-prefixed JSON frame onto a QUIC SendStream.
pub async fn write_json_frame<T: Serialize + ?Sized>(
    send: &mut SendStream,
    value: &T,
) -> Result<(), String> {
    let body = serde_json::to_vec(value).map_err(|e| format!("JSON encode error: {e}"))?;
    if body.len() > MAX_FRAME_BYTES {
        return Err(format!("Frame exceeds max size ({} bytes)", body.len()));
    }
    let prefix = (body.len() as u32).to_be_bytes();
    send.write_all(&prefix).await.map_err(|e| format!("QUIC write len error: {e}"))?;
    send.write_all(&body).await.map_err(|e| format!("QUIC write payload error: {e}"))?;
    send.flush().await.map_err(|e| format!("QUIC flush error: {e}"))
}

/// Reads a single length-prefixed JSON frame from a QUIC RecvStream.
/// Returns `Ok(None)` on clean EOF before the 4-byte length prefix.
pub async fn read_raw_frame(recv: &mut RecvStream) -> Result<Option<Vec<u8>>, String> {
    read_raw_frame_max(recv, MAX_FRAME_BYTES).await
}

/// Like [`read_raw_frame`], refusing frames larger than `max` before allocating for them.
pub async fn read_raw_frame_max(recv: &mut RecvStream, max: usize) -> Result<Option<Vec<u8>>, String> {
    let max = max.min(MAX_FRAME_BYTES);
    let mut prefix = [0u8; 4];
    match recv.read_exact(&mut prefix).await {
        Ok(()) => {}
        Err(iroh::endpoint::ReadExactError::FinishedEarly(0)) => return Ok(None),
        Err(e) => return Err(format!("QUIC read len error: {e}")),
    }
    let size = u32::from_be_bytes(prefix) as usize;
    if size > max {
        return Err(format!("Incoming frame too large: {size} bytes"));
    }
    let mut body = vec![0u8; size];
    recv.read_exact(&mut body)
        .await
        .map_err(|e| format!("QUIC read payload error: {e}"))?;
    Ok(Some(body))
}

/// Envelope sent from iOS client -> PC Connector over a QUIC bi-stream.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ClientEnvelope {
    pub auth_token: String,
    pub request_id: String,
    /// A protocol v1 method (docs/protocol.md), e.g. `"chats.list"` or `"turn.start"`.
    pub method: String,
    #[serde(default)]
    pub params: serde_json::Value,
}

/// Which path a connection is using right now and its round trip, for the apps' "transport ·
/// latency" line: `direct` (straight to the computer), `relay` (through an iroh relay) or `p2p`
/// (not settled yet). Connections start on the relay and move to a direct path when they can, so
/// "relay allowed" doesn't mean "on the relay". Shared by the iOS and Android libraries.
pub fn path_telemetry(conn: &iroh::endpoint::Connection, fallback_rtt_ms: u64) -> serde_json::Value {
    let paths = conn.paths();
    let chosen = paths.iter().find(|p| p.is_selected());
    let direct = chosen.as_ref().is_some_and(|p| p.is_ip());
    let relayed = chosen.as_ref().is_some_and(|p| p.is_relay());
    let measured = chosen.as_ref().map(|p| p.rtt().as_millis() as u64).unwrap_or(0);
    let transport = match (direct, relayed) {
        (true, _) => "direct",
        (false, true) => "relay",
        _ => "p2p",
    };
    serde_json::json!({
        "rtt_ms": (if measured > 0 { measured } else { fallback_rtt_ms }).max(1),
        "transport": transport,
        "is_direct": direct,
        "remote_id": conn.remote_id().to_string(),
    })
}

