// SPDX-License-Identifier: AGPL-3.0-only
import Foundation

/// Grants from the push relay: its word that one computer may send notifications to this
/// phone's push token (see relay/src/index.js). The phone asks the relay, the relay answers with
/// a push to that token (so only the phone that has the token gets it), and the phone hands the
/// grant to the computer with `notifications.register`. A grant is no secret worth guarding: it
/// only works for that computer's key and this token. Without one, notifications still arrive,
/// from a smaller budget the relay keeps for apps that predate grants.
enum PushGrants {
    struct Grant: Codable, Equatable {
        var value: String
        /// Unix seconds.
        var expires: Int
        /// The push token and relay it was made for; a new token or relay needs a new grant.
        var token: String
        var relay: String
    }

    private static let storeKey = "agy.push.grants"
    /// Asked for again this long before it runs out.
    static let renewBefore: TimeInterval = 10 * 24 * 60 * 60

    static func all() -> [String: Grant] {
        guard let data = UserDefaults.standard.data(forKey: storeKey) else { return [:] }
        return (try? JSONDecoder().decode([String: Grant].self, from: data)) ?? [:]
    }

    private static func save(_ list: [String: Grant]) {
        if list.isEmpty { UserDefaults.standard.removeObject(forKey: storeKey); return }
        if let data = try? JSONEncoder().encode(list) { UserDefaults.standard.set(data, forKey: storeKey) }
    }

    /// The computer's grant, if it is for this token and relay and still good.
    static func grant(for computer: String, token: String, relay: String, now: Date = Date()) -> Grant? {
        guard let g = all()[computer], g.token == token, g.relay == relay,
              Double(g.expires) > now.timeIntervalSince1970 else { return nil }
        return g
    }

    static func set(_ grant: Grant, for computer: String) {
        var list = all()
        list[computer] = grant
        save(list)
    }

    static func remove(computer: String) {
        var list = all()
        if list.removeValue(forKey: computer) != nil { save(list) }
    }

    static func removeAll() { save([:]) }

    /// The relay's address as the computer reports it, if it is one this app will contact:
    /// HTTPS with a host and nothing else in it that could carry data away.
    static func endpoint(relay: String) -> URL? {
        guard let parts = URLComponents(string: relay), parts.scheme?.lowercased() == "https",
              let host = parts.host, !host.isEmpty, parts.user == nil, parts.password == nil,
              parts.query == nil, parts.fragment == nil else { return nil }
        return URL(string: relay.hasSuffix("/") ? relay + "v1/grant" : relay + "/v1/grant")
    }

    /// Most computers one request may name (the relay's limit).
    static let maxKeysPerRequest = 16

    /// Asks the relay to push grants for `connectorKeys` (one per computer, all in one request)
    /// to `token`. The reply says only whether it was sent (its HTTP status; 404 from a relay that
    /// hands out none); the grants themselves arrive as a background push (`parse`).
    static func ask(relay: String, token: String, environment: String, connectorKeys: [String]) async -> Int? {
        guard let url = endpoint(relay: relay), !connectorKeys.isEmpty else { return nil }
        var request = URLRequest(url: url, timeoutInterval: 15)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try? JSONSerialization.data(withJSONObject: ["token": token, "env": environment, "keys": connectorKeys])
        guard let (_, response) = try? await URLSession.shared.data(for: request) else { return nil }
        return (response as? HTTPURLResponse)?.statusCode
    }

    /// What a push from the relay carries (`g`, a JSON text): one grant per computer key asked
    /// for, all good until `expires`.
    struct Received {
        let expires: Int
        let grants: [(key: String, value: String)]
    }

    static func parse(_ userInfo: [AnyHashable: Any]) -> Received? {
        guard let text = userInfo["g"] as? String,
              let obj = try? JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any],
              let expires = (obj["expires"] as? NSNumber)?.intValue,
              let list = obj["grants"] as? [[String: Any]] else { return nil }
        let grants: [(key: String, value: String)] = list.prefix(maxKeysPerRequest).compactMap { item in
            guard let key = item["key"] as? String, let value = item["grant"] as? String,
                  !value.isEmpty, value.count <= 128 else { return nil }
            return (key, value)
        }
        return grants.isEmpty ? nil : Received(expires: expires, grants: grants)
    }
}
