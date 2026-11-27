#ifndef AGY_P2P_CORE_H
#define AGY_P2P_CORE_H

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct AgyP2PClientHandle AgyP2PClientHandle;

typedef void (*AgyStreamEventCallback)(const char *event_json, void *user_ctx);

/// Creates a new Iroh QUIC + Mainline DHT P2P client node.
/// Pass `secret_key_hex` (64-char hex) to reuse an identity, or NULL/empty to generate one.
AgyP2PClientHandle *agy_p2p_client_create(const char *secret_key_hex);

/// Allows (or stops) using the public relay when a direct connection isn't possible.
/// Off until the user agrees; takes effect on the next connection.
void agy_p2p_set_relay_allowed(AgyP2PClientHandle *handle, bool allowed);

/// Destroys the P2P client node and closes active QUIC connections.
void agy_p2p_client_destroy(AgyP2PClientHandle *handle);

/// Returns JSON `{"endpoint_id":"...","secret_key_hex":"..."}` for this iOS client node.
/// Caller must free with `agy_p2p_free_string`.
char *agy_p2p_client_identity_json(AgyP2PClientHandle *handle);

/// Validates and parses a pairing code or stored ticket (`agy-rem://pair?...`).
/// Returns JSON `{"ok":true,"ticket":{...}}` or `{"ok":false,"error":"..."}`.
/// Caller must free with `agy_p2p_free_string`.
char *agy_p2p_parse_ticket(const char *ticket_str);

/// Performs a single authenticated RPC over Iroh QUIC P2P (a protocol v1 method from
/// docs/protocol.md, e.g. `chats.list`).
/// Returns JSON response enriched with `_p2p` telemetry (`rtt_ms`, `transport`, `remote_addr`).
/// Caller must free with `agy_p2p_free_string`.
char *agy_p2p_request_json(
    AgyP2PClientHandle *handle,
    const char *ticket_str,
    const char *method_str,
    const char *params_json_str,
    uint32_t timeout_ms
);

/// Starts a streaming `turn.start` turn over a dedicated QUIC bi-stream, invoking `callback`
/// for each incoming JSON event until completion, cancellation, or error.
/// Returns 0 on clean stream completion, non-zero on error (also emitted as an `error` event).
int32_t agy_p2p_stream_chat(
    AgyP2PClientHandle *handle,
    const char *ticket_str,
    const char *request_id_str,
    const char *params_json_str,
    AgyStreamEventCallback callback,
    void *user_ctx
);

/// Stops listening to an in-flight `agy_p2p_stream_chat` stream (the turn keeps running; send `turn.stop` to stop it).
void agy_p2p_cancel_stream(
    AgyP2PClientHandle *handle,
    const char *ticket_str,
    const char *request_id_str
);

/// Frees a C string returned by any `agy_p2p_*` function.
void agy_p2p_free_string(char *ptr);

#ifdef __cplusplus
}
#endif

#endif /* AGY_P2P_CORE_H */
