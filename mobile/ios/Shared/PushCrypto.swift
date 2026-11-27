// SPDX-License-Identifier: AGPL-3.0-only
import CryptoKit
import Foundation
import Security

/// One small secret in the keychain, readable only on this device after its first unlock: never
/// in iCloud or computer backups, never moved to another phone.
struct KeychainBlob {
    let service: String
    let account: String
    /// Keychain access group, or nil for the app's own items.
    var group: String?

    func read() -> Data? {
        var query = base
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &out) == errSecSuccess else { return nil }
        return out as? Data
    }

    @discardableResult
    func write(_ data: Data) -> Bool {
        let update: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        let status = SecItemUpdate(base as CFDictionary, update as CFDictionary)
        if status == errSecSuccess { return true }
        guard status == errSecItemNotFound else { return false }
        var item = base
        item.merge(update) { _, new in new }
        return SecItemAdd(item as CFDictionary, nil) == errSecSuccess
    }

    func delete() {
        SecItemDelete(base as CFDictionary)
    }

    private var base: [String: Any] {
        var q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        if let group { q[kSecAttrAccessGroup as String] = group }
        return q
    }
}

/// The end-to-end keys for notifications: one per computer, so a computer can't make a
/// notification that looks like another's, and the phone knows which computer sent one (and so
/// where an approval goes). Shared between the app and its notification extension through a
/// keychain access group. Each computer gets only its own key at registration (over the
/// encrypted P2P link); the relay never sees any.
enum PushKeyStore {
    /// Keychain access group shared by the app and the extension: `<team id>.moe.n4tsu.agyremote.shared`,
    /// read from the `AGYKeychainAccessGroup` Info.plist key (`$(DEVELOPMENT_TEAM).…`, set in
    /// project.yml and NotificationService/Info.plist) so the team id is not hard-coded here.
    static let accessGroup: String = {
        let raw = Bundle.main.object(forInfoDictionaryKey: "AGYKeychainAccessGroup") as? String ?? ""
        let group = raw.hasPrefix(".") ? String(raw.dropFirst()) : raw // unsigned build: no team
        return group.isEmpty ? "moe.n4tsu.agyremote.shared" : group
    }()
    private static var keys: KeychainBlob {
        KeychainBlob(service: "moe.n4tsu.agyremote.push", account: "push-keys-v2", group: accessGroup)
    }

    /// Computer endpoint id → key.
    static func all() -> [String: SymmetricKey] {
        guard let data = keys.read(),
              let hex = try? JSONSerialization.jsonObject(with: data) as? [String: String] else { return [:] }
        return hex.compactMapValues { h in
            let bytes = Data(hexString: h)
            return bytes?.count == 32 ? SymmetricKey(data: bytes!) : nil
        }
    }

    /// This computer's key, made (and saved) the first time.
    static func key(for computer: String) -> SymmetricKey {
        var list = all()
        if let k = list[computer] { return k }
        let k = SymmetricKey(size: .bits256)
        list[computer] = k
        save(list)
        return k
    }

    static func remove(computer: String) {
        var list = all()
        if list.removeValue(forKey: computer) != nil { save(list) }
    }

    /// Forgets every key (an app reset), so earlier registrations can no longer decrypt.
    static func removeAll() {
        keys.delete()
    }

    private static func save(_ list: [String: SymmetricKey]) {
        let hex = list.mapValues { $0.hexString }
        if let data = try? JSONSerialization.data(withJSONObject: hex) { keys.write(data) }
    }

    /// Opens a sealed notification with whichever computer's key fits. Returns the computer too.
    static func open(_ sealed: String) -> (computer: String, message: PushMessage)? {
        for (computer, key) in all() {
            if let m = PushMessage.open(sealed, key: key) { return (computer, m) }
        }
        return nil
    }
}

extension SymmetricKey {
    var hexString: String { withUnsafeBytes { $0.map { String(format: "%02x", $0) }.joined() } }
}

extension Data {
    init?(hexString: String) {
        guard hexString.count % 2 == 0 else { return nil }
        var out = Data(capacity: hexString.count / 2)
        var i = hexString.startIndex
        while i < hexString.endIndex {
            let j = hexString.index(i, offsetBy: 2)
            guard let b = UInt8(hexString[i..<j], radix: 16) else { return nil }
            out.append(b)
            i = j
        }
        self = out
    }
}

/// The decrypted content of a notification sent by agy-connector.
struct PushMessage: Decodable {
    enum Kind: String, Decodable {
        case turnComplete = "turn_complete"
        case turnFailed = "turn_failed"
        case permission
        /// agy asks the user a question (answered in the app).
        case question
        case cliReleased = "cli_released"
    }

    let kind: Kind
    let sessionId: String
    let approvalId: String?
    let title: String
    let body: String
    let host: String
    /// Why a turn failed (an error code or agy's stop reason), to say in the phone's language.
    let code: String?
    /// When the computer sent it (unix seconds).
    let ts: Int?

    /// Too old to show as current: an approval the computer stopped waiting for (it gives up
    /// after 10 minutes), or anything older than a day. A copy replayed later by the relay or the
    /// push service is caught here too, since `ts` is inside the encrypted part.
    func isStale(now: Date = Date()) -> Bool {
        guard let ts else { return false }
        let age = now.timeIntervalSince1970 - Double(ts)
        return age > (kind == .permission ? 10 * 60 : 24 * 60 * 60)
    }

    /// Opens a sealed payload: base64(nonce ‖ ciphertext ‖ tag), ChaCha20-Poly1305, padded JSON.
    static func open(_ sealedBase64: String, key: SymmetricKey) -> PushMessage? {
        guard let raw = Data(base64Encoded: sealedBase64),
              let box = try? ChaChaPoly.SealedBox(combined: raw),
              let plain = try? ChaChaPoly.open(box, using: key, authenticating: Data("agy-push-v1".utf8)) else {
            return nil
        }
        return try? JSONDecoder().decode(PushMessage.self, from: plain)
    }
}
