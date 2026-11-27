package moe.n4tsu.agyremote.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

sealed class Result<out T> {
    data class Ok<T>(val value: T, val telemetry: Telemetry?) : Result<T>()
    data class Err(val error: ApiError) : Result<Nothing>()
}

/** Typed protocol-v1 calls to one computer (identified by its pairing ticket). */
class ConnectorClient(private val transport: P2PTransport, val ticket: String) {

    /** Any request; the timeout defaults to the per-method one (iOS P2PClient.timeoutMs). */
    suspend fun call(method: String, params: JSONObject = JSONObject(), timeoutMs: Int = timeoutFor(method)): Result<JSONObject> =
        decoded(method, params, timeoutMs) { it }

    /**
     * A request whose reply is turned into [T] on a background thread (never the main one).
     * At most [PER_COMPUTER_LIMIT] blocking FFI calls run per computer, so one slow host
     * can't starve the pool (iOS GateTable).
     */
    suspend fun <T> decoded(method: String, params: JSONObject = JSONObject(), timeoutMs: Int = timeoutFor(method), decode: (JSONObject) -> T): Result<T> =
        gate(ticket).withPermit {
            withContext(Dispatchers.IO) {
                val reply = runCatching { transport.request(ticket, method, params, timeoutMs) }
                    .getOrElse { JSONObject().put("ok", false).put("error", JSONObject().put("code", "transport").put("message", it.message)) }
                ApiError.from(reply)?.let { Result.Err(it) }
                    ?: runCatching { Result.Ok(decode(reply), Telemetry.from(reply.optJSONObject("_p2p"))) }
                        .getOrElse { Result.Err(ApiError("internal", "Invalid reply")) }
            }
        }

    private inline fun <T> Result<JSONObject>.map(f: (JSONObject) -> T): Result<T> = when (this) {
        is Result.Ok -> Result.Ok(f(value), telemetry)
        is Result.Err -> this
    }

    suspend fun info() = decoded("connector.info", decode = ConnectorInfo::from)

    suspend fun chats() = decoded("chats.list") { o ->
        val arr = o.optJSONArray("chats") ?: JSONArray()
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(ChatSummary::from) }
    }

    suspend fun open(chatId: String) = decoded("chat.open", JSONObject().put("chatId", chatId)) { OpenedChat.from(it, chatId) }

    suspend fun setFolder(chatId: String, folder: String) = call("chat.set_folder", JSONObject().put("chatId", chatId).put("folder", folder)).map { }

    suspend fun setOption(chatId: String?, option: String, value: String) =
        call("chat.set_option", JSONObject().put("chatId", chatId ?: JSONObject.NULL).put("option", option).put("value", value))
            .map { Options.from(it.optJSONObject("options")) }

    suspend fun stop(chatId: String) = call("turn.stop", JSONObject().put("chatId", chatId)).map { }

    suspend fun answer(approvalId: String, choice: String) =
        call("approval.answer", JSONObject().put("approvalId", approvalId).put("choice", choice)).map { }

    /** Answers to an `ask_question` form; null skips it. */
    suspend fun answerQuestion(approvalId: String, answers: JSONObject?) =
        call("question.answer", JSONObject().put("approvalId", approvalId).also { p ->
            if (answers != null) p.put("answers", answers) else p.put("skip", true)
        }).map { }

    suspend fun searchFiles(folder: String, query: String) = call("files.search", JSONObject().put("folder", folder).put("query", query)).map { o ->
        val arr = o.optJSONArray("files") ?: JSONArray()
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { FileHit(it.optString("path"), it.optLong("size")) }
    }

    suspend fun limits(refresh: Boolean) = decoded("limits.get", JSONObject().put("refresh", refresh), decode = Limits::from)

    suspend fun unregisterNotifications() = call("notifications.unregister").map { }

    /** `turn.start`: a stream of event frames (each has `event`), ending with turn.finished/stopped/failed. */
    fun startTurn(
        chatId: String?, folder: String?, text: String, attachments: List<Attachment>, options: Options,
        requestId: String = UUID.randomUUID().toString(),
    ): Flow<JSONObject> = startTurn(requestId, JSONObject()
        .put("chatId", chatId ?: JSONObject.NULL)
        .put("folder", folder ?: JSONObject.NULL)
        .put("text", text)
        .put("attachments", JSONArray(attachments.map { it.json() }))
        .put("options", options.json()))

    /** `turn.start` with ready-made params; cancelling the flow only stops listening. */
    fun startTurn(requestId: String, params: JSONObject): Flow<JSONObject> = callbackFlow {
        val thread = Thread {
            transport.stream(ticket, requestId, "turn.start", params) { trySend(it) }
            channel.close()
        }
        thread.start()
        awaitClose { transport.cancel(requestId) }
    }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)

    /** Stops listening to a stream; the turn goes on on the computer. */
    fun detach(requestId: String) = transport.cancel(requestId)

    companion object {
        /** Blocking FFI requests allowed at once per computer (iOS GateTable(limit: 3)). */
        const val PER_COMPUTER_LIMIT = 3
        private val gates = ConcurrentHashMap<String, Semaphore>()
        private fun gate(ticket: String): Semaphore = gates.getOrPut(ticket) { Semaphore(PER_COMPUTER_LIMIT) }

        /** How long each method may take (iOS P2PClient.timeoutMs). */
        fun timeoutFor(method: String): Int = when (method) {
            "connector.info" -> 25_000
            "chat.open" -> 30_000
            "limits.get" -> 60_000
            "chat.set_option" -> 10_000
            "approval.answer" -> 5_000
            else -> 15_000
        }
    }
}

/** A checked pairing code (iOS P2PClient.Ticket): the endpoint id, the canonical URI to store,
 *  and what the ticket says about the computer. */
/** [pairingSecret] is set in a code from `agy-connector pair`: the computer's number must still be typed. */
data class PairingTicket(val endpointId: String, val uri: String, val hostName: String, val hardwareModel: String, val osVersion: String,
                         val pairingSecret: String? = null) {
    companion object {
        /** Validates a pairing code (URI or raw code); the error is the library's message or null for "invalid". */
        fun parse(transport: P2PTransport, raw: String): kotlin.Result<PairingTicket> {
            val trimmed = raw.trim()
            val o = runCatching { transport.parseTicket(trimmed) }.getOrElse { JSONObject() }
            val t = o.optJSONObject("ticket")
            if (!o.optBoolean("ok") || t == null) {
                return kotlin.Result.failure(IllegalArgumentException((o.opt("error") as? String)?.ifEmpty { null }))
            }
            val endpoint = t.optString("endpoint_id").ifEmpty { t.optString("endpointId") }.trim()
            // An endpoint id is a public key: hex or base32, never short, never with spaces.
            if (endpoint.length < 32 || !endpoint.all { it.isLetterOrDigit() }) return kotlin.Result.failure(IllegalArgumentException())
            val canonical = o.optString("uri").ifEmpty { trimmed }
            val secret = t.optString("pairing_secret").ifEmpty { null }
            return kotlin.Result.success(PairingTicket(endpoint, canonical, t.optString("host_name"), t.optString("hardware_model"), t.optString("os_version"), secret))
        }
    }
}
