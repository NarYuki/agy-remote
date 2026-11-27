package moe.n4tsu.agyremote.net

import org.json.JSONObject

/**
 * The transport the app talks to agy-connector through (iroh QUIC, see docs/protocol.md).
 * One request = one JSON frame out, one back; a stream = one frame out, event frames back.
 */
interface P2PTransport {
    /** `{"endpoint_id": "...", "secret_key_hex": "..."}` of this phone. */
    fun identity(): JSONObject
    fun parseTicket(raw: String): JSONObject
    fun setRelayAllowed(allowed: Boolean)

    /** Blocking; call from a background dispatcher. */
    fun request(ticket: String, method: String, params: JSONObject, timeoutMs: Int = 15_000): JSONObject

    /** Blocking until the stream ends; [onEvent] runs on the calling thread. Returns 0 on a clean end. */
    fun stream(ticket: String, requestId: String, method: String, params: JSONObject, onEvent: (JSONObject) -> Unit): Int
    fun cancel(requestId: String)
    fun close()
}

/** JNI bindings of `server/agy-p2p-jni` (libagy_p2p_jni.so). */
object NativeP2P {
    fun interface Listener {
        fun onEvent(json: String)
    }

    val isAvailable: Boolean = try {
        System.loadLibrary("agy_p2p_jni"); true
    } catch (_: Throwable) {
        false
    }

    @JvmStatic external fun nativeCreate(secretHex: String?): Long
    @JvmStatic external fun nativeDestroy(handle: Long)
    @JvmStatic external fun nativeSetRelayAllowed(handle: Long, allowed: Boolean)
    @JvmStatic external fun nativeIdentityJson(handle: Long): String
    @JvmStatic external fun nativeParseTicket(raw: String): String
    @JvmStatic external fun nativeRequest(handle: Long, ticket: String, method: String, params: String, timeoutMs: Int): String
    @JvmStatic external fun nativeStream(handle: Long, ticket: String, requestId: String, method: String, params: String, listener: Listener): Int
    @JvmStatic external fun nativeCancel(handle: Long, requestId: String)
}

class NativeTransport(secretHex: String?) : P2PTransport {
    private var handle: Long = NativeP2P.nativeCreate(secretHex)

    override fun identity() = JSONObject(NativeP2P.nativeIdentityJson(handle))
    override fun parseTicket(raw: String) = JSONObject(NativeP2P.nativeParseTicket(raw))
    override fun setRelayAllowed(allowed: Boolean) = NativeP2P.nativeSetRelayAllowed(handle, allowed)

    override fun request(ticket: String, method: String, params: JSONObject, timeoutMs: Int): JSONObject =
        JSONObject(NativeP2P.nativeRequest(handle, ticket, method, params.toString(), timeoutMs))

    override fun stream(ticket: String, requestId: String, method: String, params: JSONObject, onEvent: (JSONObject) -> Unit): Int =
        NativeP2P.nativeStream(handle, ticket, requestId, method, params.toString()) { json ->
            runCatching { JSONObject(json) }.getOrNull()?.let(onEvent)
        }

    override fun cancel(requestId: String) = NativeP2P.nativeCancel(handle, requestId)

    override fun close() {
        if (handle != 0L) NativeP2P.nativeDestroy(handle)
        handle = 0
    }
}

/** Used when the native library isn't packaged (e.g. built without the NDK): every call fails cleanly. */
class UnavailableTransport : P2PTransport {
    private fun err() = JSONObject().put("ok", false)
        .put("error", JSONObject().put("code", "transport").put("message", "P2P library not built (see android/README.md)"))

    override fun identity() = JSONObject().put("ok", false)
    override fun parseTicket(raw: String) = JSONObject().put("ok", false).put("error", "P2P library not built (see android/README.md)")
    override fun setRelayAllowed(allowed: Boolean) {}
    override fun request(ticket: String, method: String, params: JSONObject, timeoutMs: Int) = err()
    override fun stream(ticket: String, requestId: String, method: String, params: JSONObject, onEvent: (JSONObject) -> Unit): Int {
        onEvent(JSONObject().put("event", "transport.error").put("message", "P2P library not built"))
        return 1
    }
    override fun cancel(requestId: String) {}
    override fun close() {}
}

/**
 * One P2P client for the whole process. A ViewModel can be cleared (back, app switch) while its IO
 * threads are still inside a blocking native call, so the client is never freed while the app runs;
 * a recreated ViewModel picks up the same one.
 */
object SharedTransport {
    @Volatile private var instance: P2PTransport? = null

    fun get(secretHex: String?): P2PTransport = instance ?: synchronized(this) {
        instance ?: (if (NativeP2P.isAvailable) NativeTransport(secretHex) else UnavailableTransport()).also { instance = it }
    }
}
