// SPDX-License-Identifier: AGPL-3.0-only
package moe.n4tsu.agyremote.push

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import moe.n4tsu.agyremote.net.ConnectorClient
import moe.n4tsu.agyremote.net.NativeP2P
import moe.n4tsu.agyremote.net.NativeTransport
import moe.n4tsu.agyremote.net.Result
import moe.n4tsu.agyremote.state.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Allow / Deny on an approval notification, answered without opening the app (iOS
 * onPermissionAction). The notification doesn't say which computer asked, so each one is tried
 * until one knows the approval.
 */
class ApprovalActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val approvalId = intent.getStringExtra(EXTRA_APPROVAL) ?: return
        val sessionId = intent.getStringExtra(EXTRA_SESSION).orEmpty()
        val allow = intent.getBooleanExtra(EXTRA_ALLOW, false)
        val computer = intent.getStringExtra(EXTRA_COMPUTER).orEmpty()
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val ok = withTimeoutOrNull(9_000) { answer(app, approvalId, allow, computer) } ?: false
                if (ok && sessionId.isNotEmpty()) Notifications.cancel(app, sessionId)
            } finally {
                pending.finish()
            }
        }
    }

    /** Answers only `computer`: no other paired computer sees the approval id or can swallow it. */
    private suspend fun answer(context: Context, approvalId: String, allow: Boolean, computer: String): Boolean {
        if (computer.isEmpty()) return false
        // The app is running: use its connection.
        PushState.answerApproval?.let { return it(approvalId, allow, computer) }
        // Otherwise a short-lived endpoint with the phone's identity (the app's own is closed).
        if (!NativeP2P.isAvailable) return false
        val store = Store(context)
        val transport = NativeTransport(store.secretKeyHex)
        return try {
            transport.setRelayAllowed(store.relayAllowed)
            store.devices.filter { it.id == computer }.any { d ->
                ConnectorClient(transport, d.ticket).call("approval.answer",
                    org.json.JSONObject().put("approvalId", approvalId).put("choice", if (allow) "allow" else "deny"),
                    timeoutMs = 7_000) is Result.Ok
            }
        } finally {
            transport.close()
        }
    }

    companion object {
        private const val EXTRA_APPROVAL = "approvalId"
        private const val EXTRA_SESSION = "sessionId"
        private const val EXTRA_ALLOW = "allow"
        private const val EXTRA_COMPUTER = "computer"

        fun intent(context: Context, approvalId: String, sessionId: String, allow: Boolean, computer: String): PendingIntent {
            val i = Intent(context, ApprovalActionReceiver::class.java)
                .setAction(if (allow) "moe.n4tsu.agyremote.APPROVAL_ALLOW" else "moe.n4tsu.agyremote.APPROVAL_DENY")
                .putExtra(EXTRA_APPROVAL, approvalId).putExtra(EXTRA_SESSION, sessionId).putExtra(EXTRA_ALLOW, allow)
                .putExtra(EXTRA_COMPUTER, computer)
            return PendingIntent.getBroadcast(context, (approvalId + allow).hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }
}
