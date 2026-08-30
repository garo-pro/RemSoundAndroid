package com.garo.remsound.kit

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * How a peer's encryption lines up with ours, derived from the password fingerprint it
 * advertises in its format packets. Mirrors `RemSound.Core.PeerSecurityStatus`.
 */
enum class PeerSecurityStatus {
    /** No fingerprint seen yet (or we have no password set) — nothing to report. */
    UNKNOWN,

    /** Their password fingerprint matches ours: audio will decrypt, the link is secure. */
    SECURE,

    /** They advertised a fingerprint, but it differs from ours — different passwords. */
    PASSWORD_MISMATCH,

    /** No fingerprint at all — an older, pre-encryption Windows build that needs updating. */
    PEER_NEEDS_UPDATE,
}

/**
 * Cryptographic helpers mirroring `RemSound.Core.RemSoundCrypto`. The parameters are part of
 * the wire contract and MUST match the Windows app exactly: PBKDF2-HMAC-SHA256, 100 000
 * iterations, fixed salts, AES-256-GCM with packet layout `nonce(12) || tag(16) || ciphertext`.
 */
object RemSoundCrypto {
    const val KEY_BYTES = 32
    const val FINGERPRINT_BYTES = 8
    const val NONCE_BYTES = 12
    const val TAG_BYTES = 16

    /** nonce + tag — what encryption adds on top of the plaintext length. */
    const val ENCRYPTION_OVERHEAD_BYTES = 28

    /**
     * MUST stay 100 000. Upstream v5.6 raised it to 600 000 for one day and broke every
     * port outright; v5.7 reverted and annotated the constant. Never mirror an
     * iteration-count change without every port moving together.
     */
    private const val PBKDF2_ITERATIONS = 100_000

    private val KEY_SALT = "RemSound.v1.audio-key".toByteArray(Charsets.UTF_8)
    private val FINGERPRINT_SALT = "RemSound.v1.fingerprint".toByteArray(Charsets.UTF_8)

    /**
     * Derive the 256-bit AES key for a password. Slow on purpose (~100 ms) — run once per
     * password change, never per packet.
     */
    fun deriveKey(password: String): ByteArray = pbkdf2(password, KEY_SALT, KEY_BYTES)

    /**
     * Short, non-reversible id for a password. Peers compare fingerprints to learn they
     * share a password without revealing it.
     */
    fun fingerprint(password: String): ByteArray = pbkdf2(password, FINGERPRINT_SALT, FINGERPRINT_BYTES)

    /**
     * PBKDF2-HMAC-SHA256, spelled out rather than taken from `SecretKeyFactory`.
     *
     * The JCE provider takes a `char[]` and picks its own password encoding, which differs
     * between providers and Android versions; the wire contract is the UTF-8 bytes of the
     * password, exactly as the Windows and Apple sides derive them. An empty password
     * derives from zero bytes here, which is what both of those do too (they pass a
     * non-null pointer with length 0).
     */
    private fun pbkdf2(password: String, salt: ByteArray, outputBytes: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val passwordBytes = password.toByteArray(Charsets.UTF_8)
        // `SecretKeySpec` rejects a zero-length key, so an empty password uses one 0x00 byte.
        // That derives identical bytes: HMAC zero-pads any key shorter than the 64-byte block,
        // so an empty key and a single zero byte are the same key. The other ports reach the
        // same place from the other side (a non-null pointer with length 0), and all three
        // must agree — both ends treat "no password" as "".
        val keySpec = SecretKeySpec(
            if (passwordBytes.isEmpty()) ByteArray(1) else passwordBytes,
            "HmacSHA256",
        )
        mac.init(keySpec)

        val hashLength = mac.macLength
        val blocks = (outputBytes + hashLength - 1) / hashLength
        val output = ByteArray(blocks * hashLength)
        val block = ByteArray(salt.size + 4)
        System.arraycopy(salt, 0, block, 0, salt.size)

        for (blockIndex in 1..blocks) {
            block[salt.size] = (blockIndex ushr 24).toByte()
            block[salt.size + 1] = (blockIndex ushr 16).toByte()
            block[salt.size + 2] = (blockIndex ushr 8).toByte()
            block[salt.size + 3] = blockIndex.toByte()

            var u = mac.doFinal(block)
            val accumulated = u.copyOf()
            for (iteration in 1 until PBKDF2_ITERATIONS) {
                u = mac.doFinal(u)
                for (i in accumulated.indices) {
                    accumulated[i] = (accumulated[i].toInt() xor u[i].toInt()).toByte()
                }
            }
            System.arraycopy(accumulated, 0, output, (blockIndex - 1) * hashLength, hashLength)
        }
        return output.copyOf(outputBytes)
    }

    /** Constant-time fingerprint comparison. */
    fun fingerprintsEqual(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }
}

/**
 * Nonce source for one encryptor: a random 48-bit prefix drawn when the cipher is built, then
 * a 48-bit little-endian counter — mirroring the Windows `RemSoundCrypto.NonceSequence`.
 * Uniqueness WITHIN an instance is arithmetic rather than probabilistic; ACROSS instances
 * (every launch and key rebuild restarts the counter at 0 under the same long-lived audio
 * key) the random prefix keeps the ranges apart. 2^48 packets is unreachable, whereas a
 * 96-bit random nonce's birthday bound is something a heavy multi-day sender can approach.
 *
 * Not thread-safe: one per encryptor, same ownership rule as the key itself.
 */
internal class NonceSequence {
    private val random = SecureRandom()
    private val prefix = ByteArray(PREFIX_BYTES)
    private var counter = 0L

    init {
        reset()
    }

    /**
     * Fresh prefix, counter back to zero — called whenever the cipher key is rebuilt, so a
     * new key never inherits an old counter and an old key never sees a repeated nonce.
     */
    fun reset() {
        random.nextBytes(prefix)
        counter = 0
    }

    /**
     * prefix(6) || counter(6, little-endian). The counter's top 16 bits are never used;
     * 2^48 packets at our rates is tens of thousands of years, so it cannot wrap in practice.
     */
    fun next(): ByteArray {
        val nonce = ByteArray(RemSoundCrypto.NONCE_BYTES)
        System.arraycopy(prefix, 0, nonce, 0, PREFIX_BYTES)
        val c = counter
        counter++
        for (i in 0 until RemSoundCrypto.NONCE_BYTES - PREFIX_BYTES) {
            nonce[PREFIX_BYTES + i] = ((c ushr (8 * i)) and 0xFF).toByte()
        }
        return nonce
    }

    private companion object {
        const val PREFIX_BYTES = 6
    }
}

/**
 * Rebuilds the AES key object only when the raw key bytes actually change (cheap comparison
 * on the common no-change path). Each owner uses its cache from a single thread.
 */
private class SymmetricKeyCache {
    var key: SecretKeySpec? = null
        private set

    private var cachedBytes: ByteArray? = null

    /**
     * True when the key was actually rebuilt, so callers that hang state off the cipher (the
     * send-side nonce sequence) know to reset it.
     */
    fun ensure(keyBytes: ByteArray?): Boolean {
        if (cachedBytes.contentEquals(keyBytes)) return false
        cachedBytes = keyBytes?.copyOf()
        key = keyBytes?.let { SecretKeySpec(it, "AES") }
        return true
    }
}

/**
 * Encrypts outgoing audio payloads — the send-side mirror of [AudioDecryptor], matching the
 * Windows `SenderLane` cipher. Packet layout is the wire contract's
 * `nonce(12) || tag(16) || ciphertext`; the JCE emits `ciphertext || tag`, so the tag is
 * moved to the front explicitly. Used exclusively on the capture/encode thread.
 */
class AudioEncryptor {
    private val keyCache = SymmetricKeyCache()
    private val nonces = NonceSequence()
    private val cipher: Cipher = Cipher.getInstance("AES/GCM/NoPadding")

    val hasKey: Boolean get() = keyCache.key != null

    /**
     * Rebuild the cipher key if the raw key bytes changed, restarting the nonce sequence with
     * it (see [NonceSequence.reset]).
     */
    fun ensureKey(keyBytes: ByteArray?) {
        if (keyCache.ensure(keyBytes)) nonces.reset()
    }

    /**
     * Encrypt a plaintext into the `nonce(12) || tag(16) || ciphertext` wire layout. Null
     * when no key is set (no password — mandatory encryption means nothing is sent) or on a
     * cipher failure.
     */
    fun tryEncrypt(plaintext: ByteArray, offset: Int, length: Int): ByteArray? {
        val key = keyCache.key ?: return null
        return try {
            val nonce = nonces.next()
            cipher.init(
                Cipher.ENCRYPT_MODE,
                key,
                GCMParameterSpec(RemSoundCrypto.TAG_BYTES * 8, nonce),
            )
            val sealed = cipher.doFinal(plaintext, offset, length)
            // JCE hands back ciphertext||tag; the wire wants nonce||tag||ciphertext.
            val cipherTextLength = sealed.size - RemSoundCrypto.TAG_BYTES
            val out = ByteArray(RemSoundCrypto.ENCRYPTION_OVERHEAD_BYTES + cipherTextLength)
            System.arraycopy(nonce, 0, out, 0, RemSoundCrypto.NONCE_BYTES)
            System.arraycopy(
                sealed,
                cipherTextLength,
                out,
                RemSoundCrypto.NONCE_BYTES,
                RemSoundCrypto.TAG_BYTES,
            )
            System.arraycopy(sealed, 0, out, RemSoundCrypto.ENCRYPTION_OVERHEAD_BYTES, cipherTextLength)
            out
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Decrypts incoming audio payloads with the key derived from the configured password.
 * Mirrors the Windows `AudioDecryptor`: one instance shared by all stream sessions, used
 * exclusively on the network receive thread. Returns null on auth failure (wrong password /
 * tampered packet) — the caller drops the packet, producing silence, never garbage.
 */
class AudioDecryptor {
    private val keyCache = SymmetricKeyCache()
    private val cipher: Cipher = Cipher.getInstance("AES/GCM/NoPadding")
    private var scratch = ByteArray(4096)

    val hasKey: Boolean get() = keyCache.key != null

    /** Rebuild the cipher key if the raw key bytes changed. */
    fun ensureKey(keyBytes: ByteArray?) {
        keyCache.ensure(keyBytes)
    }

    /** Decrypt a `nonce(12) || tag(16) || ciphertext` packet. Null on failure or no key. */
    fun tryDecrypt(packet: ByteArray, offset: Int, length: Int): ByteArray? {
        val key = keyCache.key ?: return null
        if (length < RemSoundCrypto.ENCRYPTION_OVERHEAD_BYTES) return null
        return try {
            val cipherTextLength = length - RemSoundCrypto.ENCRYPTION_OVERHEAD_BYTES
            // Reassemble into the JCE's ciphertext||tag order.
            if (scratch.size < cipherTextLength + RemSoundCrypto.TAG_BYTES) {
                scratch = ByteArray(cipherTextLength + RemSoundCrypto.TAG_BYTES)
            }
            System.arraycopy(
                packet,
                offset + RemSoundCrypto.ENCRYPTION_OVERHEAD_BYTES,
                scratch,
                0,
                cipherTextLength,
            )
            System.arraycopy(
                packet,
                offset + RemSoundCrypto.NONCE_BYTES,
                scratch,
                cipherTextLength,
                RemSoundCrypto.TAG_BYTES,
            )
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(
                    RemSoundCrypto.TAG_BYTES * 8,
                    packet,
                    offset,
                    RemSoundCrypto.NONCE_BYTES,
                ),
            )
            cipher.doFinal(scratch, 0, cipherTextLength + RemSoundCrypto.TAG_BYTES)
        } catch (_: Exception) {
            null
        }
    }
}
