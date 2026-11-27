import Foundation

/// A pretend computer, "Demo Mac", for trying the app without agy-connector (App Store review,
/// a first look). It answers the same methods as a real connector (docs/protocol.md) from memory,
/// so every screen runs its normal code. Nothing here touches the network.
@MainActor
final class DemoConnector {
    /// Typed in place of a pairing code to pair the demo computer.
    nonisolated static let pairingCode = "DEMO-AGY"
    /// Stored as the demo computer's ticket and endpoint id.
    nonisolated static let ticket = "agy-demo://demo-mac"
    nonisolated static let endpointId = "agy-demo-mac"
    nonisolated static let computerName = "Demo Mac"

    nonisolated static func isCode(_ raw: String) -> Bool {
        raw.trimmingCharacters(in: .whitespacesAndNewlines).caseInsensitiveCompare(pairingCode) == .orderedSame
    }
    nonisolated static func owns(ticket: String) -> Bool { ticket == Self.ticket }

    static let shared = DemoConnector()

    private struct Chat {
        let id: String
        var title: String
        var folder: String
        var updated: Date
        var messages: [[String: Any]]
        var options: [String: String]
        var running = false
    }

    private let home = "/Users/demo/Projects"
    private var chats: [Chat] = []
    private var defaults: [String: String] = DemoConnector.startingOptions
    private var answered = false
    private var stopped: Set<String> = []
    private var dropped: Set<String> = []
    private var waiting: [String: CheckedContinuation<String, Never>] = [:]
    private var awake = false

    /// Model, effort and mode a fresh demo starts with.
    private static let startingOptions = pairs("mode=default;effort=high;model=neon-4")

    private init() { chats = Self.seed(home: home) }

    // MARK: - Requests

    func reply(method: String, body: String) async throws -> SendableDict {
        let params = (try? JSONSerialization.jsonObject(with: Data(body.utf8))) as? [String: Any] ?? [:]
        // The first contact takes a moment, like reaching a real computer; later ones are quick.
        let pause = awake ? 40 : 900
        if method == "connector.info" { awake = true }
        try? await Task.sleep(for: .milliseconds(pause))
        var out: [String: Any] = ["ok": true]
        switch method {
        case "connector.info": out.merge(info) { $1 }
        case "chats.list": out["chats"] = chats.sorted { $0.updated > $1.updated }.map(summary)
        case "chat.open":
            guard let id = params["chatId"] as? String, let c = chats.first(where: { $0.id == id }) else {
                throw P2PError.remote(code: "not_found", message: "No such chat")
            }
            out["chatId"] = id
            out["state"] = c.running ? "running" : "idle"
            out["readable"] = true
            out["context"] = ["used": 18_400 + c.messages.count * 3_150, "size": 1_048_576]
            out["options"] = c.options
            out["messages"] = c.messages
        case "chat.set_option":
            let option = params["option"] as? String ?? ""
            let value = params["value"] as? String ?? ""
            if let id = params["chatId"] as? String, let i = chats.firstIndex(where: { $0.id == id }) {
                chats[i].options[option] = value
                out["options"] = chats[i].options
            } else {
                defaults[option] = value
                out["options"] = defaults
            }
        case "chat.set_folder":
            if let id = params["chatId"] as? String, let f = params["folder"] as? String,
               let i = chats.firstIndex(where: { $0.id == id }) { chats[i].folder = f }
        case "turn.stop":
            if let id = params["chatId"] as? String { halt(chatId: id) }
        case "approval.answer":
            let id = params["approvalId"] as? String ?? ""
            guard let resume = waiting.removeValue(forKey: id) else {
                throw P2PError.remote(code: "not_found", message: "Not waiting")
            }
            let choice = params["choice"] as? String ?? "deny"
            resume.resume(returning: choice.hasPrefix("deny") ? "deny" : "allow")
        case "files.search":
            let q = (params["query"] as? String ?? "").lowercased()
            out["files"] = Self.files.filter { q.isEmpty || $0.0.lowercased().contains(q) }.map { ["path": $0.0, "size": $0.1] }
        case "limits.get": out.merge(limits) { $1 }
        case "notifications.register", "notifications.unregister": break
        default: throw P2PError.remote(code: "bad_request", message: "Unknown method")
        }
        return SendableDict(out)
    }

    private var info: [String: Any] {
        let tiers: [[String: String]] = "low medium high".split(separator: " ").map { ["id": String($0), "name": $0.capitalized] }
        var models: [[String: Any]] = []
        for (key, label, tiered) in [("neon-4", "4 Neon", true), ("carbon-4", "4 Carbon", true),
                                     ("argon-4", "4 Argon", true), ("opus-5-5", "Opus 5.5", true)] {
            models.append(["id": key, "name": label, "efforts": tiered ? tiers : []])
        }
        let modeRows = [
            ("default", "Default", "Asks before running commands or editing files."),
            ("accept_edits", "Accept edits", "Edits files freely, asks before commands."),
            ("plan", "Plan", "Reads and plans, changes nothing."),
            ("turbo", "Turbo", "Runs everything without asking."),
        ]
        let modes: [[String: Any]] = modeRows.map { row in
            ["id": row.0, "name": row.1, "description": row.2, "bypassesPermissions": row.0 == "turbo"]
        }
        let commands = [("help", "Show what agy can do"), ("compact", "Summarise the chat to free context"),
                        ("context", "Show context usage"), ("review", "Review the current changes"),
                        ("init", "Write an AGENTS.md for this folder")]
        var reply: [String: Any] = ["protocol": ProtocolV1.version, "defaults": defaults, "models": models, "modes": modes]
        var machine = Self.pairs("os=macOS 26.1;model=Mac16,10")
        machine["name"] = Self.computerName
        reply["computer"] = machine
        reply["agy"] = Self.pairs("version=1.6.0;name=agy")
        var bridge: [String: Any] = Self.pairs("minAppVersion=1.0;version=0.3.0")
        bridge["autoUpdate"] = true
        reply["connector"] = bridge
        reply["folders"] = folders.map { ["path": $0.0, "name": ($0.0 as NSString).lastPathComponent, "kind": $0.1] }
        reply["commands"] = commands.map { ["name": $0.0, "description": $0.1] }
        reply["_p2p"] = ["rtt_ms": 14, "transport": "demo"] as [String: Any]
        return reply
    }

    private var folders: [(String, String)] {
        [(home + "/weather-app", "swift"), (home + "/trail-api", "rust"), (home + "/portfolio-site", "node")]
    }

    private var limits: [String: Any] {
        let now = Date()
        let week = now.addingTimeInterval(3 * 86_400 + 5 * 3600)
        let table: [(String, Double, Date)] = [
            ("id=pro-5h;group=Neon;window=five_hours;label=5 hours", 76, now.addingTimeInterval(8_640)),
            ("id=pro-week;group=Neon;window=week;label=Week", 58, week),
            ("id=flash-week;group=Carbon;window=week;label=Week", 91, week),
        ]
        let rows: [[String: Any]] = table.map { spec, left, resets in
            var row: [String: Any] = Self.pairs(spec)
            row["remaining"] = left
            row["resets"] = Self.stamp(resets)
            return row
        }
        return ["fetched": Self.stamp(now), "limits": rows, "credits": 24.5]
    }

    private func summary(_ c: Chat) -> [String: Any] {
        ["id": c.id, "title": c.title, "folder": c.folder, "updated": Self.stamp(c.updated), "state": c.running ? "running" : "idle"]
    }

    // MARK: - Turns

    /// The phone stopped listening (unpaired); the pretend turn ends quietly.
    func forget(requestId: String) {
        dropped.insert(requestId)
        for id in chats.filter(\.running).map(\.id) { halt(chatId: id) }
    }

    private func halt(chatId: String) {
        stopped.insert(chatId)
        for (_, resume) in waiting { resume.resume(returning: "stop") }
        waiting.removeAll()
    }

    func stream(requestId: String, body: String, onEvent: @escaping @MainActor ([String: Any]) -> Void) async -> Int32 {
        let params = (try? JSONSerialization.jsonObject(with: Data(body.utf8))) as? [String: Any] ?? [:]
        let prompt = (params["text"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let began = Date()
        var chatId = params["chatId"] as? String
        if chatId.map({ id in chats.contains { $0.id == id } }) != true {
            let id = "demo-" + UUID().uuidString.prefix(8).lowercased()
            let folder = params["folder"] as? String ?? folders[0].0
            var opts = defaults
            for (k, v) in params["options"] as? [String: String] ?? [:] { opts[k] = v }
            let title = prompt.count > 48 ? String(prompt.prefix(47)) + "…" : prompt
            chats.append(Chat(id: id, title: title.isEmpty ? "New chat" : title, folder: folder, updated: .now, messages: [], options: opts))
            chatId = id
        }
        let cid = chatId!
        stopped.remove(cid)
        guard let at = chats.firstIndex(where: { $0.id == cid }) else { return 0 }
        chats[at].running = true
        chats[at].updated = .now
        chats[at].messages.append(["id": UUID().uuidString, "from": "user", "text": prompt])
        let folderName = (chats[at].folder as NSString).lastPathComponent

        var agent: [String: Any] = Self.pairs("from=agent;text=;thinking=")
        agent["id"] = UUID().uuidString
        var steps: [[String: Any]] = []
        func emit(_ ev: [String: Any]) {
            guard !dropped.contains(requestId) else { return }
            onEvent(ev)
        }
        func live() -> Bool { !stopped.contains(cid) && !dropped.contains(requestId) }
        func pause(_ ms: Int) async { try? await Task.sleep(for: .milliseconds(ms)) }
        func step(_ id: String, _ kind: String, _ title: String, finished: Bool, detail: String? = nil, extra: [String: Any] = [:]) {
            var s: [String: Any] = extra.merging(["id": id, "kind": kind, "title": title]) { $1 }
            s["status"] = finished ? "done" : "running"
            if let detail { s["detail"] = detail }
            if let i = steps.firstIndex(where: { $0["id"] as? String == id }) {
                s["at"] = steps[i]["at"]
                steps[i] = s
            } else {
                s["at"] = (agent["text"] as? String ?? "").utf16.count
                steps.append(s)
            }
            emit(["event": "turn.step", "step": s])
        }
        func plan(_ active: Int) {
            let names = ["Read the relevant files", "Make the change", "Check the result"]
            let p = names.enumerated().map { i, t in ["text": t, "status": i < active ? "done" : (i == active ? "active" : "pending")] }
            agent["plan"] = p
            emit(["event": "turn.plan", "plan": p])
        }
        func say(_ text: String, chunk: Int, every ms: Int) async {
            var rest = Substring(text)
            while !rest.isEmpty, live() {
                let piece = String(rest.prefix(chunk))
                rest = rest.dropFirst(piece.count)
                agent["text"] = (agent["text"] as? String ?? "") + piece
                emit(["event": "turn.text", "text": piece])
                await pause(ms)
            }
        }
        func close(_ event: [String: Any]) -> Int32 {
            agent["steps"] = steps
            agent["seconds"] = Date().timeIntervalSince(began)
            if let i = chats.firstIndex(where: { $0.id == cid }) {
                chats[i].messages.append(agent)
                chats[i].running = false
                chats[i].updated = .now
            }
            emit(event)
            stopped.remove(cid)
            dropped.remove(requestId)
            return 0
        }
        func stoppedEnd() -> Int32 { close(["event": "turn.stopped"]) }

        emit(["event": "turn.chat", "chatId": cid, "folder": chats[at].folder])
        await pause(350)
        for bit in ["Reading the request", " and deciding which files", " in \(folderName) matter."] {
            guard live() else { return stoppedEnd() }
            agent["thinking"] = (agent["thinking"] as? String ?? "") + bit
            emit(["event": "turn.thinking", "text": bit])
            await pause(260)
        }
        guard live() else { return stoppedEnd() }
        plan(0)
        step("look", "read", "Read the README", finished: false)
        await pause(450)
        step("look", "read", "Read the README", finished: true, extra: ["path": "README.md"])
        await say("I've read the README. Now I'll make the change.\n\n", chunk: 5, every: 22)
        guard live() else { return stoppedEnd() }

        let lower = prompt.lowercased()
        if let word = "install deploy run delete".split(separator: " ").map(String.init).first(where: { lower.contains($0) }) {
            let command = Self.command(for: word)
            var ask: [String: Any] = Self.pairs("what=run")
            ask["id"] = "demo-ask-" + UUID().uuidString.prefix(6).lowercased()
            ask["title"] = "Run \(command)"
            ask["command"] = command
            ask["choices"] = Self.allowOrDeny
            let askId = ask["id"] as! String
            emit(["event": "turn.approval", "approval": ask])
            let choice = await withCheckedContinuation { waiting[askId] = $0 }
            guard choice != "stop", live() else { return stoppedEnd() }
            var settled = ask
            settled["answer"] = choice
            agent["approvals"] = [settled]
            if choice == "deny" {
                plan(3)
                await say("Okay, I didn't run `\(command)`. Nothing was changed. Tell me if you'd like a different approach.",
                          chunk: 6, every: 30)
                guard live() else { return stoppedEnd() }
                return close(["event": "turn.finished", "seconds": Date().timeIntervalSince(began)])
            }
            step("change", "run", command, finished: false)
            await pause(900)
            guard live() else { return stoppedEnd() }
            step("change", "run", command, finished: true, detail: "Exited with status 0")
        } else {
            step("change", "edit", "Update AppModel.swift", finished: false)
            await pause(600)
            guard live() else { return stoppedEnd() }
            step("change", "edit", "Update AppModel.swift", finished: true,
                 extra: ["path": "Sources/AppModel.swift", "added": 12, "removed": 3])
        }
        plan(1)
        await pause(250)
        guard live() else { return stoppedEnd() }
        plan(2)
        emit(["event": "turn.context", "used": 21_000 + prompt.count * 4, "size": 1_048_576])
        await say(Self.answer(to: prompt, folder: folderName), chunk: 5, every: 22)
        guard live() else { return stoppedEnd() }
        plan(3)
        return close(["event": "turn.finished", "seconds": Date().timeIntervalSince(began)])
    }

    /// "a=1;b=2" as a dictionary; keeps the canned data short.
    private static func pairs(_ spec: String) -> [String: String] {
        var out: [String: String] = [:]
        for part in spec.split(separator: ";") {
            let bits = part.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
            out[String(bits[0])] = bits.count > 1 ? String(bits[1]) : ""
        }
        return out
    }

    /// The two answers every demo question offers.
    private static let allowOrDeny: [[String: String]] = [("allow", "Allow", "allow_once"), ("deny", "Deny", "deny_once")]
        .map { ["id": $0.0, "label": $0.1, "effect": $0.2] }

    private static func command(for word: String) -> String {
        switch word {
        case "deploy": "npm run deploy -- --prod"
        case "install": "npm install date-fns"
        case "delete": "rm -rf build/cache"
        default: "swift test"
        }
    }

    private static func answer(to prompt: String, folder: String) -> String {
        let topic = prompt.count > 60 ? String(prompt.prefix(59)) + "…" : prompt
        return """
        Done. Here's how I handled **\(topic.isEmpty ? "your request" : topic)** in `\(folder)`:

        1. Looked through the project to find where this belongs.
        2. Made a small, focused change.
        3. Checked that everything still builds.

        ```swift
        func refresh() async throws {
            let items = try await store.load()
            state = .loaded(items)
        }
        ```

        | Check | Result |
        | --- | --- |
        | Build | Passed |
        | Tests | 24 passed |

        This is a demo computer, so no real files were touched.
        """
    }

    // MARK: - Canned content

    nonisolated(unsafe) private static let formatter = ISO8601DateFormatter()
    private static func stamp(_ d: Date) -> String { formatter.string(from: d) }

    private static let files: [(String, Int)] = [
        ("README.md", 2_840), ("Package.swift", 1_120), ("Sources/App/AppModel.swift", 6_310),
        ("Sources/App/ForecastView.swift", 4_870), ("Sources/Networking/WeatherClient.swift", 5_420),
        ("Tests/AppTests/ForecastTests.swift", 3_090), ("docs/architecture.md", 7_200),
    ]

    private static func seed(home: String) -> [Chat] {
        let now = Date()
        let opts = startingOptions
        func user(_ t: String) -> [String: Any] { ["id": UUID().uuidString, "from": "user", "text": t] }
        func agent(_ t: String, seconds: Double, steps: [[String: Any]] = [], plan: [[String: Any]] = [],
                   thinking: String? = nil, approvals: [[String: Any]] = []) -> [String: Any] {
            ["id": UUID().uuidString, "from": "agent", "text": t, "seconds": seconds, "steps": steps, "plan": plan,
             "thinking": thinking ?? NSNull(), "approvals": approvals]
        }
        var made = 0
        func did(_ kind: String, _ title: String, _ detail: String? = nil) -> [String: Any] {
            made += 1
            return ["status": "done", "kind": kind, "title": title, "id": "seed-\(made)", "detail": detail ?? NSNull()]
        }
        func done(_ items: [String]) -> [[String: Any]] { items.map { ["text": $0, "status": "done"] } }

        let weather = home + "/weather-app", api = home + "/trail-api", site = home + "/portfolio-site"
        let cacheAnswer = """
        The forecast screen was slow because **each city** fetched on its own. I batched the requests \
        and added a five-minute cache in `WeatherClient`:

        ```swift
        let forecasts = try await client.forecasts(for: cities, maxAge: .minutes(5))
        ```

        | | Before | After |
        | --- | --- | --- |
        | Requests per refresh | 12 | 1 |
        | Refresh time | 3.4 s | 0.6 s |

        All 24 tests pass.
        """
        var testAsk: [String: Any] = pairs("what=run;id=demo-ask-seed;answer=allow;command=swift test")
        testAsk["title"] = "Run swift test"
        testAsk["choices"] = allowOrDeny
        let routeAnswer = """
        I added a `GET /trails/:id/elevation` route. It returns the elevation profile as an array of \
        `{ distance, height }` points:

        ```rust
        async fn elevation(Path(id): Path<TrailId>, State(db): State<Db>) -> Json<Vec<Point>> {
            Json(db.profile(id).await?)
        }
        ```

        - Cached per trail for one hour
        - Covered by two new integration tests
        """
        return [
            Chat(id: "demo-forecast", title: "Speed up forecast refresh", folder: weather, updated: now.addingTimeInterval(-240), messages: [
                user("The forecast screen takes ages to refresh. Can you find out why and fix it?"),
                agent(cacheAnswer, seconds: 52,
                      steps: [did("search", "Search for fetchForecast"), did("read", "Read WeatherClient.swift"),
                              did("edit", "Edit WeatherClient.swift"), did("run", "swift test", "24 passed")],
                      plan: done(["Find what each refresh requests", "Batch and cache the requests", "Run the tests"]),
                      thinking: "Each tile calls the network separately; batching should help most.", approvals: [testAsk]),
            ], options: opts),
            Chat(id: "demo-elevation", title: "Elevation endpoint for trails", folder: api, updated: now.addingTimeInterval(-3_600), messages: [
                user("Add an endpoint that returns a trail's elevation profile."),
                agent(routeAnswer, seconds: 38,
                      steps: [did("read", "Read src/routes.rs"), did("edit", "Edit src/routes.rs"),
                              did("run", "cargo test", "31 passed")],
                      plan: done(["Read the routing code", "Add the route and handler", "Add tests"])),
            ], options: opts),
            Chat(id: "demo-darkmode", title: "Dark mode for the site", folder: site, updated: now.addingTimeInterval(-7_200), messages: [
                user("Make the site follow the system dark mode."),
                agent("Done. Colours now come from CSS variables, with a `prefers-color-scheme: dark` block that swaps them. Images with white backgrounds get a subtle border in dark mode.",
                      seconds: 21, steps: [did("edit", "Edit styles/theme.css")]),
            ], options: opts),
            Chat(id: "demo-offline", title: "Offline banner", folder: weather, updated: now.addingTimeInterval(-26_000), messages: [
                user("Show a banner when the phone is offline."),
                agent("I added an `OfflineBanner` view driven by `NWPathMonitor`. It slides in at the top when the connection drops and hides again once it's back.",
                      seconds: 17, steps: [did("edit", "Edit Sources/App/RootView.swift")]),
            ], options: opts),
            Chat(id: "demo-ratelimit", title: "Rate limit the public API", folder: api, updated: now.addingTimeInterval(-90_000), messages: [
                user("Can we rate limit anonymous requests?"),
                agent("Yes. I added a token-bucket layer: **60 requests a minute** per IP for anonymous callers, no limit with an API key. Over the limit returns `429` with a `Retry-After` header.",
                      seconds: 29),
            ], options: opts),
            Chat(id: "demo-readme", title: "Tidy up the README", folder: site, updated: now.addingTimeInterval(-180_000), messages: [
                user("The README is out of date, can you refresh it?"),
                agent("I rewrote the setup section, removed the old deploy notes and added a short *Contributing* part.", seconds: 12),
            ], options: opts),
        ]
    }
}
