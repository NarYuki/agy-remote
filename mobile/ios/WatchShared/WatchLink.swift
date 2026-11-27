// SPDX-License-Identifier: AGPL-3.0-only
import Foundation

/// Wire format between the iPhone app and the watch app (WatchConnectivity).
/// The phone keeps the watch's picture current with an application context holding one
/// `WatchSnapshot`; the watch asks for things with a `WatchRequest` and gets a `WatchReply`.
enum WatchLink {
    /// Key under which payloads travel inside WatchConnectivity dictionaries.
    static let payloadKey = "p"
    /// How many messages the phone hands over when the watch opens a chat.
    static let transcriptDepth = 10
    /// Longest text kept per summary line on the wire.
    static let textLimit = 700
    /// Message bodies travel as raw Markdown up to this many UTF-8 bytes (WatchConnectivity
    /// messages top out near 64 KB, and a transcript carries several).
    static let markdownCap = 4096

    /// Keeps whole lines of a Markdown body until the byte cap; the watch renders it.
    static func capMarkdown(_ text: String) -> String {
        guard text.utf8.count > markdownCap else { return text }
        var kept = ""
        var used = 0
        for line in text.split(separator: "\n", omittingEmptySubsequences: false) {
            let cost = line.utf8.count + 1
            if used + cost > markdownCap { break }
            kept += line + "\n"
            used += cost
        }
        // An open code fence would swallow the rest; close it.
        if kept.components(separatedBy: "```").count % 2 == 0 { kept += "```\n" }
        return kept + "…"
    }

    static func pack<T: Encodable>(_ value: T) -> [String: Any] {
        guard let data = try? JSONEncoder().encode(value) else { return [:] }
        return [payloadKey: data]
    }

    static func unpack<T: Decodable>(_ type: T.Type, from dict: [String: Any]) -> T? {
        guard let data = dict[payloadKey] as? Data else { return nil }
        return try? JSONDecoder().decode(type, from: data)
    }

    /// Cuts long text down for the wrist, on a word boundary when one is close.
    static func clip(_ text: String, to limit: Int = textLimit) -> String {
        let flat = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard flat.count > limit else { return flat }
        var head = String(flat.prefix(limit))
        if let gap = head.lastIndex(where: \.isWhitespace), head.distance(from: gap, to: head.endIndex) < 40 {
            head = String(head[..<gap])
        }
        return head + "…"
    }
}

struct WatchSnapshot: Codable, Equatable, Sendable {
    struct Computer: Codable, Equatable, Identifiable, Sendable {
        var id: String
        var name: String
        var online: Bool
    }

    struct Chat: Codable, Equatable, Identifiable, Sendable {
        var id: String
        var title: String
        var folder: String
        var status: String
        var isRunning: Bool
        /// When the phone first saw this turn running (seconds since 1970).
        var runningSince: Double?
        /// The newest agent text, one short line, when the phone has it.
        var lastLine: String?
    }

    struct Choice: Codable, Equatable, Identifiable, Sendable {
        var id: String
        var label: String
        /// allow_once, allow_always, deny_once or deny_always.
        var kind: String
        var allows: Bool { kind.hasPrefix("allow") }
    }

    struct Approval: Codable, Equatable, Identifiable, Sendable {
        var id: String
        var chatId: String
        var chatTitle: String
        var summary: String
        var choices: [Choice]
    }

    var computer: Computer?
    var computers: [Computer] = []
    var chats: [Chat] = []
    var approvals: [Approval] = []
    /// Tightest remaining plan quota, 0–100.
    var quotaPercent: Int?
    /// Lets the watch tell an old context from a fresh one.
    var stamp: Double = 0

    var running: [Chat] { chats.filter(\.isRunning) }
    var idle: [Chat] { chats.filter { !$0.isRunning } }
    /// What the first page shows: the running chat, or else the latest one.
    var focus: Chat? { running.first ?? chats.first }
}

struct WatchLine: Codable, Equatable, Identifiable, Sendable {
    var id: String
    var fromUser: Bool
    var text: String
}

enum WatchRequest: Codable, Sendable {
    case refresh
    case transcript(chatId: String)
    /// `chatId` nil starts a new chat on the selected computer.
    case prompt(chatId: String?, text: String)
    case approve(chatId: String, approvalId: String, choiceId: String)
    /// From a notification action, where only allow / deny is known. `computer` is the endpoint
    /// id of the computer whose key opened the notification (empty if unknown).
    case quickAnswer(approvalId: String, allow: Bool, computer: String)
    case stop(chatId: String)
    case selectComputer(id: String)
}

struct WatchReply: Codable, Sendable {
    var ok: Bool
    var lines: [WatchLine]?
    var isRunning: Bool?
    var chatId: String?
    var note: String?
    var snapshot: WatchSnapshot?
}
