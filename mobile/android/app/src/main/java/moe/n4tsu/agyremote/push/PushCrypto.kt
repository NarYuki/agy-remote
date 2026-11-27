// SPDX-License-Identifier: AGPL-3.0-only
package moe.n4tsu.agyremote.push

import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The decrypted content of a notification sent by agy-connector (iOS Shared/PushCrypto.swift).
 * [kind] is the connector's name: turn_complete, turn_failed, permission or cli_released.
 */
data class PushMessage(
    val kind: String,
    val sessionId: String,
    val approvalId: String?,
    val title: String,
    val body: String,
    val host: String,
    /** When the computer sent it (unix seconds); 0 if unknown. */
    val ts: Long = 0,
    /** Why a turn failed (an error code or agy's stop reason), to say in the phone's language. */
    val code: String? = null,
) {
    /**
     * Too old to show as current: an approval the computer stopped waiting for (it gives up after
     * 10 minutes), or anything older than a day. `ts` is inside the encrypted part, so a copy
     * replayed later by the relay or the push service is caught too.
     */
    fun isStale(nowSeconds: Long = System.currentTimeMillis() / 1000): Boolean {
        if (ts <= 0) return false
        val limit = if (kind == "permission") 10 * 60 else 24 * 60 * 60
        return nowSeconds - ts > limit
    }

    /** The settings name of this kind (the one sent in notifications.register `kinds`). */
    val settingKind: String? get() = when (kind) {
        "turn_complete" -> "finished"
        "turn_failed" -> "failed"
        "permission", "question" -> "approval"
        "cli_released" -> "cli_released"
        else -> null
    }

    companion object {
        fun parse(json: String): PushMessage? = runCatching {
            val o = JSONObject(json)
            PushMessage(
                kind = o.getString("kind"),
                sessionId = o.optString("sessionId"),
                approvalId = o.optString("approvalId").ifEmpty { null },
                title = o.optString("title"),
                body = o.optString("body"),
                host = o.optString("host"),
                ts = o.optLong("ts", 0),
                code = o.optString("code").ifEmpty { null },
            )
        }.getOrNull()
    }
}

/**
 * Same scheme as agy-connector push.rs `seal`: base64(nonce(12) ‖ ciphertext ‖ tag(16)),
 * ChaCha20-Poly1305 with associated data "agy-push-v1", plaintext JSON padded with spaces to
 * 512 bytes. The 256-bit key is made on this phone and given to each computer over P2P.
 */
object PushCrypto {
    private val AAD = "agy-push-v1".toByteArray()

    /** The padded plaintext (trailing spaces removed), or null if it isn't ours or was tampered with. */
    fun decrypt(sealedBase64: String, key: ByteArray): String? = runCatching {
        val raw = Base64.getDecoder().decode(sealedBase64.trim())
        if (raw.size < 12 + 16 || key.size != 32) return null
        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(raw, 0, 12))
        cipher.updateAAD(AAD)
        String(cipher.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8).trimEnd(' ')
    }.getOrNull()

    fun open(sealedBase64: String, key: ByteArray): PushMessage? = decrypt(sealedBase64, key)?.let(PushMessage::parse)

    fun newKey(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun unhex(s: String): ByteArray? =
        if (s.length % 2 != 0 || !s.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) null
        else ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
