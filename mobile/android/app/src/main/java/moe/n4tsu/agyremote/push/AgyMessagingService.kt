// SPDX-License-Identifier: AGPL-3.0-only
package moe.n4tsu.agyremote.push

import android.os.Handler
import android.os.Looper
import moe.n4tsu.agyremote.state.Store
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Receives the data-only FCM messages from the push relay and decrypts them on the phone
 * (Android counterpart of the iOS NotificationService extension). Google only sees the opaque `c`.
 */
class AgyMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        // The relay's answer to a request the app just made: handed to it, never shown. Without
        // the app running there is nobody who asked, and it is dropped.
        message.data["g"]?.let { g ->
            Handler(Looper.getMainLooper()).post { PushState.onGrant?.invoke(g) }
            return
        }
        val store = Store(applicationContext)
        if (!store.notificationsEnabled) return
        val sealed = message.data["c"] ?: return
        // Each computer has its own key: the one that opens the message says which computer sent it.
        val opened = store.pushKeys().firstNotNullOfOrNull { (computer, key) -> PushCrypto.open(sealed, key)?.let { computer to it } }
        if (opened == null) {
            Notifications.postGeneric(applicationContext)
            return
        }
        val (computer, m) = opened
        // Out of date (or replayed later): not shown at all.
        if (m.isStale()) return
        // The per-kind switches (the computer filters too; this covers a switch it hasn't heard of yet).
        val setting = m.settingKind ?: return
        if (!store.notifyKind(setting)) return
        // Nothing is shown for the chat that is on screen.
        if (PushState.foreground && m.sessionId.isNotEmpty() && m.sessionId == PushState.visibleSessionId) return
        Notifications.post(applicationContext, m, computer)
    }

    override fun onNewToken(token: String) {
        Store(applicationContext).fcmToken = token
        Handler(Looper.getMainLooper()).post { PushState.onNewToken?.invoke(token) }
    }
}
