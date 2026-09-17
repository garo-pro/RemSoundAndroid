package com.garo.remsound.kit

/**
 * The connect/disconnect cue state machine, split out of `ReceiverController.updateCues` so it
 * is pure — no Context, no sockets, no audio device — and can therefore be tested in CI, the
 * same reason [LatencyAutoTune.decide] is pure.
 *
 * It implements the Windows receiver's hysteresis rule: connected the moment audio arrives OR
 * the heartbeat is solidly healthy; lost only when audio has stopped AND the heartbeat has gone
 * unreachable. Everything in between HOLDS the previous state, so a two-second Wi-Fi/VPN stall
 * never fires a false disconnect+connect pair.
 *
 * **Identity is the peer's row id, never an address.** The earlier version keyed this state on
 * the peer's primary address, on the assumption that the primary is stable across path changes.
 * It is not: [PeerAnnouncement.address] is `addressOrder[0]`, and `PeerDiscoveryService` drops a
 * path that has not announced for its expiry window. A multi-homed peer (LAN + Tailscale) whose
 * LAN leg goes quiet — exactly what happens once a VPN takes the default route and broadcast
 * stops crossing — then gets a NEW primary address, and the old key falls into the "vanished"
 * branch below: a spurious "connection lost" immediately followed by "connected", while audio
 * never actually stopped. `PeerListEntry.id` is already `d-<instanceId>` / `m-<manualId>` and is
 * stable across every path change, so that is the key.
 */
internal class ConnectionCueTracker {

    /** One selected peer as of this tick. [id] must be stable across path changes. */
    data class Peer(
        val id: String,
        val name: String,
        val audioFlowing: Boolean,
        val health: PeerHealthState,
    )

    /** Names to announce this tick. Empty lists mean "nothing changed" — the common case. */
    data class Events(val connected: List<String>, val lost: List<String>) {
        val isEmpty: Boolean get() = connected.isEmpty() && lost.isEmpty()

        companion object {
            val NONE = Events(emptyList(), emptyList())
        }
    }

    /** Remembering the name as well as the flag so a vanished peer can still be announced. */
    private data class Entry(val name: String, val connected: Boolean)

    private val state = mutableMapOf<String, Entry>()

    /** Forget everything without announcing. Stopping the receiver is its own feedback. */
    fun reset() = state.clear()

    fun update(peers: List<Peer>): Events {
        val connected = mutableListOf<String>()
        val lost = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        for (peer in peers) {
            seen.add(peer.id)
            val isConnected = peer.audioFlowing || peer.health == PeerHealthState.HEALTHY
            val isLost = !peer.audioFlowing && peer.health == PeerHealthState.UNREACHABLE
            val previous = state[peer.id]
            when {
                isConnected && previous?.connected != true -> {
                    connected.add(peer.name)
                    state[peer.id] = Entry(peer.name, true)
                }
                isLost && previous?.connected == true -> {
                    lost.add(peer.name)
                    state[peer.id] = Entry(peer.name, false)
                }
                // First sighting, neither clearly connected nor lost (selected but no audio or
                // pong yet) — seed quietly. If it later goes unreachable without ever having
                // connected, that is a connect-FAILED event and stays silent too.
                previous == null -> state[peer.id] = Entry(peer.name, false)
                // Known peer, no edge: keep the flag, refresh the name (peers can be renamed).
                else -> state[peer.id] = previous.copy(name = peer.name)
            }
        }

        // Peers that left the selected set entirely (deselected, or their discovery entry
        // expired): a disconnect cue only if they were connected when last seen. One that never
        // connected stays quiet.
        val vanished = state.keys.filterNot { seen.contains(it) }
        for (id in vanished) {
            val entry = state.remove(id) ?: continue
            if (entry.connected) lost.add(entry.name)
        }

        return if (connected.isEmpty() && lost.isEmpty()) Events.NONE else Events(connected, lost)
    }
}
