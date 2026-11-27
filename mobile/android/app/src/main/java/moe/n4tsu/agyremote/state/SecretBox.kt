// SPDX-License-Identifier: AGPL-3.0-only
package moe.n4tsu.agyremote.state

import android.content.pm.PackageManager
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts the secrets kept in SharedPreferences (this phone's P2P key, the tickets with each
 * computer's token, the notification keys) with an AES-256-GCM key that never leaves the
 * hardware: StrongBox where the phone has one, else the TEE. A copy of the preferences file
 * (a rooted phone, a forensic image) holds only ciphertext.
 *
 * Stored form: `v1:` + base64(iv(12) ‖ ciphertext ‖ tag(16)), with the preference name as
 * associated data so a value can't be moved to another name.
 */
class SecretBox(private val context: Context) {
    private val alias = "agy-remote-secrets-v1"

    /** Whether the key lives in StrongBox (shown in diagnostics). */
    val strongBox: Boolean by lazy {
        runCatching { (key().let { info(it) }) }.getOrDefault(false)
    }

    fun seal(name: String, plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        c.updateAAD(name.toByteArray())
        val out = c.iv + c.doFinal(plain.toByteArray())
        return "v1:" + Base64.getEncoder().encodeToString(out)
    }

    /** Null if absent, not ours, tampered with, or the key is gone (the value is then useless anyway). */
    fun open(name: String, sealed: String?): String? {
        val body = sealed?.removePrefix("v1:")?.takeIf { it != sealed } ?: return null
        return runCatching {
            val raw = Base64.getDecoder().decode(body)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
            c.updateAAD(name.toByteArray())
            String(c.doFinal(raw, 12, raw.size - 12))
        }.getOrNull()
    }

    @Synchronized private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val hasStrongBox = context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        if (hasStrongBox) {
            try {
                return generate(strongBox = true)
            } catch (_: StrongBoxUnavailableException) {
                // Declared but not usable for this key: fall back to the TEE below.
            }
        }
        return generate(strongBox = false)
    }

    private fun generate(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setIsStrongBoxBacked(strongBox)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run { init(spec); generateKey() }
    }

    private fun info(key: SecretKey): Boolean {
        val f = javax.crypto.SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
        val i = f.getKeySpec(key, android.security.keystore.KeyInfo::class.java) as android.security.keystore.KeyInfo
        return i.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX
    }
}
