package com.garo.remsound.kit

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The receiver pipeline façade, mirroring the Windows `AudioReceiver`: owns the single UDP
 * socket on the audio port, routes packets to one [StreamSession] per (endpoint, streamId),
 * gates on the selected-peers allow-list, tracks per-peer security status from format-packet
 * fingerprints, and prunes idle sessions. Heartbeat packets are forwarded out via a hook
 * (single-port model — heartbeats share the audio socket).
 */
class AudioReceiverEngine {
    private val lock = Any()
    private val sessions = mutableMapOf<SessionKey, StreamSession>()
    private val decryptor = AudioDecryptor()
    private var socket: UdpSocket? = null
    private var pruneTimer: ScheduledExecutorService? = null

    /** Mix bus the audio output pulls from. */
    val mixer = PlayoutMixer()

    /**
     * Packet-level telemetry (loss, reordering, inter-arrival gaps, the sender's Opus mode),
     * aggregated across every session so the numbers survive streamId rotation and pruning.
     */
    val diagnostics = StreamDiagnostics()

    private data class SessionKey(val endpoint: UdpEndpoint, val streamId: Int)

    // Pushed by the app; read on the network thread.
    private var audioKey: ByteArray? = null
    private var audioFingerprint: ByteArray? = null

    /**
     * null = no filter (diagnostics only); empty = block everyone. Compared by IP only —
     * incoming packets carry the sender's ephemeral source port, not its audio port.
     */
    private var allowedSenderAddresses: Set<Int>? = null

    /**
     * Address -> peer identity, pushed by the app from discovery. A multi-homed peer (LAN +
     * Tailscale) has several addresses that are all one box; without this map the engine cannot
     * tell a second path to the same sender from a second sender, and opens a session for each.
     * An address absent from the map is its own peer, which is how this behaved before.
     */
    private var peerGroups: Map<Int, String> = emptyMap()

    /**
     * The other path of a peer whose stream is already being played, whose Format packets are
     * currently being ignored, and how many have been ignored. Surfaced in the diagnostics panel
     * because a sender duplicating one stream down two paths is otherwise invisible from here —
     * it just sounds like bad audio.
     */
    @Volatile
    var duplicatePathEndpoint: UdpEndpoint? = null
        private set

    @Volatile
    var duplicatePathIgnoredCount = 0L
        private set

    private val peerSecurity = mutableMapOf<Int, PeerSecurityStatus>()

    @Volatile
    var bytesReceived = 0L
        private set

    @Volatile
    var bytesSent = 0L
        private set

    private var startedAt: Long? = null

    /** Time since the listener was started, in seconds, for the status panel. */
    val uptimeSeconds: Double
        get() = startedAt?.let { (System.currentTimeMillis() - it) / 1000.0 } ?: 0.0

    /** Heartbeat packets arriving on the audio socket land here. Wire BEFORE start(). */
    var onHeartbeatReceived: ((buffer: ByteArray, length: Int, remote: UdpEndpoint) -> Unit)? = null

    /** Fired when a session opens/closes — drives UI refresh and connect/disconnect cues. */
    var onSessionsChanged: (() -> Unit)? = null
    var onDiagnostic: ((String) -> Unit)? = null

    /**
     * Gate equivalent to the Windows "Receive audio" tick: when false the socket stays bound
     * (heartbeats keep flowing) but Format/Audio packets are discarded pre-decode.
     */
    private var playbackEnabled = true

    // ---- Configuration ----

    fun setPassword(password: String) {
        setKeyMaterial(RemSoundCrypto.deriveKey(password), RemSoundCrypto.fingerprint(password))
    }

    /**
     * Push pre-derived key material — lets the app run PBKDF2 once and share the result with
     * the send engine instead of paying the ~100 ms derivation twice.
     */
    fun setKeyMaterial(key: ByteArray?, fingerprint: ByteArray?) = synchronized(lock) {
        audioKey = key
        audioFingerprint = fingerprint
    }

    /**
     * Mirrors the Windows `AudioReceiver.SetPlaybackEnabled` (single-port model): disabling
     * flips the gate FIRST — so in-flight packets on the network thread cannot open a fresh
     * session mid-teardown — then disposes every open session, so a later re-enable starts
     * clean instead of draining stale audio. The socket and heartbeat routing are untouched.
     */
    fun setPlaybackEnabled(enabled: Boolean) {
        var closed: List<StreamSession> = emptyList()
        val changed = synchronized(lock) {
            val was = playbackEnabled != enabled
            playbackEnabled = enabled
            if (was && !enabled) {
                closed = sessions.values.toList()
                sessions.clear()
            }
            was
        }
        if (!changed) return
        for (session in closed) {
            mixer.removeSession(session.endpoint, session.streamId)
        }
        onDiagnostic?.invoke(
            if (enabled) "playback enabled" else "playback disabled — ${closed.size} session(s) closed",
        )
        if (closed.isNotEmpty()) onSessionsChanged?.invoke()
    }

    /**
     * Declare which addresses belong to the same peer, so [handleFormat] can recognise a second
     * path to a sender it is already playing. Keys are addresses, values any stable per-peer id.
     */
    fun setPeerAddressGroups(groups: Map<Int, String>) = synchronized(lock) {
        peerGroups = groups
    }

    private fun peerKeyLocked(address: Int): String =
        peerGroups[address] ?: UdpEndpoint(address, 0).addressString

    fun setAllowedSenders(addresses: Set<Int>?) {
        val toClose = mutableListOf<StreamSession>()
        synchronized(lock) {
            allowedSenderAddresses = addresses
            if (addresses != null) {
                val iterator = sessions.entries.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    if (!addresses.contains(entry.key.endpoint.address)) {
                        toClose.add(entry.value)
                        iterator.remove()
                    }
                }
            }
        }
        for (session in toClose) {
            mixer.removeSession(session.endpoint, session.streamId)
            onDiagnostic?.invoke(
                "session closed (sender no longer selected): ${session.endpoint} stream=${session.streamId}",
            )
        }
        if (toClose.isNotEmpty()) onSessionsChanged?.invoke()
    }

    fun peerSecurityStatus(address: Int): PeerSecurityStatus =
        synchronized(lock) { peerSecurity[address] ?: PeerSecurityStatus.UNKNOWN }

    /**
     * True when decoded audio from this address reached a playout buffer within [withinMs] —
     * drives the connect/disconnect cues off the actual audio stream.
     */
    fun isAudioFlowing(address: Int, withinMs: Long): Boolean {
        val cutoff = System.currentTimeMillis() - withinMs
        return synchronized(lock) {
            sessions.values.any { it.endpoint.address == address && it.lastWriteTime >= cutoff }
        }
    }

    /**
     * Frame duration of the active stream, in ms, rounded **up** — the auto-tune's codec floor,
     * so overestimating by half a millisecond is safer than rounding below the real frame size.
     * With several senders this takes the largest frame (most conservative). Null when nothing
     * is streaming.
     */
    val activeStreamFrameMs: Int?
        get() = synchronized(lock) {
            if (sessions.isEmpty()) return@synchronized null
            var maxSamples = 0
            var sampleRate = SessionPlayout.MIX_SAMPLE_RATE
            for (session in sessions.values) {
                if (session.format.frameSamplesPerChannel > maxSamples) {
                    maxSamples = session.format.frameSamplesPerChannel
                    sampleRate = if (session.format.sampleRate > 0) {
                        session.format.sampleRate
                    } else {
                        SessionPlayout.MIX_SAMPLE_RATE
                    }
                }
            }
            if (maxSamples == 0) null else (maxSamples * 1000 + sampleRate - 1) / sampleRate
        }

    /**
     * Incremented every time a new session opens. A rise means the gap history the auto-tune
     * samples spans a session boundary and must be discarded — a cross-session arrival gap
     * would otherwise recommend an absurd target the new session could never arm at.
     */
    @Volatile
    var sessionsOpenedCount = 0L
        private set

    /**
     * Format of the freshest active session from this address (for "receiving Opus 10 ms…"
     * status lines), or null when nothing recent.
     */
    fun activeFormat(address: Int): AudioFormatInfo? {
        val cutoff = System.currentTimeMillis() - SESSION_IDLE_TIMEOUT_MS
        return synchronized(lock) {
            sessions.values
                .filter { it.endpoint.address == address && it.lastWriteTime >= cutoff }
                .maxByOrNull { it.lastWriteTime }
                ?.format
        }
    }

    // ---- Lifecycle ----

    /** Bind the UDP socket. Heartbeats flow regardless of `playbackEnabled`. */
    fun start(port: Int = RemPacket.DEFAULT_PORT) {
        if (socket != null) return
        val sock = UdpSocket(
            onPacket = { buffer, length, remote -> handleRawPacket(buffer, length, remote) },
            onDiagnostic = { onDiagnostic?.invoke("network: $it") },
        )
        sock.start(port)
        socket = sock
        startedAt = System.currentTimeMillis()
        diagnostics.reset() // counters read against uptime, so a restart starts them clean
        duplicatePathEndpoint = null
        duplicatePathIgnoredCount = 0L

        val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "RemSound.ReceiverMaintenance").apply { isDaemon = true }
        }
        executor.scheduleWithFixedDelay(
            { runCatching { pruneIdleSessions() } },
            1000,
            1000,
            TimeUnit.MILLISECONDS,
        )
        pruneTimer = executor
    }

    fun stop() {
        pruneTimer?.shutdownNow()
        pruneTimer = null
        socket?.stop()
        socket = null
        startedAt = null
        duplicatePathEndpoint = null
        synchronized(lock) { sessions.clear() }
        mixer.removeAllSessions()
    }

    /**
     * Send raw bytes from the audio socket — heartbeat transport (single-port model: pings and
     * pongs leave from the same socket / NAT pinhole audio arrives on, which is also what
     * claims our slot on the v1 pairwise relay).
     */
    fun sendFromAudioSocket(data: ByteArray, to: UdpEndpoint): Boolean {
        val sent = socket?.send(data, to) ?: false
        if (sent) bytesSent += data.size
        return sent
    }

    // ---- Packet path (network thread) ----

    /**
     * Test seam: drive the packet path without binding a socket, the same way
     * [PeerDiscoveryService.handleAnnouncement] is driven. Session routing is where the
     * multi-path bugs live, and none of it needs a network to exercise.
     */
    internal fun handlePacketForTest(buffer: ByteArray, length: Int, remote: UdpEndpoint) =
        handleRawPacket(buffer, length, remote)

    private fun handleRawPacket(buffer: ByteArray, length: Int, remote: UdpEndpoint) {
        bytesReceived += length
        val header = RemPacket.readHeader(buffer, length) ?: return

        when (header.type) {
            RemPacketType.FORMAT -> handleFormat(
                remote,
                header.streamId,
                buffer,
                RemPacket.HEADER_SIZE,
                length - RemPacket.HEADER_SIZE,
            )
            RemPacketType.AUDIO -> handleAudio(
                remote,
                header.streamId,
                header.sequence,
                buffer,
                RemPacket.HEADER_SIZE,
                length - RemPacket.HEADER_SIZE,
            )
            RemPacketType.HEARTBEAT -> onHeartbeatReceived?.invoke(buffer, length, remote)
            RemPacketType.ADDR_CHECK ->
                // Relay address-proof: echo the packet back to its source, verbatim, from this
                // same socket. Deliberately NOT gated on the allow-list — the challenge comes
                // from the relay, which the user need not have selected as a peer, and refusing
                // to echo costs us every relayed stream once the relay enforces. Safe to answer
                // blind: the reply is byte-identical to what arrived and goes only to the
                // sender's own address, so it can neither amplify nor be aimed at a third party.
                sendFromAudioSocket(buffer.copyOf(length), remote)
            // Legacy / not handled — silently ignored, wire-safe.
            RemPacketType.KEEP_ALIVE, RemPacketType.CONTROL -> Unit
        }
    }

    private fun isSenderAllowedLocked(remote: UdpEndpoint): Boolean {
        val allowed = allowedSenderAddresses ?: return true
        return allowed.contains(remote.address)
    }

    private fun handleFormat(remote: UdpEndpoint, streamId: Int, payload: ByteArray, offset: Int, length: Int) {
        val parsed = RemPacket.readFormat(payload, offset, length) ?: return
        val format = parsed.format

        var isNew = false
        var ignoredDuplicate: UdpEndpoint? = null
        var reportDuplicate = false
        val superseded = mutableListOf<StreamSession>()
        synchronized(lock) {
            // Gate read under the lock: setPlaybackEnabled(false) flips it before disposing
            // sessions, so a packet racing the teardown cannot open a fresh one.
            if (!playbackEnabled || !isSenderAllowedLocked(remote)) return

            // Record whether this peer's password matches ours, from the advertised
            // fingerprint — the UI reads this to explain silence (mismatch / out-of-date peer).
            val myFingerprint = audioFingerprint
            val fingerprint = parsed.passwordFingerprint
            peerSecurity[remote.address] = when {
                fingerprint == null -> PeerSecurityStatus.PEER_NEEDS_UPDATE
                myFingerprint == null -> PeerSecurityStatus.UNKNOWN
                RemSoundCrypto.fingerprintsEqual(fingerprint, myFingerprint) -> PeerSecurityStatus.SECURE
                else -> PeerSecurityStatus.PASSWORD_MISMATCH
            }

            val key = SessionKey(remote, streamId)
            val existing = sessions[key]
            if (existing != null && existing.matchesFormat(format)) return // nothing to do

            // Every other session for the same peer and the same lane. Same PEER, not same
            // endpoint: a multi-homed sender reaches us at several addresses, and before
            // [peerGroups] existed each of those opened its own session, so one stream arriving
            // down two paths was decoded twice and summed into the mix at two different path
            // delays — double the CPU and audible comb filtering. Lane-mismatched sessions still
            // coexist (BothIndependent mode really does send two lanes per peer).
            val peerKey = peerKeyLocked(remote.address)
            // Materialised as pairs, not as live map entries: the entries are removed below and
            // a view object outliving its mapping is a trap waiting for a map implementation
            // change.
            val rivals = sessions.entries
                .filter { entry ->
                    entry.key != key &&
                        peerKeyLocked(entry.key.endpoint.address) == peerKey &&
                        entry.value.format.lane == format.lane
                }
                .map { it.key to it.value }

            val now = System.currentTimeMillis()
            for ((rivalKey, rivalSession) in rivals) {
                if (rivalKey.endpoint.address == remote.address) {
                    // Same path, new streamId (or a rebound source port): the sender rerolls
                    // streamId on codec changes and engine restarts. Supersede at once so the old
                    // session does not sit idle racking up phantom underruns.
                    continue
                }
                // A DIFFERENT path of the same peer. If it is still delivering, this is a
                // duplicate rather than a handover: ignore the newcomer and keep playing the path
                // that already works, instead of tearing down a healthy stream (which would
                // re-arm the jitter buffer) or, worse, playing both.
                if (now - rivalSession.lastWriteTime <= PATH_HANDOVER_QUIET_MS) {
                    ignoredDuplicate = remote
                    reportDuplicate = duplicatePathEndpoint != remote
                    duplicatePathEndpoint = remote
                    duplicatePathIgnoredCount++
                    return@synchronized
                }
            }

            val playout = mixer.getOrCreateSession(remote, streamId)
            isNew = existing == null
            if (isNew) sessionsOpenedCount++
            sessions[key] = StreamSession(remote, streamId, format, playout, decryptor, diagnostics)

            for ((rivalKey, rivalSession) in rivals) {
                superseded.add(rivalSession)
                sessions.remove(rivalKey)
            }
            if (duplicatePathEndpoint?.address == remote.address) duplicatePathEndpoint = null
        }

        if (ignoredDuplicate != null) {
            if (reportDuplicate) {
                onDiagnostic?.invoke(
                    "duplicate path ignored (same peer already streaming): $ignoredDuplicate",
                )
            }
            return
        }

        for (old in superseded) {
            mixer.removeSession(old.endpoint, old.streamId)
            onDiagnostic?.invoke(
                "session superseded (streamId rotated): ${old.endpoint} old=${old.streamId} new=$streamId",
            )
        }
        if (isNew) {
            onDiagnostic?.invoke("session opened: $remote stream=$streamId ${format.displayDescription}")
            onSessionsChanged?.invoke()
        } else {
            onDiagnostic?.invoke("stream format changed: $remote stream=$streamId ${format.displayDescription}")
        }
    }

    private fun handleAudio(
        remote: UdpEndpoint,
        streamId: Int,
        sequence: Int,
        payload: ByteArray,
        offset: Int,
        length: Int,
    ) {
        val session = synchronized(lock) {
            if (!playbackEnabled || !isSenderAllowedLocked(remote)) return
            decryptor.ensureKey(audioKey)
            sessions[SessionKey(remote, streamId)]
        } ?: return // no Format seen yet — the session opens on Format

        session.handleAudioPayload(sequence, payload, offset, length)
    }

    // ---- Maintenance ----

    private fun pruneIdleSessions() {
        val now = System.currentTimeMillis()
        val removed = mutableListOf<StreamSession>()
        synchronized(lock) {
            val iterator = sessions.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.value.lastWriteTime > SESSION_IDLE_TIMEOUT_MS) {
                    removed.add(entry.value)
                    iterator.remove()
                }
            }
            // Hard-cap backstop: evict the idlest beyond MAX_LIVE_SESSIONS.
            if (sessions.size > MAX_LIVE_SESSIONS) {
                val excess = sessions.size - MAX_LIVE_SESSIONS
                val idlest = sessions.entries.sortedBy { it.value.lastWriteTime }.take(excess)
                for (entry in idlest) {
                    removed.add(entry.value)
                    sessions.remove(entry.key)
                }
            }
        }

        for (session in removed) {
            mixer.removeSession(session.endpoint, session.streamId)
            onDiagnostic?.invoke("session pruned (idle): ${session.endpoint} stream=${session.streamId}")
        }
        if (removed.isNotEmpty()) onSessionsChanged?.invoke()
    }

    companion object {
        const val SESSION_IDLE_TIMEOUT_MS = 4000L
        const val MAX_LIVE_SESSIONS = 32

        /**
         * How quiet the path currently being played has to go before another path of the same
         * peer may take the lane over. Long enough that ordinary jitter cannot trigger a
         * handover, short enough that a path that genuinely died is replaced before the idle
         * prune (4 s) would have dropped it anyway.
         */
        const val PATH_HANDOVER_QUIET_MS = 1000L
    }
}
