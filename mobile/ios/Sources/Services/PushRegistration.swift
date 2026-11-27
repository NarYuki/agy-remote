// SPDX-License-Identifier: AGPL-3.0-only
import CryptoKit
import SwiftUI
import UIKit
import UserNotifications

/// Receives the APNs device token and notification taps, and forwards them to the view model.
final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    /// Set by the app once the view model exists.
    @MainActor static var onDeviceToken: ((Data) -> Void)?
    @MainActor static var onRegistrationError: ((Error) -> Void)?
    @MainActor static var onOpenSession: ((String) -> Void)?
    /// A grant from the push relay arrived (`PushGrants.parse` reads it).
    @MainActor static var onGrant: (([AnyHashable: Any]) -> Void)?
    /// Allow / Deny tapped on an approval notification.
    /// approval id, allow?, and the computer that sent it (endpoint id; empty if unknown).
    @MainActor static var onPermissionAction: ((String, Bool, String) async -> Void)?

    static let permissionCategory = "permission"
    static let allowAction = "allow"
    static let denyAction = "deny"
    /// Session currently on screen; its notifications are not shown as banners.
    @MainActor static var visibleSessionId: String?

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        let center = UNUserNotificationCenter.current()
        center.delegate = self
        // Approval notifications get Allow / Deny buttons. Allow needs the phone unlocked.
        center.setNotificationCategories([
            UNNotificationCategory(
                identifier: Self.permissionCategory,
                actions: [
                    UNNotificationAction(identifier: Self.allowAction, title: String(localized: "Allow"), options: [.authenticationRequired]),
                    UNNotificationAction(identifier: Self.denyAction, title: String(localized: "Deny"), options: [.destructive]),
                ],
                intentIdentifiers: []
            ),
        ])
        return true
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        Task { @MainActor in Self.onDeviceToken?(deviceToken) }
    }

    /// A background push: only the relay's grants come this way (nothing is shown for them).
    func application(
        _ application: UIApplication,
        didReceiveRemoteNotification userInfo: [AnyHashable: Any],
        fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void
    ) {
        guard let text = userInfo["g"] as? String else {
            completionHandler(.noData)
            return
        }
        Task { @MainActor in
            Self.onGrant?(["g": text])
            completionHandler(.newData)
        }
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        Task { @MainActor in Self.onRegistrationError?(error) }
    }

    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping @Sendable (UNNotificationPresentationOptions) -> Void
    ) {
        let info = notification.request.content.userInfo
        // Grants come as background pushes; one sent as an alert (an older relay) is taken in
        // here and not shown either.
        if let text = info["g"] as? String {
            Task { @MainActor in
                Self.onGrant?(["g": text])
                completionHandler([])
            }
            return
        }
        let sid = info["sessionId"] as? String
        Task { @MainActor in
            completionHandler(sid != nil && sid == Self.visibleSessionId ? [] : [.banner, .sound, .list])
        }
    }

    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping @Sendable () -> Void
    ) {
        let info = response.notification.request.content.userInfo
        // A grant from an older relay, shown because the app wasn't open: a tap brings it in.
        if let text = info["g"] as? String {
            Task { @MainActor in
                Self.onGrant?(["g": text])
                completionHandler()
            }
            return
        }
        let sid = info["sessionId"] as? String
        let action = response.actionIdentifier
        if action == Self.allowAction || action == Self.denyAction, let approval = info["approvalId"] as? String {
            let computer = info["computer"] as? String ?? ""
            Task { @MainActor in
                // Keep running long enough to reach the computer if the app was in the background.
                let bg = UIApplication.shared.beginBackgroundTask(withName: "approval")
                await Self.onPermissionAction?(approval, action == Self.allowAction, computer)
                UIApplication.shared.endBackgroundTask(bg)
                completionHandler()
            }
            return
        }
        Task { @MainActor in
            if let sid { Self.onOpenSession?(sid) }
            completionHandler()
        }
    }
}

/// APNs environment this build's push token belongs to. It follows the signing, not the build
/// configuration: a Release build installed from Xcode is signed for development and gets a
/// sandbox token. App Store and TestFlight builds carry no `embedded.mobileprovision`, or one
/// that says "production".
enum PushEnvironment {
    static let name: String = {
        guard let url = Bundle.main.url(forResource: "embedded", withExtension: "mobileprovision"),
              let data = try? Data(contentsOf: url),
              let text = String(data: data, encoding: .isoLatin1),
              let key = text.range(of: "<key>aps-environment</key>"),
              let open = text.range(of: "<string>", range: key.upperBound..<text.endIndex),
              let close = text.range(of: "</string>", range: open.upperBound..<text.endIndex)
        else { return "production" }
        return text[open.upperBound..<close.lowerBound] == "development" ? "sandbox" : "production"
    }()
}


