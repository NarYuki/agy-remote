// SPDX-License-Identifier: AGPL-3.0-only
package moe.n4tsu.agyremote.ui.chat

/**
 * Links in agent replies. They are never handed to the system as they are (an `ACTION_VIEW` on
 * a `file://` link crashed the app, and other schemes could start any app or pair the phone):
 * a tap goes to [onLink], which the view model sets.
 */
object ChatLinks {
    enum class Kind { WEB, FILE, REFUSED }

    @Volatile var onLink: ((String) -> Unit)? = null

    private val scheme = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")

    /** http(s) with a host: a web page. `file:` or a path without a scheme: a file on the computer. Anything else: refused. */
    fun classify(link: String): Kind {
        val t = link.trim()
        if (t.isEmpty()) return Kind.REFUSED
        return when (scheme.find(t)?.groupValues?.get(1)?.lowercase()) {
            "http", "https" -> {
                val host = runCatching { java.net.URI(t).host }.getOrNull()
                if (host.isNullOrEmpty()) Kind.REFUSED else Kind.WEB
            }
            "file" -> Kind.FILE
            null -> Kind.FILE
            else -> Kind.REFUSED
        }
    }
}
