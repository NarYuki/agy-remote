import Foundation

/// Swift face of the Rust P2P library (`agy_p2p_core.h`). Every call blocks, so each one runs on
/// a background thread and is exposed as `async`.
final class P2PClient: @unchecked Sendable {
    private let handle: OpaquePointer
    /// This phone's P2P secret key: in the keychain, on this device only (not in any backup).
    static let identityItem = KeychainBlob(service: "moe.n4tsu.agyremote.p2p", account: "identity-v1")
    /// This phone's endpoint id, shown in Settings for diagnostics.
    let endpointId: String

    /// Nil when the library couldn't create a client.
    init?() {
        let stored = Self.identityItem.read().flatMap { String(data: $0, encoding: .utf8) }.flatMap { $0.isEmpty ? nil : $0 }
        guard let created = agy_p2p_client_create(stored) else { return nil }
        handle = created
        var ownId = ""
        if let raw = agy_p2p_client_identity_json(handle) {
            let json = String(cString: raw)
            agy_p2p_free_string(raw)
            if let obj = try? JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any] {
                ownId = obj["endpoint_id"] as? String ?? ""
                // Keep the key it uses (new, or a replacement for an unusable one) in the keychain.
                if let key = obj["secret_key_hex"] as? String, !key.isEmpty, key != stored {
                    _ = Self.identityItem.write(Data(key.utf8))
                }
            }
        }
        endpointId = ownId
    }

    /// At most a few blocking calls per computer at once, so one slow host can't starve the pool.
    private let gates = GateTable(limit: 3)

    /// How long each method may take before giving up.
    static func timeoutMs(for method: String) -> UInt32 {
        switch method {
        case "connector.info": 25_000
        case "chat.open": 30_000
        case "limits.get": 60_000
        case "chat.set_option": 10_000
        case "approval.answer": 5_000
        default: 15_000
        }
    }

    deinit { agy_p2p_client_destroy(handle) }

    func setRelayAllowed(_ allowed: Bool) {
        agy_p2p_set_relay_allowed(handle, allowed)
    }

    /// A checked pairing code: the connector's endpoint id, the canonical `agy-rem://pair` URI to
    /// store, and what the ticket says about the computer.
    struct Ticket {
        let endpointId: String
        let uri: String
        let hostName: String
        let hardwareModel: String
        let osVersion: String
        /// Set in a code from `agy-connector pair`: the phone still has to type the computer's number.
        var pairingSecret: String?
    }

    /// Validates a pairing code (URI or raw code).
    static func parseTicket(_ raw: String) -> Result<Ticket, P2PError> {
        let input = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        let invalid = String(localized: "Invalid pairing code")
        guard let reply = agy_p2p_parse_ticket(input) else { return .failure(.message(invalid)) }
        let text = String(cString: reply)
        agy_p2p_free_string(reply)
        let parsed = (try? JSONSerialization.jsonObject(with: Data(text.utf8))) as? [String: Any] ?? [:]
        guard parsed["ok"] as? Bool == true, let body = parsed["ticket"] as? [String: Any] else {
            return .failure(.message((parsed["error"] as? String) ?? invalid))
        }
        let rawNode = body["endpoint_id"] as? String
        let node = rawNode?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let wellFormed = node.count >= 32 && node.unicodeScalars.allSatisfy { CharacterSet.alphanumerics.contains($0) && $0.isASCII }
        guard wellFormed else { return .failure(.message(invalid)) }
        // The canonical URI sits next to `ticket` in the reply, not inside it.
        let canonical = (parsed["uri"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? input
        return .success(Ticket(endpointId: node, uri: canonical,
                               hostName: (body["host_name"] as? String) ?? "",
                               hardwareModel: (body["hardware_model"] as? String) ?? "",
                               osVersion: (body["os_version"] as? String) ?? "",
                               pairingSecret: (body["pairing_secret"] as? String).flatMap { $0.isEmpty ? nil : $0 }))
    }

    /// One request, one reply.
    static func encode(_ params: [String: Any]) -> String {
        (try? JSONSerialization.data(withJSONObject: params)).flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
    }

    func request(ticket: String, method: String, body: String = "{}", timeoutMs: UInt32? = nil) async throws -> [String: Any] {
        try await rawRequest(ticket: ticket, method: method, body: body, timeoutMs: timeoutMs).value
    }

    /// A request whose reply is turned into `T` off the main actor (e.g. a decoded transcript).
    func request<T: Sendable>(ticket: String, method: String, body: String = "{}",
                              decode: @escaping @Sendable ([String: Any]) -> T) async throws -> T {
        let raw = try await rawRequest(ticket: ticket, method: method, body: body, timeoutMs: nil)
        return await Task.detached(priority: .userInitiated) { decode(raw.value) }.value
    }

    private func rawRequest(ticket: String, method: String, body: String, timeoutMs: UInt32?) async throws -> SendableDict {
        if DemoConnector.owns(ticket: ticket) { return try await DemoConnector.shared.reply(method: method, body: body) }
        let gate = await gates.gate(for: ticket)
        await gate.wait()
        defer { Task { await gate.signal() } }
        let handle = self.handle
        let timeoutMs = timeoutMs ?? Self.timeoutMs(for: method)
        return try await withCheckedThrowingContinuation { (cont: CheckedContinuation<SendableDict, Error>) in
            DispatchQueue.global(qos: .userInitiated).async {
                guard let out = agy_p2p_request_json(handle, ticket, method, body, timeoutMs) else {
                    cont.resume(throwing: P2PError.transport("No reply")); return
                }
                let s = String(cString: out)
                agy_p2p_free_string(out)
                guard let raw = try? JSONSerialization.jsonObject(with: Data(s.utf8)) as? [String: Any] else {
                    cont.resume(throwing: P2PError.transport("Invalid reply")); return
                }
                let obj = SendableDict(raw)
                if obj.value["ok"] as? Bool == false {
                    if let err = obj.value["error"] as? [String: Any] {
                        cont.resume(throwing: P2PError.remote(code: err["code"] as? String ?? "internal", message: err["message"] as? String ?? ""))
                    } else {
                        cont.resume(throwing: P2PError.transport(obj.value["error"] as? String ?? "Unknown error"))
                    }
                    return
                }
                cont.resume(returning: obj)
            }
        }
    }

    /// `turn.start`: delivers each event frame to `onEvent` (on the main actor) until the turn ends
    /// or the stream breaks. The result code is delivered through the main queue after the last
    /// event, so the caller never sees the end before an event. 0 means a clean end.
    func streamTurn(ticket: String, requestId: String, body: String, onEvent: @escaping @MainActor ([String: Any]) -> Void) async -> Int32 {
        if DemoConnector.owns(ticket: ticket) {
            return await DemoConnector.shared.stream(requestId: requestId, body: body, onEvent: onEvent)
        }
        let handle = self.handle
        let box = StreamBox(onEvent)
        return await withCheckedContinuation { cont in
            DispatchQueue.global(qos: .userInitiated).async {
                let ctx = Unmanaged.passRetained(box).toOpaque()
                let code = agy_p2p_stream_chat(handle, ticket, requestId, body, { json, ctx in
                    guard let json, let ctx else { return }
                    let box = Unmanaged<StreamBox>.fromOpaque(ctx).takeUnretainedValue()
                    let s = String(cString: json)
                    guard let obj = try? JSONSerialization.jsonObject(with: Data(s.utf8)) as? [String: Any] else { return }
                    let payload = SendableDict(obj)
                    DispatchQueue.main.async { MainActor.assumeIsolated { box.handler(payload.value) } }
                }, ctx)
                Unmanaged<StreamBox>.fromOpaque(ctx).release()
                DispatchQueue.main.async { cont.resume(returning: code) }
            }
        }
    }

    /// Stops listening; the turn continues on the computer.
    func detach(ticket: String, requestId: String) {
        if DemoConnector.owns(ticket: ticket) { Task { @MainActor in DemoConnector.shared.forget(requestId: requestId) }; return }
        let handle = self.handle
        DispatchQueue.global().async { agy_p2p_cancel_stream(handle, ticket, requestId) }
    }
}

/// A counting semaphore for async callers.
actor AsyncGate {
    private var free: Int
    private var waiters: [CheckedContinuation<Void, Never>] = []
    init(_ n: Int) { free = n }
    func wait() async {
        if free > 0 { free -= 1; return }
        await withCheckedContinuation { waiters.append($0) }
    }
    func signal() {
        if waiters.isEmpty { free += 1 } else { waiters.removeFirst().resume() }
    }
}

private actor GateTable {
    let limit: Int
    private var gates: [String: AsyncGate] = [:]
    init(limit: Int) { self.limit = limit }
    func gate(for key: String) -> AsyncGate {
        if let g = gates[key] { return g }
        let g = AsyncGate(limit); gates[key] = g; return g
    }
}

private final class StreamBox: @unchecked Sendable {
    let handler: @MainActor ([String: Any]) -> Void
    init(_ h: @escaping @MainActor ([String: Any]) -> Void) { handler = h }
}

struct SendableDict: @unchecked Sendable {
    let value: [String: Any]
    init(_ v: [String: Any]) { value = v }
}

enum P2PError: Error, LocalizedError {
    case message(String)
    case transport(String)
    case remote(code: String, message: String)

    var code: String? {
        if case let .remote(c, _) = self { return c }
        return nil
    }

    /// The original (English) detail, for the diagnostics in Settings.
    var diagnostic: String {
        switch self {
        case let .message(text), let .transport(text): text
        case let .remote(c, text): text.isEmpty ? c : "\(c): \(text)"
        }
    }

    var errorDescription: String? {
        switch self {
        case let .message(text):
            return text
        case .transport:
            // The network library's English detail stays in the diagnostics (lastP2PError).
            return String(localized: "Couldn't reach the computer.")
        case let .remote(c, text):
            switch c {
            case "folder_required": return String(localized: "Pick a folder for this chat first.")
            case "open_in_cli": return String(localized: "This chat is open in the agy CLI. Close it there to reply here.")
            case "busy": return String(localized: "This chat is already working on something.")
            case "agy_unavailable": return String(localized: "agy isn't available on the computer.")
            case "not_found": return String(localized: "Not found on the computer.")
            case "not_allowed": return String(localized: "The computer refused this request.")
            case "not_paired": return String(localized: "This phone isn't paired with the computer any more. Run agy-connector pair on the computer and scan the code again.")
            // The computer's English detail is for logs; the phone says it in its own language.
            default: return String(localized: "Something went wrong on the computer.")
            }
        }
    }
}
