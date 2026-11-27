// SPDX-License-Identifier: AGPL-3.0-only
import Foundation

/// The last snapshot from the phone, kept in the app group so the complications can draw it.
enum SnapshotStore {
    static let group = "group.moe.n4tsu.agyremote"
    private static let key = "watch.snapshot"

    private static var defaults: UserDefaults { UserDefaults(suiteName: group) ?? .standard }

    static func save(_ snap: WatchSnapshot) {
        guard let data = try? JSONEncoder().encode(snap) else { return }
        defaults.set(data, forKey: key)
    }

    static func load() -> WatchSnapshot? {
        guard let data = defaults.data(forKey: key) else { return nil }
        return try? JSONDecoder().decode(WatchSnapshot.self, from: data)
    }
}

/// Where a complication tap lands in the app.
enum WatchPage: String, Hashable, CaseIterable {
    case now, approvals, chats

    static let scheme = "agyremote-watch"
    var url: URL { URL(string: "\(Self.scheme)://\(rawValue)")! }
    init?(url: URL) {
        guard url.scheme == Self.scheme, let page = WatchPage(rawValue: url.host() ?? "") else { return nil }
        self = page
    }
}
