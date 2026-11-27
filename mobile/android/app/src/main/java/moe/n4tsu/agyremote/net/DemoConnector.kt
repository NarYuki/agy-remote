package moe.n4tsu.agyremote.net

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * "Demo Mac": a pretend computer for trying the app without agy-connector (Play review, a first
 * look). It is a [P2PTransport] that answers the protocol methods (docs/protocol.md) from memory,
 * so the app's normal code paths run unchanged. It never opens a socket.
 */
object DemoConnector : P2PTransport {
    /** Typed in place of a pairing code to pair the demo computer. */
    const val PAIRING_CODE = "DEMO-AGY"
    const val TICKET = "agy-demo://demo-mac"
    const val ENDPOINT_ID = "agy-demo-mac"
    const val COMPUTER_NAME = "Demo Mac"

    fun isCode(raw: String) = raw.trim().equals(PAIRING_CODE, ignoreCase = true)
    fun owns(ticket: String) = ticket == TICKET

    val pairingTicket get() = PairingTicket(ENDPOINT_ID, TICKET, COMPUTER_NAME, "Mac16,10", "macOS 26.1")

    private class Chat(
        val id: String, var title: String, var folder: String, var updated: Long,
        val messages: MutableList<JSONObject>, val options: JSONObject, var running: Boolean = false,
    )

    private const val HOME = "/Users/demo/Projects"
    private val folders = listOf("$HOME/weather-app" to "swift", "$HOME/trail-api" to "rust", "$HOME/portfolio-site" to "node")
    private val lock = Any()
    private val chats: MutableList<Chat> = seed()
    private val defaults = JSONObject().put("model", "neon-4").put("effort", "high").put("mode", "default")
    @Volatile private var awake = false
    private val stopped = ConcurrentHashMap.newKeySet<String>()
    private val dropped = ConcurrentHashMap.newKeySet<String>()
    private val waiting = ConcurrentHashMap<String, LinkedBlockingQueue<String>>()

    override fun identity(): JSONObject = JSONObject()
    override fun parseTicket(raw: String): JSONObject = JSONObject().put("ok", false)
    override fun setRelayAllowed(allowed: Boolean) {}
    override fun close() {}

    private fun nap(ms: Long) = Thread.sleep(ms)
    private fun stamp(ms: Long) = Instant.ofEpochMilli(ms).toString()
    private fun fail(code: String, msg: String) =
        JSONObject().put("ok", false).put("error", JSONObject().put("code", code).put("message", msg))

    override fun request(ticket: String, method: String, params: JSONObject, timeoutMs: Int): JSONObject {
        // The first contact takes a moment, like reaching a real computer; later ones are quick.
        nap(if (awake) 40 else 900)
        if (method == "connector.info") awake = true
        val out = JSONObject().put("ok", true)
        synchronized(lock) {
            when (method) {
                "connector.info" -> info().let { i -> i.keys().forEach { out.put(it, i.get(it)) } }
                "chats.list" -> out.put("chats", JSONArray(chats.sortedByDescending { it.updated }.map(::summary)))
                "chat.open" -> {
                    val c = chats.firstOrNull { it.id == params.optString("chatId") } ?: return fail("not_found", "No such chat")
                    out.put("chatId", c.id).put("state", if (c.running) "running" else "idle").put("readable", true)
                        .put("context", JSONObject().put("used", 18_400 + c.messages.size * 3_150).put("size", 1_048_576))
                        .put("options", JSONObject(c.options.toString())).put("messages", JSONArray(c.messages.map { JSONObject(it.toString()) }))
                }
                "chat.set_option" -> {
                    val target = chats.firstOrNull { it.id == params.optString("chatId") }?.options ?: defaults
                    target.put(params.optString("option"), params.optString("value"))
                    out.put("options", JSONObject(target.toString()))
                }
                "chat.set_folder" -> chats.firstOrNull { it.id == params.optString("chatId") }?.folder = params.optString("folder")
                "turn.stop" -> halt(params.optString("chatId"))
                "approval.answer" -> {
                    val q = waiting.remove(params.optString("approvalId")) ?: return fail("not_found", "Not waiting")
                    q.offer(if (params.optString("choice").startsWith("deny")) "deny" else "allow")
                }
                "files.search" -> {
                    val q = params.optString("query").lowercase()
                    out.put("files", JSONArray(FILES.filter { q.isEmpty() || it.first.lowercase().contains(q) }
                        .map { JSONObject().put("path", it.first).put("size", it.second) }))
                }
                "limits.get" -> limits().let { l -> l.keys().forEach { out.put(it, l.get(it)) } }
                "notifications.register", "notifications.unregister" -> {}
                else -> return fail("bad_request", "Unknown method")
            }
        }
        return out
    }

    private fun info(): JSONObject {
        val efforts = JSONArray(listOf("low" to "Low", "medium" to "Medium", "high" to "High").map { JSONObject().put("id", it.first).put("name", it.second) })
        val models = JSONArray(listOf(
            Triple("neon-4", "4 Neon", true), Triple("carbon-4", "4 Carbon", true),
            Triple("argon-4", "4 Argon", true), Triple("opus-5-5", "Opus 5.5", true),
        ).map { JSONObject().put("id", it.first).put("name", it.second).put("efforts", if (it.third) efforts else JSONArray()) })
        val modes = JSONArray(listOf(
            Triple("default", "Default", "Asks before running commands or editing files."),
            Triple("accept_edits", "Accept edits", "Edits files freely, asks before commands."),
            Triple("plan", "Plan", "Reads and plans, changes nothing."),
            Triple("turbo", "Turbo", "Runs everything without asking."),
        ).map { JSONObject().put("id", it.first).put("name", it.second).put("description", it.third) })
        modes.getJSONObject(3).put("bypassesPermissions", true)
        val commands = listOf("help" to "Show what agy can do", "compact" to "Summarise the chat to free context",
            "context" to "Show context usage", "review" to "Review the current changes", "init" to "Write an AGENTS.md for this folder")
        return JSONObject()
            .put("protocol", 1)
            .put("computer", JSONObject().put("name", COMPUTER_NAME).put("model", "Mac16,10").put("os", "macOS 26.1"))
            .put("agy", JSONObject().put("name", "agy").put("version", "1.6.0"))
            .put("connector", JSONObject().put("version", "0.3.0").put("minAppVersion", "1.0").put("autoUpdate", true))
            .put("folders", JSONArray(folders.map { JSONObject().put("path", it.first).put("name", it.first.substringAfterLast('/')).put("kind", it.second) }))
            .put("models", models).put("modes", modes).put("defaults", JSONObject(defaults.toString()))
            .put("commands", JSONArray(commands.map { JSONObject().put("name", it.first).put("description", it.second) }))
            .put("_p2p", JSONObject().put("rtt_ms", 14).put("transport", "demo"))
    }

    private fun limits(): JSONObject {
        val now = System.currentTimeMillis()
        fun row(spec: String, left: Double, inMs: Long): JSONObject {
            val (id, group, window, label) = spec.split('|')
            return JSONObject().put("id", id).put("group", group).put("window", window).put("label", label)
                .put("remaining", left).put("resets", stamp(now + inMs))
        }
        return JSONObject().put("fetched", stamp(now)).put("credits", 24.5).put("limits", JSONArray(listOf(
            row("pro-5h|Neon|five_hours|5 hours", 76.0, 8_640_000),
            row("pro-week|Neon|week|Week", 58.0, 277_200_000),
            row("flash-week|Carbon|week|Week", 91.0, 277_200_000),
        )))
    }

    private fun summary(c: Chat) = JSONObject().put("id", c.id).put("title", c.title).put("folder", c.folder)
        .put("updated", stamp(c.updated)).put("state", if (c.running) "running" else "idle")

    private fun halt(chatId: String) {
        stopped += chatId
        waiting.values.forEach { it.offer("stop") }
        waiting.clear()
    }

    /** The app stopped listening (unpaired, closed): the pretend turn ends quietly. */
    override fun cancel(requestId: String) {
        dropped += requestId
        synchronized(lock) { chats.filter { it.running }.forEach { halt(it.id) } }
    }

    override fun stream(ticket: String, requestId: String, method: String, params: JSONObject, onEvent: (JSONObject) -> Unit): Int {
        val prompt = params.optString("text").trim()
        val began = System.currentTimeMillis()
        val chat = synchronized(lock) {
            chats.firstOrNull { it.id == params.optString("chatId") } ?: run {
                val opts = JSONObject(defaults.toString())
                params.optJSONObject("options")?.let { o -> o.keys().forEach { opts.put(it, o.get(it)) } }
                val title = if (prompt.length > 48) prompt.take(47) + "…" else prompt.ifEmpty { "New chat" }
                val folder = params.optString("folder").takeIf { it.isNotEmpty() && it != "null" } ?: folders[0].first
                Chat("demo-" + UUID.randomUUID().toString().take(8), title, folder, began, mutableListOf(), opts).also { chats += it }
            }.also {
                it.running = true; it.updated = began
                it.messages += JSONObject().put("id", UUID.randomUUID().toString()).put("from", "user").put("text", prompt)
            }
        }
        val cid = chat.id
        stopped -= cid
        val folderName = chat.folder.substringAfterLast('/')
        val agent = JSONObject().put("id", UUID.randomUUID().toString()).put("from", "agent").put("text", "").put("thinking", "")
        val steps = mutableListOf<JSONObject>()
        val text = StringBuilder()
        val thought = StringBuilder()

        fun emit(ev: JSONObject) { if (requestId !in dropped) onEvent(ev) }
        fun live() = cid !in stopped && requestId !in dropped
        fun step(id: String, kind: String, title: String, finished: Boolean, detail: String? = null) {
            val s = JSONObject().put("id", id).put("kind", kind).put("title", title).put("status", if (finished) "done" else "running")
            if (detail != null) s.put("detail", detail)
            val i = steps.indexOfFirst { it.optString("id") == id }
            if (i >= 0) steps[i] = s else steps += s
            emit(JSONObject().put("event", "turn.step").put("step", s))
        }
        fun plan(active: Int) {
            val p = JSONArray(listOf("Read the relevant files", "Make the change", "Check the result").mapIndexed { i, t ->
                JSONObject().put("text", t).put("status", if (i < active) "done" else if (i == active) "active" else "pending")
            })
            agent.put("plan", p)
            emit(JSONObject().put("event", "turn.plan").put("plan", p))
        }
        fun say(s: String, chunk: Int, every: Long) {
            var i = 0
            while (i < s.length && live()) {
                val piece = s.substring(i, minOf(s.length, i + chunk)); i += chunk
                text.append(piece)
                emit(JSONObject().put("event", "turn.text").put("text", piece))
                nap(every)
            }
        }
        fun close(ev: JSONObject): Int {
            agent.put("text", text.toString()).put("thinking", thought.toString()).put("steps", JSONArray(steps))
                .put("seconds", (System.currentTimeMillis() - began) / 1000.0)
            synchronized(lock) { chat.messages += agent; chat.running = false; chat.updated = System.currentTimeMillis() }
            emit(ev)
            stopped -= cid; dropped -= requestId
            return 0
        }
        fun stoppedEnd() = close(JSONObject().put("event", "turn.stopped"))
        fun finished() = close(JSONObject().put("event", "turn.finished").put("seconds", (System.currentTimeMillis() - began) / 1000.0))

        emit(JSONObject().put("event", "turn.chat").put("chatId", cid).put("folder", chat.folder))
        nap(350)
        for (bit in listOf("Reading the request", " and deciding which files", " in $folderName matter.")) {
            if (!live()) return stoppedEnd()
            thought.append(bit)
            emit(JSONObject().put("event", "turn.thinking").put("text", bit))
            nap(260)
        }
        if (!live()) return stoppedEnd()
        plan(0)
        step("s1", "read", "Read README.md", false)
        nap(450)
        step("s1", "read", "Read README.md", true)

        val lower = prompt.lowercase()
        val risky = "deploy install delete run".split(" ").firstOrNull { lower.contains(it) }
        if (risky != null) {
            val command = when (risky) {
                "deploy" -> "npm run deploy -- --prod"
                "install" -> "npm install date-fns"
                "delete" -> "rm -rf build/cache"
                else -> "swift test"
            }
            val askId = "demo-ask-" + UUID.randomUUID().toString().take(6)
            val ask = JSONObject().put("id", askId).put("what", "run").put("title", "Run $command").put("command", command)
                .put("choices", JSONArray(listOf(
                    JSONObject().put("id", "allow").put("label", "Allow").put("effect", "allow_once"),
                    JSONObject().put("id", "deny").put("label", "Deny").put("effect", "deny_once"))))
            val answer = LinkedBlockingQueue<String>()
            waiting[askId] = answer
            emit(JSONObject().put("event", "turn.approval").put("approval", ask))
            var choice: String? = null
            while (choice == null && live()) choice = answer.poll(200, TimeUnit.MILLISECONDS)
            waiting.remove(askId)
            if (choice == null || choice == "stop" || !live()) return stoppedEnd()
            agent.put("approvals", JSONArray().put(JSONObject(ask.toString()).put("answer", choice)))
            if (choice == "deny") {
                plan(3)
                say("Okay, I didn't run `$command`. Nothing was changed. Tell me if you'd like a different approach.", 6, 30)
                return if (live()) finished() else stoppedEnd()
            }
            step("s2", "run", command, false)
            nap(900)
            if (!live()) return stoppedEnd()
            step("s2", "run", command, true, "Finished with exit code 0")
        } else {
            step("s2", "edit", "Edit src/app/AppModel.kt", false)
            nap(600)
            if (!live()) return stoppedEnd()
            step("s2", "edit", "Edit src/app/AppModel.kt", true)
        }
        plan(1)
        nap(250)
        if (!live()) return stoppedEnd()
        plan(2)
        emit(JSONObject().put("event", "turn.context").put("used", 21_000 + prompt.length * 4).put("size", 1_048_576))
        say(answerFor(prompt, folderName), 5, 22)
        if (!live()) return stoppedEnd()
        plan(3)
        return finished()
    }

    private fun answerFor(prompt: String, folder: String): String {
        val topic = (if (prompt.length > 60) prompt.take(59) + "…" else prompt).ifEmpty { "your request" }
        return """
            |Done. Here's how I handled **$topic** in `$folder`:
            |
            |1. Looked through the project to find where this belongs.
            |2. Made a small, focused change.
            |3. Checked that everything still builds.
            |
            |```kotlin
            |suspend fun refresh() {
            |    val items = store.load()
            |    state = State.Loaded(items)
            |}
            |```
            |
            || Check | Result |
            || --- | --- |
            || Build | Passed |
            || Tests | 24 passed |
            |
            |This is a demo computer, so no real files were touched.
        """.trimMargin()
    }

    private val FILES = listOf(
        "README.md" to 2_840, "Package.swift" to 1_120, "Sources/App/AppModel.swift" to 6_310,
        "Sources/App/ForecastView.swift" to 4_870, "Sources/Networking/WeatherClient.swift" to 5_420,
        "Tests/AppTests/ForecastTests.swift" to 3_090, "docs/architecture.md" to 7_200,
    )

    private fun seed(): MutableList<Chat> {
        val now = System.currentTimeMillis()
        fun opts() = JSONObject().put("model", "neon-4").put("effort", "high").put("mode", "default")
        fun user(t: String) = JSONObject().put("id", UUID.randomUUID().toString()).put("from", "user").put("text", t)
        fun agent(t: String, seconds: Double, steps: List<JSONObject> = emptyList(), plan: List<String> = emptyList(),
                  thinking: String? = null, approvals: List<JSONObject> = emptyList()) = JSONObject()
            .put("id", UUID.randomUUID().toString()).put("from", "agent").put("text", t).put("seconds", seconds)
            .put("steps", JSONArray(steps)).put("plan", JSONArray(plan.map { JSONObject().put("text", it).put("status", "done") }))
            .put("thinking", thinking ?: JSONObject.NULL).put("approvals", JSONArray(approvals))
        var made = 0
        fun did(kind: String, title: String, detail: String? = null) = JSONObject()
            .put("id", "seed-${++made}").put("kind", kind).put("title", title).put("status", "done").put("detail", detail ?: JSONObject.NULL)

        val (weather, api, site) = folders.map { it.first }
        val cache = """
            |The forecast screen was slow because **each city** fetched on its own. I batched the requests and added a five-minute cache in `WeatherClient`:
            |
            |```swift
            |let forecasts = try await client.forecasts(for: cities, maxAge: .minutes(5))
            |```
            |
            || | Before | After |
            || --- | --- | --- |
            || Requests per refresh | 12 | 1 |
            || Refresh time | 3.4 s | 0.6 s |
            |
            |All 24 tests pass.
        """.trimMargin()
        val testAsk = JSONObject().put("id", "demo-ask-seed").put("what", "run").put("title", "Run swift test")
            .put("command", "swift test").put("answer", "allow").put("choices", JSONArray(listOf(
                JSONObject().put("id", "allow").put("label", "Allow").put("effect", "allow_once"),
                JSONObject().put("id", "deny").put("label", "Deny").put("effect", "deny_once"))))
        val route = """
            |I added a `GET /trails/:id/elevation` route. It returns the elevation profile as an array of `{ distance, height }` points:
            |
            |```rust
            |async fn elevation(Path(id): Path<TrailId>, State(db): State<Db>) -> Json<Vec<Point>> {
            |    Json(db.profile(id).await?)
            |}
            |```
            |
            |- Cached per trail for one hour
            |- Covered by two new integration tests
        """.trimMargin()
        return mutableListOf(
            Chat("demo-forecast", "Speed up forecast refresh", weather, now - 240_000, mutableListOf(
                user("The forecast screen takes ages to refresh. Can you find out why and fix it?"),
                agent(cache, 52.0, listOf(did("search", "Search for fetchForecast"), did("read", "Read WeatherClient.swift"),
                    did("edit", "Edit WeatherClient.swift"), did("run", "swift test", "24 passed")),
                    listOf("Find what each refresh requests", "Batch and cache the requests", "Run the tests"),
                    "Each tile calls the network separately; batching should help most.", listOf(testAsk)),
            ), opts()),
            Chat("demo-elevation", "Elevation endpoint for trails", api, now - 3_600_000, mutableListOf(
                user("Add an endpoint that returns a trail's elevation profile."),
                agent(route, 38.0, listOf(did("read", "Read src/routes.rs"), did("edit", "Edit src/routes.rs"),
                    did("run", "cargo test", "31 passed")), listOf("Read the routing code", "Add the route and handler", "Add tests")),
            ), opts()),
            Chat("demo-darkmode", "Dark mode for the site", site, now - 7_200_000, mutableListOf(
                user("Make the site follow the system dark mode."),
                agent("Done. Colours now come from CSS variables, with a `prefers-color-scheme: dark` block that swaps them. Images with white backgrounds get a subtle border in dark mode.",
                    21.0, listOf(did("edit", "Edit styles/theme.css"))),
            ), opts()),
            Chat("demo-offline", "Offline banner", weather, now - 26_000_000, mutableListOf(
                user("Show a banner when the phone is offline."),
                agent("I added an offline banner driven by the network monitor. It slides in at the top when the connection drops and hides again once it's back.",
                    17.0, listOf(did("edit", "Edit Sources/App/RootView.swift"))),
            ), opts()),
            Chat("demo-ratelimit", "Rate limit the public API", api, now - 90_000_000, mutableListOf(
                user("Can we rate limit anonymous requests?"),
                agent("Yes. I added a token-bucket layer: **60 requests a minute** per IP for anonymous callers, no limit with an API key. Over the limit returns `429` with a `Retry-After` header.", 29.0),
            ), opts()),
            Chat("demo-readme", "Tidy up the README", site, now - 180_000_000, mutableListOf(
                user("The README is out of date, can you refresh it?"),
                agent("I rewrote the setup section, removed the old deploy notes and added a short *Contributing* part.", 12.0),
            ), opts()),
        )
    }
}
