// SPDX-License-Identifier: AGPL-3.0-only
package moe.n4tsu.agyremote.push

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URI
import javax.net.ssl.HttpsURLConnection

/**
 * Grants from the push relay: its word that one computer may send notifications to this phone's
 * push token (see relay/src/index.js; iOS PushGrants). The phone asks the relay, the relay
 * answers with a push to that token (so only the phone that has the token gets it), and the
 * phone hands the grant to the computer with `notifications.register`. A grant is no secret
 * worth guarding: it only works for that computer's key and this token. Without one,
 * notifications still arrive, from a smaller budget the relay keeps for apps that predate grants.
 */
object PushGrants {
    data class Grant(val value: String, /** Unix seconds. */ val expires: Long, val token: String, val relay: String) {
        fun json(): JSONObject = JSONObject().put("value", value).put("expires", expires).put("token", token).put("relay", relay)

        companion object {
            fun from(o: JSONObject) = Grant(o.optString("value"), o.optLong("expires"), o.optString("token"), o.optString("relay"))
        }
    }

    /** What the relay sent (`g`, a JSON text): one grant per computer key asked for, all good until [expires]. */
    data class Received(val expires: Long, val grants: List<Pair<String, String>>)

    /** Most computers one request may name (the relay's limit). */
    const val MAX_KEYS_PER_REQUEST = 16

    /** Asked for again this long before it runs out. */
    const val RENEW_BEFORE_SECONDS = 10L * 24 * 60 * 60

    /**
     * Where to ask, if the relay's address (as the computer reports it) is one this app will
     * contact: HTTPS with a host and nothing else in it that could carry data away.
     */
    fun endpoint(relay: String): String? {
        val u = runCatching { URI(relay) }.getOrNull() ?: return null
        if (!u.scheme.equals("https", ignoreCase = true) || u.host.isNullOrEmpty()) return null
        if (u.userInfo != null || u.query != null || u.fragment != null) return null
        return relay.trimEnd('/') + "/v1/grant"
    }

    /**
     * Asks the relay to push grants for [connectorKeys] (one per computer, all in one request) to
     * [token]. The reply says only whether it was sent (its HTTP status; 404 from a relay that
     * hands out none); the grants themselves arrive as an FCM data message ([parse]).
     */
    suspend fun ask(relay: String, token: String, connectorKeys: List<String>): Int? = withContext(Dispatchers.IO) {
        val url = endpoint(relay) ?: return@withContext null
        if (connectorKeys.isEmpty()) return@withContext null
        runCatching {
            val c = URI(url).toURL().openConnection() as HttpsURLConnection
            try {
                c.requestMethod = "POST"
                c.connectTimeout = 15_000
                c.readTimeout = 15_000
                c.instanceFollowRedirects = false
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(JSONObject().put("token", token).put("platform", "fcm").put("keys", org.json.JSONArray(connectorKeys)).toString().toByteArray()) }
                c.responseCode
            } finally {
                c.disconnect()
            }
        }.getOrNull()
    }

    fun parse(text: String): Received? = runCatching {
        val o = JSONObject(text)
        val expires = o.optLong("expires", 0)
        val list = o.optJSONArray("grants") ?: return@runCatching null
        val grants = (0 until minOf(list.length(), MAX_KEYS_PER_REQUEST)).mapNotNull { i ->
            val item = list.optJSONObject(i) ?: return@mapNotNull null
            val key = item.optString("key")
            val value = item.optString("grant")
            if (key.isEmpty() || value.isEmpty() || value.length > 128) null else key to value
        }
        if (expires <= 0 || grants.isEmpty()) null else Received(expires, grants)
    }.getOrNull()
}
