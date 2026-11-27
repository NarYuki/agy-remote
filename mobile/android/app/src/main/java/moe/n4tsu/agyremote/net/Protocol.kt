package moe.n4tsu.agyremote.net

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Protocol v1 (docs/protocol.md) data types. */
const val SUPPORTED_PROTOCOL = 1

private fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key, "").takeIf { has(key) }
private inline fun <T> JSONArray?.mapObjects(f: (JSONObject) -> T): List<T> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it)?.let(f) }

data class ApiError(val code: String, val message: String) {
    companion object {
        fun from(obj: JSONObject): ApiError? {
            if (obj.optBoolean("ok", false)) return null
            return when (val e = obj.opt("error")) {
                is JSONObject -> ApiError(e.optString("code", "internal"), e.optString("message"))
                is String -> ApiError("transport", e)
                else -> ApiError("internal", "")
            }
        }
    }
}

data class Telemetry(val rttMs: Int, val transport: String) {
    companion object {
        fun from(obj: JSONObject?): Telemetry? = obj?.let { Telemetry(it.optInt("rtt_ms"), it.optString("transport")) }
    }
}

/** A folder on a paired computer. [host] is that computer's endpoint id; [synthesized] marks a
 *  folder the connector didn't list but a chat uses (iOS WorkspaceFolder). */
data class Folder(val path: String, val name: String, val kind: String?, val host: String = "", val synthesized: Boolean = false) {
    companion object {
        /** "/a/b/" → "/a/b", so the same folder written two ways groups together. */
        fun normalize(path: String): String {
            var p = path.trim()
            while (p.length > 1 && p.endsWith("/")) p = p.dropLast(1)
            return p
        }

        fun from(o: JSONObject): Folder? {
            val clean = normalize(o.optString("path"))
            if (clean.isEmpty()) return null
            return Folder(clean, o.str("name")?.ifEmpty { null } ?: clean.substringAfterLast('/').ifEmpty { clean }, o.str("kind"))
        }
    }
}

/** ISO-8601 → epoch ms (0 when missing or unreadable). */
fun parseIsoMillis(s: String?): Long =
    if (s.isNullOrEmpty()) 0L else runCatching { java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() }
        .recoverCatching { java.time.Instant.parse(s).toEpochMilli() }.getOrDefault(0L)
data class Effort(val id: String, val name: String)
data class Model(val id: String, val name: String, val efforts: List<Effort>) {
    val shortName: String
        get() {
            val gap = name.indexOf(' ')
            if (gap < 0) return name
            val brand = name.substring(0, gap).lowercase()
            return if (brand in VENDOR_WORDS) name.substring(gap + 1) else name
        }

    private companion object {
        val VENDOR_WORDS: Set<String> = "gemini claude gpt openai anthropic google".split(' ').toHashSet()
    }
}
data class Mode(val id: String, val name: String, val description: String?) {
    companion object {
        /** agy's modes (and the connector's Turbo) in the phone's language: id → (name, description). Set at startup. */
        @Volatile var known: Map<String, Pair<String, String>> = emptyMap()

        fun from(id: String, name: String, description: String?): Mode =
            known[id]?.let { (n, d) -> Mode(id, n, d) } ?: Mode(id, name, description)
    }
}
data class Command(val name: String, val description: String?)
data class Options(val model: String?, val effort: String?, val mode: String?) {
    fun json() = JSONObject().put("model", model ?: JSONObject.NULL).put("effort", effort ?: JSONObject.NULL).put("mode", mode ?: JSONObject.NULL)
    companion object {
        fun from(o: JSONObject?) = Options(o?.str("model"), o?.str("effort"), o?.str("mode"))
    }
}

data class ConnectorInfo(
    val protocol: Int,
    val computerName: String,
    val computerModel: String,
    val os: String,
    val agyVersion: String,
    val connectorVersion: String,
    val minAppVersion: String?,
    val updateNote: String?,
    val folders: List<Folder>,
    val models: List<Model>,
    val modes: List<Mode>,
    val defaults: Options,
    val commands: List<Command>,
    /** The `protocol` number, or null when the connector didn't send one. */
    val protocolOrNull: Int? = null,
    /** Says nothing about its version or protocol: predates protocol 1 (iOS versionMissing). */
    val versionMissing: Boolean = false,
    /** RTT reported with the reply, if any. */
    val telemetry: Telemetry? = null,
    /** The computer's push relay and the key it signs with there, for asking that relay for a grant. */
    val pushRelay: String? = null,
    val pushKey: String? = null,
) {
    companion object {
        fun from(o: JSONObject): ConnectorInfo {
            val computer = o.optJSONObject("computer") ?: JSONObject()
            val agy = o.optJSONObject("agy") ?: JSONObject()
            val connector = o.optJSONObject("connector") ?: JSONObject()
            return ConnectorInfo(
                protocol = o.optInt("protocol", 0),
                computerName = computer.optString("name"),
                computerModel = computer.optString("model"),
                os = computer.optString("os"),
                agyVersion = agy.optString("version"),
                connectorVersion = connector.optString("version"),
                minAppVersion = connector.str("minAppVersion"),
                pushRelay = connector.optJSONObject("push")?.str("relay"),
                pushKey = connector.optJSONObject("push")?.str("key"),
                updateNote = connector.str("updateNote"),
                folders = o.optJSONArray("folders").mapObjects { Folder.from(it) }.filterNotNull(),
                models = o.optJSONArray("models").mapObjects { m ->
                    Model(m.optString("id"), m.optString("name"), m.optJSONArray("efforts").mapObjects { Effort(it.optString("id"), it.optString("name")) })
                },
                modes = o.optJSONArray("modes").mapObjects { Mode.from(it.optString("id"), it.optString("name"), it.str("description")) },
                defaults = Options.from(o.optJSONObject("defaults")),
                commands = o.optJSONArray("commands").mapObjects { Command(it.optString("name"), it.str("description")) },
                protocolOrNull = if (o.has("protocol") && !o.isNull("protocol")) o.optInt("protocol") else null,
                versionMissing = !connector.has("version") && !o.has("protocol"),
                telemetry = Telemetry.from(o.optJSONObject("_p2p")),
            )
        }
    }
}

enum class ChatState { IDLE, RUNNING, OPEN_IN_CLI;
    companion object {
        fun from(s: String?) = when (s) { "running" -> RUNNING; "open_in_cli" -> OPEN_IN_CLI; else -> IDLE }
    }
}

/** A chat; [host] is the endpoint id of the computer it lives on, [updatedAt] epoch ms of [updated]. */
data class ChatSummary(
    val id: String, val title: String, val folder: String?, val updated: String, val state: ChatState,
    val host: String = "", val updatedAt: Long = parseIsoMillis(updated),
    /** The connector's revision of the transcript, when it sends one. */
    val revision: String? = null,
) {
    /** Changes whenever the chat's transcript may have changed (iOS changeStamp). */
    val changeStamp: String get() = "$updatedAt|${revision.orEmpty()}"

    companion object {
        fun from(o: JSONObject): ChatSummary? {
            val id = o.str("id")?.takeIf { it.isNotEmpty() } ?: return null
            val rev = if (o.has("revision") && !o.isNull("revision")) o.opt("revision")?.toString() else null
            return ChatSummary(id, o.optString("title"),
                o.str("folder")?.let(Folder::normalize)?.ifEmpty { null }, o.optString("updated"), ChatState.from(o.str("state")),
                revision = rev)
        }
    }
}

data class Step(
    val id: String, val kind: String, val title: String, val status: String, val detail: String?,
    /** Reply text (UTF-16 units) that came before this step: where it goes between the text. */
    val at: Int? = null,
    /** The file it touched, and for edits the lines that came and went. */
    val path: String? = null, val added: Int? = null, val removed: Int? = null,
) {
    val isRunning: Boolean get() = status != "done" && status != "failed"

    /** Field-by-field update from a `turn.step` frame: only the keys it carries change (iOS). */
    fun merged(o: JSONObject): Step = copy(
        title = o.str("title")?.takeIf { it.isNotEmpty() } ?: title,
        kind = if (o.has("kind")) o.optString("kind", kind) else kind,
        status = if (o.has("status")) o.optString("status", status) else status,
        detail = o.str("detail") ?: detail,
        path = o.str("path") ?: path,
        added = if (o.has("added")) o.optInt("added") else added,
        removed = if (o.has("removed")) o.optInt("removed") else removed,
    )

    companion object {
        fun from(o: JSONObject) = Step(
            o.optString("id"), o.optString("kind", "other"), o.optString("title"), o.optString("status", "done"), o.str("detail"),
            at = if (o.has("at")) o.optInt("at") else null, path = o.str("path"),
            added = if (o.has("added")) o.optInt("added") else null, removed = if (o.has("removed")) o.optInt("removed") else null,
        )
    }
}

data class PlanEntry(val text: String, val status: String) {
    companion object {
        fun from(o: JSONObject) = PlanEntry(o.optString("text"), o.optString("status", "pending"))
    }
}

data class Choice(val id: String, val label: String, val effect: String)

data class Approval(
    val id: String,
    val what: String,
    val title: String,
    val command: String?,
    val path: String?,
    val before: String?,
    val after: String?,
    val choices: List<Choice>,
    /** Set locally once answered. */
    val chosen: String? = null,
    /** The computer no longer knows this question (answer came back not_found). */
    val stale: Boolean = false,
    /** Set when agy asks the user (`ask_question`): answered with options, not allow / deny. */
    val questions: List<FormQuestion> = emptyList(),
) {
    val isQuestion: Boolean get() = questions.isNotEmpty()

    companion object {
        fun from(o: JSONObject) = Approval(
            o.optString("id"), o.optString("what", "other"), o.optString("title"),
            o.str("command"), o.str("path"), o.str("before"), o.str("after"),
            o.optJSONArray("choices").mapObjects { Choice(it.optString("id"), it.optString("label"), it.optString("effect")) },
            // An answered question kept in a transcript shows the choice made.
            chosen = o.str("answer"),
            questions = if (o.optString("what") != "question") emptyList() else o.optJSONArray("questions").mapObjects { q ->
                FormQuestion(q.optString("key"), q.optString("question"), q.optBoolean("multi"),
                    q.optJSONArray("options").mapObjects { FormQuestion.Option(it.optString("id"), it.optString("label")) },
                    q.str("other"))
            },
        )
    }
}

/** One question of an `ask_question` form: its options, and the key for a written answer. */
data class FormQuestion(val key: String, val question: String, val multi: Boolean, val options: List<Option>, val otherKey: String?) {
    data class Option(val id: String, val label: String)
}

/**
 * An attachment. On the wire: an image carries base64 [data]; a workspace file carries [path].
 * Composer-only fields: [preview] (small JPEG for the strip and bubble) and [bytes] (a non-image
 * file picked on the phone; text ones are put into the prompt when sent).
 */
data class Attachment(
    val kind: String, val name: String, val mime: String? = null, val data: String? = null, val path: String? = null,
    val id: String = UUID.randomUUID().toString(), val preview: ByteArray? = null, val bytes: ByteArray? = null,
) {
    val isWorkspaceFile: Boolean get() = path != null

    /** Phone files that are neither images nor UTF-8 text can't be sent (iOS isSendable). */
    val isSendable: Boolean get() = when {
        path != null || kind == "image" -> true
        bytes == null -> true
        else -> utf8Text != null
    }

    /** The phone file's text, when it is valid UTF-8. */
    val utf8Text: String? get() = bytes?.let {
        runCatching { java.nio.charset.StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(it)).toString() }.getOrNull()
    }

    /** Can go out again in a rerun (has content or a path). */
    val isResendable: Boolean get() = path != null || bytes != null || (kind == "image" && data != null)

    fun json(): JSONObject = JSONObject().put("kind", kind).apply {
        if (kind == "image") put("name", name).put("mime", mime).put("data", data) else put("path", path ?: name)
    }
}

data class Message(
    val id: String,
    val fromUser: Boolean,
    val text: String,
    val attachments: List<Attachment> = emptyList(),
    val thinking: String? = null,
    val steps: List<Step> = emptyList(),
    val plan: List<PlanEntry> = emptyList(),
    val approvals: List<Approval> = emptyList(),
    val seconds: Double? = null,
    val live: Boolean = false,
    /** What a live agent message is doing ("Thinking", "Responding", a step title…); "" when done. */
    val statusLabel: String = "",
) {
    companion object {
        /** A transcript; messages without an id get `<chat>-<index>` so reloads match (iOS). */
        fun list(arr: JSONArray?, chatId: String): List<Message> =
            if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { from(it, "$chatId-$i") } }

        fun from(o: JSONObject, fallbackId: String? = null): Message {
            val id = o.str("id")?.ifEmpty { null } ?: fallbackId ?: UUID.randomUUID().toString()
            val atts = o.optJSONArray("attachments")
            return from(o, id, if (atts == null) emptyList() else (0 until atts.length()).mapNotNull { i ->
                atts.optJSONObject(i)?.let { a -> Attachment(if (a.optString("kind") == "image") "image" else "file", a.optString("name"), id = "$id-a$i") }
            })
        }

        private fun from(o: JSONObject, id: String, attachments: List<Attachment>) = Message(
            id = id,
            fromUser = o.optString("from") == "user",
            text = o.optString("text"),
            attachments = attachments,
            thinking = o.str("thinking"),
            steps = o.optJSONArray("steps").mapObjects(Step::from),
            plan = o.optJSONArray("plan").mapObjects(PlanEntry::from),
            approvals = o.optJSONArray("approvals").mapObjects(Approval::from),
            seconds = if (o.has("seconds") && !o.isNull("seconds")) o.optDouble("seconds") else null,
            live = o.optBoolean("live", false),
        ).let { m -> if (m.live) m.copy(statusLabel = StatusText.live(m)) else m }
    }
}

/** Status lines for live messages (iOS ProtocolV1.readableStatus / liveStatus). */
object StatusText {
    /** Localised words, set once by the app (defaults are English). */
    @Volatile var words: Map<String, String> = emptyMap()
    private fun w(key: String, fallback: String) = words[key] ?: fallback

    fun step(s: Step): String {
        val t = s.title.trim()
        if (t.isNotEmpty()) return if (t.length > 38) t.take(37) + "…" else t
        return when (s.kind) {
            "read" -> w("reading", "Reading")
            "edit", "delete", "move" -> w("editing", "Editing")
            "search" -> w("searching", "Searching")
            "execute", "run" -> w("running", "Running")
            "fetch" -> w("fetching", "Fetching")
            "think" -> w("thinking", "Thinking")
            else -> w("working", "Working")
        }
    }

    fun live(m: Message): String {
        m.approvals.firstOrNull { it.chosen == null }?.let {
            return if (it.isQuestion) w("answer", "Waiting for your answer") else w("waiting", "Waiting for approval")
        }
        m.steps.lastOrNull { it.isRunning }?.let { return step(it) }
        if (m.text.isNotEmpty()) return w("responding", "Responding")
        return w("thinking", "Thinking")
    }
}

data class ContextUsage(val used: Long, val size: Long) {
    val fraction: Float get() = if (size <= 0) 0f else (used.toFloat() / size).coerceIn(0f, 1f)
}

data class OpenedChat(
    val chatId: String,
    val state: ChatState,
    val readable: Boolean,
    val unreadableReason: String?,
    val context: ContextUsage?,
    val options: Options,
    val messages: List<Message>,
) {
    companion object {
        fun from(o: JSONObject) = from(o, o.optString("chatId"))

        /** Decodes a `chat.open` reply; fallback message ids use [chatId]. */
        fun from(o: JSONObject, chatId: String) = OpenedChat(
            o.optString("chatId").ifEmpty { chatId }, ChatState.from(o.str("state")), o.optBoolean("readable", true), o.str("unreadableReason"),
            o.optJSONObject("context")?.let { ContextUsage(it.optLong("used"), it.optLong("size")) },
            Options.from(o.optJSONObject("options")),
            Message.list(o.optJSONArray("messages"), chatId),
        )
    }
}

data class Limit(val id: String, val group: String, val window: String, val label: String, val remaining: Double, val resets: String?)
data class Limits(val fetched: String, val limits: List<Limit>, val credits: Double?) {
    /** Header limit: the Gemini-group one with the least left; null when none is Gemini (iOS headerPlanLimit). */
    val headerLimit: Limit? get() = tightestGemini
    val fetchedAt: Long get() = parseIsoMillis(fetched)

    /** The tighter Gemini plan limit (header gauge); the tightest overall when agy lists no Gemini ones. */
    val tightestGemini: Limit?
        get() = limits.filter { it.group.contains("gemini", ignoreCase = true) }.ifEmpty { limits }.minByOrNull { it.remaining }

    companion object {
        fun from(o: JSONObject) = Limits(
            o.optString("fetched"),
            o.optJSONArray("limits").mapObjects {
                Limit(it.optString("id"), it.optString("group"), it.optString("window"), it.optString("label"), it.optDouble("remaining", 0.0), it.str("resets"))
            },
            if (o.has("credits") && !o.isNull("credits")) o.optDouble("credits") else null,
        )
    }
}

data class FileHit(val path: String, val size: Long)
