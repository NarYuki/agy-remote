use std::{
    collections::HashMap,
    ffi::{CStr, CString, c_char, c_void},
    sync::Mutex,
    time::{Duration, Instant},
};

use agy_p2p_common::{
    AGY_ALPN, ClientEnvelope, PairingTicket, read_raw_frame, resolve_dht_endpoint, write_json_frame,
};
use data_encoding::HEXLOWER_PERMISSIVE;
use iroh::{
    Endpoint, EndpointAddr, EndpointId, RelayMode, SecretKey,
    endpoint::{Connection, presets},
};
use n0_mainline::Dht;
use serde_json::{Value, json};
use tokio::sync::{Mutex as AsyncMutex, OnceCell, oneshot};

pub type AgyStreamEventCallback = extern "C" fn(*const c_char, *mut c_void);

pub struct AgyP2PClientHandle {
    runtime: tokio::runtime::Runtime,
    secret_bytes: [u8; 32],
    /// The endpoint and whether it was built with the relay allowed.
    endpoint: AsyncMutex<Option<(bool, Endpoint)>>,
    /// The user agreed to use the public relay when a direct connection isn't possible.
    relay_allowed: std::sync::atomic::AtomicBool,
    dht: OnceCell<Option<Dht>>,
    connections: AsyncMutex<HashMap<EndpointId, Connection>>,
    cancels: Mutex<HashMap<String, oneshot::Sender<()>>>,
}

impl AgyP2PClientHandle {
    fn relay_allowed(&self) -> bool {
        self.relay_allowed.load(std::sync::atomic::Ordering::Relaxed)
    }

    /// The endpoint, rebuilt when the relay setting changes. Without the user's consent it has
    /// no relay at all: only direct paths are tried.
    async fn get_endpoint(&self) -> Result<Endpoint, String> {
        let relay_ok = self.relay_allowed();
        let mut guard = self.endpoint.lock().await;
        match guard.as_ref() {
            Some((flag, ep)) if *flag == relay_ok => return Ok(ep.clone()),
            _ => {}
        }
        if let Some((_, old)) = guard.take() {
            self.connections.lock().await.clear();
            old.close().await;
        }
        let ep = Endpoint::builder(presets::N0)
            .secret_key(SecretKey::from_bytes(&self.secret_bytes))
            .alpns(vec![AGY_ALPN.to_vec()])
            .relay_mode(if relay_ok { RelayMode::Default } else { RelayMode::Disabled })
            .bind()
            .await
            .map_err(|e| format!("Failed to bind Iroh QUIC endpoint: {e}"))?;
        *guard = Some((relay_ok, ep.clone()));
        Ok(ep)
    }

    async fn get_dht(&self) -> Option<&Dht> {
        self.dht
            .get_or_init(|| async { Dht::client().ok() })
            .await
            .as_ref()
    }

    async fn get_or_connect(
        &self,
        ticket: &PairingTicket,
        timeout: Duration,
    ) -> Result<Connection, String> {
        let target_id = ticket.parsed_endpoint_id()?;

        {
            let mut guard = self.connections.lock().await;
            if let Some(existing) = guard.get(&target_id) {
                if existing.close_reason().is_none() {
                    return Ok(existing.clone());
                }
                guard.remove(&target_id);
            }
        }

        let endpoint = self.get_endpoint().await?;
        let with_relay = self.relay_allowed();
        let initial_addr = ticket.to_endpoint_addr(None, with_relay)?;

        // Fast path: dial direct UDP / relay hints from ticket first.
        let fast_budget = timeout.min(Duration::from_millis(3200));
        let conn_res =
            tokio::time::timeout(fast_budget, endpoint.connect(initial_addr, AGY_ALPN)).await;

        let conn = match conn_res {
            Ok(Ok(c)) => c,
            _ => {
                // Fallback 1: Resolve latest signed BEP-44 record from global Mainline BitTorrent DHT.
                let dht_record = if let Some(dht) = self.get_dht().await {
                    tokio::time::timeout(
                        Duration::from_millis(3500),
                        resolve_dht_endpoint(dht, &target_id),
                    )
                    .await
                    .ok()
                    .flatten()
                } else {
                    None
                };

                let refreshed_addr = if dht_record.is_some() {
                    ticket.to_endpoint_addr(dht_record.as_ref(), with_relay)?
                } else {
                    // Fallback 2: Pure EndpointId lookup via Iroh Pkarr/DNS discovery.
                    EndpointAddr::new(target_id)
                };

                tokio::time::timeout(timeout, endpoint.connect(refreshed_addr, AGY_ALPN))
                    .await
                    .map_err(|_| "P2P connection timed out".to_string())?
                    .map_err(|e| format!("P2P QUIC dial failed: {e}"))?
            }
        };

        if conn.remote_id() != target_id {
            return Err("Peer EndpointId mismatch".to_string());
        }

        let mut guard = self.connections.lock().await;
        guard.insert(target_id, conn.clone());
        Ok(conn)
    }

    /// Forgets and closes `conn` if it is still the cached connection to its computer.
    async fn close_if_current(&self, conn: &Connection) {
        let mut guard = self.connections.lock().await;
        if guard.get(&conn.remote_id()).is_some_and(|c| c.stable_id() == conn.stable_id()) {
            guard.remove(&conn.remote_id());
        }
        drop(guard);
        conn.close(0u32.into(), b"unresponsive");
    }

    async fn invalidate_connection(&self, target_id: &EndpointId) {
        let mut guard = self.connections.lock().await;
        guard.remove(target_id);
    }
}

fn to_c_string(val: &Value) -> *mut c_char {
    let s = serde_json::to_string(val)
        .unwrap_or_else(|_| "{\"ok\":false,\"error\":\"serialization error\"}".to_string());
    CString::new(s)
        .unwrap_or_else(|_| CString::new("{}").unwrap())
        .into_raw()
}

fn cstr_to_string(ptr: *const c_char) -> String {
    if ptr.is_null() {
        return String::new();
    }
    unsafe { CStr::from_ptr(ptr) }
        .to_string_lossy()
        .into_owned()
}

#[unsafe(no_mangle)]
pub extern "C" fn agy_p2p_client_create(secret_key_hex: *const c_char) -> *mut AgyP2PClientHandle {
    let hex = cstr_to_string(secret_key_hex);
    let secret_bytes: [u8; 32] = match HEXLOWER_PERMISSIVE.decode(hex.trim().as_bytes()) {
        Ok(v) if v.len() == 32 => v.try_into().unwrap_or_else(|_| rand::random()),
        _ => rand::random(),
    };
    let Ok(runtime) = tokio::runtime::Builder::new_multi_thread().worker_threads(2).enable_all().build()
    else {
        return std::ptr::null_mut();
    };
    let client = AgyP2PClientHandle {
        runtime,
        secret_bytes,
        endpoint: AsyncMutex::new(None),
        relay_allowed: std::sync::atomic::AtomicBool::new(false),
        dht: OnceCell::new(),
        connections: AsyncMutex::new(HashMap::new()),
        cancels: Mutex::new(HashMap::new()),
    };
    Box::into_raw(Box::new(client))
}

/// Allows (or stops) using the public relay when a direct connection isn't possible. Takes
/// effect on the next connection.
#[unsafe(no_mangle)]
pub extern "C" fn agy_p2p_set_relay_allowed(handle: *mut AgyP2PClientHandle, allowed: bool) {
    if let Some(h) = unsafe { handle.as_ref() } {
        h.relay_allowed.store(allowed, std::sync::atomic::Ordering::Relaxed);
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn agy_p2p_client_destroy(handle: *mut AgyP2PClientHandle) {
    if handle.is_null() {
        return;
    }
    let boxed = unsafe { Box::from_raw(handle) };
    let ep = boxed.endpoint.try_lock().ok().and_then(|g| g.as_ref().map(|(_, e)| e.clone()));
    if let Some(ep) = ep {
        boxed.runtime.block_on(async move {
            ep.close().await;
        });
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn agy_p2p_client_identity_json(handle: *mut AgyP2PClientHandle) -> *mut c_char {
    if handle.is_null() {
        return to_c_string(&json!({"ok": false, "error": "null handle"}));
    }
    let h = unsafe { &*handle };
    let secret = SecretKey::from_bytes(&h.secret_bytes);
    let pub_id = secret.public().to_string();
    let hex_secret = data_encoding::HEXLOWER.encode(&h.secret_bytes);
    to_c_string(&json!({
        "ok": true,
        "endpoint_id": pub_id,
        "secret_key_hex": hex_secret,
    }))
}

#[unsafe(no_mangle)]
pub extern "C" fn agy_p2p_parse_ticket(ticket_str: *const c_char) -> *mut c_char {
    let raw = cstr_to_string(ticket_str);
    match PairingTicket::parse_any(&raw) {
        Ok(ticket) => to_c_string(&json!({
            "ok": true,
            "ticket": ticket,
            "uri": ticket.to_uri(),
        })),
        Err(err) => to_c_string(&json!({
            "ok": false,
            "error": err,
        })),
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn agy_p2p_request_json(
    handle: *mut AgyP2PClientHandle,
    ticket_str: *const c_char,
    method_str: *const c_char,
    params_json_str: *const c_char,
    timeout_ms: u32,
) -> *mut c_char {
    let Some(h) = (unsafe { handle.as_ref() }) else {
        return to_c_string(&json!({ "ok": false, "error": "null client handle" }));
    };
    let raw_ticket = cstr_to_string(ticket_str);
    let method = cstr_to_string(method_str);
    let params: Value =
        serde_json::from_str(&cstr_to_string(params_json_str)).unwrap_or_else(|_| json!({}));
    let budget = Duration::from_millis(u64::from(timeout_ms.max(1500)));
    let ticket = match PairingTicket::parse_any(&raw_ticket) {
        Ok(t) => t,
        Err(e) => return to_c_string(&json!({ "ok": false, "error": e })),
    };
    let started = Instant::now();
    let result: Result<Value, String> = h.runtime.block_on(async {
        let mut attempt = 0;
        loop {
            attempt += 1;
            let conn = h.get_or_connect(&ticket, budget).await?;
            let env = ClientEnvelope {
                auth_token: ticket.auth_token.clone(),
                request_id: format!("rpc-{}", rand::random::<u64>()),
                method: method.clone(),
                params: params.clone(),
            };
            let round_trip = async {
                let (mut tx, mut rx) =
                    conn.open_bi().await.map_err(|e| format!("QUIC open_bi error: {e}"))?;
                write_json_frame(&mut tx, &env).await?;
                let _ = tx.finish();
                let data = read_raw_frame(&mut rx)
                    .await?
                    .ok_or_else(|| "Empty response from PC connector".to_string())?;
                let mut v: Value = serde_json::from_slice(&data)
                    .map_err(|e| format!("Invalid response JSON: {e}"))?;
                if let Value::Object(map) = &mut v {
                    let elapsed = started.elapsed().as_millis() as u64;
                    map.insert("_p2p".to_string(), agy_p2p_common::path_telemetry(&conn, elapsed));
                }
                Ok::<Value, String>(v)
            };
            let err = match tokio::time::timeout(budget, round_trip).await {
                Ok(Ok(v)) => return Ok(v),
                Ok(Err(e)) => e,
                Err(_) => {
                    // No reply in time: the computer is likely gone (e.g. agy-connector restarted).
                    // Closing the connection also ends turn streams waiting on it, instead of
                    // leaving them open until the QUIC idle timeout.
                    h.close_if_current(&conn).await;
                    "RPC timed out over P2P QUIC".to_string()
                }
            };
            if attempt >= 2 {
                return Err(err);
            }
            h.invalidate_connection(&conn.remote_id()).await;
        }
    });
    match result {
        Ok(v) => to_c_string(&v),
        Err(e) => to_c_string(&json!({ "ok": false, "error": e })),
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn agy_p2p_stream_chat(
    handle: *mut AgyP2PClientHandle,
    ticket_str: *const c_char,
    request_id_str: *const c_char,
    params_json_str: *const c_char,
    callback: AgyStreamEventCallback,
    user_ctx: *mut c_void,
) -> i32 {
    let Some(h) = (unsafe { handle.as_ref() }) else { return -1 };
    let deliver = |v: &Value| {
        let text = CString::new(v.to_string()).unwrap_or_else(|_| CString::new("{}").unwrap());
        callback(text.as_ptr(), user_ctx);
    };
    let raw_ticket = cstr_to_string(ticket_str);
    let request_id = cstr_to_string(request_id_str);
    let params: Value =
        serde_json::from_str(&cstr_to_string(params_json_str)).unwrap_or_else(|_| json!({}));
    let ticket = match PairingTicket::parse_any(&raw_ticket) {
        Ok(t) => t,
        Err(e) => {
            deliver(&json!({ "type": "error", "message": e }));
            return -2;
        }
    };
    let (cancel_tx, mut cancel_rx) = oneshot::channel::<()>();
    if let Ok(mut map) = h.cancels.lock() {
        map.insert(request_id.clone(), cancel_tx);
    }
    let started = Instant::now();
    let limit = Duration::from_secs(10);
    let result: Result<(), String> = h.runtime.block_on(async {
        let first = h.get_or_connect(&ticket, limit).await?;
        let (conn, (mut tx, mut rx)) = match first.open_bi().await {
            Ok(streams) => (first, streams),
            Err(_) => {
                h.invalidate_connection(&first.remote_id()).await;
                let again = h.get_or_connect(&ticket, limit).await?;
                let streams =
                    again.open_bi().await.map_err(|e| format!("QUIC open_bi error: {e}"))?;
                (again, streams)
            }
        };
        let elapsed = started.elapsed().as_millis() as u64;
        deliver(&json!({ "type": "connected", "_p2p": agy_p2p_common::path_telemetry(&conn, elapsed) }));
        let env = ClientEnvelope {
            auth_token: ticket.auth_token.clone(),
            request_id: request_id.clone(),
            method: "turn.start".to_string(),
            params,
        };
        write_json_frame(&mut tx, &env).await?;
        let _ = tx.finish();
        loop {
            let next = tokio::select! {
                _ = &mut cancel_rx => None,
                r = read_raw_frame(&mut rx) => Some(r?),
            };
            let Some(Some(data)) = next else { break };
            let Ok(frame) = serde_json::from_slice::<Value>(&data) else { continue };
            deliver(&frame);
            let ev = frame["event"].as_str().unwrap_or_default();
            let done = matches!(ev, "turn.finished" | "turn.stopped" | "turn.failed")
                || frame["ok"].as_bool() == Some(false);
            if done {
                break;
            }
        }
        Ok(())
    });
    if let Ok(mut map) = h.cancels.lock() {
        map.remove(&request_id);
    }
    match result {
        Ok(()) => 0,
        Err(message) => {
            deliver(&json!({ "type": "error", "message": message }));
            1
        }
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn agy_p2p_cancel_stream(
    handle: *mut AgyP2PClientHandle,
    ticket_str: *const c_char,
    request_id_str: *const c_char,
) {
    if handle.is_null() {
        return;
    }
    let h = unsafe { &*handle };
    let request_id = cstr_to_string(request_id_str);
    if let Ok(mut map) = h.cancels.lock()
        && let Some(tx) = map.remove(&request_id)
    {
        let _ = tx.send(());
    }

    // Only stops listening: the turn keeps running on the computer (protocol v1). The app sends
    // `turn.stop` itself when the user wants the turn stopped.
    let _ = ticket_str;
}

#[unsafe(no_mangle)]
pub extern "C" fn agy_p2p_free_string(ptr: *mut c_char) {
    if !ptr.is_null() {
        unsafe {
            let _ = CString::from_raw(ptr);
        }
    }
}
