package com.garo.remsound.kit

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CryptoTest {
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    /**
     * Cross-implementation vectors, identical to the ones pinned in the Windows app and the
     * Apple port (PBKDF2-HMAC-SHA256, 100 000 iterations, salts "RemSound.v1.audio-key" /
     * "RemSound.v1.fingerprint"). If these fail, this receiver can no longer decrypt Windows
     * senders' audio — the fix is never to update the expectation.
     */
    @Test
    fun keyDerivationMatchesTheOtherImplementations() {
        assertEquals(
            "e7fe94e96d7cfa6c51ba8e1590f50e37e234e5b0b3e662b24be48a4261f59c18",
            hex(RemSoundCrypto.deriveKey("")),
        )
        assertEquals(
            "b419d5d5ab025172af8ea4f8923ef9176bf2c0e720e40873d82aa4350f0e87d3",
            hex(RemSoundCrypto.deriveKey("test123")),
        )
        assertEquals(
            "5b105a781f1a3705d9ca53b4cf37014840484f99bb26c29ce22f067f15a12a8d",
            hex(RemSoundCrypto.deriveKey("correct horse battery staple")),
        )
    }

    @Test
    fun fingerprintMatchesTheOtherImplementations() {
        assertEquals("7a78e2d810154bf7", hex(RemSoundCrypto.fingerprint("")))
        assertEquals("fb6a9f52926ac190", hex(RemSoundCrypto.fingerprint("test123")))
        assertEquals(
            "c00a33adf9a2555f",
            hex(RemSoundCrypto.fingerprint("correct horse battery staple")),
        )
    }

    /**
     * "No password" is the empty string on every side, and all three must derive the same bytes
     * for it. This implementation feeds HMAC a single zero byte because the JCE rejects a
     * zero-length key; the vector above already proves that is the same key, and this pins the
     * property so the workaround cannot quietly become a difference.
     */
    @Test
    fun emptyPasswordDerivesTheSameKeyAsTheOtherPorts() {
        assertEquals(RemSoundCrypto.KEY_BYTES, RemSoundCrypto.deriveKey("").size)
        assertEquals(RemSoundCrypto.FINGERPRINT_BYTES, RemSoundCrypto.fingerprint("").size)
    }

    @Test
    fun fingerprintsEqualIsLengthSafe() {
        val a = RemSoundCrypto.fingerprint("test123")
        val b = RemSoundCrypto.fingerprint("test123")
        val c = RemSoundCrypto.fingerprint("other")
        assertTrue(RemSoundCrypto.fingerprintsEqual(a, b))
        assertFalse(RemSoundCrypto.fingerprintsEqual(a, c))
        assertFalse(RemSoundCrypto.fingerprintsEqual(a, a.copyOf(a.size - 1)))
    }

    /**
     * The wire layout is `nonce(12) || tag(16) || ciphertext`. The JCE produces
     * `ciphertext || tag`, so this is the test that catches the two being wired up the wrong way
     * round — which would decrypt nothing and look exactly like a wrong password.
     */
    @Test
    fun encryptorProducesTheWindowsPacketLayout() {
        val keyBytes = RemSoundCrypto.deriveKey("test123")
        val plaintext = "RemSound audio frame".toByteArray()

        val encryptor = AudioEncryptor()
        assertFalse(encryptor.hasKey)
        // Mandatory encryption: no key, nothing is sent.
        assertNull(encryptor.tryEncrypt(plaintext, 0, plaintext.size))

        encryptor.ensureKey(keyBytes)
        val packet = encryptor.tryEncrypt(plaintext, 0, plaintext.size)!!
        assertEquals(plaintext.size + RemSoundCrypto.ENCRYPTION_OVERHEAD_BYTES, packet.size)

        val decryptor = AudioDecryptor()
        assertNull(decryptor.tryDecrypt(packet, 0, packet.size)) // no key, no audio
        decryptor.ensureKey(keyBytes)
        assertArrayEquals(plaintext, decryptor.tryDecrypt(packet, 0, packet.size))
    }

    @Test
    fun decryptorRejectsWrongKeyAndTamper() {
        val keyBytes = RemSoundCrypto.deriveKey("test123")
        val encryptor = AudioEncryptor().apply { ensureKey(keyBytes) }
        val packet = encryptor.tryEncrypt(byteArrayOf(1, 2, 3, 4), 0, 4)!!

        val decryptor = AudioDecryptor()
        decryptor.ensureKey(RemSoundCrypto.deriveKey("wrong"))
        assertNull(decryptor.tryDecrypt(packet, 0, packet.size))

        decryptor.ensureKey(keyBytes)
        assertNotNull(decryptor.tryDecrypt(packet, 0, packet.size))

        val tampered = packet.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0xFF).toByte()
        assertNull(decryptor.tryDecrypt(tampered, 0, tampered.size))
        assertNull(decryptor.tryDecrypt(packet, 0, 10)) // shorter than the overhead
    }

    /**
     * Counter nonces, mirroring the Windows sender: unique by arithmetic, not by chance. Nonce
     * reuse under one AES-GCM key is catastrophic, so both halves of the rule are pinned — no
     * repeat within a key, and a fresh sequence whenever the key is rebuilt.
     */
    @Test
    fun encryptorNoncesNeverRepeatAndRestartWithTheKey() {
        val encryptor = AudioEncryptor()
        encryptor.ensureKey(RemSoundCrypto.deriveKey("test123"))

        fun nonceOf(packet: ByteArray) = packet.copyOfRange(0, RemSoundCrypto.NONCE_BYTES)

        val seen = mutableSetOf<String>()
        var first = ByteArray(0)
        for (i in 0 until 2000) {
            val nonce = nonceOf(encryptor.tryEncrypt(byteArrayOf(1, 2, 3, 4), 0, 4)!!)
            assertEquals(RemSoundCrypto.NONCE_BYTES, nonce.size)
            if (i == 0) first = nonce
            assertTrue("nonce repeated at packet $i", seen.add(hex(nonce)))
            // The 48-bit prefix is constant while the key is; only the counter half advances.
            assertArrayEquals(first.copyOfRange(0, 6), nonce.copyOfRange(0, 6))
        }

        // A rebuilt key restarts the counter at 0, so a new random prefix is what keeps the new
        // sequence away from the old one.
        encryptor.ensureKey(RemSoundCrypto.deriveKey("different"))
        val afterRekey = nonceOf(encryptor.tryEncrypt(byteArrayOf(1, 2, 3, 4), 0, 4)!!)
        assertFalse(afterRekey.copyOfRange(0, 6).contentEquals(first.copyOfRange(0, 6)))
        assertArrayEquals(ByteArray(6), afterRekey.copyOfRange(6, 12))
    }

    @Test
    fun encryptorAndDecryptorRoundTripRepeatedly() {
        val keyBytes = RemSoundCrypto.deriveKey("test123")
        val encryptor = AudioEncryptor().apply { ensureKey(keyBytes) }
        val decryptor = AudioDecryptor().apply { ensureKey(keyBytes) }

        val plaintext = ByteArray(240) { 0x5A }
        repeat(3) {
            val packet = encryptor.tryEncrypt(plaintext, 0, plaintext.size)!!
            assertEquals(plaintext.size + RemSoundCrypto.ENCRYPTION_OVERHEAD_BYTES, packet.size)
            assertArrayEquals(plaintext, decryptor.tryDecrypt(packet, 0, packet.size))
        }
    }
}
