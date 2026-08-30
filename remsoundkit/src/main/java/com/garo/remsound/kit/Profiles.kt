package com.garo.remsound.kit

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A named snapshot of the connection-relevant configuration — a lightweight take on the Windows
 * client's profiles. Deliberately covers only what changes between setups: the remembered
 * peers, which of them are enabled, the receive/send toggles, the microphone, and the maximum
 * delay. The profile's password belongs to the snapshot too, but lives in [SecureStore] (one
 * account per profile id, see [ProfileStore]) — never in the JSON.
 */
data class ReceiverProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val manualPeers: List<ManualPeer>,
    val selectedPeerAddresses: List<String>,
    val receiveEnabled: Boolean,
    val sendEnabled: Boolean,
    /** Stable input id; null = system default (matches `ReceiverSettings.selectedMicrophoneId`). */
    val selectedMicrophoneId: String?,
    val targetLatencyMs: Int,
    /**
     * Whether the delay is retuned continuously (see [LatencyAutoTune]). Part of the snapshot
     * because it changes the meaning of [targetLatencyMs]: with it on, the stored delay is a
     * starting point the tuner moves, not a value the profile pins.
     */
    val autoTuneLatencyEnabled: Boolean = false,
) {
    fun toJson(): JSONObject {
        val peers = JSONArray()
        for (peer in manualPeers) peers.put(peer.toJson())
        val addresses = JSONArray()
        for (address in selectedPeerAddresses) addresses.put(address)
        return JSONObject()
            .put("id", id)
            .put("name", name)
            .put("manualPeers", peers)
            .put("selectedPeerAddresses", addresses)
            .put("receiveEnabled", receiveEnabled)
            .put("sendEnabled", sendEnabled)
            .put("selectedMicrophoneId", selectedMicrophoneId ?: JSONObject.NULL)
            .put("targetLatencyMs", targetLatencyMs)
            .put("autoTuneLatencyEnabled", autoTuneLatencyEnabled)
    }

    companion object {
        /**
         * Hand-written so a field added later cannot destroy the user's profiles: **every field
         * after `id`/`name` decodes with a default**. The read path treats a throw as "no
         * profiles", so a decoder that insisted on a field meeting JSON written by an older
         * build would silently wipe the list rather than fail loudly. Keep it this way when
         * adding fields.
         */
        fun fromJson(json: JSONObject): ReceiverProfile? {
            val id = json.optString("id").ifEmpty { return null }
            val name = json.optString("name").ifEmpty { return null }
            val peersArray = json.optJSONArray("manualPeers") ?: JSONArray()
            val peers = (0 until peersArray.length()).mapNotNull {
                peersArray.optJSONObject(it)?.let(ManualPeer::fromJson)
            }
            val addressArray = json.optJSONArray("selectedPeerAddresses") ?: JSONArray()
            val addresses = (0 until addressArray.length()).map { addressArray.optString(it) }
                .filter { it.isNotEmpty() }
            val microphone = if (json.isNull("selectedMicrophoneId")) {
                null
            } else {
                json.optString("selectedMicrophoneId").ifEmpty { null }
            }
            return ReceiverProfile(
                id = id,
                name = name,
                manualPeers = peers,
                selectedPeerAddresses = addresses,
                receiveEnabled = json.optBoolean("receiveEnabled", true),
                sendEnabled = json.optBoolean("sendEnabled", false),
                selectedMicrophoneId = microphone,
                targetLatencyMs = json.optInt(
                    "targetLatencyMs",
                    ReceiverSettings.DEFAULT_TARGET_LATENCY_MS,
                ),
                autoTuneLatencyEnabled = json.optBoolean("autoTuneLatencyEnabled", false),
            )
        }
    }
}

/** What the app applies when it launches (the Profiles tab's "At launch" picker). */
sealed interface StartupProfileChoice {
    /** Nothing applied — the app starts on the settings exactly as last left (default). */
    data object Off : StartupProfileChoice

    /** Re-apply whichever profile was applied most recently. */
    data object LastApplied : StartupProfileChoice

    /** Always apply one specific profile. */
    data class Fixed(val id: String) : StartupProfileChoice
}

/**
 * Persists the profile list as JSON in SharedPreferences and each profile's password as its own
 * [SecureStore] account, so passwords never sit in plain preferences.
 *
 * The Apple port additionally mirrors profiles through iCloud. Android has no first-party
 * equivalent that is end-to-end encrypted for the password half, and shipping a
 * password-through-the-cloud path that is not would break the rule the Apple side's design
 * exists to protect — so profiles here are local to the device.
 */
class ProfileStore(private val settings: ReceiverSettings) {
    private val prefs = settings.preferences
    private val secure = settings.secureStore

    var profiles: List<ReceiverProfile>
        get() {
            val raw = prefs.getString("profiles", null) ?: return emptyList()
            return try {
                val array = JSONArray(raw)
                (0 until array.length()).mapNotNull {
                    array.optJSONObject(it)?.let(ReceiverProfile::fromJson)
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
        set(value) {
            val array = JSONArray()
            for (profile in value) array.put(profile.toJson())
            prefs.edit().putString("profiles", array.toString()).apply()
        }

    fun password(profileId: String): String = secure.read(passwordAccount(profileId))

    /**
     * An empty password stores an empty item — saving a profile must never be able to *remove*
     * one. Removal is [removePassword].
     */
    fun setPassword(password: String, profileId: String) {
        secure.write(passwordAccount(profileId), password)
    }

    /** Drop a profile's password. Belongs to the delete path alone. */
    fun removePassword(profileId: String) {
        secure.delete(passwordAccount(profileId))
    }

    /**
     * Launch-time profile application: rewrites the persisted live settings in place, BEFORE
     * [ReceiverController] reads them, so no property observers or engines are involved
     * (applying through the controller's setters during startup re-enters `start()`). Every
     * profile field has a persisted setting behind it, so the rewrite covers the whole profile —
     * send included.
     */
    fun applyStartupProfile() {
        val profileId = when (val choice = settings.startupProfile) {
            StartupProfileChoice.Off -> return
            StartupProfileChoice.LastApplied -> settings.lastAppliedProfileId
            is StartupProfileChoice.Fixed -> choice.id
        } ?: return
        val profile = profiles.firstOrNull { it.id == profileId } ?: return
        settings.manualPeers = profile.manualPeers
        settings.selectedPeerAddresses = profile.selectedPeerAddresses.toSet()
        settings.receiveEnabled = profile.receiveEnabled
        settings.sendEnabled = profile.sendEnabled
        settings.selectedMicrophoneId = profile.selectedMicrophoneId
        settings.targetLatencyMs = profile.targetLatencyMs
        settings.autoTuneLatencyEnabled = profile.autoTuneLatencyEnabled
        settings.password = password(profileId)
        settings.lastAppliedProfileId = profileId
    }

    private fun passwordAccount(profileId: String) = "profile-password-$profileId"
}
