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

    private val lastSeen = mutableMapOf<UdpEndpoint, Long>()

    /** Stamp every endpoint discovery can currently see for a selected peer. */
    fun record(endpoints: Collection<UdpEndpoint>, now: Long) {
        for (endpoint in endpoints) lastSeen[endpoint] = now
    }

    /**
     * Endpoints seen within the grace window, oldest entries dropped as it runs. The caller
     * filters these against the live selection before allowing anything.
     */
    fun remembered(now: Long): List<UdpEndpoint> {
        lastSeen.entries.removeAll { now - it.value > graceMs }
        return lastSeen.keys.toList()
    }

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
