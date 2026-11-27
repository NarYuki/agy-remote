// SPDX-License-Identifier: AGPL-3.0-only
import Foundation

/// A made-up snapshot for widget placeholders and the debug gallery. Not user-facing text.
enum SamplePicture {
    static var busy: WatchSnapshot {
        var s = WatchSnapshot()
        s.computer = .init(id: "mac", name: "Studio Mac", online: true)
        s.computers = [s.computer!]
        s.chats = [
            .init(id: "a", title: "Speed up forecast refresh", folder: "weatherkit-app", status: "Building",
                  isRunning: true, runningSince: Date().timeIntervalSince1970 - 194, lastLine: nil),
            .init(id: "b", title: "Add offline banner", folder: "weatherkit-app", status: "", isRunning: false,
                  runningSince: nil, lastLine: "Added the banner and a test for it."),
        ]
        s.approvals = [.init(id: "p", chatId: "a", chatTitle: "Speed up forecast refresh",
                             summary: "swift build -c release", choices: [])]
        s.quotaPercent = 71
        return s
    }
}
