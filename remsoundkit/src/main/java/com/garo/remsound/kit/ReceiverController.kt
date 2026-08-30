package com.garo.remsound.kit

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.provider.Settings as AndroidSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID

/** One row in the peer list — a discovered peer, a manual entry, or both merged by address. */
data class PeerListEntry(
    val id: String,
    val name: String,
    val addressString: String,
    /**
     * Every endpoint this peer is reachable at — multi-homed peers (LAN + VPN) have several.
     * The first is the primary/display one. Empty while a manual host resolves.
     */
    val audioEndpoints: List<UdpEndpoint>,
    val isManual: Boolean,
    val manualPeerId: String?,
    val isSelected: Boolean,
    val statusText: String,
) {
    val audioEndpoint: UdpEndpoint? get() = audioEndpoints.firstOrNull()
    internal val addresses: List<Int> get() = audioEndpoints.map { it.address }
    internal val allAddressStrings: List<String> get() = audioEndpoints.map { it.addressString }
}

/**
 * App-facing coordinator: owns the engine, audio output, discovery, heartbeat, and cues, and
 * publishes UI state as [StateFlow]s. All published state is written on the main dispatcher;
 * the underlying services run on their own threads and are polled / event-driven into main
 * updates.
 */
class ReceiverController private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ---- Published state ----

    private val _peers = MutableStateFlow<List<PeerListEntry>>(emptyList())
    val peers: StateFlow<List<PeerListEntry>> = _peers.asStateFlow()

    private val _statusSummary = MutableStateFlow("Stopped")
    val statusSummary: StateFlow<String> = _statusSummary.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /**
     * The at-a-glance connection state — who is connected, their ping, uptime. Kept short on
     * purpose: this is the list a screen-reader user swipes through every time they open the
     * app, so the packet-level material lives in [diagnosticDetails] instead.
     */
    private val _connectionDetails = MutableStateFlow<List<String>>(emptyList())
    val connectionDetails: StateFlow<List<String>> = _connectionDetails.asStateFlow()

    /**
     * The technical panel behind the Diagnostics button: traffic, buffer depth, glitch and
     * packet counters, timing, codec mode. Same 1 Hz tick; collected whether or not anyone is
     * looking, so opening the dialog shows real history rather than starting from zero.
     */
    private val _diagnosticDetails = MutableStateFlow<List<String>>(emptyList())
    val diagnosticDetails: StateFlow<List<String>> = _diagnosticDetails.asStateFlow()

    /**
     * Inputs the user can pick for microphone sending. Refreshed when the hardware set changes
     * (device-callback), at start, and on send start — never on a timer; enumeration IPC
     * alongside live playback causes audible glitches.
     */
    private val _availableMicrophones = MutableStateFlow<List<AudioInputDevice>>(emptyList())
    val availableMicrophones: StateFlow<List<AudioInputDevice>> = _availableMicrophones.asStateFlow()

    /** Plain-sentence state of the send path ("Sending microphone audio to 1 peer"). */
    private val _sendStatus = MutableStateFlow("")
    val sendStatus: StateFlow<String> = _sendStatus.asStateFlow()

    /**
     * Live traffic rates as one spoken sentence — the Connectivity tab exposes this as its
     * accessibility state description, so a screen-reader user hears the rates without opening
     * the tab. Same 1 Hz tick as [connectionDetails].
     */
    private val _trafficSummary = MutableStateFlow("")
    val trafficSummary: StateFlow<String> = _trafficSummary.asStateFlow()

    private val _profiles = MutableStateFlow<List<ReceiverProfile>>(emptyList())
    val profiles: StateFlow<List<ReceiverProfile>> = _profiles.asStateFlow()

    /**
     * The profile the current configuration exactly matches, if any — drives the "Currently
     * applied" row marker and the Profiles tab's accessibility state. Drift-checked: apply
     * "Home", then change any profile-covered setting, and Home stops reading as applied.
     */
    private val _appliedProfile = MutableStateFlow<ReceiverProfile?>(null)
    val appliedProfile: StateFlow<ReceiverProfile?> = _appliedProfile.asStateFlow()

    private val _lastAutoTuneNote = MutableStateFlow<String?>(null)
    val lastAutoTuneNote: StateFlow<String?> = _lastAutoTuneNote.asStateFlow()

    /**
     * One-shot messages for the screen reader — the Compose layer announces them and clears the
     * flow. Used where the change came from somewhere the relevant control is not focused, or
     * not even on screen (a headset press, the notification, a profile applying).
     */
    private val _announcement = MutableStateFlow<String?>(null)
    val announcement: StateFlow<String?> = _announcement.asStateFlow()

    fun consumeAnnouncement() {
        _announcement.value = null
    }

    // ---- Settings-backed state ----

    private val settings = ReceiverSettings(appContext)
    private val profileStore = ProfileStore(settings)

    private val _receiveEnabled = MutableStateFlow(true)
    val receiveEnabled: StateFlow<Boolean> = _receiveEnabled.asStateFlow()

    private val _sendEnabled = MutableStateFlow(false)
    val sendEnabled: StateFlow<Boolean> = _sendEnabled.asStateFlow()

    private val _selectedMicrophoneId = MutableStateFlow<String?>(null)
    val selectedMicrophoneId: StateFlow<String?> = _selectedMicrophoneId.asStateFlow()

    private val _volume = MutableStateFlow(1.0f)
    val volume: StateFlow<Float> = _volume.asStateFlow()

    private val _volumeBoost = MutableStateFlow(VolumeBoost.OFF)
    val volumeBoost: StateFlow<VolumeBoost> = _volumeBoost.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _targetLatencyMs = MutableStateFlow(ReceiverSettings.DEFAULT_TARGET_LATENCY_MS)
    val targetLatencyMs: StateFlow<Int> = _targetLatencyMs.asStateFlow()

    private val _autoTuneLatencyEnabled = MutableStateFlow(false)
    val autoTuneLatencyEnabled: StateFlow<Boolean> = _autoTuneLatencyEnabled.asStateFlow()

    private val _cuesEnabled = MutableStateFlow(true)
    val cuesEnabled: StateFlow<Boolean> = _cuesEnabled.asStateFlow()

    private val _exclusiveAudio = MutableStateFlow(true)
    val exclusiveAudio: StateFlow<Boolean> = _exclusiveAudio.asStateFlow()

    private val _headsetTransportControls = MutableStateFlow(true)
    val headsetTransportControls: StateFlow<Boolean> = _headsetTransportControls.asStateFlow()

    private val _password = MutableStateFlow("")
    val password: StateFlow<String> = _password.asStateFlow()

    private val _startupProfile = MutableStateFlow<StartupProfileChoice>(StartupProfileChoice.Off)
    val startupProfile: StateFlow<StartupProfileChoice> = _startupProfile.asStateFlow()

    // ---- Services ----

    private val engine = AudioReceiverEngine()
    private val mixer get() = engine.mixer
    private val output = AudioOutput(appContext, engine.mixer)
    private val discovery = PeerDiscoveryService()
    private val heartbeat = HeartbeatService()
    private val sendEngine = AudioSendEngine()
    private val microphone = MicrophoneCapture(appContext)
    private val remoteControls = RemoteTransportControls(appContext)

    /** Wired by the app layer, which owns the cue audio resources. */
    var cues: CuePlayer? = null

    /** The media-session token, for the foreground-service notification. */
    val mediaSessionToken get() = remoteControls.sessionToken

    private var manualPeers: List<ManualPeer> = emptyList()
    private var selectedAddresses: MutableSet<String> = mutableSetOf()
    private var sendTargetCount = 0

    /**
     * The persisted send toggle (possibly just rewritten by a startup profile) was on at launch
     * — honoured at the end of the first [start], once the engines and discovery are up
     * (flipping send any earlier re-enters `start()`). Consumed once; a later stop/start never
     * resurrects it.
     */
    private var startupSendPending = false

    /**
     * Whether the first [start] will resume microphone sending. The foreground service reads it
     * *before* starting the controller: from API 34 a service may only open the microphone once
     * it is running with the `microphone` type, and `start()` resumes capture synchronously.
     */
    val willResumeSendingAtStart: Boolean get() = startupSendPending && canSendMicrophone

    /** Whether RECORD_AUDIO has been granted — the service needs this to declare its type. */
    val canSendMicrophone: Boolean get() = microphone.hasPermission()

    /** Monotonic token guarding async PBKDF2 results — see [applyPassword]. */
    private var passwordGeneration = 0

    /** Resolved IPv4 endpoints per manual peer id. */
    private var manualResolved: MutableMap<String, List<UdpEndpoint>> = mutableMapOf()

    /**
     * DNS retry state: a manual peer whose name fails to resolve once — e.g. a Tailscale
     * MagicDNS name looked up before the tunnel is fully up — must not stay "Resolving…"
     * forever. The 1 Hz tick re-kicks resolution while any peer is unresolved, paced by this
     * timestamp and serialised by the in-flight flag.
     */
    private var lastResolveAttempt = 0L
    private var resolveInFlight = false

    /** Addresses currently delivering audio — drives the "Receiving from N peers" summary. */
    private var audibleAddresses: Set<Int> = emptySet()

    /**
     * Connect/disconnect cue state per selected peer, keyed by the stable primary address.
     * Mirrors the Windows receiver's hysteresis rule — see [updateCues].
     */
    private val peerConnectedState = mutableMapOf<Int, Boolean>()

    private var refreshJob: Job? = null

    /**
     * Whether a UI is currently on screen. Defaults to false so a service-only start does no
     * presentation work; the activity reports visibility. Gates only the presentation half of
     * the refresh tick.
     */
    private var uiVisible = false

    // Previous traffic-counter snapshot for the per-second rate lines.
    private var lastBytesReceived = 0L
    private var lastBytesSent = 0L
    private var lastRateAt = System.currentTimeMillis()
    private var lastRxRateKBs = 0.0
    private var lastTxRateKBs = 0.0

    // Sliding window of cumulative glitch totals for the "last minute" connection line.
    private data class GlitchSample(val at: Long, val underruns: Long, val trims: Long, val concealedMs: Long)

    private val glitchSamples = mutableListOf<GlitchSample>()

    // The same sliding-minute treatment for the packet counters. The peak arrival gap is a peak
    // rather than a total, so it is drained from the engine each tick and kept per-sample — the
    // reported figure is the max across the window, not a difference of endpoints.
    private data class PacketSample(
        val at: Long,
        val stats: StreamDiagnosticsSnapshot,
        val peakGapMs: Int,
        val peakRenderGapMs: Int,
    )

    private val packetSamples = mutableListOf<PacketSample>()

    // ---- Continuous auto-tune state (see LatencyAutoTune) ----
    private var autoTuneSamples = mutableListOf<LatencyAutoTune.Sample>()
    private var lastAutoTuneRun = 0L
    private var lastUserLatencyChange = 0L
    private var lastTuneBlockingUnderruns = 0L
    private var lastSessionsOpenedCount = 0L

    /**
     * True only while the tuner is assigning the target, so the setter can tell an automatic
     * move from a user's.
     */
    private var autoTuneIsMovingTarget = false

    /**
     * Last thing the audio output reported (focus change, restart, failure). Shown in the
     * Diagnostics panel: when a headset press stops the audio without changing our state, this
     * is what says whether the system interrupted us behind our back or nothing happened at
     * that layer at all.
     */
    private var lastAudioEvent: Pair<String, Long>? = null

    /**
     * Last time we re-published the media session to take the transport back after playback
     * recovered. Diagnostics only — the reclaim is invisible when it fails (we cannot see who
     * holds the buttons), so at least the attempt is on the record.
     */
    private var lastTransportReclaim: Long? = null

    init {
        // Startup profile (if configured): rewrite the persisted settings BEFORE they are read
        // below — rewriting-then-loading avoids every setter side effect.
        profileStore.applyStartupProfile()

        manualPeers = settings.manualPeers
        selectedAddresses = settings.selectedPeerAddresses.toMutableSet()
        _profiles.value = profileStore.profiles
        _startupProfile.value = settings.startupProfile
        _volume.value = settings.volume
        _volumeBoost.value = VolumeBoost.fromDb(settings.volumeBoostDb)
        _targetLatencyMs.value = settings.targetLatencyMs
        _cuesEnabled.value = settings.cuesEnabled
        _autoTuneLatencyEnabled.value = settings.autoTuneLatencyEnabled
        _password.value = settings.password
        _exclusiveAudio.value = settings.exclusiveAudio
        _headsetTransportControls.value = settings.headsetTransportControls
        _receiveEnabled.value = settings.receiveEnabled
        _selectedMicrophoneId.value = settings.selectedMicrophoneId
        // Loaded into the pending flag, not the toggle itself: capture must not start until the
        // engines are up (end of the first start()).
        startupSendPending = settings.sendEnabled

        mixer.volume = _volume.value
        mixer.boost = _volumeBoost.value
        mixer.setTargetLatencyMs(_targetLatencyMs.value)
        output.setExclusiveAudio(_exclusiveAudio.value)
        engine.setPlaybackEnabled(_receiveEnabled.value)
        microphone.setPreferredInput(_selectedMicrophoneId.value)
        recomputeAppliedProfile()

        // Headset / lock-screen transport. The session is only claimed once the receiver is
        // actually running (`applyRemoteControls` in `start()`); wiring the handlers here just
        // says what they do.
        remoteControls.onPlay = { setReceiveFromTransport(true) }
        remoteControls.onPause = { setReceiveFromTransport(false) }
        remoteControls.onToggle = { setReceiveFromTransport(!_receiveEnabled.value) }

        output.onDiagnostic = { message ->
            scope.launch { lastAudioEvent = message to System.currentTimeMillis() }
        }
        output.onPlaybackRecovered = {
            scope.launch { reclaimTransportControls() }
        }

        engine.onHeartbeatReceived = { buffer, length, remote ->
            heartbeat.handleInjectedPacket(buffer, length, remote)
        }
        heartbeat.sendTransport = { data, endpoint -> engine.sendFromAudioSocket(data, endpoint) }
        discovery.onPeersChanged = {
            scope.launch {
                // A peer appearing (or changing address) must re-feed the allow-list and
                // heartbeat tracking, or a previously-selected peer discovered after start()
                // shows as selected while all its audio packets are rejected.
                applyPeerSelection()
                refreshNow()
            }
        }
        engine.onSessionsChanged = { scope.launch { refreshNow() } }

        // Send path: outbound audio leaves the SAME socket inbound audio arrives on (the shared
        // NAT pinhole), and the capture thread feeds the send engine directly.
        sendEngine.transport = { data, endpoint -> engine.sendFromAudioSocket(data, endpoint) }
        microphone.onSamples = { samples, frames -> sendEngine.submit(samples, frames) }
        // Refresh the picker's input list only when the hardware set actually changes — NOT on
        // the 1 Hz tick. Enumerating input devices every second does audio-server IPC alongside
        // live playback and audibly glitches it.
        microphone.onInputsChanged = { scope.launch { refreshMicrophoneList() } }
        microphone.startWatchingInputs()
    }

    // ---- Lifecycle ----

    fun start() {
        if (_isRunning.value) return
        _lastError.value = null
        glitchSamples.clear()
        packetSamples.clear()
        autoTuneSamples.clear()
        _lastAutoTuneNote.value = null
        lastUserLatencyChange = System.currentTimeMillis()
        refreshMicrophoneList()
        applyPassword()
        try {
            engine.start(settings.listenPort)
            output.start()
        } catch (e: Exception) {
            val message = "Could not start: ${e.message ?: e.javaClass.simpleName}"
            _lastError.value = message
            engine.stop()
            _statusSummary.value = "Stopped — $message"
            return
        }
        heartbeat.start()
        discovery.start(deviceName(), settings.listenPort)
        discovery.setCapabilities(_sendEnabled.value, _receiveEnabled.value)
        _isRunning.value = true
        applyRemoteControls()
        applyPeerSelection()
        resolveManualPeers()

        refreshJob = scope.launch {
            while (isActive) {
                delay(1000)
                refreshNow()
            }
        }
        refreshNow()

        // Send was persisted as on (directly or via a startup profile): resume it now.
        // isRunning is already true, so the send setter cannot re-enter start().
        if (startupSendPending) {
            startupSendPending = false
            setSendEnabled(true)
        }
    }

    fun stop() {
        if (_sendEnabled.value) setSendEnabled(false)
        refreshJob?.cancel()
        refreshJob = null
        discovery.stop()
        heartbeat.stop()
        remoteControls.deactivate()
        output.stop()
        engine.stop()
        _isRunning.value = false
        audibleAddresses = emptySet()
        peerConnectedState.clear() // cleared silently — stopping is its own feedback
        _statusSummary.value = "Stopped"
        _connectionDetails.value = emptyList()
        _trafficSummary.value = ""
        refreshPeerList()
    }

    private fun deviceName(): String {
        val configured = try {
            AndroidSettings.Global.getString(appContext.contentResolver, "device_name")
        } catch (_: Exception) {
            null
        }
        return configured?.trim()?.ifEmpty { null }
            ?: "${Build.MANUFACTURER} ${Build.MODEL}".trim()
    }

    private fun applyPassword() {
        // Each edit bumps the generation so a slow derivation of an older password can never
        // land after a newer one (or after the password was cleared).
        passwordGeneration++
        val generation = passwordGeneration
        val pw = _password.value
        if (pw.isEmpty()) {
            // No password = no key: stop decrypting and sending immediately (the engines
            // otherwise keep running on the previously derived key).
            engine.setKeyMaterial(null, null)
            sendEngine.setKeyMaterial(null, null)
            return
        }
        // PBKDF2 at 100k iterations takes ~50-100 ms — off the main thread. Derived once,
        // shared by the receive and send engines.
        scope.launch {
            val material = withContext(Dispatchers.Default) {
                RemSoundCrypto.deriveKey(pw) to RemSoundCrypto.fingerprint(pw)
            }
            if (passwordGeneration != generation) return@launch
            engine.setKeyMaterial(material.first, material.second)
            sendEngine.setKeyMaterial(material.first, material.second)
        }
    }

    // ---- Setters (the Swift port's property observers) ----

    /**
     * Microphone sending on/off — persisted like the receive toggle. Sending saved as on resumes
     * at launch via `startupSendPending`: capture can only start once the engines are up.
     * Independent of [receiveEnabled] (Windows parity): both ride the always-bound audio socket.
     */
    fun setSendEnabled(enabled: Boolean) {
        if (_sendEnabled.value == enabled) return
        _sendEnabled.value = enabled
        settings.sendEnabled = enabled
        if (enabled) startSending() else stopSending()
        discovery.setCapabilities(_sendEnabled.value, _receiveEnabled.value)
        // Here rather than at the call sites, so the cue follows the state wherever it was moved
        // from — the toggle, the notification, or a profile being applied.
        cues?.play(if (enabled) CuePlayer.Cue.SEND_ON else CuePlayer.Cue.SEND_OFF)
        recomputeAppliedProfile()
    }

    /**
     * Playback of received audio — the Windows "Receive audio" checkbox. Gates ONLY playback:
     * the socket, heartbeats, and discovery stay up regardless (single-port model), so sending
     * and peer health keep working while this is off, and peers see an honest CanReceive flag.
     */
    fun setReceiveEnabled(enabled: Boolean) {
        if (_receiveEnabled.value == enabled) return
        _receiveEnabled.value = enabled
        settings.receiveEnabled = enabled
        engine.setPlaybackEnabled(enabled)
        discovery.setCapabilities(_sendEnabled.value, enabled)
        if (enabled && !_isRunning.value) start()
        cues?.play(if (enabled) CuePlayer.Cue.RECEIVE_ON else CuePlayer.Cue.RECEIVE_OFF)
        // Whatever moved this — the UI, a profile, or a headset press — the lock-screen play
        // button must not be left showing the opposite state.
        updateNowPlaying()
        recomputeAppliedProfile()
        refreshNow()
    }

    /** Which input to send from; null = system default. Persisted. */
    fun setSelectedMicrophoneId(id: String?) {
        if (_selectedMicrophoneId.value == id) return
        _selectedMicrophoneId.value = id
        settings.selectedMicrophoneId = id
        microphone.setPreferredInput(id)
        // Live switch: rebuild the capture graph on the new input.
        if (microphone.isRunning) {
            microphone.stop()
            try {
                microphone.start()
            } catch (e: Exception) {
                _lastError.value = "Could not switch microphone: ${e.message}"
                setSendEnabled(false)
            }
        }
        recomputeAppliedProfile()
    }

    fun setVolume(value: Float) {
        _volume.value = value
        mixer.volume = value
        settings.volume = value
    }

    /**
     * Extra playback gain for quiet senders, on top of [volume]. Persisted; deliberately NOT
     * part of a profile — like volume and the other audio options, it belongs to the device you
     * are listening on, not to the connection setup.
     */
    fun setVolumeBoost(boost: VolumeBoost) {
        _volumeBoost.value = boost
        mixer.boost = boost
        settings.volumeBoostDb = boost.db
    }

    fun setMuted(muted: Boolean) {
        _isMuted.value = muted
        mixer.isMuted = muted
    }

    /**
     * Quick mute. Announces the result because it can be triggered from a place the mute control
     * is not focused, so the state change would otherwise be silent for a screen-reader user.
     */
    fun toggleMute() {
        setMuted(!_isMuted.value)
        announce(if (_isMuted.value) "Audio muted" else "Audio unmuted")
    }

    fun setTargetLatencyMs(ms: Int) {
        val clamped = ReceiverSettings.clampLatency(ms)
        _targetLatencyMs.value = clamped
        // The auto-tune moves this too. When it does, the change is runtime state, not a setting
        // the user chose: it must not be persisted over their value, and it must not restart the
        // deferral window that exists to let a *user* edit settle.
        if (autoTuneIsMovingTarget) {
            mixer.setTargetLatencyMs(clamped, drainOnLower = false)
        } else {
            mixer.setTargetLatencyMs(clamped)
            settings.targetLatencyMs = clamped
            lastUserLatencyChange = System.currentTimeMillis()
            recomputeAppliedProfile()
        }
    }

    /** Continuously retune [targetLatencyMs] to what the link needs. See [LatencyAutoTune]. */
    fun setAutoTuneLatencyEnabled(enabled: Boolean) {
        if (_autoTuneLatencyEnabled.value == enabled) return
        _autoTuneLatencyEnabled.value = enabled
        settings.autoTuneLatencyEnabled = enabled
        // Start from a clean slate either way: stale history from before the toggle would
        // otherwise drive the first tick.
        autoTuneSamples.clear()
        lastUserLatencyChange = System.currentTimeMillis()
        if (!enabled) {
            // Hand the user's own setting back — the tuned value was never theirs.
            setTargetLatencyMs(settings.targetLatencyMs)
        }
        recomputeAppliedProfile()
    }

    /**
     * One switch for every cue sound, not just the connect/disconnect pair it started as:
     * receive on/off, send on/off, and profile saved ride it too.
     */
    fun setCuesEnabled(enabled: Boolean) {
        _cuesEnabled.value = enabled
        cues?.enabled = enabled
        settings.cuesEnabled = enabled
    }

    /**
     * Take sole control of audio so playback and the network survive the screen going off, at
     * the cost of interrupting other apps' audio — and of being interrupted by theirs. It gates
     * the headset transport too: an app that never takes focus is not where the system routes a
     * media button, so the session is released and re-claimed with this rather than left
     * registered against a system that will never route a press to us.
     */
    fun setExclusiveAudio(exclusive: Boolean) {
        if (_exclusiveAudio.value == exclusive) return
        _exclusiveAudio.value = exclusive
        settings.exclusiveAudio = exclusive
        output.setExclusiveAudio(exclusive)
        applyRemoteControls()
    }

    fun setHeadsetTransportControls(enabled: Boolean) {
        if (_headsetTransportControls.value == enabled) return
        _headsetTransportControls.value = enabled
        settings.headsetTransportControls = enabled
        applyRemoteControls()
    }

    fun setPassword(value: String) {
        if (_password.value == value) return
        _password.value = value
        settings.password = value
        applyPassword()
        recomputeAppliedProfile()
    }

    fun setStartupProfile(choice: StartupProfileChoice) {
        if (_startupProfile.value == choice) return
        _startupProfile.value = choice
        settings.startupProfile = choice
    }

    // ---- Transport controls ----

    /**
     * A transport press arrived from outside the app (headset button, lock screen). Announced
     * for the same reason [toggleMute] is: the press comes from somewhere the receive toggle is
     * not focused — or not even on screen — so the state change would otherwise be silent.
     */
    private fun setReceiveFromTransport(enabled: Boolean) {
        if (_receiveEnabled.value == enabled) return
        setReceiveEnabled(enabled)
        announce(if (enabled) "Receiving audio" else "Receiving paused")
    }

    /**
     * Whether the app may claim the system transport at all. That takes exclusive audio on top
     * of the setting: an app that never holds focus is not where the system routes a media
     * button, so with mixing on the press goes to whatever else is playing no matter what we
     * register.
     */
    private val canClaimTransportControls: Boolean
        get() = _headsetTransportControls.value && _exclusiveAudio.value

    /**
     * Claim or release the system transport, following the setting and whether the receiver is
     * up at all. Idempotent — safe to call from either trigger.
     */
    private fun applyRemoteControls() {
        if (!canClaimTransportControls || !_isRunning.value) {
            remoteControls.deactivate()
            return
        }
        remoteControls.activate()
        updateNowPlaying()
    }

    /**
     * Re-stake the media-button claim after playback came back from something that had stopped
     * it. Whatever interrupted us became the app the buttons reach while it played, and it keeps
     * that slot after it stops. Blind by necessity — there is no API to ask who holds it — so
     * this fires whether or not it was lost, and the attempt is reported in Diagnostics.
     */
    private fun reclaimTransportControls() {
        if (!canClaimTransportControls || !_isRunning.value) return
        remoteControls.reassert()
        lastTransportReclaim = System.currentTimeMillis()
    }

    /** Push the receive state to the lock screen. Change-gated, and NOT on the 1 Hz tick. */
    private fun updateNowPlaying() {
        remoteControls.update(
            isPlaying = _receiveEnabled.value,
            detail = if (_receiveEnabled.value) "Receiving audio" else "Receiving paused",
        )
    }

    // ---- Peer management ----

    fun addManualPeer(host: String, port: Int = RemPacket.DEFAULT_PORT) {
        val trimmed = host.trim()
        if (trimmed.isEmpty()) return
        if (manualPeers.any { it.host == trimmed && it.port == port }) return
        manualPeers = manualPeers + ManualPeer(host = trimmed, port = port)
        settings.manualPeers = manualPeers
        resolveManualPeers()
        recomputeAppliedProfile()
        refreshNow()
    }

    fun removeManualPeer(id: String) {
        manualPeers.firstOrNull { it.id == id }?.let { peer ->
            for (endpoint in manualResolved[id].orEmpty()) {
                selectedAddresses.remove(endpoint.addressString)
            }
            selectedAddresses.remove(peer.host)
        }
        manualPeers = manualPeers.filterNot { it.id == id }
        manualResolved.remove(id)
        settings.manualPeers = manualPeers
        settings.selectedPeerAddresses = selectedAddresses
        applyPeerSelection()
        recomputeAppliedProfile()
        refreshNow()
    }

    fun setPeerSelected(entry: PeerListEntry, selected: Boolean) {
        // Select/deselect every address the peer is reachable at, plus the manual hostname if
        // there is one, so the choice survives path changes and re-resolution.
        val strings = entry.allAddressStrings.toMutableSet()
        strings.add(entry.addressString)
        entry.manualPeerId?.let { manualId ->
            manualPeers.firstOrNull { it.id == manualId }?.let { strings.add(it.host) }
        }
        if (selected) selectedAddresses.addAll(strings) else selectedAddresses.removeAll(strings)
        settings.selectedPeerAddresses = selectedAddresses
        applyPeerSelection()
        recomputeAppliedProfile()
        refreshNow()
    }

    private fun resolveManualPeers() {
        if (resolveInFlight) return
        resolveInFlight = true
        lastResolveAttempt = System.currentTimeMillis()
        val peersToResolve = manualPeers
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                peersToResolve.associate { it.id to UdpEndpoint.resolve(it.host, it.port) }
            }
            resolveInFlight = false
            // Merge per peer instead of replacing wholesale: a transient DNS failure during a
            // retry must not wipe a previously good resolution (that would drop the peer from
            // the allow-list mid-stream), a peer added while this lookup was in flight must not
            // lose its own fresher entry, and a peer removed meanwhile must not be re-inserted.
            for ((id, endpoints) in result) {
                if (manualPeers.none { it.id == id }) continue
                if (endpoints.isEmpty() && manualResolved[id].orEmpty().isNotEmpty()) continue
                manualResolved[id] = endpoints
            }
            applyPeerSelection()
            refreshNow()
        }
    }

    /**
     * Names that failed to resolve retry every few seconds while the receiver is up, so a
     * Tailscale name entered (or launched) before the tunnel was up heals itself. Runs on the
     * 1 Hz tick; plain DNS on an IO dispatcher, no audio-server IPC involved.
     */
    private fun retryUnresolvedPeersIfNeeded() {
        if (!_isRunning.value || resolveInFlight) return
        if (manualPeers.none { manualResolved[it.id].orEmpty().isEmpty() }) return
        if (System.currentTimeMillis() - lastResolveAttempt < RESOLVE_RETRY_INTERVAL_MS) return
        resolveManualPeers()
    }

    /**
     * Push the current selection into the allow-list, heartbeat tracking, and discovery unicast
     * targets.
     */
    private fun applyPeerSelection() {
        val allowed = mutableSetOf<Int>()
        val tracked = mutableListOf<UdpEndpoint>()
        val unicast = mutableListOf<Int>()

        for (peer in discovery.currentPeers) {
            unicast.addAll(peer.addresses)
            // Selected if ANY of its addresses is — and then allow/track ALL of them: the sender
            // picks its own route, so audio can arrive from any of the peer's paths.
            if (peer.addressStrings.any { selectedAddresses.contains(it) }) {
                allowed.addAll(peer.addresses)
                for (endpoint in peer.audioEndpoints) {
                    if (!tracked.contains(endpoint)) tracked.add(endpoint)
                }
            }
        }
        for (peer in manualPeers) {
            for (endpoint in manualResolved[peer.id].orEmpty()) {
                unicast.add(endpoint.address)
                if (selectedAddresses.contains(endpoint.addressString) ||
                    selectedAddresses.contains(peer.host)
                ) {
                    allowed.add(endpoint.address)
                    if (!tracked.contains(endpoint)) tracked.add(endpoint)
                }
            }
        }

        engine.setAllowedSenders(allowed)
        heartbeat.setTrackedPeers(tracked)
        discovery.setUnicastPeerAddresses(unicast)
    }

    // ---- Profiles ----

    /**
     * Save the current configuration under [name]. A name matching an existing profile
     * (case-insensitive) updates that profile in place — that is the edit path, alongside the
     * row's explicit "save current settings here" action.
     */
    fun saveProfile(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val existing = _profiles.value.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
        if (existing != null) {
            updateProfile(existing.id)
            return
        }
        val profile = profileSnapshot(UUID.randomUUID().toString(), trimmed)
        _profiles.value = _profiles.value + profile
        profileStore.setPassword(_password.value, profile.id)
        profileStore.profiles = _profiles.value
        markApplied(profile.id) // the saved profile IS the current configuration
        cues?.play(CuePlayer.Cue.PROFILE_SAVED)
        announce("Profile $trimmed saved")
    }

    /** Overwrite an existing profile with the current configuration, keeping its name. */
    fun updateProfile(id: String) {
        val index = _profiles.value.indexOfFirst { it.id == id }
        if (index < 0) return
        val name = _profiles.value[index].name
        _profiles.value = _profiles.value.toMutableList().also {
            it[index] = profileSnapshot(id, name)
        }
        profileStore.setPassword(_password.value, id)
        profileStore.profiles = _profiles.value
        markApplied(id) // now identical to the current configuration
        cues?.play(CuePlayer.Cue.PROFILE_SAVED)
        announce("Profile $name updated")
    }

    fun renameProfile(id: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        val index = _profiles.value.indexOfFirst { it.id == id }
        if (index < 0) return
        _profiles.value = _profiles.value.toMutableList().also {
            it[index] = it[index].copy(name = trimmed)
        }
        profileStore.profiles = _profiles.value
        recomputeAppliedProfile()
    }

    fun deleteProfile(id: String) {
        val profile = _profiles.value.firstOrNull { it.id == id } ?: return
        profileStore.removePassword(id) // the one path that may remove it
        _profiles.value = _profiles.value.filterNot { it.id == id }
        profileStore.profiles = _profiles.value
        // Drop dangling launch references so the picker never shows a deleted profile.
        if (_startupProfile.value == StartupProfileChoice.Fixed(id)) {
            setStartupProfile(StartupProfileChoice.Off)
        }
        if (settings.lastAppliedProfileId == id) settings.lastAppliedProfileId = null
        recomputeAppliedProfile()
        announce("Profile ${profile.name} deleted")
    }

    /**
     * Replace the live configuration with a saved profile. Only the profile's fields are touched
     * — volume, cues, exclusive audio, and the rest stay as they are.
     */
    fun applyProfile(id: String) {
        val profile = _profiles.value.firstOrNull { it.id == id } ?: return
        manualPeers = profile.manualPeers
        settings.manualPeers = manualPeers
        // Keep resolutions for peers that survive the swap (matched by id); drop the rest.
        val ids = manualPeers.map { it.id }.toSet()
        manualResolved = manualResolved.filterKeys { ids.contains(it) }.toMutableMap()
        selectedAddresses = profile.selectedPeerAddresses.toMutableSet()
        settings.selectedPeerAddresses = selectedAddresses
        // Before the delay, not after: switching auto-tune off hands back the persisted user
        // value, which would otherwise land on top of the profile's own delay.
        setAutoTuneLatencyEnabled(profile.autoTuneLatencyEnabled)
        setTargetLatencyMs(profile.targetLatencyMs)
        setSelectedMicrophoneId(profile.selectedMicrophoneId)
        setPassword(profileStore.password(profile.id))
        setReceiveEnabled(profile.receiveEnabled)
        // Send last: it may start the capture pipeline (microphone permission prompt included),
        // and by now the key material and peer selection it needs are in place.
        setSendEnabled(profile.sendEnabled)
        markApplied(profile.id)
        applyPeerSelection()
        resolveManualPeers()
        refreshNow()
        announce("Profile ${profile.name} applied")
    }

    /**
     * Record which profile the settings now come from — persisted (feeds the "last applied"
     * launch mode) and published (feeds the applied marker).
     */
    private fun markApplied(id: String) {
        settings.lastAppliedProfileId = id
        recomputeAppliedProfile()
    }

    private fun profileSnapshot(id: String, name: String) = ReceiverProfile(
        id = id,
        name = name,
        manualPeers = manualPeers,
        selectedPeerAddresses = selectedAddresses.sorted(),
        receiveEnabled = _receiveEnabled.value,
        sendEnabled = _sendEnabled.value,
        selectedMicrophoneId = _selectedMicrophoneId.value,
        targetLatencyMs = _targetLatencyMs.value,
        autoTuneLatencyEnabled = _autoTuneLatencyEnabled.value,
    )

    /**
     * Drift check for the "Currently applied" marker: the live configuration must still match
     * the snapshot, password included.
     *
     * A profile with auto-tune on does not pin a delay — the tuner owns that value at runtime
     * (which is also why its moves are not persisted). Comparing it would drop the marker within
     * seconds of applying such a profile, reporting drift for the one thing the profile
     * explicitly delegates. Every other field is still compared, so a real edit — including
     * turning auto-tune off — still clears the marker.
     */
    private fun recomputeAppliedProfile() {
        val id = settings.lastAppliedProfileId
        val profile = id?.let { candidate -> _profiles.value.firstOrNull { it.id == candidate } }
        if (profile == null || profileStore.password(profile.id) != _password.value) {
            _appliedProfile.value = null
            return
        }
        var live = profileSnapshot(profile.id, profile.name)
        if (profile.autoTuneLatencyEnabled && live.autoTuneLatencyEnabled) {
            live = live.copy(targetLatencyMs = profile.targetLatencyMs)
        }
        _appliedProfile.value = if (live == profile) profile else null
    }

    // ---- Periodic refresh ----

    /**
     * Report whether a UI is on screen. While invisible the 1 Hz tick runs only its functional
     * half; on becoming visible we run one immediate full refresh so the UI is never stale.
     */
    fun setUiVisible(visible: Boolean) {
        if (uiVisible == visible) return
        uiVisible = visible
        if (visible) {
            // Coming back to the foreground is one of the two edges that can win the media
            // buttons back from whatever took them while we were away.
            reclaimTransportControls()
            refreshNow()
        }
    }

    private fun refreshNow() {
        if (!_isRunning.value) {
            refreshPeerList()
            if (_connectionDetails.value.isNotEmpty()) _connectionDetails.value = emptyList()
            if (_diagnosticDetails.value.isNotEmpty()) _diagnosticDetails.value = emptyList()
            if (_trafficSummary.value.isNotEmpty()) _trafficSummary.value = ""
            return
        }
        // Functional half — MUST run whether or not a UI is visible; this app spends most of its
        // life with the screen off, but cues, DNS retry, send-path health, and the render-block
        // adaptation still have to work. refreshPeerList runs first because updateCues and
        // updateSendTargets read the peer list, and rebuilding it is the cheapest way to keep
        // that data fresh; updateCues also fires the connect/lost announcements, which
        // background screen-reader users depend on, so it is never gated.
        retryUnresolvedPeersIfNeeded()
        refreshPeerList()
        updateCues()
        updateSendTargets()
        updateIoBufferDemand()
        // Functional: the only way back from an interruption that ended without telling us.
        output.pollInterruptionRecovery()
        // Functional, not presentational: the peak arrival gap is a read-and-reset value, so
        // sampling only while a UI is on screen would fold a whole backgrounded session into the
        // first sample and report it under a "last minute" label.
        samplePacketDiagnostics()

        // Presentation half — status-string building. Pure waste while nothing is on screen;
        // setUiVisible(true) forces one immediate full refresh so the UI never shows stale data.
        if (!uiVisible) return
        updateSendStatus()
        updateSummary()
        updateConnectionDetails()
    }

    /**
     * Battery: the render thread wakes once per block. When nothing is flowing — no playout
     * session AND the mic is not capturing — that cadence is pure wakeup cost against a silent
     * bus, so the block grows; it shrinks back the moment audio appears. The track and the audio
     * focus stay put throughout.
     */
    private fun updateIoBufferDemand() {
        output.setLowLatencyDemand(mixer.activeSessionCount > 0 || microphone.isRunning)
    }

    // ---- Microphone sending ----

    private fun startSending() {
        if (!_isRunning.value) start()
        if (!_isRunning.value) {
            _sendEnabled.value = false
            return
        }
        if (!microphone.hasPermission()) {
            _sendEnabled.value = false
            settings.sendEnabled = false
            _lastError.value = MICROPHONE_DENIED_MESSAGE
            return
        }
        beginCapture()
    }

    private fun beginCapture() {
        // Starting capture can expose inputs the playback-only state hid; re-list now.
        refreshMicrophoneList()
        microphone.setPreferredInput(_selectedMicrophoneId.value)
        sendEngine.start()
        updateSendTargets()
        try {
            microphone.start()
            announce("Microphone sending started")
        } catch (e: Exception) {
            sendEngine.stop()
            _sendEnabled.value = false
            settings.sendEnabled = false
            _lastError.value = "Could not start the microphone: ${e.message}"
        }
        refreshNow()
    }

    private fun stopSending() {
        val wasCapturing = microphone.isRunning
        microphone.stop()
        sendEngine.stop()
        if (wasCapturing) announce("Microphone sending stopped")
        refreshNow()
    }

    /**
     * The user refused the microphone at the prompt. The send switch was never turned on — the
     * activity asks first and only enables sending once permission exists — so this only has to
     * say why nothing happened, rather than undo a half-started capture.
     */
    fun reportMicrophonePermissionDenied() {
        _lastError.value = MICROPHONE_DENIED_MESSAGE
    }

    /**
     * One destination per selected peer — its healthiest heartbeat path, falling back to the
     * primary address. Never more than one of a peer's addresses: sending the same stream down
     * two paths would open two doubled-up sessions on its receiver.
     */
    private fun updateSendTargets() {
        if (!sendEngine.isRunning) {
            sendTargetCount = 0
            return
        }
        val health = heartbeat.allPeerHealth()
        val targets = mutableListOf<UdpEndpoint>()
        for (entry in _peers.value) {
            if (!entry.isSelected || entry.audioEndpoints.isEmpty()) continue
            val best = bestHealth(entry.addresses, health)
            if (best != null && best.state == PeerHealthState.HEALTHY) {
                targets.add(best.audioEndpoint)
            } else {
                entry.audioEndpoint?.let { targets.add(it) }
            }
        }
        sendTargetCount = targets.size
        sendEngine.setTargets(targets)
    }

    fun refreshMicrophoneList() {
        val inputs = microphone.availableInputs()
        if (inputs != _availableMicrophones.value) _availableMicrophones.value = inputs
    }

    private fun updateSendStatus() {
        val text = when {
            !_sendEnabled.value -> ""
            _password.value.isEmpty() -> "Set a password below to send — audio is always encrypted"
            sendTargetCount == 0 -> "No peers selected — tick a peer on the Connectivity tab to send to it"
            else -> buildString {
                append("Sending microphone audio to $sendTargetCount peer")
                if (sendTargetCount != 1) append("s")
                // Capture cadence diagnostic: ~10 ms means smooth packet pacing; a much larger
                // figure would mean burst sending (the receiving side would need a huge buffer).
                val chunkMs = microphone.captureChunkMs
                if (chunkMs > 0) append(String.format(Locale.US, ". Capture chunk %.0f ms", chunkMs))
                val dropped = microphone.captureDroppedFrames
                if (dropped > 0) append(". $dropped capture frames dropped")
            }
        }
        if (text != _sendStatus.value) _sendStatus.value = text
    }

    private fun updateConnectionDetails() {
        val lines = mutableListOf<String>() // general status — always on screen
        val tech = mutableListOf<String>() // technical detail — behind the Diagnostics button

        // Connected = selected peers whose heartbeat is currently healthy, like Windows. One line
        // per selected peer ROW (a multi-homed peer is pinged on every path; show the best), and
        // the line set stays structurally stable from tick to tick — every peer always gets a
        // line, and the rate/buffer lines are always present — otherwise the rows below shift
        // every second and become impossible to tap.
        val health = heartbeat.allPeerHealth()
        val trackedEntries = _peers.value.filter { it.isSelected && it.audioEndpoints.isNotEmpty() }
        val bests = trackedEntries.map { it to bestHealth(it.addresses, health) }
        val healthyCount = bests.count { it.second?.state == PeerHealthState.HEALTHY }
        lines.add(
            if (healthyCount == 0) {
                "Not connected to any peer"
            } else {
                "Connected to $healthyCount peer${if (healthyCount == 1) "" else "s"}"
            },
        )
        for ((entry, best) in bests) {
            val status = when (best?.state) {
                PeerHealthState.HEALTHY -> best.rttMs?.let { "ping $it ms" } ?: "ping pending"
                PeerHealthState.STALE -> "connection unstable"
                PeerHealthState.UNREACHABLE -> "not responding"
                PeerHealthState.UNKNOWN, null -> "waiting for a reply"
            }
            lines.add("${entry.name}: $status")
        }

        lines.add("Uptime: ${formatDuration(engine.uptimeSeconds)}")

        // Per-second rates from the counter deltas since the previous tick. Bursty refreshes
        // (peer/session change callbacks) can land < 0.2 s apart — keep showing the last computed
        // rate then instead of dropping the line or resetting the baseline.
        val now = System.currentTimeMillis()
        val dt = (now - lastRateAt) / 1000.0
        val received = engine.bytesReceived
        val sent = engine.bytesSent
        if (dt > 0.2) {
            lastRxRateKBs = maxOf(0.0, (received - lastBytesReceived) / 1000.0 / dt)
            lastTxRateKBs = maxOf(0.0, (sent - lastBytesSent) / 1000.0 / dt)
            lastBytesReceived = received
            lastBytesSent = sent
            lastRateAt = now
        }
        tech.add(
            String.format(Locale.US, "Receiving %.1f kB/s; sending %.1f kB/s", lastRxRateKBs, lastTxRateKBs),
        )
        // Whole numbers for the spoken tab value — decimals are noise read aloud.
        val traffic = String.format(
            Locale.US,
            "Receiving %.0f kilobytes per second, sending %.0f kilobytes per second",
            lastRxRateKBs,
            lastTxRateKBs,
        )
        if (traffic != _trafficSummary.value) _trafficSummary.value = traffic
        tech.add(
            String.format(
                Locale.US,
                "Total received %.1f MB; sent %.1f MB",
                received / 1_000_000.0,
                sent / 1_000_000.0,
            ),
        )

        if (mixer.activeSessionCount > 0) {
            tech.add(
                String.format(
                    Locale.US,
                    "Audio buffer %d ms; output latency %.0f ms",
                    mixer.currentBufferMs,
                    output.reportedOutputLatencyMs,
                ),
            )
        } else {
            // Whether audio is playing at all is general status, not diagnostics.
            lines.add("No audio playing")
        }

        // Glitch visibility: dropouts = the buffer ran dry mid-playback (network jitter exceeded
        // the buffered cushion); trims = the buffer overfilled past the jitter margin and old
        // audio was cut to bound latency. Reported over a sliding minute so "is it glitching
        // right now" is answerable from the panel, with a screen reader.
        val totals = mixer.glitchTotals
        glitchSamples.add(GlitchSample(now, totals.underruns, totals.trims, totals.concealedMs))
        glitchSamples.removeAll { now - it.at > 60_000 }
        val oldestGlitch = glitchSamples.firstOrNull()
        if (mixer.activeSessionCount > 0 && oldestGlitch != null) {
            val dropouts = totals.underruns - oldestGlitch.underruns
            val trims = totals.trims - oldestGlitch.trims
            if (dropouts == 0L && trims == 0L) {
                tech.add("No audio dropouts in the last minute")
            } else {
                tech.add(
                    "Last minute: $dropouts audio dropout${if (dropouts == 1L) "" else "s"}, " +
                        "$trims buffer trim${if (trims == 1L) "" else "s"}",
                )
            }
            // Duration, not just frequency. Raising the buffer on a jittery link can leave the
            // dropout COUNT unchanged while making each one far shorter — a difference the
            // listener hears clearly and a count alone reports as "no improvement".
            val concealed = totals.concealedMs - oldestGlitch.concealedMs
            if (concealed > 0) tech.add("Silence inserted last minute: $concealed ms")
        }

        appendTransportDiagnostics(tech, now)
        appendNetworkDiagnostics(tech)

        if (lines != _connectionDetails.value) _connectionDetails.value = lines
        if (tech != _diagnosticDetails.value) _diagnosticDetails.value = tech
    }

    /**
     * The whole connection panel as pasteable plain text, with a timestamp and the app version
     * so a pasted report is self-describing. The status and error lines come first because they
     * are the context the numbers below only make sense against.
     */
    val connectionReport: String
        get() = buildList {
            add("RemSound connection details")
            add(java.text.DateFormat.getDateTimeInstance().format(java.util.Date()))
            runCatching {
                val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
                add("Version ${info.versionName}")
            }
            add("")
            add(_statusSummary.value)
            _lastError.value?.let { add("Error: $it") }
            add("Maximum delay: ${_targetLatencyMs.value} ms")
            add("")
            addAll(_connectionDetails.value)
            addAll(_diagnosticDetails.value)
        }.joinToString("\n")

    /** Put [connectionReport] on the clipboard, and say so — a copy has no other feedback. */
    fun copyConnectionReport() {
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("RemSound diagnostics", connectionReport))
        announce("Connection details copied")
    }

    /**
     * Take one point of the sliding minute of packet counters. Called from the functional half of
     * the refresh tick — see the note at the call site.
     */
    private fun samplePacketDiagnostics() {
        val now = System.currentTimeMillis()
        // Drained every tick: the peak is a since-last-read value, so a skipped read would
        // silently widen the window it represents.
        val peakGapMs = engine.diagnostics.drainPeakGapMs()
        val peakRenderGapMs = mixer.drainPeakRenderGapMs()
        packetSamples.add(PacketSample(now, engine.diagnostics.snapshot(), peakGapMs, peakRenderGapMs))
        packetSamples.removeAll { now - it.at > 60_000 }

        // Feed the tuner's own history. Only while audio is actually flowing — a second with no
        // packets carries no timing information, and upstream skips those too.
        if (mixer.activeSessionCount > 0) {
            autoTuneSamples.add(LatencyAutoTune.Sample(peakGapMs, peakRenderGapMs))
            val excess = autoTuneSamples.size - LatencyAutoTune.HISTORY_SECONDS
            if (excess > 0) repeat(excess) { autoTuneSamples.removeAt(0) }
        }
        runAutoTuneIfDue(now)
    }

    /**
     * Drive [LatencyAutoTune] on its interval. Runs in the functional half of the refresh tick —
     * the whole point is that it works while the app is backgrounded, which is exactly when a
     * mobile link misbehaves.
     */
    private fun runAutoTuneIfDue(now: Long) {
        // A new session invalidates the history: it may span a session boundary, and a
        // cross-session gap would recommend a target the new stream could never arm at.
        val opened = engine.sessionsOpenedCount
        if (opened != lastSessionsOpenedCount) {
            lastSessionsOpenedCount = opened
            autoTuneSamples.clear()
            lastUserLatencyChange = now
        }

        // The underrun baseline must track even while disabled, or enabling the tuner would hand
        // it a huge first delta and make it skip.
        val tuneBlocking = mixer.glitchTotals.tuneBlocking
        val underrunDelta = tuneBlocking - lastTuneBlockingUnderruns

        if (!_autoTuneLatencyEnabled.value) {
            lastTuneBlockingUnderruns = tuneBlocking
            return
        }
        val intervalMs = LatencyAutoTune.DEFAULT_INTERVAL_SEC * 1000L
        if (now - lastAutoTuneRun < intervalMs) return
        lastAutoTuneRun = now
        lastTuneBlockingUnderruns = tuneBlocking

        val frameMs = engine.activeStreamFrameMs ?: return

        val decision = LatencyAutoTune.decide(
            LatencyAutoTune.Input(
                samples = autoTuneSamples.toList(),
                frameMs = frameMs,
                currentTargetMs = _targetLatencyMs.value,
                minTargetMs = ReceiverSettings.MIN_TARGET_LATENCY_MS,
                maxTargetMs = ReceiverSettings.MAX_TARGET_LATENCY_MS,
                tuneBlockingUnderrunDelta = underrunDelta,
                deferring = now - lastUserLatencyChange < intervalMs,
            ),
        )

        val note = when (decision) {
            is LatencyAutoTune.Decision.Retarget -> {
                autoTuneIsMovingTarget = true
                setTargetLatencyMs(decision.ms)
                autoTuneIsMovingTarget = false
                "Auto-tune set the delay to ${decision.ms} ms"
            }
            is LatencyAutoTune.Decision.Hold -> {
                val reason = decision.reason
                if (reason is LatencyAutoTune.HoldReason.UnderrunsSinceLastTick) {
                    "Auto-tune held at ${_targetLatencyMs.value} ms " +
                        "(${reason.count} dropout${if (reason.count == 1L) "" else "s"} since the last check)"
                } else {
                    "Auto-tune holding the delay at ${_targetLatencyMs.value} ms"
                }
            }
        }
        if (note != _lastAutoTuneNote.value) _lastAutoTuneNote.value = note
    }

    /**
     * Packet-level lines: what the *network* did, as opposed to what the jitter buffer did. The
     * two are routinely confused — a dropout counter alone cannot tell you whether packets were
     * lost or merely arrived late, and the fixes differ completely (redundancy on the sender vs.
     * a deeper buffer here).
     */
    private fun appendNetworkDiagnostics(lines: MutableList<String>) {
        if (mixer.activeSessionCount == 0) return
        val oldest = packetSamples.firstOrNull() ?: return
        val latest = packetSamples.lastOrNull() ?: return
        val stats = latest.stats

        val received = stats.audioPacketsReceived - oldest.stats.audioPacketsReceived
        val lost = stats.packetsLost - oldest.stats.packetsLost
        val late = stats.packetsLate - oldest.stats.packetsLate
        val duplicate = stats.packetsDuplicate - oldest.stats.packetsDuplicate
        val expected = received + lost

        if (expected > 0) {
            val parts = mutableListOf("$received received")
            val lossPercent = lost * 100.0 / expected
            parts.add(String.format(Locale.US, "%d lost (%.2f%%)", lost, lossPercent))
            if (late > 0) parts.add("$late arrived late")
            if (duplicate > 0) parts.add("$duplicate duplicated")
            lines.add("Packets last minute: " + parts.joinToString(", "))
        }

        // Inter-arrival gaps are what the jitter buffer has to absorb: a gap longer than the
        // buffered cushion is a dropout no matter how little was lost.
        val windowPeak = packetSamples.maxOfOrNull { it.peakGapMs } ?: 0
        if (windowPeak > 0) {
            val over30 = stats.gapsOver30ms - oldest.stats.gapsOver30ms
            val over60 = stats.gapsOver60ms - oldest.stats.gapsOver60ms
            val over100 = stats.gapsOver100ms - oldest.stats.gapsOver100ms
            // Say which clock produced these. Timed on the receive thread they measure our own
            // scheduling as much as the network — under background throttling a burst of on-time
            // packets reads as one huge gap. Android exposes no kernel delivery timestamp
            // through the datagram API, so this is always thread-timed.
            lines.add(
                "Packet timing last minute (thread-timed): longest gap $windowPeak ms; " +
                    "$over30 over 30 ms, $over60 over 60 ms, $over100 over 100 ms",
            )
            if (windowPeak > _targetLatencyMs.value) {
                lines.add(
                    "The longest gap was larger than the ${_targetLatencyMs.value} ms maximum delay, " +
                        "so the buffer could not cover it",
                )
            }
            // The output device's own callback period is the OTHER half of the auto-tune's
            // recommendation, and a slow output can inflate the delay as much as the network
            // does. Shown so the target is decomposable into network vs device.
            val renderPeak = packetSamples.maxOfOrNull { it.peakRenderGapMs } ?: 0
            if (renderPeak > 0) lines.add("Longest audio callback gap: $renderPeak ms")
        }

        if (stats.opusMode != OpusPacketMode.UNKNOWN) {
            lines.add("Sender codec mode: Opus ${stats.opusMode.displayDescription}")
        }

        val decryptFailures = stats.decryptFailures - oldest.stats.decryptFailures
        if (decryptFailures > 0) {
            lines.add("Packets that failed to decrypt last minute: $decryptFailures")
        }
        val resyncs = stats.resyncs - oldest.stats.resyncs
        if (resyncs > 0) lines.add("Sender stream restarts last minute: $resyncs")
    }

    /**
     * Diagnostics for the headset / lock-screen transport. Deliberately reports the *absence* of
     * a command too: "nothing has been routed to us" and "we were sent something and ignored it"
     * look identical from the outside, and they need opposite fixes.
     */
    private fun appendTransportDiagnostics(tech: MutableList<String>, now: Long) {
        if (!_headsetTransportControls.value) {
            tech.add("Headset controls: off")
            return
        }
        // On but never claimed: worth saying out loud, because from the outside this looks
        // exactly like a press that was routed elsewhere by the system.
        if (!_exclusiveAudio.value) {
            tech.add("Headset controls: on, but inactive while RemSound mixes with other sounds")
            return
        }
        val command = remoteControls.lastCommand
        if (command != null) {
            val age = ((now - command.atMillis) / 1000.0).toInt()
            tech.add("Headset controls: last button was ${command.name}, $age second${if (age == 1) "" else "s"} ago")
        } else {
            tech.add("Headset controls: on, no button press received yet")
        }
        lastTransportReclaim?.let {
            val age = ((now - it) / 1000.0).toInt()
            tech.add("Headset controls: reclaimed after audio recovered, $age second${if (age == 1) "" else "s"} ago")
        }
        lastAudioEvent?.let { (message, at) ->
            val age = ((now - at) / 1000.0).toInt()
            tech.add("Audio engine: $message, $age second${if (age == 1) "" else "s"} ago")
        }
    }

    private fun updateCues() {
        // Mirrors the Windows receiver's cue rule: connected the moment audio arrives OR the
        // heartbeat is solidly healthy; lost only when audio has stopped AND the heartbeat has
        // gone unreachable. Everything in between (heartbeat stale, audio briefly paused) HOLDS
        // the previous state — hysteresis, so a two-second Wi-Fi/VPN stall never fires a false
        // disconnect+connect cue pair. Audio arrives hundreds of times a second, so a 3-second
        // gap is a genuine interruption, not jitter; the unreachable heartbeat (~5 s of no
        // replies) is the slower gate for a real, total loss.
        val audioWindowMs = 3000L
        val health = heartbeat.allPeerHealth()
        val nowAudible = mutableSetOf<Int>()
        val seen = mutableSetOf<Int>()
        val connected = mutableListOf<Int>()
        val lost = mutableListOf<Int>()

        for (entry in _peers.value) {
            if (!entry.isSelected) continue
            val primary = entry.audioEndpoint ?: continue
            // Keyed by the stable primary address even when audio arrives on another path, so a
            // path switch does not fire a spurious disconnect+connect cue pair.
            val key = primary.address
            seen.add(key)
            val audioFlowing = entry.addresses.any { engine.isAudioFlowing(it, audioWindowMs) }
            if (audioFlowing) nowAudible.add(key)

            val state = bestHealth(entry.addresses, health)?.state ?: PeerHealthState.UNKNOWN
            val isConnected = audioFlowing || state == PeerHealthState.HEALTHY
            val isLost = !audioFlowing && state == PeerHealthState.UNREACHABLE
            val wasConnected = peerConnectedState[key] ?: false
            when {
                isConnected && !wasConnected -> {
                    connected.add(key)
                    peerConnectedState[key] = true
                }
                isLost && wasConnected -> {
                    lost.add(key)
                    peerConnectedState[key] = false
                }
                // First sighting and neither clearly connected nor lost (address entered but no
                // audio or pong yet) — seed quietly. If it later goes unreachable without ever
                // connecting, that is a connect-FAILED event and stays silent too.
                !peerConnectedState.containsKey(key) -> peerConnectedState[key] = false
            }
        }

        // Peers that vanished from tracking entirely (deselected or expired): a disconnect cue
        // only if they were connected when last seen — one that never connected stays quiet.
        val vanished = peerConnectedState.keys.filterNot { seen.contains(it) }
        for (key in vanished) {
            if (peerConnectedState[key] == true) lost.add(key)
            peerConnectedState.remove(key)
        }

        if (connected.isNotEmpty()) cues?.play(CuePlayer.Cue.CONNECT)
        if (lost.isNotEmpty()) cues?.play(CuePlayer.Cue.DISCONNECT)
        // "Connected"/"lost", not "receiving audio" — with the heartbeat leg of the rule, a peer
        // can be connected before (or without) sending any audio.
        for (address in connected) announce("Connected to ${nameFor(address)}")
        for (address in lost) announce("Connection to ${nameFor(address)} lost")
        audibleAddresses = nowAudible
    }

    private fun nameFor(address: Int): String {
        val addressString = UdpEndpoint(address, 0).addressString
        return _peers.value.firstOrNull {
            it.addresses.contains(address) || it.addressString == addressString
        }?.name ?: addressString
    }

    /**
     * Best heartbeat result across a peer's addresses: healthiest state first, then lowest round
     * trip.
     */
    private fun bestHealth(addresses: List<Int>, health: List<PeerHealth>): PeerHealth? =
        health.filter { addresses.contains(it.audioEndpoint.address) }
            .minWithOrNull(compareBy({ healthRank(it.state) }, { it.rttMs ?: Int.MAX_VALUE }))

    private fun healthRank(state: PeerHealthState): Int = when (state) {
        PeerHealthState.HEALTHY -> 0
        PeerHealthState.STALE -> 1
        PeerHealthState.UNKNOWN -> 2
        PeerHealthState.UNREACHABLE -> 3
    }

    private fun announce(message: String) {
        _announcement.value = message
    }

    private fun refreshPeerList() {
        val entries = mutableListOf<PeerListEntry>()
        val seenAddresses = mutableSetOf<String>()

        for (peer in discovery.currentPeers) {
            val selected = peer.addressStrings.any { selectedAddresses.contains(it) }
            entries.add(
                PeerListEntry(
                    // instanceId, NOT address — stable across path changes.
                    id = "d-${peer.instanceId}",
                    name = peer.name,
                    addressString = peer.addressString,
                    audioEndpoints = peer.audioEndpoints,
                    isManual = false,
                    manualPeerId = null,
                    isSelected = selected,
                    statusText = statusText(peer.addresses, selected),
                ),
            )
            seenAddresses.addAll(peer.addressStrings)
        }

        for (peer in manualPeers) {
            val resolved = manualResolved[peer.id].orEmpty()
            if (resolved.isEmpty()) {
                entries.add(
                    PeerListEntry(
                        id = "m-${peer.id}",
                        name = peer.displayName,
                        addressString = peer.host,
                        audioEndpoints = emptyList(),
                        isManual = true,
                        manualPeerId = peer.id,
                        isSelected = selectedAddresses.contains(peer.host),
                        statusText = if (_isRunning.value) "Resolving…" else "—",
                    ),
                )
                continue
            }
            // Merged with a discovery row when any resolved address matches one.
            if (resolved.any { seenAddresses.contains(it.addressString) }) continue
            val selected = selectedAddresses.contains(peer.host) ||
                resolved.any { selectedAddresses.contains(it.addressString) }
            entries.add(
                PeerListEntry(
                    id = "m-${peer.id}",
                    name = peer.displayName,
                    addressString = resolved[0].addressString,
                    audioEndpoints = resolved,
                    isManual = true,
                    manualPeerId = peer.id,
                    isSelected = selected,
                    statusText = statusText(resolved.map { it.address }, selected),
                ),
            )
        }

        if (entries != _peers.value) _peers.value = entries
    }

    private fun statusText(addresses: List<Int>, selected: Boolean): String {
        if (!_isRunning.value) return "—"
        if (!selected) return "Not selected"

        val parts = mutableListOf<String>()
        val flowing = addresses.firstOrNull { engine.isAudioFlowing(it, 1500) }
        val format = flowing?.let { engine.activeFormat(it) }
        if (format != null) {
            val codec = if (format.codec == AudioTransportCodec.OPUS) "Opus" else "PCM"
            parts.add("Receiving $codec ${Math.round(format.frameDurationMs)} ms frames")
        } else {
            parts.add("No audio")
        }

        // Worst-news-first across the peer's paths: a mismatch on any of them matters more than
        // a clean link on another.
        val security = addresses.map { engine.peerSecurityStatus(it) }
        when {
            security.contains(PeerSecurityStatus.PASSWORD_MISMATCH) -> parts.add("password does not match")
            security.contains(PeerSecurityStatus.PEER_NEEDS_UPDATE) -> parts.add("peer app needs update")
            security.contains(PeerSecurityStatus.SECURE) -> parts.add("encrypted link")
        }

        val health = bestHealth(addresses, heartbeat.allPeerHealth())
        when (health?.state) {
            PeerHealthState.HEALTHY -> health.rttMs?.let { parts.add("$it ms round trip") }
            PeerHealthState.STALE -> parts.add("connection unstable")
            PeerHealthState.UNREACHABLE -> parts.add("unreachable")
            PeerHealthState.UNKNOWN, null -> Unit
        }
        return parts.joinToString(", ")
    }

    private fun updateSummary() {
        val summary = when {
            !_isRunning.value -> "Stopped"
            // Sending and peer connections keep working — say so instead of "Stopped".
            !_receiveEnabled.value -> "Receiving is off — peers stay connected"
            audibleAddresses.isNotEmpty() ->
                "Receiving from ${audibleAddresses.size} peer${if (audibleAddresses.size == 1) "" else "s"} — " +
                    "buffer ${mixer.currentBufferMs} ms"
            _password.value.isEmpty() -> "Listening — set a password to receive audio"
            else -> "Listening on port ${settings.listenPort}"
        }
        if (summary != _statusSummary.value) _statusSummary.value = summary
    }

    private fun formatDuration(seconds: Double): String {
        val total = seconds.toInt()
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val secs = total % 60
        return when {
            hours > 0 -> "$hours h $minutes min"
            minutes > 0 -> "$minutes min $secs s"
            else -> "$secs seconds"
        }
    }

    companion object {
        private const val RESOLVE_RETRY_INTERVAL_MS = 5000L
        private const val MICROPHONE_DENIED_MESSAGE =
            "Microphone access is not allowed. Enable it in the app's permissions to send audio."

        @Volatile
        private var instance: ReceiverController? = null

        /**
         * The one live instance. The activity's UI and the foreground service must drive the SAME
         * receiver, so both go through this instead of creating their own.
         */
        fun shared(context: Context): ReceiverController =
            instance ?: synchronized(this) {
                instance ?: ReceiverController(context).also { instance = it }
            }
    }
}
