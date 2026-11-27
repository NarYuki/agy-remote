import Foundation
import SwiftUI
import UIKit
import UniformTypeIdentifiers

// App-side models. They mirror docs/protocol.md v1, shaped for the views.

struct AgyToolCall: Identifiable, Equatable, Hashable {
    let id: String
    /// UI kind: read, edit, delete, move, search, execute, fetch, think, other.
    var kind: String
    var name: String
    var title: String
    /// "pending", "completed" or "failed".
    var status: String
    var detail: String?
    /// How much reply text (UTF-16 units) came before this step: where it goes between the text.
    var at: Int?
    /// The file it touched, and for edits the lines that came and went.
    var path: String?
    var added: Int?
    var removed: Int?
}

struct AgyPlanEntry: Equatable, Hashable {
    enum Status: String, Equatable, Hashable { case pending, inProgress, completed }
    var content: String
    var status: Status
}

struct AgyPermissionOption: Identifiable, Equatable, Hashable {
    var optionId: String
    var name: String
    /// allow_once, allow_always, deny_once or deny_always.
    var kind: String
    var id: String { optionId }

    /// The label to show: agy's plain English answers ("Allow", "Deny", "Always") in the UI language, anything else as sent.
    var displayName: String {
        switch (kind, name) {
        case ("allow_once", "Allow"): String(localized: "Allow")
        case ("deny_once", "Deny"): String(localized: "Deny")
        case ("allow_always", "Always"): String(localized: "Always", comment: "Permission answer: allow this kind of action from now on")
        default: name
        }
    }
}

struct PendingPermissionRequest: Identifiable, Equatable, Hashable {
    var id: String
    var title: String
    /// JSON object text (command, path, old/new text, content).
    var detail: String
    var options: [AgyPermissionOption]
    var selectedOptionId: String?
    /// The computer no longer has this question.
    var isStale = false
    /// Set when agy asks the user (`ask_question`): answered with options, not allow / deny.
    var questions: [FormQuestion] = []
    var isQuestion: Bool { !questions.isEmpty }

    func markedStale() -> Self {
        var copy = self
        copy.isStale = true
        return copy
    }
}

/// One question of an `ask_question` form: its options, and the key for a written answer.
struct FormQuestion: Equatable, Hashable {
    struct Option: Equatable, Hashable { var id: String; var label: String }
    var key: String
    var question: String
    var multi: Bool
    var options: [Option]
    var otherKey: String?
}

struct MessageAttachment: Identifiable, Equatable, Hashable {
    /// Deterministic for decoded attachments (derived from the message id and position), a
    /// fresh UUID for ones picked on the phone. Not part of equality, so reloads compare equal.
    var id: String = UUID().uuidString
    enum Kind: String { case image, file }
    var kind: Kind
    var name: String
    /// Small preview for the composer strip and the bubble (for images).
    var imageData: Data?
    /// What is sent: the resized image, or a file picked on the phone.
    var payload: Data?
    var mimeType: String?
    /// Path relative to the chat's folder, for a file that lives on the computer.
    var workspacePath: String?
    /// Size of the original file, before any downscaling.
    var byteCount: Int?

    var isWorkspaceFile: Bool { workspacePath != nil }

    static func == (a: MessageAttachment, b: MessageAttachment) -> Bool {
        a.kind == b.kind && a.name == b.name && a.workspacePath == b.workspacePath && a.mimeType == b.mimeType
            && a.payload == b.payload && a.imageData == b.imageData
    }

    func hash(into h: inout Hasher) {
        h.combine(kind); h.combine(name); h.combine(workspacePath); h.combine(payload?.count); h.combine(imageData?.count)
    }

    /// Phone files that are neither images nor UTF-8 text can't be sent.
    var isSendable: Bool {
        if workspacePath != nil || kind == .image { return true }
        if kind == .file, payload == nil { return true }
        return payload.flatMap { String(data: $0, encoding: .utf8) } != nil
    }

    /// An image scaled so its longer side is at most 1920 pt, plus a 240 pt preview.
    static func image(_ source: UIImage, name label: String = "photo.jpg") -> Self? {
        func fitted(_ picture: UIImage, within edge: CGFloat) -> UIImage {
            let w = picture.size.width, h = picture.size.height
            let longest = Swift.max(w, h)
            guard longest > edge else { return picture }
            let ratio = edge / longest
            let target = CGSize(width: Swift.max(1, (w * ratio).rounded()), height: Swift.max(1, (h * ratio).rounded()))
            let fmt = UIGraphicsImageRendererFormat.default()
            fmt.scale = 1
            return UIGraphicsImageRenderer(size: target, format: fmt).image { _ in
                picture.draw(in: CGRect(origin: .zero, size: target))
            }
        }
        let big = fitted(source, within: 1920)
        guard let body = big.jpegData(compressionQuality: 0.84) else { return nil }
        let thumb = fitted(big, within: 240).jpegData(compressionQuality: 0.78) ?? body
        var stem = (label as NSString).deletingPathExtension
        if stem.isEmpty { stem = "photo" }
        return Self(kind: .image, name: stem + ".jpg", imageData: thumb, payload: body,
                    mimeType: "image/jpeg", byteCount: body.count)
    }

    /// A file from the phone (Files app). Images go through `image(_:name:)`.
    static func localFile(_ data: Data, name: String) -> MessageAttachment {
        if let img = UIImage(data: data), let a = image(img, name: name) { return a }
        let ext = (name as NSString).pathExtension
        let mime = UTType(filenameExtension: ext)?.preferredMIMEType ?? "application/octet-stream"
        return MessageAttachment(kind: .file, name: name, payload: data, mimeType: mime, byteCount: data.count)
    }

    /// A file inside the chat's folder on the computer.
    static func workspaceFile(_ file: WorkspaceFile) -> MessageAttachment {
        MessageAttachment(kind: .file, name: (file.path as NSString).lastPathComponent, workspacePath: file.path)
    }
}

struct ChatMessage: Identifiable, Equatable {
    enum Role: String, Equatable { case user, agent }
    var id: String
    var role: Role
    var text: String
    var attachments = [MessageAttachment]()
    /// The agent's reasoning, condensed.
    var thoughtSummary = String()
    var toolCalls = [AgyToolCall]()
    var plan = [AgyPlanEntry]()
    var permissions = [PendingPermissionRequest]()
    var durationSeconds: Double? = nil
    var isStreaming: Bool = false
    var activeStatusLabel = String()
}

struct AgyEffortOption: Identifiable, Equatable, Hashable {
    var id: String { value }
    let value: String
    let displayName: String
}

struct AgyBaseModel: Identifiable, Equatable, Hashable {
    let id: String
    let name: String
    var efforts: [AgyEffortOption]

    /// "Gemini 4 Neon (Thinking)" → "4 Neon": without a leading "Gemini "/"Claude " and a
    /// trailing "(Thinking)"-style suffix.
    var shortName: String {
        var out = name.trimmingCharacters(in: .whitespaces)
        for brand in ["Gemini ", "Claude "] where out.hasPrefix(brand) {
            out = String(out.dropFirst(brand.count))
            break
        }
        if out.hasSuffix(")"), let open = out.range(of: " (", options: .backwards) {
            let word = out[open.upperBound..<out.index(before: out.endIndex)]
            if !word.isEmpty, word.allSatisfy(\.isLetter) {
                out = String(out[..<open.lowerBound])
            }
        }
        out = out.trimmingCharacters(in: .whitespaces)
        return out.isEmpty ? name : out
    }

    static func effortDisplayName(value: String, name: String) -> String {
        let v = value.lowercased()
        if Self.extraSpellings.contains(v) { return String(localized: "Extra") }
        return v == "max" ? String(localized: "Max") : name
    }

    private static let extraSpellings: Set<String> = ["xhigh", "extra_high", "extra-high"]
}

struct AgyMode: Identifiable, Equatable, Hashable {
    let id: String
    let name: String
    var description: String = ""
    /// The connector marks the mode as running everything without asking.
    var bypassFlag = false

    /// Runs everything without asking (Turbo): shown with a warning. By id or flag only.
    var bypassesPermissions: Bool { bypassFlag || id.lowercased() == "turbo" }

    var symbol: String {
        let key = id.lowercased()
        for (names, glyph) in Self.glyphTable where names.contains(key) { return glyph }
        return bypassFlag ? "exclamationmark.shield.fill" : "slider.horizontal.3"
    }

    private static let glyphTable: [(Set<String>, String)] = [
        (["plan"], "checklist"),
        (["auto"], "gearshape.2.fill"),
        (["accept_edits", "acceptedits", "accept-edits"], "pencil"),
        (["default"], "checkmark.shield.fill"),
        (["turbo"], "exclamationmark.shield.fill"),
    ]
}

/// Order of the drawer's projects.
enum FolderSort: String, CaseIterable {
    case recent, name
}

struct WorkspaceFolder: Identifiable, Equatable, Hashable {
    /// Unique over all computers: host and path.
    var id: String { host + "|" + path }
    let path: String
    let name: String
    let kind: String?
    /// Paired computer (endpoint id) the folder is on.
    var host: String = ""
    /// Not listed by the connector; made up from the chats that use it.
    var synthesized = false

    /// "/a/b/" → "/a/b", so the same folder written two ways groups together.
    static func normalize(_ path: String) -> String {
        var p = path.trimmingCharacters(in: .whitespaces)
        while p.count > 1, p.hasSuffix("/") { p.removeLast() }
        return p
    }
}

struct ChatSummary: Identifiable, Equatable, Hashable {
    enum State: String { case idle, running, openInCli = "open_in_cli" }
    let id: String
    var title: String
    var folder: String?
    var updated: Date
    var state: State
    /// Paired computer (endpoint id) the chat lives on.
    var host: String = ""
    /// A revision marker, when the connector sends one; with `updated` it tells changes apart.
    var revision: String?
    var changeStamp: String { "\(updated.timeIntervalSince1970)|\(revision ?? "")" }
}

struct SlashCommand: Identifiable, Equatable, Hashable {
    var name: String
    var description: String
    var id: String { name }
}

struct WorkspaceFile: Identifiable, Equatable, Hashable {
    var path: String
    var size: Int
    var id: String { path }
}

/// A paired computer.
struct RemoteDevice: Identifiable, Equatable, Codable {
    enum Status: String, Codable { case connected, standby, offline }
    /// Trying to reach it (shown as "Connecting…" wherever the computer appears).
    var isConnecting: Bool { status == .standby }
    /// The path in use, from the P2P library's `direct` / `relay` / `p2p`.
    var transportLabel: String {
        switch transport {
        case "direct": String(localized: "Direct")
        case "relay": String(localized: "Relay")
        case "": ""
        default: "P2P"
        }
    }

    let id: String
    var ticket: String
    var name: String
    var hardwareModel: String = ""
    var osVersion: String = ""
    var agentVersion: String = ""
    var connectorVersion: String = ""
    var status: Status = .standby
    var latencyMs: Int = 0
    var transport: String = ""
    var relayAllowed: Bool = false
    /// Oldest app version the connector works with ("" when it doesn't say).
    var minAppVersion: String = ""
    /// What the connector says about an available or running update.
    var updateNote: String?
    /// connector.info came back without any connector version (a pre-protocol connector).
    var versionMissing = false

    /// The simulated computer from the demo pairing code.
    var isDemo: Bool { id == DemoConnector.endpointId }

    /// Oldest agy-connector this app works with.
    static let minimumConnector = "0.2.0"

    private enum CodingKeys: String, CodingKey { case id, ticket, name, hardwareModel, osVersion }

    init(id endpoint: String, ticket uri: String, name label: String,
         hardwareModel hw: String = "", osVersion os: String = "", agentVersion agent: String = "",
         connectorVersion connector: String = "", status state: Status = .standby,
         latencyMs ping: Int = 0, transport link: String = "") {
        id = endpoint; ticket = uri; name = label
        hardwareModel = hw; osVersion = os
        agentVersion = agent; connectorVersion = connector
        status = state; latencyMs = ping; transport = link
    }

    init(from decoder: Decoder) throws {
        let box = try decoder.container(keyedBy: CodingKeys.self)
        func text(_ k: CodingKeys) throws -> String { try box.decodeIfPresent(String.self, forKey: k) ?? "" }
        id = try box.decode(String.self, forKey: .id)
        ticket = try box.decode(String.self, forKey: .ticket)
        osVersion = try text(.osVersion)
        hardwareModel = try text(.hardwareModel)
        name = try text(.name)
    }

    /// Connected, and the connector reports a version older than this app needs.
    var connectorTooOld: Bool {
        guard status == .connected else { return false }
        if connectorVersion.isEmpty { return versionMissing }
        return !VersionNumber.atLeast(connectorVersion, Self.minimumConnector)
    }

    /// Connected, and the connector asks for a newer app than this one.
    var appTooOld: Bool {
        status == .connected && !minAppVersion.isEmpty && !VersionNumber.atLeast(VersionNumber.app, minAppVersion)
    }

    /// SF Symbol for the kind of computer, from its hardware model id.
    var deviceIconName: String {
        let probe = "\(hardwareModel) \(name)".lowercased()
        let studio = ["mac14,13", "mac14,14"].contains { probe.hasPrefix($0) }
        let rules: [(Bool, String)] = [
            (["macpro", "mac pro"].contains { probe.contains($0) }, "macpro.gen3"),
            (studio || probe.contains("studio"), "macstudio"),
            (["macmini", "mac mini"].contains { probe.contains($0) }, "macmini"),
            (["macbook", "laptop"].contains { probe.contains($0) }, "laptopcomputer"),
        ]
        return rules.first { $0.0 }?.1 ?? "desktopcomputer"
    }
}

/// Dotted version numbers compared part by part; absent parts are zero.
enum VersionNumber {
    static var app: String {
        (Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String) ?? "0"
    }

    static func parts(_ v: String) -> [Int] {
        var t = v.trimmingCharacters(in: .whitespaces)
        if t.lowercased().hasPrefix("v") { t.removeFirst() }
        return t.split(separator: ".").map { piece in Int(piece.prefix { $0.isNumber }) ?? 0 }
    }

    static func atLeast(_ version: String, _ floor: String) -> Bool {
        let lhs = parts(version), rhs = parts(floor)
        let n = max(lhs.count, rhs.count)
        for k in 0..<n {
            let l = k < lhs.count ? lhs[k] : 0
            let r = k < rhs.count ? rhs[k] : 0
            if l != r { return l > r }
        }
        return true
    }
}

/// One hit of the global search.
enum GlobalSearchResult: Identifiable {
    case chat(ChatSummary, folderName: String)
    case folder(WorkspaceFolder)
    case model(AgyBaseModel)
    case message(chatId: String, message: ChatMessage)

    var id: String {
        let parts: [String]
        switch self {
        case .chat(let summary, folderName: _): parts = ["chat", summary.host, summary.id]
        case .folder(let f): parts = ["folder", f.host, f.path]
        case .model(let m): parts = ["model", m.id]
        case .message(chatId: let owner, message: let msg): parts = ["message", owner, msg.id]
        }
        return parts.joined(separator: ":")
    }
}

struct SessionUsage: Equatable {
    var used: Int
    var size: Int
    var fraction: Double {
        guard size > 0 else { return 0 }
        let share = Double(used) / Double(size)
        return share > 1 ? 1 : share
    }
}

struct QuotaLimit: Identifiable, Equatable {
    var id: String
    var group: String
    var window: String
    var label: String
    var remaining: Double
    var resets: Date?
}

struct QuotaSnapshot: Equatable {
    var limits: [QuotaLimit]
    var credits: Double?
    var fetchedAt: Date
}

enum ThinkingIndicatorStyle: String, CaseIterable, Identifiable {
    case ring, prompt, braille, infinity, brackets, mobius, dots, random
    var id: String { rawValue }
    var title: LocalizedStringKey {
        switch self {
        case .ring: "Ring"
        case .prompt: "Prompt  >_"
        case .braille: "Braille"
        case .infinity: "Infinity"
        case .brackets: "Brackets  < >"
        case .mobius: "Möbius strip"
        case .dots: "Dots"
        case .random: "Random"
        }
    }
}

enum NotificationKind: String, CaseIterable, Identifiable {
    case finished, failed, approval, cliReleased = "cli_released"
    var id: String { rawValue }
    var title: LocalizedStringKey {
        switch self {
        case .finished: "Finished"
        case .failed: "Failed"
        case .approval: "Approval needed"
        case .cliReleased: "Available again"
        }
    }
    var detail: LocalizedStringKey {
        switch self {
        case .finished: "When a reply is complete."
        case .failed: "When a turn stops with an error."
        case .approval: "When the agent asks before editing or running something, or asks you a question."
        case .cliReleased: "When the agy CLI session of a chat you're viewing is released, so you can reply from here."
        }
    }
}
