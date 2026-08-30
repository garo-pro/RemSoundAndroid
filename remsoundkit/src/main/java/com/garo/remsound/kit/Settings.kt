package com.garo.remsound.kit

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * A manually entered peer (Tailscale IP, LAN IP, or relay hostname). Port defaults to the
 * canonical RemSound port; users never have to type one.
 */
data class ManualPeer(
    val id: String = java.util.UUID.randomUUID().toString(),
    val host: String,
    val port: Int = RemPacket.DEFAULT_PORT,
) {
    val displayName: String get() = if (port == RemPacket.DEFAULT_PORT) host else "$host:$port"

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("host", host)
        .put("port", port)

    companion object {
        fun fromJson(json: JSONObject): ManualPeer? {
            val host = json.optString("host")
            if (host.isEmpty()) return null
            return ManualPeer(
                id = json.optString("id").ifEmpty { java.util.UUID.randomUUID().toString() },
                host = host,
                port = json.optInt("port", RemPacket.DEFAULT_PORT),
            )
        }
    }
}

/**
 * The live persistent settings — what the app runs on right now. Plain values in
 * SharedPreferences; the password encrypted at rest by [SecureStore]. Named snapshots of the
 * connection-relevant subset live in [ProfileStore].
 */
class ReceiverSettings(context: Context, prefsName: String = PREFS_NAME) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    private val secure = SecureStore(context)

    var manualPeers: List<ManualPeer>
        get() {
            val raw = prefs.getString("manualPeers", null) ?: return emptyList()
            return try {
                val array = JSONArray(raw)
                (0 until array.length()).mapNotNull { ManualPeer.fromJson(array.getJSONObject(it)) }
            } catch (_: Exception) {
                emptyList()
            }
        }
        set(value) {
            val array = JSONArray()
            for (peer in value) array.put(peer.toJson())
            prefs.edit().putString("manualPeers", array.toString()).apply()
        }

    /**
     * Addresses (dotted-quad strings) of peers the user has ticked. Discovered peers are
     * re-identified across launches by address — the Windows side rerolls its discovery
     * InstanceId every start, so the address is the only stable key.
     */
    var selectedPeerAddresses: Set<String>
        get() = prefs.getStringSet("selectedPeerAddresses", emptySet())?.toSet() ?: emptySet()
        set(value) = prefs.edit().putStringSet("selectedPeerAddresses", value).apply()

    var targetLatencyMs: Int
        get() {
            val value = prefs.getInt("targetLatencyMs", 0)
            return if (value == 0) DEFAULT_TARGET_LATENCY_MS else clampLatency(value)
        }
        set(value) = prefs.edit().putInt("targetLatencyMs", clampLatency(value)).apply()

    var volume: Float
        get() = prefs.getFloat("volume", 1.0f)
        set(value) = prefs.edit().putFloat("volume", value).apply()

    /**
     * Extra playback gain in dB (0 = off). Stored as the raw decibel step so an unknown value
     * from a future build reads back as "off" rather than a silent misconfiguration.
     */
    var volumeBoostDb: Int
        get() = prefs.getInt("volumeBoostDb", 0)
        set(value) = prefs.edit().putInt("volumeBoostDb", value).apply()

    var cuesEnabled: Boolean
        get() = prefs.getBoolean("cuesEnabled", true)
        set(value) = prefs.edit().putBoolean("cuesEnabled", value).apply()

    /** The "Receive audio" playback toggle — persisted like the Windows checkbox. Default on. */
    var receiveEnabled: Boolean
        get() = prefs.getBoolean("receiveEnabled", true)
        set(value) = prefs.edit().putBoolean("receiveEnabled", value).apply()

    /** The "Send microphone" toggle. Persisted like the receive toggle. Default off. */
    var sendEnabled: Boolean
        get() = prefs.getBoolean("sendEnabled", false)
        set(value) = prefs.edit().putBoolean("sendEnabled", value).apply()

    /**
     * Continuously retune the playout target to what the link currently needs, instead of
     * holding whatever the delay control was last set to. Default **off**, mirroring the
     * Windows receiver's default for the same feature — it moves a value the user chose, so it
     * is opt-in on every platform.
     */
    var autoTuneLatencyEnabled: Boolean
        get() = prefs.getBoolean("autoTuneLatencyEnabled", false)
        set(value) = prefs.edit().putBoolean("autoTuneLatencyEnabled", value).apply()

    /**
     * Hold audio focus exclusively so playback — and the UDP socket under it — survives the
     * screen going off, and so the app stays the one the media buttons reach. Default **on**;
     * off lets RemSound play alongside another app instead of interrupting it and being
     * interrupted by it.
     */
    var exclusiveAudio: Boolean
        get() = prefs.getBoolean("exclusiveAudio", true)
        set(value) = prefs.edit().putBoolean("exclusiveAudio", value).apply()

    /**
     * Let a headset button, or the play/pause control on the lock screen and in the
     * notification, pause and resume receiving. Default **on**. Inert while [exclusiveAudio]
     * is off — a session that never takes focus is not where the system routes a media button.
     */
    var headsetTransportControls: Boolean
        get() = prefs.getBoolean("headsetTransportControls", true)
        set(value) = prefs.edit().putBoolean("headsetTransportControls", value).apply()

    /**
     * Stable id of the input the user picked for microphone sending (see
     * `MicrophoneCapture.availableInputs`). Null/empty = system default input.
     */
    var selectedMicrophoneId: String?
        get() = prefs.getString("selectedMicrophoneId", null)?.ifEmpty { null }
        set(value) = prefs.edit().putString("selectedMicrophoneId", value ?: "").apply()

    /**
     * What to apply at launch (Profiles tab). Stored as "" / "last" / a profile id; anything
     * unparseable reads as [StartupProfileChoice.Off].
     */
    var startupProfile: StartupProfileChoice
        get() = when (val raw = prefs.getString("startupProfile", "") ?: "") {
            "" -> StartupProfileChoice.Off
            "last" -> StartupProfileChoice.LastApplied
            else -> StartupProfileChoice.Fixed(raw)
        }
        set(value) {
            val raw = when (value) {
                StartupProfileChoice.Off -> ""
                StartupProfileChoice.LastApplied -> "last"
                is StartupProfileChoice.Fixed -> value.id
            }
            prefs.edit().putString("startupProfile", raw).apply()
        }

    /**
     * The profile most recently applied (by hand or at launch) — feeds the "last applied"
     * startup mode. A stale id (profile since deleted) is harmless:
     * [ProfileStore.applyStartupProfile] looks it up and no-ops on a miss.
     */
    var lastAppliedProfileId: String?
        get() = prefs.getString("lastAppliedProfileId", null)?.ifEmpty { null }
        set(value) = prefs.edit().putString("lastAppliedProfileId", value ?: "").apply()

    var listenPort: Int
        get() {
            val value = prefs.getInt("listenPort", 0)
            return if (value == 0) RemPacket.DEFAULT_PORT else value.coerceIn(1, 65535)
        }
        set(value) = prefs.edit().putInt("listenPort", value).apply()

    // ---- Password (encrypted at rest) ----

    var password: String
        get() = secure.read(PASSWORD_ACCOUNT)
        set(value) = secure.write(PASSWORD_ACCOUNT, value)

    internal val secureStore: SecureStore get() = secure

    internal val preferences: SharedPreferences get() = prefs

    companion object {
        const val PREFS_NAME = "remsound"
        private const val PASSWORD_ACCOUNT = "profile-password"

        /** Bounds of the delay control, shared with [PlayoutMixer] and the auto-tune's clamp. */
        const val MIN_TARGET_LATENCY_MS = 5
        const val MAX_TARGET_LATENCY_MS = 500

        /** The Windows app's default, and the fallback for a profile written before the field. */
        const val DEFAULT_TARGET_LATENCY_MS = 80

        fun clampLatency(ms: Int): Int = ms.coerceIn(MIN_TARGET_LATENCY_MS, MAX_TARGET_LATENCY_MS)
    }
}

/**
 * Password storage, the Android stand-in for the Apple port's Keychain: one AES-GCM key held
 * in the hardware-backed Android Keystore, wrapping each account's value into
 * SharedPreferences as `nonce || ciphertext`.
 *
 * There is no iCloud Keychain equivalent, so nothing here syncs and there are no
 * "synchronizable" and device-local flavours of an item to keep apart. What does carry over is
 * the rule that made that distinction matter: **writing an empty value stores an empty item,
 * it never deletes one.** Removal is [delete], reached only from an explicit user deletion, so
 * saving a profile from a device that has not been given the password yet cannot blank it.
 */
class SecureStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val key: SecretKey?
        get() = try {
            val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            (store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: generateKey()
        } catch (_: Exception) {
            null
        }

    private fun generateKey(): SecretKey? = try {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // Available after first unlock, so a reboot while locked can still bring the
                // receiver up once the user unlocks — the same accessibility class the Apple
                // port asks for.
                .setUserAuthenticationRequired(false)
                .build(),
        )
        generator.generateKey()
    } catch (_: Exception) {
        null
    }

    fun read(account: String): String {
        val stored = prefs.getString(account, null) ?: return ""
        val secretKey = key ?: return ""
        return try {
            val blob = Base64.decode(stored, Base64.NO_WRAP)
            if (blob.size <= NONCE_BYTES) return ""
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey,
                GCMParameterSpec(TAG_BITS, blob, 0, NONCE_BYTES),
            )
            String(cipher.doFinal(blob, NONCE_BYTES, blob.size - NONCE_BYTES), Charsets.UTF_8)
        } catch (_: Exception) {
            // A key regenerated by a factory reset or a restored backup makes old blobs
            // undecryptable. Reading "" makes the app ask for the password again, which is the
            // only recoverable outcome.
            ""
        }
    }

    /**
     * Stores a value — including an empty one, which is a value like any other here. Removing
     * an item is [delete], never a write.
     */
    fun write(account: String, value: String) {
        val secretKey = key ?: return
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val sealed = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val blob = cipher.iv + sealed
            prefs.edit().putString(account, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
        } catch (_: Exception) {
            // Nothing sensible to do — the password simply is not remembered on this device.
        }
    }

    /** Remove an account's stored value. Only an explicit user deletion may call this. */
    fun delete(account: String) {
        prefs.edit().remove(account).apply()
    }

    private companion object {
        const val PREFS_NAME = "remsound-secure"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "remsound-password-key"
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
    }
}
