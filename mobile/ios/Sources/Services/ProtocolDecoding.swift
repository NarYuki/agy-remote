import Foundation

// JSON (docs/protocol.md v1) → app models. Unknown fields are ignored; missing ones get defaults.

enum ProtocolV1 {
    static let version = 1

    // ISO8601DateFormatter is thread-safe; made once.
    nonisolated(unsafe) private static let fractional: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()
    nonisolated(unsafe) private static let plain: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f
    }()

    static func date(_ any: Any?) -> Date? {
        guard let s = any as? String else { return nil }
        return fractional.date(from: s) ?? plain.date(from: s)
    }

    static func folder(_ o: [String: Any]) -> WorkspaceFolder? {
        let clean = WorkspaceFolder.normalize(o.field("path") ?? "")
        if clean.isEmpty { return nil }
        return WorkspaceFolder(path: clean, name: o.field("name") ?? (clean as NSString).lastPathComponent,
                               kind: o.field("kind"))
    }

    static func model(_ o: [String: Any]) -> AgyBaseModel? {
        guard let key: String = o.field("id") else { return nil }
        var levels = [AgyEffortOption]()
        for row in o.field("efforts") ?? [[String: Any]]() {
            guard let v: String = row.field("id") else { continue }
            let shown = AgyBaseModel.effortDisplayName(value: v, name: row.field("name") ?? v.capitalized)
            levels.append(AgyEffortOption(value: v, displayName: shown))
        }
        return AgyBaseModel(id: key, name: o.field("name") ?? key, efforts: levels)
    }

    /// Name and description of the modes agy has (and Turbo), in the phone's language.
    static func modeText(_ id: String) -> (name: String, about: String)? {
        switch id {
        case "default": (String(localized: "Default"), String(localized: "Ask before edits and commands, per your permission settings."))
        case "accept-edits": (String(localized: "Accept edits"), String(localized: "Approve file edits in the workspace without asking."))
        case "auto": (String(localized: "Auto"), String(localized: "Approve edits and safe commands; ask before irreversible actions."))
        case "plan": (String(localized: "Plan"), String(localized: "Plan carefully before executing a task."))
        case "turbo": (String(localized: "Turbo"), String(localized: "Runs in agy's auto mode and allows every request once, without asking."))
        default: nil
        }
    }

    static func mode(_ o: [String: Any]) -> AgyMode? {
        guard let key: String = o.field("id") else { return nil }
        let flag: Bool = o.field("bypassesPermissions") ?? o.field("turbo") ?? false
        // agy's modes (and the connector's Turbo) in the phone's language; any other mode as sent.
        let known = Self.modeText(key)
        var m = AgyMode(id: key, name: known?.name ?? o.field("name") ?? key.capitalized)
        m.description = known?.about ?? o.field("description") ?? ""
        m.bypassFlag = flag
        return m
    }

    static func chat(_ o: [String: Any]) -> ChatSummary? {
        guard let key: String = o.field("id") else { return nil }
        let place = WorkspaceFolder.normalize(o.field("folder") ?? "")
        let raw: String = o.field("state") ?? ""
        var rev: String? = o.field("revision")
        if rev == nil, let num: NSNumber = o.field("revision") { rev = num.stringValue }
        var c = ChatSummary(id: key, title: o.field("title") ?? "", folder: place.isEmpty ? nil : place,
                            updated: date(o["updated"]) ?? .distantPast, state: ChatSummary.State(rawValue: raw) ?? .idle)
        c.revision = rev
        return c
    }

    static func step(_ o: [String: Any]) -> AgyToolCall? {
        guard let key: String = o.field("id") else { return nil }
        let wire: String = o.field("kind") ?? "other"
        let reported: String = o.field("status") ?? ""
        let phase = reported == "done" ? "completed" : (reported == "failed" ? reported : "pending")
        var call = AgyToolCall(id: key, kind: wire, name: wire, title: o.field("title") ?? "", status: phase)
        if wire == "run" { call.kind = "execute" }
        call.detail = o.field("detail")
        call.at = (o["at"] as? NSNumber)?.intValue
        call.path = o.field("path")
        call.added = (o["added"] as? NSNumber)?.intValue
        call.removed = (o["removed"] as? NSNumber)?.intValue
        return call
    }

    static func plan(_ list: Any?) -> [AgyPlanEntry] {
        (list as? [[String: Any]] ?? []).map { p in
            let status: AgyPlanEntry.Status = switch p["status"] as? String {
            case "done": .completed
            case "active": .inProgress
            default: .pending
            }
            return AgyPlanEntry(content: p["text"] as? String ?? "", status: status)
        }
    }

    /// An approval becomes the permission request the card understands: `detail` holds the
    /// command, or the path with old/new text, as a small JSON object.
    static func approval(_ o: [String: Any]) -> PendingPermissionRequest? {
        guard let key: String = o.field("id") else { return nil }
        var info: [String: Any] = [:]
        for name in ["command", "path"] { if let v = o[name] { info[name] = v } }
        if let after = o["after"] {
            let before: String = o.field("before") ?? ""
            if before.isEmpty {
                info["content"] = after
            } else {
                info["new_string"] = after
                info["old_string"] = before
            }
        }
        let blob = info.isEmpty ? nil : try? JSONSerialization.data(withJSONObject: info)
        var picks = [AgyPermissionOption]()
        for c in o.field("choices") ?? [[String: Any]]() {
            if let cid: String = c.field("id") {
                picks.append(AgyPermissionOption(optionId: cid, name: c.field("label") ?? cid, kind: c.field("effect") ?? "allow_once"))
            }
        }
        var ask = PendingPermissionRequest(id: key, title: "", detail: blob.map { String(decoding: $0, as: UTF8.self) } ?? "", options: picks)
        ask.title = o.field("title") ?? ""
        if (o.field("what") as String?) == "question" {
            ask.questions = (o.field("questions") ?? [[String: Any]]()).compactMap { q in
                guard let key: String = q.field("key") else { return nil }
                let options = (q.field("options") ?? [[String: Any]]()).compactMap { opt -> FormQuestion.Option? in
                    guard let id: String = opt.field("id") else { return nil }
                    return FormQuestion.Option(id: id, label: opt.field("label") ?? id)
                }
                return FormQuestion(key: key, question: q.field("question") ?? "", multi: q.field("multi") ?? false,
                                    options: options, otherKey: q.field("other"))
            }
        }
        // A question already answered (kept in a transcript) shows the choice made.
        if let picked: String = o.field("answer") { ask.selectedOptionId = picked }
        return ask
    }

    /// A transcript's messages; ones without an id get `<chat>-<index>` so reloads match.
    static func messages(_ list: Any?, chatId: String) -> [ChatMessage] {
        (list as? [[String: Any]] ?? []).enumerated().compactMap { i, o in message(o, fallbackId: "\(chatId)-\(i)") }
    }

    static func message(_ o: [String: Any], fallbackId: String? = nil) -> ChatMessage? {
        let id = (o["id"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? fallbackId ?? UUID().uuidString
        let isUser = (o["from"] as? String) == "user"
        var m = ChatMessage(id: id, role: isUser ? .user : .agent, text: o["text"] as? String ?? "")
        m.attachments = (o["attachments"] as? [[String: Any]] ?? []).enumerated().map { i, a in
            MessageAttachment(id: "\(id)-a\(i)", kind: (a["kind"] as? String) == "image" ? .image : .file, name: a["name"] as? String ?? "")
        }
        m.thoughtSummary = o["thinking"] as? String ?? ""
        m.toolCalls = (o["steps"] as? [[String: Any]] ?? []).compactMap(step)
        m.plan = plan(o["plan"])
        m.permissions = (o["approvals"] as? [[String: Any]] ?? []).compactMap(approval)
        m.durationSeconds = o["seconds"] as? Double
        m.isStreaming = o["live"] as? Bool ?? false
        if m.isStreaming { m.activeStatusLabel = liveStatus(of: m) }
        return m
    }

    /// A short line for what a running tool call is doing.
    static func readableStatus(_ step: AgyToolCall) -> String {
        let title = step.title.trimmingCharacters(in: .whitespacesAndNewlines)
        if !title.isEmpty { return title.count > 38 ? String(title.prefix(37)) + "…" : title }
        switch step.kind {
        case "read": return String(localized: "Reading")
        case "edit", "delete", "move": return String(localized: "Editing")
        case "search": return String(localized: "Searching")
        case "execute": return String(localized: "Running")
        case "fetch": return String(localized: "Fetching")
        case "think": return String(localized: "Thinking")
        default: return String(localized: "Working")
        }
    }

    /// Status of an agent message still being written: waiting on a question, running a tool,
    /// writing the reply, or thinking.
    static func liveStatus(of m: ChatMessage) -> String {
        if let open = m.permissions.first(where: { $0.selectedOptionId == nil }) {
            return open.isQuestion ? String(localized: "Waiting for your answer") : String(localized: "Waiting for approval")
        }
        if let running = m.toolCalls.last(where: { $0.status == "pending" }) { return readableStatus(running) }
        if !m.text.isEmpty { return String(localized: "Responding") }
        return String(localized: "Thinking")
    }

    static func limits(_ o: [String: Any]) -> QuotaSnapshot {
        let limits = (o["limits"] as? [[String: Any]] ?? []).compactMap { l -> QuotaLimit? in
            guard let id = l["id"] as? String else { return nil }
            return QuotaLimit(id: id, group: l["group"] as? String ?? "", window: l["window"] as? String ?? "other",
                              label: l["label"] as? String ?? id, remaining: (l["remaining"] as? NSNumber)?.doubleValue ?? 0,
                              resets: date(l["resets"]))
        }
        return QuotaSnapshot(limits: limits, credits: (o["credits"] as? NSNumber)?.doubleValue, fetchedAt: date(o["fetched"]) ?? .now)
    }
}

private extension Dictionary where Key == String, Value == Any {
    /// The value under `key` when it has the wanted type.
    func field<T>(_ key: String) -> T? { self[key] as? T }
}
