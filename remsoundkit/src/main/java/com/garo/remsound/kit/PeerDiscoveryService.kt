package com.garo.remsound.kit

import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * A peer seen via discovery announcements. Mirrors `RemSound.Core.PeerAnnouncement`, except
 * that one instance keeps ALL the source addresses it announces from. A multi-homed peer
 * (LAN + VPN, e.g. Tailscale) announces from several addresses at once; the Windows model of
 * one address per instance makes the stored peer flap between paths on every alternating
 * announcement, which churns the allow-list, heartbeat state, and row identity.
 */
data class PeerAnnouncement(
    val instanceId: String,
    val name: String,
    val audioPort: Int,
    val canSend: Boolean,
    val canReceive: Boolean,
    /** All live IPv4 source addresses, first-seen first. Never empty. */
    val addresses: List<Int>,
) {
    /** Primary (oldest still-live) address — the stable display identity. */
    val address: Int get() = addresses[0]

    val addressString: String get() = UdpEndpoint(address, 0).addressString

    val addressStrings: List<String> get() = addresses.map { UdpEndpoint(it, 0).addressString }

    val audioEndpoints: List<UdpEndpoint> get() = addresses.map { UdpEndpoint(it, audioPort) }
}

/**
 * UDP peer discovery, wire-compatible with the Windows `PeerDiscoveryService`: JSON
 * announcements on UDP 47821 every 1.5 s, peers expire after 8 s. Announcements go out by LAN
 * broadcast AND by unicast to known peer IPs (broadcast does not traverse VPNs like
 * Tailscale). Receiving an announcement auto-adds the source IP to the unicast targets so
 * discovery becomes bidirectional even if only one side knew the other's address.
 *
 * The JSON keys are PascalCase and matched **case-sensitively** by the Windows side's
 * System.Text.Json — `InstanceId`, `Name`, `AudioPort`, `CanSend`, `CanReceive`. All failures
 * here are swallowed: discovery is convenience, and manual peers plus unicast announcements
 * still work.
 */
class PeerDiscoveryService {
    private val instanceId: String = UUID.randomUUID().toString()
    private val lock = Any()

    /**
     * Mutable per-instance state behind [PeerAnnouncement] snapshots: each announce path
     * (source address) expires independently, and `addressOrder` keeps the primary stable.
     */
    private class PeerRecord(
        var name: String,
        var audioPort: Int,
        var canSend: Boolean,
        var canReceive: Boolean,
        val addressOrder: MutableList<Int>,
        val lastSeenByAddress: MutableMap<Int, Long>,
    )

    private val peers = mutableMapOf<String, PeerRecord>()
    private var socket: UdpSocket? = null
    private var timer: ScheduledExecutorService? = null

    private var audioPort = RemPacket.DEFAULT_PORT
    private var displayName = "Android device"

    /**
     * Manually entered / selected peers (via [setUnicastPeerAddresses]). These NEVER expire —
     * the user asked for them, and a quiet peer must still receive our announcements.
     */
    private var providedUnicastTargets: List<Int> = emptyList()

    /**
     * Auto-learned announcement sources (address → last time it announced to us). An entry
     * that has not been refreshed within the peer-expiry window is pruned before each send,
     * so we stop unicasting our 1.5 s announcement to peers that vanished hours ago —
     * pointless traffic that only holds the network radio in its active state (battery).
     */
    private val learnedUnicastTargets = mutableMapOf<Int, Long>()

    /**
     * Advertised capabilities — the live send/receive toggles, like Windows (its
     * UpdateCapabilities re-announces on every checkbox change).
     */
    private var canSend = true
    private var canReceive = true

    /** Fired (on an arbitrary thread) whenever the visible peer set changes. */
    var onPeersChanged: (() -> Unit)? = null
    var onDiagnostic: ((String) -> Unit)? = null

    val currentPeers: List<PeerAnnouncement>
        get() = synchronized(lock) {
            pruneExpiredLocked()
            peers.map { (id, record) ->
                PeerAnnouncement(
                    instanceId = id,
                    name = record.name,
                    audioPort = record.audioPort,
                    canSend = record.canSend,
                    canReceive = record.canReceive,
                    addresses = record.addressOrder.toList(),
                )
            }.sortedWith(compareBy({ it.name }, { it.addressString }))
        }

    fun start(displayName: String, audioPort: Int) {
        stop()
        this.displayName = displayName
        this.audioPort = audioPort

        val sock = UdpSocket(
            onPacket = { buffer, length, remote -> handleAnnouncement(buffer, length, remote) },
            onDiagnostic = { onDiagnostic?.invoke("discovery: $it") },
        )
        try {
            sock.start(port = DISCOVERY_PORT, enableBroadcast = true)
        } catch (e: Exception) {
            // Port already in use or a platform restriction — discovery is best-effort.
            onDiagnostic?.invoke("discovery: bind failed: $e")
            return
        }
        socket = sock

        val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "RemSound.Discovery").apply { isDaemon = true }
        }
        executor.scheduleWithFixedDelay(
            { runCatching { sendAnnouncement() } },
            0,
            ANNOUNCE_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
        timer = executor
    }

    fun stop() {
        timer?.shutdownNow()
        timer = null
        socket?.stop()
        socket = null
    }

    /**
     * Update the advertised CanSend/CanReceive flags and re-announce immediately so peers
     * learn the change now, not up to 1.5 s later (Windows `UpdateCapabilities` parity).
     */
    fun setCapabilities(canSend: Boolean, canReceive: Boolean) {
        val changed = synchronized(lock) {
            val was = this.canSend != canSend || this.canReceive != canReceive
            this.canSend = canSend
            this.canReceive = canReceive
            was
        }
        if (!changed) return
        timer?.execute { runCatching { sendAnnouncement() } }
    }

    /**
     * Replace the set of IPs that announcements are unicast to (manual/remembered peers).
     * These are the user's chosen peers and never expire.
     */
    fun setUnicastPeerAddresses(addresses: List<Int>) {
        synchronized(lock) { providedUnicastTargets = addresses.distinct() }
        timer?.execute { runCatching { sendAnnouncement() } }
    }

    // ---- Wire format ----

    private fun sendAnnouncement() {
        val sock = socket ?: return
        val (announceCanSend, announceCanReceive) = synchronized(lock) { canSend to canReceive }
        val json = JSONObject()
            .put("InstanceId", instanceId)
            .put("Name", displayName)
            .put("AudioPort", audioPort)
            .put("CanSend", announceCanSend)
            .put("CanReceive", announceCanReceive)
            .toString()
            .toByteArray(Charsets.UTF_8)

        for (target in NetworkInterfaces.broadcastAddresses(DISCOVERY_PORT)) {
            sock.send(json, target) // best effort
        }

        val unicast = synchronized(lock) {
            pruneLearnedUnicastLocked(System.currentTimeMillis())
            // User-chosen peers (never expire) unioned with still-live learned sources.
            (providedUnicastTargets + learnedUnicastTargets.keys).toSet()
        }
        for (address in unicast) {
            sock.send(json, UdpEndpoint(address, DISCOVERY_PORT))
        }
    }

    /**
     * Drop learned unicast targets that have not announced within the peer-expiry window.
     * Provided (manual/selected) targets are untouched — they never expire.
     */
    private fun pruneLearnedUnicastLocked(now: Long) {
        val cutoff = now - PEER_EXPIRY_MS
        learnedUnicastTargets.entries.removeAll { it.value < cutoff }
    }

    /**
     * Test seam: the effective unicast set (provided ∪ still-live learned) as of `now`,
     * pruning expired learned entries. [sendAnnouncement] uses the same logic live.
     */
    internal fun unicastTargets(nowMillis: Long): List<Int> = synchronized(lock) {
        pruneLearnedUnicastLocked(nowMillis)
        (providedUnicastTargets + learnedUnicastTargets.keys).toSet().toList()
    }

    // Internal (not private) so tests can drive the multi-address bookkeeping without sockets.
    internal fun handleAnnouncement(buffer: ByteArray, length: Int, remote: UdpEndpoint) {
        val message = try {
            JSONObject(String(buffer, 0, length, Charsets.UTF_8))
        } catch (_: Exception) {
            return
        }
        val messageInstanceId = message.optString("InstanceId")
        if (messageInstanceId.isEmpty()) return
        // Our own broadcasts come back to us; the InstanceId check filters them out.
        if (messageInstanceId == instanceId) return

        val trimmedName = message.optString("Name").trim()
        val name = trimmedName.ifEmpty { remote.addressString }
        val peerAudioPort = message.optInt("AudioPort", RemPacket.DEFAULT_PORT).coerceIn(0, 65535)
        val peerCanSend = message.optBoolean("CanSend", false)
        val peerCanReceive = message.optBoolean("CanReceive", false)
        val now = System.currentTimeMillis()

        var changed: Boolean
        synchronized(lock) {
            // Announce back the way it came — makes discovery bidirectional over VPNs.
            // Stamping the source's learned-target time on every announcement keeps it a
            // unicast target while it keeps announcing, and lets it expire from that set once
            // it goes quiet, so we do not keep transmitting to a vanished peer.
            learnedUnicastTargets[remote.address] = now

            val existing = peers[messageInstanceId]
            if (existing != null) {
                changed = existing.name != name ||
                    existing.audioPort != peerAudioPort ||
                    existing.canSend != peerCanSend ||
                    existing.canReceive != peerCanReceive
                existing.name = name
                existing.audioPort = peerAudioPort
                existing.canSend = peerCanSend
                existing.canReceive = peerCanReceive
                if (!existing.lastSeenByAddress.containsKey(remote.address)) {
                    existing.addressOrder.add(remote.address)
                    changed = true // new path for a known peer — allow-list etc. must re-feed
                }
                existing.lastSeenByAddress[remote.address] = now
            } else {
                peers[messageInstanceId] = PeerRecord(
                    name = name,
                    audioPort = peerAudioPort,
                    canSend = peerCanSend,
                    canReceive = peerCanReceive,
                    addressOrder = mutableListOf(remote.address),
                    lastSeenByAddress = mutableMapOf(remote.address to now),
                )
                changed = true
            }
            pruneExpiredLocked()
        }

        if (changed) onPeersChanged?.invoke()
    }

    private fun pruneExpiredLocked() {
        val cutoff = System.currentTimeMillis() - PEER_EXPIRY_MS
        val iterator = peers.entries.iterator()
        while (iterator.hasNext()) {
            val record = iterator.next().value
            record.addressOrder.removeAll { (record.lastSeenByAddress[it] ?: 0L) < cutoff }
            record.lastSeenByAddress.entries.removeAll { it.value < cutoff }
            if (record.addressOrder.isEmpty()) iterator.remove()
        }
    }

    companion object {
        const val DISCOVERY_PORT = 47821
        private const val ANNOUNCE_INTERVAL_MS = 1500L
        private const val PEER_EXPIRY_MS = 8000L
    }
}
