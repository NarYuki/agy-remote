package moe.n4tsu.agyremote.state

import android.content.Context
import moe.n4tsu.agyremote.ui.theme.ThemeSetting
import org.json.JSONArray
import org.json.JSONObject

/** A paired computer as remembered on the phone. */
data class PairedDevice(
    val ticket: String,
    val id: String,
    val name: String,
    val hardwareModel: String = "",
    val relayAllowed: Boolean = false,
    val osVersion: String = "",
) {
    /** The simulated computer from the demo pairing code. */
    val isDemo: Boolean get() = id == moe.n4tsu.agyremote.net.DemoConnector.ENDPOINT_ID

    fun json(): JSONObject = JSONObject().put("ticket", ticket).put("id", id).put("name", name)
        .put("hardwareModel", hardwareModel).put("relayAllowed", relayAllowed).put("osVersion", osVersion)

    /** The fields that are stored; a change in anything else doesn't rewrite the list (iOS storedFields). */
    val storedKey: String get() = listOf(id, ticket, name, hardwareModel, osVersion).joinToString("\u0001")

    companion object {
        fun from(o: JSONObject) = PairedDevice(o.optString("ticket"), o.optString("id"), o.optString("name"),
            o.optString("hardwareModel"), o.optBoolean("relayAllowed"), o.optString("osVersion"))
    }
}

enum class IndicatorStyle { RING, PROMPT, BRAILLE, INFINITY, BRACKETS, MOBIUS, DOTS, RANDOM }

/** Small persistent settings (SharedPreferences). */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("agy-remote", Context.MODE_PRIVATE)
    /** Secrets are stored only encrypted with a hardware key (see [SecretBox]). */
    private val box = SecretBox(context.applicationContext)

    private fun secret(name: String): String? = box.open(name, prefs.getString(name, null))
    private fun setSecret(name: String, value: String?, now: Boolean = false) {
        val e = prefs.edit()
        if (value == null) e.remove(name) else e.putString(name, box.seal(name, value))
        if (now) e.commit() else e.apply()
    }

    /** This phone's P2P secret key (hex). */
    var secretKeyHex: String?
        get() = secret("secretKeyHex")
        set(v) = setSecret("secretKeyHex", v, now = true)

    var onboardingDone: Boolean
        get() = prefs.getBoolean("onboardingDone", false)
        set(v) = prefs.edit().putBoolean("onboardingDone", v).apply()

    var devices: List<PairedDevice>
        get() = runCatching {
            // The tickets carry this phone's token for each computer.
            val arr = JSONArray(secret("devices") ?: "[]")
            (0 until arr.length()).map { PairedDevice.from(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        set(v) = setSecret("devices", JSONArray(v.map { it.json() }).toString())

    var selectedDeviceId: String?
        get() = prefs.getString("selectedDevice", null)
        set(v) = prefs.edit().putString("selectedDevice", v).apply()

    var theme: ThemeSetting
        get() = runCatching { ThemeSetting.valueOf(prefs.getString("theme", null) ?: "SYSTEM") }.getOrDefault(ThemeSetting.SYSTEM)
        set(v) = prefs.edit().putString("theme", v.name).apply()

    var indicator: IndicatorStyle
        get() = runCatching { IndicatorStyle.valueOf(prefs.getString("indicator", null) ?: "RANDOM") }.getOrDefault(IndicatorStyle.RANDOM)
        set(v) = prefs.edit().putString("indicator", v.name).apply()

    var notificationsEnabled: Boolean
        get() = prefs.getBoolean("notifications", false)
        set(v) = prefs.edit().putBoolean("notifications", v).apply()

    /**
     * Notification keys, one per computer (endpoint id → hex): a computer can't make a
     * notification that looks like another's, and the key that opens one says which computer
     * sent it (so an approval goes back only there).
     */
    fun pushKeys(): Map<String, ByteArray> {
        val o = runCatching { org.json.JSONObject(secret("pushKeys") ?: "{}") }.getOrElse { org.json.JSONObject() }
        return o.keys().asSequence().mapNotNull { id ->
            moe.n4tsu.agyremote.push.PushCrypto.unhex(o.optString(id))?.takeIf { it.size == 32 }?.let { id to it }
        }.toMap()
    }

    /** This computer's key (hex), made and saved the first time. */
    @Synchronized fun pushKeyFor(computer: String): String {
        pushKeys()[computer]?.let { return moe.n4tsu.agyremote.push.PushCrypto.hex(it) }
        val hex = moe.n4tsu.agyremote.push.PushCrypto.hex(moe.n4tsu.agyremote.push.PushCrypto.newKey())
        val o = runCatching { org.json.JSONObject(secret("pushKeys") ?: "{}") }.getOrElse { org.json.JSONObject() }
        setSecret("pushKeys", o.put(computer, hex).toString(), now = true)
        return hex
    }

    @Synchronized fun removePushKey(computer: String) {
        val o = runCatching { org.json.JSONObject(secret("pushKeys") ?: "{}") }.getOrElse { return }
        if (o.remove(computer) != null) setSecret("pushKeys", o.toString())
    }

    fun clearPushKeys() = prefs.edit().remove("pushKeys").apply()

    /** The push relay's grants, one per computer (see [moe.n4tsu.agyremote.push.PushGrants]). Not secrets. */
    private fun pushGrants(): JSONObject = runCatching { JSONObject(prefs.getString("pushGrants", null) ?: "{}") }.getOrElse { JSONObject() }

    /** The computer's grant, if it is for this token and relay and still good at [nowSeconds]. */
    fun pushGrant(computer: String, token: String, relay: String, nowSeconds: Long = System.currentTimeMillis() / 1000): moe.n4tsu.agyremote.push.PushGrants.Grant? {
        val g = pushGrants().optJSONObject(computer)?.let { moe.n4tsu.agyremote.push.PushGrants.Grant.from(it) } ?: return null
        return g.takeIf { it.token == token && it.relay == relay && it.expires > nowSeconds }
    }

    @Synchronized fun setPushGrant(computer: String, grant: moe.n4tsu.agyremote.push.PushGrants.Grant) =
        prefs.edit().putString("pushGrants", pushGrants().put(computer, grant.json()).toString()).apply()

    @Synchronized fun removePushGrant(computer: String) {
        val o = pushGrants()
        if (o.remove(computer) != null) prefs.edit().putString("pushGrants", o.toString()).apply()
    }

    /** Whether the secrets' key is in StrongBox (else the TEE). */
    val secretsInStrongBox: Boolean get() = box.strongBox

    /** Last FCM registration token. */
    var fcmToken: String?
        get() = prefs.getString("fcmToken", null)
        set(v) = prefs.edit().putString("fcmToken", v).apply()

    fun notifyKind(kind: String) = prefs.getBoolean("notify.$kind", true)
    fun setNotifyKind(kind: String, on: Boolean) = prefs.edit().putBoolean("notify.$kind", on).apply()

    var groupByFolder: Boolean
        get() = prefs.getBoolean("groupByFolder", true)
        set(v) = prefs.edit().putBoolean("groupByFolder", v).apply()

    /** Order of the drawer's projects: "recent" (newest chat first) or "name". */
    var folderSort: String
        get() = prefs.getString("folderSort", null) ?: "recent"
        set(v) = prefs.edit().putString("folderSort", v).apply()

    /** The drawer's project list is folded away. */
    var projectsCollapsed: Boolean
        get() = prefs.getBoolean("projectsCollapsed", false)
        set(v) = prefs.edit().putBoolean("projectsCollapsed", v).apply()

    var lastFolder: String?
        get() = prefs.getString("lastFolder", null)
        set(v) = prefs.edit().putString("lastFolder", v).apply()

    /** Relay consent applies to every paired computer (iOS: agy.pref.relayAllowed). */
    var relayAllowed: Boolean
        get() = prefs.getBoolean("relayAllowed", false)
        set(v) = prefs.edit().putBoolean("relayAllowed", v).apply()

    var selectedModelId: String?
        get() = prefs.getString("selectedModel", null)?.ifEmpty { null }
        set(v) = prefs.edit().putString("selectedModel", v).apply()

    var selectedEffort: String?
        get() = prefs.getString("selectedEffort", null)?.ifEmpty { null }
        set(v) = prefs.edit().putString("selectedEffort", v).apply()

    var selectedModeId: String
        get() = prefs.getString("selectedMode", null)?.ifEmpty { null } ?: "default"
        set(v) = prefs.edit().putString("selectedMode", v).apply()

    /** Low-limit notice dismissals: limit id → the level it was dismissed at. */
    var quotaDismissals: Map<String, Double>
        get() = runCatching {
            val o = JSONObject(prefs.getString("quotaDismissals", null) ?: "{}")
            o.keys().asSequence().associateWith { o.getDouble(it) }
        }.getOrDefault(emptyMap())
        set(v) = prefs.edit().putString("quotaDismissals", JSONObject(v).toString()).apply()

    /** Folders open in the drawer; null until the user folds or unfolds one (then new ones auto-open). */
    var openFolders: Set<String>?
        get() = prefs.getStringSet("openFolders", null)?.toSet()
        set(v) = prefs.edit().putStringSet("openFolders", v).apply()

    /** Onboarding flag, or null when never written (iOS: paired before the flow counts as done). */
    val onboardingStored: Boolean get() = prefs.contains("onboardingDone")

    fun clear() = prefs.edit().clear().apply()
}
