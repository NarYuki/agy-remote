// SPDX-License-Identifier: AGPL-3.0-only
import UserNotifications

/// Decrypts AGY Remote notifications on the device before they are shown. If anything fails, the
/// generic "New activity" text from the relay is shown instead.
final class NotificationService: UNNotificationServiceExtension {
    override func didReceive(
        _ request: UNNotificationRequest,
        withContentHandler contentHandler: @escaping (UNNotificationContent) -> Void
    ) {
        let content = (request.content.mutableCopy() as? UNMutableNotificationContent) ?? UNMutableNotificationContent()
        defer { contentHandler(content) }

        // Each computer has its own key: the one that opens it says which computer sent it.
        guard let sealed = request.content.userInfo["c"] as? String,
              let (computer, message) = PushKeyStore.open(sealed),
              !message.isStale() else {
            // Undecryptable or out of date: the relay's generic text, with no approval buttons.
            return
        }

        let chat = message.title.isEmpty ? String(localized: "Chat") : message.title
        switch message.kind {
        case .turnComplete:
            content.title = chat
            content.body = message.body.isEmpty ? String(localized: "Done") : message.body
        case .turnFailed:
            content.title = String(localized: "\(chat) — failed", comment: "Notification title when a turn fails")
            content.body = Self.failure(message.code)
        case .cliReleased:
            content.title = String(localized: "You can reply now")
            content.body = String(localized: "\(chat): agy CLI session released. Continue it from your phone.")
        case .permission:
            content.title = String(localized: "Approval needed")
            content.subtitle = chat
            content.body = message.body
            content.interruptionLevel = .timeSensitive
            // Allow / Deny only when the computer sent the approval id: it leaves it out when the
            // request is too long to read in full here, so it is answered in the app.
            if message.approvalId?.isEmpty == false { content.categoryIdentifier = "permission" }
        case .question:
            content.title = String(localized: "Answer needed")
            content.subtitle = chat
            content.body = message.body
            content.interruptionLevel = .timeSensitive
        }
        content.threadIdentifier = message.sessionId
        content.userInfo = [
            "sessionId": message.sessionId,
            "kind": message.kind.rawValue,
            "approvalId": message.approvalId ?? "",
            "computer": computer,
        ]
    }

    /// Why the turn failed, in the phone's language (the computer sends a code, not prose).
    static func failure(_ code: String?) -> String {
        switch code {
        case "max_tokens": String(localized: "The reply hit its length limit and was cut off.")
        case "max_turn_requests": String(localized: "The agent reached its step limit for one turn.")
        case "refusal": String(localized: "The model declined to continue.")
        case "agy_unavailable": String(localized: "agy isn't available on the computer.")
        case "open_in_cli": String(localized: "This chat is open in the agy CLI. Close it there to reply here.")
        default: String(localized: "Something went wrong on the computer.")
        }
    }
}
