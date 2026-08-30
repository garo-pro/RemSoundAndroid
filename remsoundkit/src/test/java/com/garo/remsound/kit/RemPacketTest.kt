package com.garo.remsound.kit

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-contract tests. Everything asserted here is what makes Windows interop work; a failure
 * means this port has stopped speaking the same protocol, not that a test needs updating.
 */
class RemPacketTest {

    @Test
    fun headerRoundTripsThroughTheWireLayout() {
        val header = RemPacket.writeHeader(RemPacketType.AUDIO, streamId = 0x1234, sequence = 0x89ABCDEF.toInt())
        assertEquals(RemPacket.HEADER_SIZE, header.size)
        // Magic 'RMND' little-endian.
        assertArrayEquals(byteArrayOf(0x52, 0x4D, 0x4E, 0x44), header.copyOfRange(0, 4))
        assertEquals(1, header[4].toInt())
        assertEquals(2, header[5].toInt())

        val parsed = RemPacket.readHeader(header, header.size)
        assertNotNull(parsed)
        assertEquals(RemPacketType.AUDIO, parsed!!.type)
        assertEquals(0x1234, parsed.streamId)
        assertEquals(0x89ABCDEF.toInt(), parsed.sequence)
    }

    @Test
    fun streamIdZeroIsCoercedToOneOnBothSides() {
        val header = RemPacket.writeHeader(RemPacketType.FORMAT, streamId = 0, sequence = 1)
        assertEquals(1, readLe16(header, 6))
        assertEquals(1, RemPacket.readHeader(header, header.size)!!.streamId)
    }

    @Test
    fun headerRejectsBadMagicWrongVersionAndUnknownType() {
        val good = RemPacket.writeHeader(RemPacketType.AUDIO, 1, 1)

        val badMagic = good.copyOf().also { it[0] = 0x00 }
        assertNull(RemPacket.readHeader(badMagic, badMagic.size))

        // Header version 2 must be rejected outright, not best-effort parsed.
        val badVersion = good.copyOf().also { it[4] = 2 }
        assertNull(RemPacket.readHeader(badVersion, badVersion.size))

        val badType = good.copyOf().also { it[5] = 99 }
        assertNull(RemPacket.readHeader(badType, badType.size))

        assertNull(RemPacket.readHeader(good, RemPacket.HEADER_SIZE - 1))
    }

    @Test
    fun formatPayloadAcceptsAllThreeLengths() {
        val format = AudioFormatInfo(
            sampleRate = 48000,
            channels = 2,
            bitsPerSample = 24,
            encoding = 1,
            blockAlign = 6,
            averageBytesPerSecond = 288000,
            codec = AudioTransportCodec.OPUS,
            frameSamplesPerChannel = 480,
            lane = RenderRoute.ASIO_LANE,
        )
        val fingerprint = ByteArray(8) { it.toByte() }
        val full = RemPacket.writeFormatPayload(format, fingerprint)
        assertEquals(RemPacket.FORMAT_PAYLOAD_WITH_FINGERPRINT_SIZE, full.size)

        val parsedFull = RemPacket.readFormat(full, 0, full.size)!!
        assertEquals(format, parsedFull.format)
        assertArrayEquals(fingerprint, parsedFull.passwordFingerprint)

        // 36 bytes: lane survives, fingerprint absent means "peer needs update".
        val extended = RemPacket.readFormat(full, 0, RemPacket.FORMAT_PAYLOAD_EXTENDED_SIZE)!!
        assertEquals(RenderRoute.ASIO_LANE, extended.format.lane)
        assertNull(extended.passwordFingerprint)

        // 32 bytes: a pre-lane sender, which must read as the mixed lane rather than be dropped.
        val legacy = RemPacket.readFormat(full, 0, RemPacket.FORMAT_PAYLOAD_SIZE)!!
        assertEquals(RenderRoute.MIXED, legacy.format.lane)
        assertEquals(48000, legacy.format.sampleRate)
    }

    @Test
    fun unknownLaneValueClampsToMixed() {
        val payload = RemPacket.writeFormatPayload(
            AudioFormatInfo(48000, 2, 24, 1, 6, 288000),
            null,
        ).also { it[32] = 77 }
        assertEquals(RenderRoute.MIXED, RemPacket.readFormat(payload, 0, payload.size)!!.format.lane)
    }

    @Test
    fun frameSamplesFieldIsASampleCountNotMilliseconds() {
        // Field @28 is samples per channel: 480 at 48 kHz is 10 ms, not 480 ms.
        val payload = RemPacket.writeFormatPayload(
            AudioFormatInfo(48000, 2, 16, 1, 4, 192000, AudioTransportCodec.OPUS, 480),
            null,
        )
        assertEquals(480, readLe32(payload, 28))
        val parsed = RemPacket.readFormat(payload, 0, payload.size)!!.format
        assertEquals(480, parsed.frameSamplesPerChannel)
        assertEquals(10.0, parsed.frameDurationMs, 0.0001)
    }

    @Test
    fun heartbeatPayloadEchoesTheOriginatorTimestampVerbatim() {
        val payload = RemPacket.writeHeartbeatPayload(HeartbeatKind.PING, 1_234_567_890L)
        assertEquals(RemPacket.HEARTBEAT_PAYLOAD_SIZE, payload.size)
        val parsed = RemPacket.readHeartbeat(payload, 0, payload.size)!!
        assertEquals(HeartbeatKind.PING, parsed.kind)
        assertEquals(1_234_567_890L, parsed.originatorTickMs)
    }

    @Test
    fun pcmSubHeaderRejectsImpossiblePartIndexes() {
        val source = byteArrayOf(1, 0, 0, 0, 2, 3)
        val sub = RemPcmFrame.readSubHeader(source, 0, source.size)!!
        assertEquals(1, sub.frameId)
        assertEquals(2, sub.partIndex)
        assertEquals(3, sub.totalParts)

        assertNull(RemPcmFrame.readSubHeader(byteArrayOf(1, 0, 0, 0, 0, 0), 0, 6)) // totalParts 0
        assertNull(RemPcmFrame.readSubHeader(byteArrayOf(1, 0, 0, 0, 3, 3), 0, 6)) // index >= total
    }

    @Test
    fun formatIdentityIgnoresTheFieldsSessionsMayChange() {
        val a = AudioFormatInfo(48000, 2, 24, 1, 6, 288000, AudioTransportCodec.OPUS, 480)
        val b = a.copy(bitsPerSample = 16, blockAlign = 4, averageBytesPerSecond = 1)
        assertTrue(a.matchesIdentityOf(b))
        assertTrue(!a.matchesIdentityOf(a.copy(frameSamplesPerChannel = 960)))
        assertTrue(!a.matchesIdentityOf(a.copy(codec = AudioTransportCodec.PCM)))
    }
}
