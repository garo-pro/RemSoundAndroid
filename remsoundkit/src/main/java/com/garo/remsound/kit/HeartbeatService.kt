package com.garo.remsound.kit

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

enum class PeerHealthState {
    UNKNOWN,
    HEALTHY,
    STALE,
    UNREACHABLE,
}

data class PeerHealth(
    val audioEndpoint: UdpEndpoint,
    val state: PeerHealthState,
    val rttMs: Int?,
)

/**
 * Bidirectional UDP heartbeat, wire-compatible with the Windows `HeartbeatService`
 * single-port model: pings ride the audio port via the shared socket, pongs echo the
 * originator's monotonic timestamp verbatim so RTT needs no clock sync between peers. The
 * 1 Hz cadence doubles as the NAT keepalive — which is also what claims our slot on the
 * public relay (v1 pairwise mode) so reflected audio can reach us.
 */
class HeartbeatService {
    /**
     * Outbound transport. REQUIRED — wire to the receiver's main UDP socket so heartbeats
     * share the socket (and NAT pinhole) that audio arrives on.
     */
    var sendTransport: ((data: ByteArray, to: UdpEndpoint) -> Boolean)? = null
    var onDiagnostic: ((String) -> Unit)? = null

    private val lock = Any()
    private val peers = mutableMapOf<UdpEndpoint, PeerState>()
    private var timer: ScheduledExecutorService? = null
    private var sequence = 0
    private val startNanos = System.nanoTime()

    private val monotonicMs: Long
        get() = (System.nanoTime() - startNanos) / 1_000_000

    fun start() {
        synchronized(lock) {
            if (timer != null) return
            val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "RemSound.Heartbeat").apply { isDaemon = true }
            }
            executor.scheduleWithFixedDelay(
                { runCatching { sendPings() } },
                PING_INTERVAL_MS,
                PING_INTERVAL_MS,
                TimeUnit.MILLISECONDS,
            )
            timer = executor
        }
    }

    fun stop() {
        synchronized(lock) {
            timer?.shutdownNow()
            timer = null
        }
    }

    /** Replace the tracked peer set (each endpoint is the peer's audio port). */
    fun setTrackedPeers(audioEndpoints: List<UdpEndpoint>) {
        synchronized(lock) {
            val desired = audioEndpoints.toSet()
            peers.keys.retainAll(desired)
            for (endpoint in desired) {
                peers.getOrPut(endpoint) { PeerState() }
            }
        }
    }

    fun allPeerHealth(): List<PeerHealth> = synchronized(lock) {
        val now = System.currentTimeMillis()
        peers.map { (endpoint, state) -> snapshotHealthLocked(endpoint, state, now) }
    }

    private fun snapshotHealthLocked(endpoint: UdpEndpoint, state: PeerState, now: Long): PeerHealth {
        val lastPong = state.lastPong
        if (lastPong == null) {
            val firstPing = state.firstPingSent
            return if (firstPing != null && now - firstPing > UNREACHABLE_WINDOW_MS) {
                PeerHealth(endpoint, PeerHealthState.UNREACHABLE, null)
            } else {
                PeerHealth(endpoint, PeerHealthState.UNKNOWN, null)
            }
        }
        val age = now - lastPong
        val healthState = when {
            age <= HEALTHY_WINDOW_MS -> PeerHealthState.HEALTHY
            age <= UNREACHABLE_WINDOW_MS -> PeerHealthState.STALE
            else -> PeerHealthState.UNREACHABLE
        }
        return PeerHealth(endpoint, healthState, state.rttEwmaMs)
    }

    private fun sendPings() {
        val transport = sendTransport ?: return
        val targets: List<UdpEndpoint>
        val seq: Int
        synchronized(lock) {
            targets = peers.keys.toList()
            val now = System.currentTimeMillis()
            for (state in peers.values) {
                if (state.firstPingSent == null) state.firstPingSent = now
            }
            sequence++
            seq = sequence
        }

        // streamId 0xFFFF marks heartbeats, same as the Windows sender.
        val packet = RemPacket.writeHeader(RemPacketType.HEARTBEAT, 0xFFFF, seq) +
            RemPacket.writeHeartbeatPayload(HeartbeatKind.PING, monotonicMs)
        for (target in targets) {
            transport(packet, target)
        }
    }

    /** Feed a heartbeat packet that arrived on the shared audio socket. */
    fun handleInjectedPacket(buffer: ByteArray, length: Int, remote: UdpEndpoint) {
        val header = RemPacket.readHeader(buffer, length) ?: return
        if (header.type != RemPacketType.HEARTBEAT) return
        val payload = RemPacket.readHeartbeat(
            buffer,
            RemPacket.HEADER_SIZE,
            length - RemPacket.HEADER_SIZE,
        ) ?: return

        if (payload.kind == HeartbeatKind.PING) {
            val seq = synchronized(lock) {
                sequence++
                sequence
            }
            // Echo the originator's timestamp back as a Pong, to wherever the ping came from
            // (works for LAN-direct and relay-return alike).
            val reply = RemPacket.writeHeader(RemPacketType.HEARTBEAT, 0xFFFF, seq) +
                RemPacket.writeHeartbeatPayload(HeartbeatKind.PONG, payload.originatorTickMs)
            sendTransport?.invoke(reply, remote)
            return
        }

        // Pong: RTT against our own clock; match tracked peers by IP only (the pong's source
        // port is the peer's outbound/NAT port, not its audio port).
        val rtt = maxOf(0L, monotonicMs - payload.originatorTickMs).toInt()
        val now = System.currentTimeMillis()
        synchronized(lock) {
            for ((endpoint, state) in peers) {
                if (endpoint.address != remote.address) continue
                val previous = state.rttEwmaMs
                state.rttEwmaMs = if (previous == null) rtt else (previous * 0.7 + rtt * 0.3).toInt()
                state.lastPong = now
            }
        }
    }

    private class PeerState {
        var firstPingSent: Long? = null
        var lastPong: Long? = null
        var rttEwmaMs: Int? = null
    }

    companion object {
        const val PING_INTERVAL_MS = 1000L
        const val HEALTHY_WINDOW_MS = 2000L
        const val UNREACHABLE_WINDOW_MS = 5000L
    }
}
