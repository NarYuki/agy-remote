// SPDX-License-Identifier: AGPL-3.0-only
package moe.n4tsu.agyremote.push

/** What the push service needs to know about the running app (iOS AppDelegate statics). */
object PushState {
    /** Whether an activity is started (on screen). */
    @Volatile var foreground = false
    /** Chat currently on screen; its notifications are not shown (iOS visibleSessionId). */
    @Volatile var visibleSessionId: String? = null
    /** Set by the view model: a new FCM token arrived (called on the main thread). */
    @Volatile var onNewToken: ((String) -> Unit)? = null
    /** Set by the view model: the push relay sent a grant (`g`, see [PushGrants]); called on the main thread. */
    @Volatile var onGrant: ((String) -> Unit)? = null
    /** Set by the view model: Allow/Deny tapped on an approval notification. True if a computer took it. */
    /** Answers an approval on `computer` (endpoint id of the computer whose key opened it). */
    @Volatile var answerApproval: (suspend (approvalId: String, allow: Boolean, computer: String) -> Boolean)? = null
}
