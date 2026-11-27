// SPDX-License-Identifier: AGPL-3.0-only
import SwiftUI
import UserNotifications
import WatchKit

@main
struct AGYWatchApp: App {
    @WKApplicationDelegateAdaptor(WatchAppDelegate.self) private var delegate
    @State private var link = PhoneLink()

    var body: some Scene {
        WindowGroup {
            root
                .task {
                    link.start()
                    WatchAppDelegate.link = link
                }
                .preferredColorScheme(.dark)
        }
    }

    @ViewBuilder private var root: some View {
        WatchRoot(link: link)
    }
}

/// Approval notifications mirrored from the phone carry Allow / Deny; with a watch app
/// installed the watch uses its own categories, so they are declared here too and the
/// answer goes back through the phone.
final class WatchAppDelegate: NSObject, WKApplicationDelegate, UNUserNotificationCenterDelegate {
    @MainActor static var link: PhoneLink?

    static let category = "permission"
    static let allow = "allow"
    static let deny = "deny"

    func applicationDidFinishLaunching() {
        let center = UNUserNotificationCenter.current()
        center.delegate = self
        let yes = UNNotificationAction(identifier: Self.allow, title: String(localized: "Allow"), options: [.authenticationRequired])
        let no = UNNotificationAction(identifier: Self.deny, title: String(localized: "Deny"), options: [.destructive])
        center.setNotificationCategories([
            UNNotificationCategory(identifier: Self.category, actions: [yes, no], intentIdentifiers: []),
        ])
    }

    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping @Sendable (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .sound, .list])
    }

    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping @Sendable () -> Void
    ) {
        let action = response.actionIdentifier
        let approval = response.notification.request.content.userInfo["approvalId"] as? String
        guard let approval, action == Self.allow || action == Self.deny else {
            completionHandler()
            return
        }
        let yes = action == Self.allow
        let computer = response.notification.request.content.userInfo["computer"] as? String ?? ""
        Task { @MainActor in
            await Self.link?.ask(.quickAnswer(approvalId: approval, allow: yes, computer: computer))
            completionHandler()
        }
    }
}
