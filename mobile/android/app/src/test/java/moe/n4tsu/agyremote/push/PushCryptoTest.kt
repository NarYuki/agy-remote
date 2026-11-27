// SPDX-License-Identifier: AGPL-3.0-only
package moe.n4tsu.agyremote.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * Opens the blob agy-connector's `seal` produced for a fixed key and nonce
 * (server/agy-connector/tests/push_vector.txt, checked by push.rs `sealed_test_vector_matches_android`).
 */
class PushCryptoTest {
    private val key = ByteArray(32) { 7 }
    private val vector: String by lazy {
        val f = listOf("../../../server/agy-connector/tests/push_vector.txt", "../../server/agy-connector/tests/push_vector.txt")
            .map(::File).firstOrNull { it.exists() } ?: error("push_vector.txt not found from ${File(".").absolutePath}")
        f.readText().trim()
    }

    @Test fun opensTheConnectorsTestVector() {
        val m = PushCrypto.open(vector, key) ?: error("could not open the connector's blob")
        assertEquals("permission", m.kind)
        assertEquals("approval", m.settingKind)
        assertEquals("chat-1", m.sessionId)
        assertEquals("appr-9", m.approvalId)
        assertEquals("Fix the build", m.title)
        assertEquals("Run ./gradlew test?", m.body)
        assertEquals("studio", m.host)
    }

    @Test fun blobIsFixedSize() {
        // nonce 12 + padded plaintext 512 + tag 16
        assertEquals(540, Base64.getDecoder().decode(vector).size)
        assertTrue(PushCrypto.decrypt(vector, key)!!.endsWith("}"))
    }

    @Test fun wrongKeyOrTamperingFails() {
        assertNull(PushCrypto.open(vector, ByteArray(32) { 8 }))
        val raw = Base64.getDecoder().decode(vector)
        raw[40] = (raw[40].toInt() xor 1).toByte()
        assertNull(PushCrypto.open(Base64.getEncoder().encodeToString(raw), key))
        assertNull(PushCrypto.open("not base64!", key))
    }

    @Test fun oldNotificationsAreStale() {
        val m = PushCrypto.open(vector, key)!! // ts 1700000000, a permission request
        assertEquals(1700000000L, m.ts)
        assertTrue(m.isStale(nowSeconds = 1700000000L + 11 * 60))
        assertTrue(!m.isStale(nowSeconds = 1700000000L + 60))
        val done = m.copy(kind = "turn_complete")
        assertTrue(!done.isStale(nowSeconds = 1700000000L + 11 * 60))
        assertTrue(done.isStale(nowSeconds = 1700000000L + 25 * 3600))
        assertTrue("no time: shown as before", !m.copy(ts = 0).isStale())
    }

    @Test fun hexRoundTrips() {
        val k = PushCrypto.newKey()
        assertEquals(64, PushCrypto.hex(k).length)
        assertTrue(k.contentEquals(PushCrypto.unhex(PushCrypto.hex(k))))
        assertNull(PushCrypto.unhex("zz"))
    }
}
