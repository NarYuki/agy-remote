package moe.n4tsu.agyremote.state

import android.content.Context

/**
 * User-facing texts the view model shows (toasts, alerts). Each is looked up by resource name so
 * a translation added to the string resources is picked up; until then the English (iOS)
 * text is used.
 */
object Texts {
    fun get(context: Context, name: String, fallback: String, vararg args: Any): String {
        val res = context.resources
        @Suppress("DiscouragedApi")
        val id = res.getIdentifier(name, "string", context.packageName)
        val pattern = if (id != 0) runCatching { res.getString(id) }.getOrNull() ?: fallback else fallback
        return if (args.isEmpty()) pattern else runCatching { String.format(pattern, *args) }.getOrDefault(pattern)
    }

    // The relay question (iOS AGYRemoteApp alert), asked once per launch after two failures in a row.
    fun relayTitle(c: Context) = get(c, "can_t_reach_your_computer_directly", "Can't reach your computer directly")
    fun relayMessage(c: Context) = get(c, "your_network_may_be_blocking_a_direct_connectio",
        "Your network may be blocking a direct connection. AGY Remote can pass the connection through a public relay. " +
            "It stays end-to-end encrypted: the relay can't read it, but it can see that your phone and computer are talking. " +
            "You can change this in Settings.")
    fun relayAllow(c: Context) = get(c, "use_relay", "Use Relay")
    fun relayNotNow(c: Context) = get(c, "not_now", "Not Now")

    fun reconnecting(c: Context) = get(c, "reconnecting", "Reconnecting…")
    fun couldntStartReply(c: Context) = get(c, "couldn_t_start_the_reply_on_the_computer", "Couldn't start the reply on the computer.")
    fun couldntReach(c: Context) = get(c, "couldn_t_reach_the_computer", "Couldn't reach the computer.")
    fun notConnected(c: Context) = get(c, "not_connected", "Not connected")
    fun invalidPairingCode(c: Context) = get(c, "invalid_pairing_code", "Invalid pairing code")
    fun unsendable(c: Context, names: String) = get(c, "x_can_t_be_sent_only_images_and_text_files_from",
        "%s can't be sent: only images and text files from the phone are supported.", names)
    fun appNeedsUpdate(c: Context, name: String) = get(c, "agy_remote_needs_an_update_to_work_with_x", "AGY Remote needs an update to work with %s.", name)
    fun updateConnector(c: Context, name: String) = get(c, "update_agy_connector_on_x_then_restart_it", "Update agy-connector on %s, then restart it.", name)
    fun closeInCli(c: Context) = get(c, "close_this_chat_in_the_agy_cli_to_continue_it_he", "Close this chat in the agy CLI to continue it here.")
    fun cliTouched(c: Context) = get(c, "this_chat_was_also_opened_in_the_agy_cli_while_t",
        "This chat was also opened in the agy CLI while this reply was running. agy keeps only one side's changes, so some messages may be missing.")
    fun couldntSwitch(c: Context, mode: String, error: String) = get(c, "couldn_t_switch_to_x_x", "Couldn't switch to %1\$s: %2\$s", mode, error)
    fun thinking(c: Context) = get(c, "thinking", "Thinking")
    fun responding(c: Context) = get(c, "responding", "Responding")
    fun waitingForApproval(c: Context) = get(c, "waiting_for_approval", "Waiting for approval")

    /** An error from the computer, as iOS P2PError.errorDescription words it. */
    fun error(c: Context, code: String, message: String): String = when (code) {
        "folder_required" -> get(c, "pick_a_folder_for_this_chat_first", "Pick a folder for this chat first.")
        "open_in_cli" -> get(c, "this_chat_is_open_in_the_agy_cli_close_it_there", "This chat is open in the agy CLI. Close it there to reply here.")
        "busy" -> get(c, "this_chat_is_already_working_on_something", "This chat is already working on something.")
        "agy_unavailable" -> get(c, "agy_isn_t_available_on_the_computer", "agy isn't available on the computer.")
        "not_found" -> get(c, "not_found_on_the_computer", "Not found on the computer.")
        "not_allowed" -> get(c, "the_computer_refused_this_request", "The computer refused this request.")
        "not_paired" -> get(c, "this_phone_isn_t_paired_with_the_computer_any_mo", "This phone isn't paired with the computer any more. Run agy-connector pair on the computer and scan the code again.")
        // The English detail (network library or computer) is for logs; say it in the phone's language.
        "transport" -> couldntReach(c)
        else -> get(c, "something_went_wrong_on_the_computer", "Something went wrong on the computer.")
    }
}
