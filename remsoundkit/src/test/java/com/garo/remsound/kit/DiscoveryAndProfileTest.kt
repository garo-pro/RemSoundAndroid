package com.garo.remsound.kit

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryAndProfileTest {

    private fun announcement(
        instanceId: String,
        name: String = "Windows PC",
        audioPort: Int = RemPacket.DEFAULT_PORT,
        canSend: Boolean = true,
        canReceive: Boolean = true,
    ): ByteArray = JSONObject()
        .put("InstanceId", instanceId)
        .put("Name", name)
        .put("AudioPort", audioPort)
        .put("CanSend", canSend)
        .put("CanReceive", canReceive)
        .toString()
        .toByteArray()

    private fun endpoint(host: String) = UdpEndpoint.parseLiteral(host, PeerDiscoveryService.DISCOVERY_PORT)!!

    /**
     * The discovery JSON keys are PascalCase and matched case-sensitively by the Windows side's
     * System.Text.Json. Getting one wrong makes us invisible to every Windows peer while looking
     * perfectly healthy from here.
     */
    @Test
    fun discoveryJsonUsesTheKeysWindowsExpects() {
        val json = JSONObject(String(announcement("id-1")))
        assertTrue(json.has("InstanceId"))
        assertTrue(json.has("Name"))
        assertTrue(json.has("AudioPort"))
        assertTrue(json.has("CanSend"))
        assertTrue(json.has("CanReceive"))
    }

    /**
     * A multi-homed peer (LAN + Tailscale) announces from several source addresses at once.
     * Keying row identity on a single address makes the stored peer flap between paths on every
     * alternating announcement, churning the allow-list and heartbeat state — so ALL live paths
     * are kept, oldest first.
     */
    @Test
    fun aPeerKeepsEveryAddressItAnnouncesFrom() {
        val discovery = PeerDiscoveryService()
        val payload = announcement("instance-a")
        discovery.handleAnnouncement(payload, payload.size, endpoint("192.168.1.50"))
        discovery.handleAnnouncement(payload, payload.size, endpoint("100.101.102.103"))

        val peers = discovery.currentPeers
        assertEquals(1, peers.size)
        val peer = peers[0]
        assertEquals(2, peer.addresses.size)
        // The primary is the oldest still-live path, so the display identity stays stable.
        assertEquals("192.168.1.50", peer.addressString)
        assertTrue(peer.addressStrings.containsAll(listOf("192.168.1.50", "100.101.102.103")))
        assertEquals(2, peer.audioEndpoints.size)
        assertTrue(peer.audioEndpoints.all { it.port == RemPacket.DEFAULT_PORT })
    }

    @Test
    fun twoInstancesFromOneAddressStayTwoPeers() {
        val discovery = PeerDiscoveryService()
        val a = announcement("instance-a", name = "PC one")
        val b = announcement("instance-b", name = "PC two")
        discovery.handleAnnouncement(a, a.size, endpoint("192.168.1.50"))
        discovery.handleAnnouncement(b, b.size, endpoint("192.168.1.50"))
        assertEquals(2, discovery.currentPeers.size)
    }

    /**
     * Receiving an announcement auto-adds its source as a unicast target — that is the mechanism
     * that makes discovery work over a VPN, where broadcast never arrives. Learned targets expire
     * with the peer; the user's own chosen peers never do.
     */
    @Test
    fun announcementSourcesBecomeUnicastTargetsAndExpire() {
        val discovery = PeerDiscoveryService()
        val payload = announcement("instance-a")
        discovery.handleAnnouncement(payload, payload.size, endpoint("10.0.0.7"))

        val now = System.currentTimeMillis()
        val learned = UdpEndpoint.parseLiteral("10.0.0.7", 0)!!.address
        assertTrue(discovery.unicastTargets(now).contains(learned))

        // A minute later the learned entry is stale and must stop being transmitted to.
        assertFalse(discovery.unicastTargets(now + 60_000).contains(learned))

        // A chosen peer stays a target regardless of how quiet it is.
        val chosen = UdpEndpoint.parseLiteral("10.0.0.9", 0)!!.address
        discovery.setUnicastPeerAddresses(listOf(chosen))
        assertTrue(discovery.unicastTargets(now + 60_000).contains(chosen))
    }

    @Test
    fun malformedAnnouncementsAreIgnoredRatherThanCrashing() {
        val discovery = PeerDiscoveryService()
        val junk = "not json at all".toByteArray()
        discovery.handleAnnouncement(junk, junk.size, endpoint("10.0.0.1"))
        val noId = JSONObject().put("Name", "x").toString().toByteArray()
        discovery.handleAnnouncement(noId, noId.size, endpoint("10.0.0.2"))
        assertTrue(discovery.currentPeers.isEmpty())
    }

    // ---- Profiles ----

    @Test
    fun profileRoundTripsThroughJson() {
        val profile = ReceiverProfile(
            id = "abc",
            name = "Home",
            manualPeers = listOf(ManualPeer(id = "p1", host = "100.64.0.1")),
            selectedPeerAddresses = listOf("192.168.1.50"),
            receiveEnabled = true,
            sendEnabled = true,
            selectedMicrophoneId = "12",
            targetLatencyMs = 120,
            autoTuneLatencyEnabled = true,
        )
        val decoded = ReceiverProfile.fromJson(JSONObject(profile.toJson().toString()))
        assertEquals(profile, decoded)
    }

    /**
     * The decode path treats a failure as "no profiles", so a decoder that insisted on a field
     * would silently WIPE the user's saved profiles the first time it met JSON from an older
     * build. Every field after id/name must decode with a default — this is what pins that.
     */
    @Test
    fun aProfileFromAnOlderBuildStillDecodes() {
        val minimal = JSONObject().put("id", "abc").put("name", "Old")
        val decoded = ReceiverProfile.fromJson(minimal)
        assertNotNull(decoded)
        assertEquals("Old", decoded!!.name)
        assertTrue(decoded.manualPeers.isEmpty())
        assertTrue(decoded.selectedPeerAddresses.isEmpty())
        assertTrue(decoded.receiveEnabled) // receiving has always defaulted on
        assertFalse(decoded.sendEnabled)
        assertNull(decoded.selectedMicrophoneId)
        assertEquals(ReceiverSettings.DEFAULT_TARGET_LATENCY_MS, decoded.targetLatencyMs)
        assertFalse(decoded.autoTuneLatencyEnabled)
    }

    @Test
    fun aProfileWithoutAnIdOrNameIsSkippedRatherThanInvented() {
        assertNull(ReceiverProfile.fromJson(JSONObject().put("name", "No id")))
        assertNull(ReceiverProfile.fromJson(JSONObject().put("id", "no-name")))
    }

    /** A profile's password lives in the secure store, never in the JSON that gets persisted. */
    @Test
    fun encodedProfileJsonNeverContainsAPassword() {
        val profile = ReceiverProfile(
            id = "abc",
            name = "Home",
            manualPeers = listOf(ManualPeer(host = "10.0.0.1")),
            selectedPeerAddresses = listOf("10.0.0.1"),
            receiveEnabled = true,
            sendEnabled = false,
            selectedMicrophoneId = null,
            targetLatencyMs = 80,
        )
        val json = profile.toJson().toString()
        assertFalse(json.contains("password", ignoreCase = true))
    }

    @Test
    fun endpointFormattingRoundTrips() {
        val endpoint = UdpEndpoint.parseLiteral("192.168.1.50", RemPacket.DEFAULT_PORT)!!
        assertEquals("192.168.1.50", endpoint.addressString)
        assertEquals("192.168.1.50:47830", endpoint.toString())
        assertNull(UdpEndpoint.parseLiteral("not.an.address", 1))
        assertNull(UdpEndpoint.parseLiteral("300.1.1.1", 1))
        // 255.255.255.255 is the limited broadcast the discovery announce also targets.
        assertEquals("255.255.255.255", UdpEndpoint(-1, 0).addressString)
    }
}
