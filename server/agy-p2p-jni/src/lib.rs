//! JNI bindings of the AGY Remote P2P client for the Android app.
//!
//! Same transport as `agy-p2p-ffi` (iroh QUIC + Mainline DHT lookup, see `agy-p2p-common`), but
//! speaking protocol v1 (`docs/protocol.md`): any method can be called, and any method can be
//! streamed (`turn.start`), ending at `turn.finished` / `turn.stopped` / `turn.failed`.
//!
//! Kotlin side: `android/app/src/main/java/moe/n4tsu/agyremote/net/NativeP2P.kt`.

use std::{
    collections::HashMap,
    sync::{
        Mutex,
        atomic::{AtomicBool, Ordering},
    },
    time::{Duration, Instant},
};

use agy_p2p_common::{
    AGY_ALPN, ClientEnvelope, PairingTicket, read_raw_frame, resolve_dht_endpoint, write_json_frame,
};
use data_encoding::{HEXLOWER, HEXLOWER_PERMISSIVE};
use iroh::{
    Endpoint, EndpointAddr, EndpointId, RelayMode, SecretKey,
    endpoint::{Connection, presets},
};
use jni::{
    JNIEnv,
    objects::{JClass, JObject, JString, JValue},
    sys::{jboolean, jint, jlong, jstring},
};
use n0_mainline::Dht;
use serde_json::{Value, json};
use tokio::sync::{Mutex as AsyncMutex, OnceCell, oneshot};

/// Events that end a streamed request.
const TERMINAL_EVENTS: [&str; 3] = ["turn.finished", "turn.stopped", "turn.failed"];

pub struct Client {
    runtime: tokio::runtime::Runtime,
    secret_bytes: [u8; 32],
    endpoint: AsyncMutex<Option<(bool, Endpoint)>>,
    relay_allowed: AtomicBool,
    dht: OnceCell<Option<Dht>>,
    connections: AsyncMutex<HashMap<EndpointId, Connection>>,
    cancels: Mutex<HashMap<String, oneshot::Sender<()>>>,
}

impl Client {
    pub fn new(secret_hex: &str) -> Option<Self> {
        let secret_bytes: [u8; 32] = HEXLOWER_PERMISSIVE
            .decode(secret_hex.trim().as_bytes())
            .ok()
            .and_then(|v| v.try_into().ok())
            .unwrap_or_else(rand::random);
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .build()
            .ok()?;
        Some(Self {
            runtime,
            secret_bytes,
            endpoint: AsyncMutex::new(None),
            relay_allowed: AtomicBool::new(false),
            dht: OnceCell::new(),
            connections: AsyncMutex::new(HashMap::new()),
            cancels: Mutex::new(HashMap::new()),
        })
    }

    pub fn identity(&self) -> Value {
        let secret = SecretKey::from_bytes(&self.secret_bytes);
        json!({
            "ok": true,
            "endpoint_id": secret.public().to_string(),
            "secret_key_hex": HEXLOWER.encode(&self.secret_bytes),
        })
    }

    async fn get_endpoint(&self) -> Result<Endpoint, String> {
        let want_relay = self.relay_allowed.load(Ordering::Relaxed);
        let mut slot = self.endpoint.lock().await;
        if let Some((built_with, ep)) = slot.as_ref()
            && *built_with == want_relay
        {
            return Ok(ep.clone());
        }
        if let Some((_, stale)) = slot.take() {
            self.connections.lock().await.clear();
            stale.close().await;
        }
        let mode = if want_relay { RelayMode::Default } else { RelayMode::Disabled };
        let fresh = Endpoint::builder(presets::N0)
            .secret_key(SecretKey::from_bytes(&self.secret_bytes))
            .alpns(vec![AGY_ALPN.to_vec()])
            .relay_mode(mode)
            .bind()
            .await
            .map_err(|e| format!("Failed to bind Iroh QUIC endpoint: {e}"))?;
        *slot = Some((want_relay, fresh.clone()));
        Ok(fresh)
    }

    async fn connect(&self, ticket: &PairingTicket, timeout: Duration) -> Result<Connection, String> {
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
        let with_relay = self.relay_allowed.load(Ordering::Relaxed);
        let initial = ticket.to_endpoint_addr(None, with_relay)?;
        let fast = timeout.min(Duration::from_millis(3200));
        let conn = match tokio::time::timeout(fast, endpoint.connect(initial, AGY_ALPN)).await {
            Ok(Ok(c)) => c,
            _ => {
                let dht = self.dht.get_or_init(|| async { Dht::client().ok() }).await.as_ref();
                let record = match dht {
                    Some(dht) => tokio::time::timeout(
                        Duration::from_millis(3500),
                        resolve_dht_endpoint(dht, &target_id),
                    )
                    .await
                    .ok()
                    .flatten(),
                    None => None,
                };
                let addr = if record.is_some() {
                    ticket.to_endpoint_addr(record.as_ref(), with_relay)?
                } else {
                    EndpointAddr::new(target_id)
                };
                tokio::time::timeout(timeout, endpoint.connect(addr, AGY_ALPN))
                    .await
                    .map_err(|_| "P2P connection timed out".to_string())?
                    .map_err(|e| format!("P2P QUIC dial failed: {e}"))?
            }
        };
        if conn.remote_id() != target_id {
            return Err("Peer EndpointId mismatch".to_string());
        }
        self.connections.lock().await.insert(target_id, conn.clone());
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

    async fn invalidate(&self, ticket: &PairingTicket) {
        if let Ok(id) = ticket.parsed_endpoint_id() {
            self.connections.lock().await.remove(&id);
        }
    }

    /// One request, one reply frame. The reply gets `_p2p` telemetry (`rtt_ms`, `transport`).
    pub fn request(&self, raw_ticket: &str, method: &str, params: Value, timeout_ms: u32) -> Value {
        let ticket = match PairingTicket::parse_any(raw_ticket) {
            Ok(t) => t,
            Err(e) => return transport_error(&e),
        };
        let budget = Duration::from_millis(u64::from(timeout_ms.max(1500)));
        let began = Instant::now();
        self.runtime.block_on(async {
            let mut failure = String::from("Failed to complete P2P request");
            for _ in 0..2 {
                let conn = match self.connect(&ticket, budget).await {
                    Ok(c) => c,
                    Err(e) => {
                        failure = e;
                        continue;
                    }
                };
                let envelope = ClientEnvelope {
                    auth_token: ticket.auth_token.clone(),
                    request_id: format!("rpc-{}", rand::random::<u64>()),
                    method: method.to_string(),
                    params: params.clone(),
                };
                let exchange = async {
                    let (mut tx, mut rx) =
                        conn.open_bi().await.map_err(|e| format!("QUIC open_bi error: {e}"))?;
                    write_json_frame(&mut tx, &envelope).await?;
                    let _ = tx.finish();
                    let bytes = read_raw_frame(&mut rx)
                        .await?
                        .ok_or_else(|| "Empty response from PC connector".to_string())?;
                    let mut reply: Value = serde_json::from_slice(&bytes)
                        .map_err(|e| format!("Invalid response JSON: {e}"))?;
                    if let Some(obj) = reply.as_object_mut() {
                        let ms = began.elapsed().as_millis() as u64;
                        obj.insert("_p2p".into(), agy_p2p_common::path_telemetry(&conn, ms));
                    }
                    Ok::<Value, String>(reply)
                };
                match tokio::time::timeout(budget, exchange).await {
                    Ok(Ok(v)) => return v,
                    Ok(Err(e)) => failure = e,
                    Err(_) => {
                        failure = "RPC timed out over P2P QUIC".into();
                        // No reply in time: the computer is likely gone (e.g. agy-connector restarted).
                        // Closing the connection also ends turn streams waiting on it, instead of
                        // leaving them open until the QUIC idle timeout.
                        self.close_if_current(&conn).await;
                    }
                }
                self.invalidate(&ticket).await;
            }
            transport_error(&failure)
        })
    }

    /// Streams a method's event frames to `emit` until a terminal event, the stream closing,
    /// or `cancel(request_id)`. Returns 0 on a clean end, non-zero on a transport error (which
    /// is also emitted as a `transport.error` event).
    pub fn stream(
        &self,
        raw_ticket: &str,
        request_id: &str,
        method: &str,
        params: Value,
        mut emit: impl FnMut(&Value),
    ) -> i32 {
        let ticket = match PairingTicket::parse_any(raw_ticket) {
            Ok(t) => t,
            Err(e) => {
                emit(&json!({ "event": "transport.error", "message": e }));
                return 2;
            }
        };
        let (stop_tx, mut stop_rx) = oneshot::channel::<()>();
        if let Ok(mut map) = self.cancels.lock() {
            map.insert(request_id.to_string(), stop_tx);
        }
        let began = Instant::now();
        let dial = Duration::from_secs(10);
        let outcome: Result<(), String> = self.runtime.block_on(async {
            let mut conn = self.connect(&ticket, dial).await?;
            let (mut tx, mut rx) = match conn.open_bi().await {
                Ok(pair) => pair,
                Err(_) => {
                    self.invalidate(&ticket).await;
                    conn = self.connect(&ticket, dial).await?;
                    conn.open_bi().await.map_err(|e| format!("QUIC open_bi error: {e}"))?
                }
            };
            let ms = began.elapsed().as_millis() as u64;
            emit(&json!({ "event": "transport.connected", "_p2p": agy_p2p_common::path_telemetry(&conn, ms) }));
            let envelope = ClientEnvelope {
                auth_token: ticket.auth_token.clone(),
                request_id: request_id.to_string(),
                method: method.to_string(),
                params,
            };
            write_json_frame(&mut tx, &envelope).await?;
            let _ = tx.finish();
            loop {
                let frame = tokio::select! {
                    _ = &mut stop_rx => return Ok(()),
                    f = read_raw_frame(&mut rx) => f?,
                };
                let Some(bytes) = frame else { return Ok(()) };
                let Ok(event) = serde_json::from_slice::<Value>(&bytes) else { continue };
                emit(&event);
                let name = event.get("event").and_then(Value::as_str).unwrap_or("");
                let refused = event.get("ok").and_then(Value::as_bool) == Some(false);
                if refused || TERMINAL_EVENTS.contains(&name) {
                    return Ok(());
                }
            }
        });
        if let Ok(mut map) = self.cancels.lock() {
            map.remove(request_id);
        }
        match outcome {
            Ok(()) => 0,
            Err(message) => {
                emit(&json!({ "event": "transport.error", "message": message }));
                1
            }
        }
    }

    /// Stops reading a stream locally. Stopping the turn itself is `turn.stop` (a normal request).
    pub fn cancel(&self, request_id: &str) {
        if let Ok(mut map) = self.cancels.lock()
            && let Some(tx) = map.remove(request_id)
        {
            let _ = tx.send(());
        }
    }

    fn shutdown(self) {
        let ep = self.endpoint.try_lock().ok().and_then(|g| g.as_ref().map(|(_, e)| e.clone()));
        if let Some(ep) = ep {
            self.runtime.block_on(async move { ep.close().await });
        }
    }
}

fn transport_error(message: &str) -> Value {
    json!({ "ok": false, "error": { "code": "transport", "message": message } })
}

// MARK: - JNI (class moe.n4tsu.agyremote.net.NativeP2P)

fn jstr(env: &mut JNIEnv, s: &JString) -> String {
    if s.is_null() {
        return String::new();
    }
    env.get_string(s).map(|s| s.into()).unwrap_or_default()
}

fn out(env: &mut JNIEnv, v: &Value) -> jstring {
    env.new_string(v.to_string()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

fn client<'a>(handle: jlong) -> Option<&'a Client> {
    if handle == 0 { None } else { Some(unsafe { &*(handle as *const Client) }) }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_moe_n4tsu_agyremote_net_NativeP2P_nativeCreate(
    mut env: JNIEnv,
    _: JClass,
    secret_hex: JString,
) -> jlong {
    let hex = jstr(&mut env, &secret_hex);
    match Client::new(&hex) {
        Some(c) => Box::into_raw(Box::new(c)) as jlong,
        None => 0,
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_moe_n4tsu_agyremote_net_NativeP2P_nativeDestroy(_: JNIEnv, _: JClass, handle: jlong) {
    if handle != 0 {
        let boxed = unsafe { Box::from_raw(handle as *mut Client) };
        boxed.shutdown();
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_moe_n4tsu_agyremote_net_NativeP2P_nativeSetRelayAllowed(
    _: JNIEnv,
    _: JClass,
    handle: jlong,
    allowed: jboolean,
) {
    if let Some(c) = client(handle) {
        c.relay_allowed.store(allowed != 0, Ordering::Relaxed);
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_moe_n4tsu_agyremote_net_NativeP2P_nativeIdentityJson(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
) -> jstring {
    let v = client(handle).map(Client::identity).unwrap_or_else(|| transport_error("null handle"));
    out(&mut env, &v)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_moe_n4tsu_agyremote_net_NativeP2P_nativeParseTicket(
    mut env: JNIEnv,
    _: JClass,
    raw: JString,
) -> jstring {
    let text = jstr(&mut env, &raw);
    let reply = match PairingTicket::parse_any(&text) {
        Ok(t) => json!({ "ok": true, "uri": t.to_uri(), "ticket": t }),
        Err(e) => json!({ "ok": false, "error": e }),
    };
    out(&mut env, &reply)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_moe_n4tsu_agyremote_net_NativeP2P_nativeRequest(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    ticket: JString,
    method: JString,
    params: JString,
    timeout_ms: jint,
) -> jstring {
    let ticket = jstr(&mut env, &ticket);
    let method = jstr(&mut env, &method);
    let params: Value = serde_json::from_str(&jstr(&mut env, &params)).unwrap_or_else(|_| json!({}));
    let v = match client(handle) {
        Some(c) => c.request(&ticket, &method, params, timeout_ms.max(0) as u32),
        None => transport_error("null handle"),
    };
    out(&mut env, &v)
}

/// Blocks the calling (background) thread; calls `listener.onEvent(String)` for every frame.
#[unsafe(no_mangle)]
pub extern "system" fn Java_moe_n4tsu_agyremote_net_NativeP2P_nativeStream(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    ticket: JString,
    request_id: JString,
    method: JString,
    params: JString,
    listener: JObject,
) -> jint {
    let ticket = jstr(&mut env, &ticket);
    let request_id = jstr(&mut env, &request_id);
    let method = jstr(&mut env, &method);
    let params: Value = serde_json::from_str(&jstr(&mut env, &params)).unwrap_or_else(|_| json!({}));
    let Some(c) = client(handle) else { return -1 };
    // `emit` runs on this same thread (block_on), so the env stays valid.
    c.stream(&ticket, &request_id, &method, params, |val| {
        if let Ok(s) = env.new_string(val.to_string()) {
            let obj = JObject::from(s);
            let _ = env.call_method(&listener, "onEvent", "(Ljava/lang/String;)V", &[JValue::Object(&obj)]);
            let _ = env.delete_local_ref(obj);
            if env.exception_check().unwrap_or(false) {
                let _ = env.exception_clear();
            }
        }
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_moe_n4tsu_agyremote_net_NativeP2P_nativeCancel(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    request_id: JString,
) {
    let id = jstr(&mut env, &request_id);
    if let Some(c) = client(handle) {
        c.cancel(&id);
    }
}
