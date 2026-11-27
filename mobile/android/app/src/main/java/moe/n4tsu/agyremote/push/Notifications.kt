// SPDX-License-Identifier: AGPL-3.0-only
package moe.n4tsu.agyremote.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import moe.n4tsu.agyremote.MainActivity
import moe.n4tsu.agyremote.R

/** Notification channels (one per kind, so each can be tuned in system settings) and posting. */
object Notifications {
    const val CHANNEL_FINISHED = "finished"
    const val CHANNEL_FAILED = "failed"
    const val CHANNEL_APPROVAL = "approval"
    const val CHANNEL_CLI_RELEASED = "cli_released"
    /** Shown only when a notification can't be decrypted (iOS: the relay's "New activity"). */
    const val CHANNEL_OTHER = "other"
    const val TAG_GENERIC = "agy-generic"

    /** Creates or renames the channels; called at startup so names follow the current language. */
    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        fun ch(id: String, name: Int, importance: Int) = NotificationChannel(id, context.getString(name), importance)
        nm.createNotificationChannels(listOf(
            ch(CHANNEL_FINISHED, R.string.finished, NotificationManager.IMPORTANCE_DEFAULT),
            ch(CHANNEL_FAILED, R.string.failed, NotificationManager.IMPORTANCE_DEFAULT),
            ch(CHANNEL_APPROVAL, R.string.needs_approval, NotificationManager.IMPORTANCE_HIGH),
            ch(CHANNEL_CLI_RELEASED, R.string.agy_cli_session_released, NotificationManager.IMPORTANCE_DEFAULT),
            ch(CHANNEL_OTHER, R.string.notifications, NotificationManager.IMPORTANCE_DEFAULT),
        ))
    }

    private fun openIntent(context: Context, sessionId: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (!sessionId.isNullOrEmpty()) intent.putExtra("sessionId", sessionId)
        return PendingIntent.getActivity(context, (sessionId ?: "").hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** Builds and posts the notification for [m] (iOS NotificationService). One per chat: a newer one replaces it. */
    /** `computer`: endpoint id of the computer that sent it; approvals are answered only there. */
    fun post(context: Context, m: PushMessage, computer: String) {
        val chat = m.title.ifEmpty { context.getString(R.string.chat) }
        val b = NotificationCompat.Builder(context, CHANNEL_FINISHED)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setAutoCancel(true)
            .setContentIntent(openIntent(context, m.sessionId))
            .setGroup(m.sessionId.ifEmpty { null })
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
        fun text(t: String) = b.setContentText(t).setStyle(NotificationCompat.BigTextStyle().bigText(t))
        when (m.kind) {
            "turn_complete" -> b.setChannelId(CHANNEL_FINISHED)
                .setContentTitle(chat).also { text(m.body.ifEmpty { context.getString(R.string.done) }) }
            "turn_failed" -> b.setChannelId(CHANNEL_FAILED)
                .setContentTitle(context.getString(R.string.push_failed_title, chat)).also { text(failure(context, m.code)) }
            "cli_released" -> b.setChannelId(CHANNEL_CLI_RELEASED)
                .setContentTitle(context.getString(R.string.push_you_can_reply_now))
                .also { text(context.getString(R.string.push_cli_released_body, chat)) }
            "permission" -> {
                b.setChannelId(CHANNEL_APPROVAL)
                    .setContentTitle(context.getString(R.string.push_approval_needed))
                    .setSubText(chat)
                    .also { text(m.body) }
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                m.approvalId?.let { id ->
                    // Allow needs the phone unlocked, like iOS .authenticationRequired.
                    b.addAction(NotificationCompat.Action.Builder(0, context.getString(R.string.allow),
                        ApprovalActionReceiver.intent(context, id, m.sessionId, true, computer)).setAuthenticationRequired(true).build())
                    b.addAction(NotificationCompat.Action.Builder(0, context.getString(R.string.deny),
                        ApprovalActionReceiver.intent(context, id, m.sessionId, false, computer)).build())
                }
            }
            "question" -> b.setChannelId(CHANNEL_APPROVAL)
                .setContentTitle(context.getString(R.string.push_answer_needed))
                .setSubText(chat)
                .also { text(m.body) }
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
            else -> return
        }
        notify(context, m.sessionId, b)
    }

    /** Why the turn failed, in the phone's language (the computer sends a code, not prose). */
    private fun failure(context: Context, code: String?): String = context.getString(when (code) {
        "max_tokens" -> R.string.push_failed_length
        "max_turn_requests" -> R.string.push_failed_steps
        "refusal" -> R.string.push_failed_refusal
        "agy_unavailable" -> R.string.push_failed_agy
        "open_in_cli" -> R.string.push_failed_open_in_cli
        else -> R.string.push_failed_other
    })

    /** The relay's fallback when the message can't be opened (key changed, or not ours). */
    fun postGeneric(context: Context) {
        val b = NotificationCompat.Builder(context, CHANNEL_OTHER)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(context.getString(R.string.agy_remote_title))
            .setContentText(context.getString(R.string.push_new_activity))
            .setAutoCancel(true)
            .setContentIntent(openIntent(context, null))
        notify(context, TAG_GENERIC, b)
    }

    fun cancel(context: Context, sessionId: String) = NotificationManagerCompat.from(context).cancel(sessionId, 0)

    @android.annotation.SuppressLint("MissingPermission")
    private fun notify(context: Context, tag: String, b: NotificationCompat.Builder) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        runCatching { nm.notify(tag, 0, b.build()) }
    }
}
