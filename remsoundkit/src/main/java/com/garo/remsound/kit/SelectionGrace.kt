package com.garo.remsound.kit

/**
 * Short-term memory of where a selected peer was last reachable, so the allow-list survives a
 * gap in discovery.
 *
 * `ReceiverController.applyPeerSelection` rebuilds the allow-list from `discovery.currentPeers`
 * as it stands at that instant, and `AudioReceiverEngine.setAllowedSenders` closes every live
 * session whose address is no longer in the set. That rebuild is event-driven — it also runs
 * from the manual-peer DNS retry every few seconds — so it can land inside a window where a peer
 * that is still streaming has momentarily aged out of discovery.
 *
 * On a LAN that never happens: announcements go out by broadcast every 1.5 s and the expiry is
 * 8 s. Over a VPN it happens readily — broadcast does not cross the tunnel, so only the unicast
 * announcements carry, and losing six in a row on a relayed path is unremarkable. The result was
 * that a perfectly healthy stream got its sessions torn down and had to re-arm.
 *
 * So an address that discovery has shown for a selected peer stays eligible for [graceMs] after
 * discovery last saw it. This deliberately does NOT widen who may be heard: the caller
 * intersects what comes back with the user's current selection, so deselecting a peer drops its
 * addresses immediately, and nothing is remembered across a restart. It only spans a gap. A peer
 * whose announcements never arrive at all is what manual peers are for — those never expire.
 */
internal class SelectionGrace(private val graceMs: Long = DEFAULT_GRACE_MS) {

    /** One remembered path: when it was last seen, which peer it belongs to, and how it is selected. */
    internal data class Remembered(
        val endpoint: UdpEndpoint,
        /** Same identity the engine groups paths by (`d-<instanceId>` / `m-<manualId>`). */
        val group: String,
        /**
         * Every string that can mark this peer as selected (all of its address strings, plus a
         * manual peer's hostname). The peer is still selected while ANY of them is, which is the
         * same rule `applyPeerSelection` uses for live peers � an expired path's own address
         * string may never have been selected itself if the path appeared after the user ticked
         * the peer.
         */
        val selectionKeys: Set<String>,
    )

    private val lastSeen = mutableMapOf<UdpEndpoint, Pair<Long, Remembered>>()

    /** Stamp every endpoint discovery can currently see for a selected peer. */
    @Synchronized
    fun record(
        endpoints: Collection<UdpEndpoint>,
        now: Long,
        group: String = "",
        selectionKeys: Set<String> = emptySet(),
    ) {
        for (endpoint in endpoints) {
            val keys = selectionKeys + endpoint.addressString
            lastSeen[endpoint] = now to Remembered(endpoint, group, keys)
        }
    }

    /**
     * Paths seen within the grace window, oldest entries dropped as it runs. The caller
     * filters these against the live selection before allowing anything.
     */
    @Synchronized
    fun remembered(now: Long): List<Remembered> {
        lastSeen.entries.removeAll { now - it.value.first > graceMs }
        return lastSeen.values.map { it.second }
    }

    @Synchronized
    fun clear() = lastSeen.clear()

    companion object {
        /**
         * Comfortably longer than the 8 s discovery expiry — about twenty consecutive lost
         * announcements — and still short enough that a peer really gone stops being allowed
         * well inside a minute.
         */
        const val DEFAULT_GRACE_MS = 30_000L
    }
}
