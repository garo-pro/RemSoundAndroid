package com.garo.remsound.kit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The multi-homed-peer failure modes: a sender reachable at both a LAN address and a VPN
 * address (Tailscale) at the same time. All three regressions fixed here only appear on that
 * shape of network, which is exactly why none of them showed up on a plain Wi-Fi LAN.
 */
class MultiPathTest {

    // ---- Connect / disconnect cues ----

    private fun peer(
        id: String,
        name: String = "Windows PC",
        audio: Boolean = false,
        health: PeerHealthState = PeerHealthState.UNKNOWN,
    ) = ConnectionCueTracker.Peer(id = id, name = name, audioFlowing = audio, health = health)

    /**
     * The regression this class exists for. The cue state used to be keyed on the peer's primary
     * address, which is `addressOrder[0]` and therefore changes the moment the oldest path ages
     * out of discovery — routine once a VPN takes the default route and LAN broadcast stops
     * arriving. Keying on the row id (stable across every path change) is what keeps this quiet.
     */
    @Test
    fun aPathChangeDoesNotFireADisconnectConnectPair() {
        val tracker = ConnectionCueTracker()
        assertEquals(
            listOf("Windows PC"),
            tracker.update(listOf(peer("d-instance-a", audio = true))).connected,
        )
        // Same peer, same row id, still streaming — only the path underneath it changed.
        assertTrue(tracker.update(listOf(peer("d-instance-a", audio = true))).isEmpty)
    }

    @Test
    fun heartbeatHealthConnectsBeforeAnyAudioArrives() {
        val tracker = ConnectionCueTracker()
        val events = tracker.update(listOf(peer("d-a", health = PeerHealthState.HEALTHY)))
        assertEquals(listOf("Windows PC"), events.connected)
    }

    /**
     * The Windows hysteresis rule: lost needs audio stopped AND the heartbeat unreachable. A
     * stale heartbeat during a two-second stall holds the previous state instead of firing a
     * false pair.
     */
    @Test
    fun aStalledPathHoldsConnectedRatherThanFiringALostCue() {
        val tracker = ConnectionCueTracker()
        tracker.update(listOf(peer("d-a", audio = true)))
        assertTrue(tracker.update(listOf(peer("d-a", health = PeerHealthState.STALE))).isEmpty)
        assertTrue(tracker.update(listOf(peer("d-a", health = PeerHealthState.UNKNOWN))).isEmpty)
        assertEquals(
            listOf("Windows PC"),
            tracker.update(listOf(peer("d-a", health = PeerHealthState.UNREACHABLE))).lost,
        )
    }

    @Test
    fun aPeerThatNeverConnectedVanishesSilently() {
        val tracker = ConnectionCueTracker()
        tracker.update(listOf(peer("d-a")))
        assertTrue(tracker.update(emptyList()).isEmpty)
    }

    @Test
    fun aConnectedPeerThatVanishesIsAnnouncedByTheNameItHadLast() {
        val tracker = ConnectionCueTracker()
        tracker.update(listOf(peer("d-a", name = "Studio PC", audio = true)))
        assertEquals(listOf("Studio PC"), tracker.update(emptyList()).lost)
        // And it is forgotten, so it cannot be announced twice.
        assertTrue(tracker.update(emptyList()).isEmpty)
    }

    // ---- Allow-list grace ----

    private fun ep(host: String) = UdpEndpoint.parseLiteral(host, RemPacket.DEFAULT_PORT)!!

    /**
     * Discovery liveness must not gate playback. Announcements are unicast-only over a VPN
     * (broadcast does not cross the tunnel), so losing enough of them to pass the 8 s expiry is
     * routine — and the allow-list rebuild that follows used to close the live sessions of a
     * peer that was still streaming perfectly well.
     */
    @Test
    fun anAddressSurvivesAGapInDiscovery() {
        val grace = SelectionGrace(graceMs = 30_000L)
        val now = 1_000_000L
        grace.record(listOf(ep("100.101.102.103")), now)

        // Ten seconds later discovery has expired the peer, but the grace window has not.
        assertEquals(listOf(ep("100.101.102.103")), grace.remembered(now + 10_000))
        // A minute later a peer that really is gone stops being allowed.
        assertTrue(grace.remembered(now + 60_000).isEmpty())
    }

    @Test
    fun aFreshSightingRestartsTheGraceWindow() {
        val grace = SelectionGrace(graceMs = 10_000L)
        grace.record(listOf(ep("192.168.1.50")), 0L)
        grace.record(listOf(ep("192.168.1.50")), 8_000L)
        assertEquals(1, grace.remembered(15_000L).size)
        assertTrue(grace.remembered(20_000L).isEmpty())
    }

    @Test
    fun clearForgetsEverything() {
        val grace = SelectionGrace()
        grace.record(listOf(ep("10.0.0.1")), 0L)
        grace.clear()
        assertTrue(grace.remembered(0L).isEmpty())
    }

    // ---- Duplicate-path sessions ----

    private fun formatPacket(streamId: Int, lane: RenderRoute): ByteArray {
        val format = AudioFormatInfo(
            sampleRate = 48000,
            channels = 2,
            bitsPerSample = 16,
            encoding = 1,
            blockAlign = 4,
            averageBytesPerSecond = 192000,
            codec = AudioTransportCodec.OPUS,
            frameSamplesPerChannel = 480,
            lane = lane,
        )
        return RemPacket.writeHeader(RemPacketType.FORMAT, streamId, 0) +
            RemPacket.writeFormatPayload(format, null)
    }

    private fun AudioReceiverEngine.feedFormat(
        from: String,
        streamId: Int,
        lane: RenderRoute = RenderRoute.MIXED,
    ) {
        val packet = formatPacket(streamId, lane)
        // Source port 51000: senders transmit from an ephemeral port, not the audio port.
        handlePacketForTest(packet, packet.size, UdpEndpoint.parseLiteral(from, 51000)!!)
    }

    /**
     * One sender, two addresses, one stream. Sessions are keyed by (endpoint, streamId), so
     * before the engine was told which addresses are the same box, each path opened its own
     * session: the stream was decoded twice and summed into the mix at two different path
     * delays. That is double the CPU — on a phone already running userspace WireGuard — plus
     * comb filtering that sounds exactly like a bad link.
     */
    @Test
    fun aSecondPathToTheSamePeerDoesNotOpenASecondSession() {
        val engine = AudioReceiverEngine()
        val lan = UdpEndpoint.parseLiteral("192.168.1.50", 0)!!.address
        val vpn = UdpEndpoint.parseLiteral("100.101.102.103", 0)!!.address
        engine.setPeerAddressGroups(mapOf(lan to "d-instance-a", vpn to "d-instance-a"))

        engine.feedFormat("192.168.1.50", streamId = 7)
        assertEquals(1, engine.mixer.activeSessionCount)

        // The same machine announces the same stream down its VPN path, still within the
        // handover window. It must be ignored, not mixed in alongside.
        engine.feedFormat("100.101.102.103", streamId = 9)
        assertEquals(1, engine.mixer.activeSessionCount)
        assertNotNull(engine.duplicatePathEndpoint)
        assertEquals("100.101.102.103", engine.duplicatePathEndpoint?.addressString)
        assertEquals(1L, engine.duplicatePathIgnoredCount)
    }

    /** Two genuinely different senders still get a session each. */
    @Test
    fun twoDifferentPeersStillGetASessionEach() {
        val engine = AudioReceiverEngine()
        val a = UdpEndpoint.parseLiteral("192.168.1.50", 0)!!.address
        val b = UdpEndpoint.parseLiteral("192.168.1.51", 0)!!.address
        engine.setPeerAddressGroups(mapOf(a to "d-instance-a", b to "d-instance-b"))

        engine.feedFormat("192.168.1.50", streamId = 1)
        engine.feedFormat("192.168.1.51", streamId = 2)
        assertEquals(2, engine.mixer.activeSessionCount)
        assertNull(engine.duplicatePathEndpoint)
    }

    /**
     * BothIndependent mode really does send two concurrent lanes per peer, so the duplicate
     * guard must be per lane — never collapse them.
     */
    @Test
    fun twoLanesFromOnePeerStillCoexist() {
        val engine = AudioReceiverEngine()
        val lan = UdpEndpoint.parseLiteral("192.168.1.50", 0)!!.address
        engine.setPeerAddressGroups(mapOf(lan to "d-instance-a"))

        engine.feedFormat("192.168.1.50", streamId = 1, lane = RenderRoute.WASAPI_LANE)
        engine.feedFormat("192.168.1.50", streamId = 2, lane = RenderRoute.ASIO_LANE)
        assertEquals(2, engine.mixer.activeSessionCount)
    }

    /** streamId rotation on one path still supersedes, exactly as before. */
    @Test
    fun aRotatedStreamIdOnTheSamePathSupersedes() {
        val engine = AudioReceiverEngine()
        engine.feedFormat("192.168.1.50", streamId = 1)
        engine.feedFormat("192.168.1.50", streamId = 2)
        assertEquals(1, engine.mixer.activeSessionCount)
    }

    /** With no grouping pushed, every address is its own peer — the pre-existing behaviour. */
    @Test
    fun withoutGroupingEachAddressIsItsOwnPeer() {
        val engine = AudioReceiverEngine()
        engine.feedFormat("192.168.1.50", streamId = 1)
        engine.feedFormat("100.101.102.103", streamId = 2)
        assertEquals(2, engine.mixer.activeSessionCount)
    }
}
