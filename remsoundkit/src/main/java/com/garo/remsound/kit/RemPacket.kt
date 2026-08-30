package com.garo.remsound.kit

/**
 * Wire format for RemSound packets, mirroring `RemSound.Core.RemPacket` in the Windows app
 * (v3.x wire protocol, header version 1). Header is 12 bytes; body length is implied by the
 * UDP datagram. Header layout (little-endian):
 *
 *     uint32 magic    'RMND'
 *     uint8  version  1
 *     uint8  type     RemPacketType
 *     uint16 streamId
 *     uint32 sequence
 */
enum class RemPacketType(val wireValue: Int) {
    FORMAT(1),
    AUDIO(2),

    /** Legacy (pre-2026-05-06 Windows builds). Silently ignored, never sent. */
    KEEP_ALIVE(3),
    HEARTBEAT(4),

    /**
     * Remote-control message (volume nudges). Not handled by this receiver — parsed and
     * dropped, same wire-safety contract as old Windows peers. (Windows 5.6+ seals this
     * payload with the audio key; we ignore it either way.)
     */
    CONTROL(5),

    // 6-9 are the relay's v2 lobby types (hello / roster / full / bye) — out of scope.

    /**
     * Relay address-proof challenge (server-v2.5). The relay sends a random cookie to every
     * newly seen client address; echoing the packet back verbatim proves the address really
     * receives. The relay runs this watch-only today, but once it flips
     * `--require-addr-check` an endpoint that never echoes gets ALL forwarded traffic
     * withheld — so we must answer.
     */
    ADDR_CHECK(10);

    companion object {
        fun fromWire(value: Int): RemPacketType? = entries.firstOrNull { it.wireValue == value }
    }
}

enum class HeartbeatKind(val wireValue: Int) {
    PING(0),
    PONG(1);

    companion object {
        fun fromWire(value: Int): HeartbeatKind? = entries.firstOrNull { it.wireValue == value }
    }
}

object RemPacket {
    const val HEADER_SIZE = 12

    /** Minimum format payload (pre-2026-05-11 Windows senders). */
    const val FORMAT_PAYLOAD_SIZE = 32

    /** 32 base + 1 Lane byte + 3 reserved-zero bytes. */
    const val FORMAT_PAYLOAD_EXTENDED_SIZE = 36

    /** Extended payload + 8-byte password fingerprint (2026-05-31+ senders). */
    const val FORMAT_PAYLOAD_WITH_FINGERPRINT_SIZE = 44
    const val PASSWORD_FINGERPRINT_SIZE = 8

    /** 1 byte HeartbeatKind + 8 bytes originator-monotonic timestamp (ms). */
    const val HEARTBEAT_PAYLOAD_SIZE = 9

    /** Single canonical port: receiver bind, peer dials, and the public relay. */
    const val DEFAULT_PORT = 47830

    /** 'RMND' little-endian. */
    const val MAGIC = 0x444E4D52

    const val VERSION: Byte = 1

    // ---- Header ----

    fun writeHeader(type: RemPacketType, streamId: Int, sequence: Int): ByteArray {
        val out = ByteArray(HEADER_SIZE)
        writeLe32(out, 0, MAGIC)
        out[4] = VERSION
        out[5] = type.wireValue.toByte()
        val id = if (streamId == 0) 1 else streamId
        writeLe16(out, 6, id)
        writeLe32(out, 8, sequence)
        return out
    }

    data class Header(val type: RemPacketType, val streamId: Int, val sequence: Int)

    /**
     * Parses the 12-byte header. Returns null for short packets, bad magic, a header version
     * other than 1, or an unknown packet type — the caller drops the packet, matching the
     * Windows receiver's silent-drop dispatch.
     */
    fun readHeader(packet: ByteArray, length: Int): Header? {
        if (length < HEADER_SIZE || packet.size < HEADER_SIZE) return null
        if (readLe32(packet, 0) != MAGIC) return null
        if (packet[4] != VERSION) return null
        val type = RemPacketType.fromWire(packet[5].toInt() and 0xFF) ?: return null
        var streamId = readLe16(packet, 6)
        if (streamId == 0) streamId = 1
        return Header(type, streamId, readLe32(packet, 8))
    }

    // ---- Format payload ----

    class ParsedFormat(val format: AudioFormatInfo, val passwordFingerprint: ByteArray?)

    /**
     * Reads a Format payload (legacy 32-byte, extended 36-byte, or fingerprinted 44-byte).
     * Unknown Lane values clamp to [RenderRoute.MIXED] — better to play the audio in the
     * default route than drop the stream. A missing fingerprint means the sender is a
     * pre-encryption build that needs to update.
     */
    fun readFormat(payload: ByteArray, offset: Int, length: Int): ParsedFormat? {
        if (length < FORMAT_PAYLOAD_SIZE) return null

        fun int32(fieldOffset: Int): Int = readLe32(payload, offset + fieldOffset)

        val fingerprint = if (length >= FORMAT_PAYLOAD_WITH_FINGERPRINT_SIZE) {
            payload.copyOfRange(offset + 36, offset + 44)
        } else {
            null
        }
        val lane = if (length >= FORMAT_PAYLOAD_EXTENDED_SIZE) {
            RenderRoute.fromWire(payload[offset + 32].toInt() and 0xFF)
        } else {
            RenderRoute.MIXED
        }

        val format = AudioFormatInfo(
            sampleRate = int32(0),
            channels = int32(4),
            bitsPerSample = int32(8),
            encoding = int32(12),
            blockAlign = int32(16),
            averageBytesPerSecond = int32(20),
            codec = AudioTransportCodec.fromWire(int32(24)) ?: AudioTransportCodec.PCM,
            frameSamplesPerChannel = int32(28),
            lane = lane,
        )
        return ParsedFormat(format, fingerprint)
    }

    /**
     * Writes a Format payload, mirroring the Windows `RemPacket.WriteFormatPayload`: eight
     * little-endian int32 fields, the Lane byte + 3 reserved-zero bytes, and (when a
     * fingerprint is supplied) the 8-byte password fingerprint — 36 or 44 bytes total.
     */
    fun writeFormatPayload(format: AudioFormatInfo, passwordFingerprint: ByteArray?): ByteArray {
        val withFingerprint = passwordFingerprint != null &&
            passwordFingerprint.size == PASSWORD_FINGERPRINT_SIZE
        val out = ByteArray(
            if (withFingerprint) FORMAT_PAYLOAD_WITH_FINGERPRINT_SIZE else FORMAT_PAYLOAD_EXTENDED_SIZE,
        )
        writeLe32(out, 0, format.sampleRate)
        writeLe32(out, 4, format.channels)
        writeLe32(out, 8, format.bitsPerSample)
        writeLe32(out, 12, format.encoding)
        writeLe32(out, 16, format.blockAlign)
        writeLe32(out, 20, format.averageBytesPerSecond)
        writeLe32(out, 24, format.codec.wireValue)
        writeLe32(out, 28, format.frameSamplesPerChannel)
        out[32] = format.lane.wireValue.toByte()
        // 33..35 stay zero (reserved).
        if (withFingerprint) {
            System.arraycopy(passwordFingerprint, 0, out, 36, PASSWORD_FINGERPRINT_SIZE)
        }
        return out
    }

    // ---- Heartbeat payload ----

    fun writeHeartbeatPayload(kind: HeartbeatKind, originatorTickMs: Long): ByteArray {
        val out = ByteArray(HEARTBEAT_PAYLOAD_SIZE)
        out[0] = kind.wireValue.toByte()
        writeLe64(out, 1, originatorTickMs)
        return out
    }

    data class ParsedHeartbeat(val kind: HeartbeatKind, val originatorTickMs: Long)

    fun readHeartbeat(payload: ByteArray, offset: Int, length: Int): ParsedHeartbeat? {
        if (length < HEARTBEAT_PAYLOAD_SIZE) return null
        val kind = HeartbeatKind.fromWire(payload[offset].toInt() and 0xFF) ?: return null
        return ParsedHeartbeat(kind, readLe64(payload, offset + 1))
    }
}

/**
 * PCM transport sub-header, mirroring `RemPcmFrame`. PCM frames are larger than one UDP
 * datagram, so they are split into multi-part chunks. Sub-header (6 bytes, little-endian)
 * prepended to the audio bytes:
 *
 *     uint32 frameId
 *     uint8  partIndex
 *     uint8  totalParts
 */
object RemPcmFrame {
    const val SUB_HEADER_SIZE = 6

    data class SubHeader(val frameId: Int, val partIndex: Int, val totalParts: Int)

    fun readSubHeader(source: ByteArray, offset: Int, length: Int): SubHeader? {
        if (length < SUB_HEADER_SIZE) return null
        val frameId = readLe32(source, offset)
        val partIndex = source[offset + 4].toInt() and 0xFF
        val totalParts = source[offset + 5].toInt() and 0xFF
        if (totalParts == 0 || partIndex >= totalParts) return null
        return SubHeader(frameId, partIndex, totalParts)
    }
}

// ---- Little-endian helpers ----

internal fun writeLe16(out: ByteArray, offset: Int, value: Int) {
    out[offset] = (value and 0xFF).toByte()
    out[offset + 1] = ((value ushr 8) and 0xFF).toByte()
}

internal fun writeLe32(out: ByteArray, offset: Int, value: Int) {
    for (i in 0 until 4) {
        out[offset + i] = ((value ushr (8 * i)) and 0xFF).toByte()
    }
}

internal fun writeLe64(out: ByteArray, offset: Int, value: Long) {
    for (i in 0 until 8) {
        out[offset + i] = ((value ushr (8 * i)) and 0xFF).toByte()
    }
}

internal fun readLe16(source: ByteArray, offset: Int): Int =
    (source[offset].toInt() and 0xFF) or ((source[offset + 1].toInt() and 0xFF) shl 8)

internal fun readLe32(source: ByteArray, offset: Int): Int =
    (source[offset].toInt() and 0xFF) or
        ((source[offset + 1].toInt() and 0xFF) shl 8) or
        ((source[offset + 2].toInt() and 0xFF) shl 16) or
        ((source[offset + 3].toInt() and 0xFF) shl 24)

internal fun readLe64(source: ByteArray, offset: Int): Long {
    var value = 0L
    for (i in 0 until 8) {
        value = value or ((source[offset + i].toLong() and 0xFF) shl (8 * i))
    }
    return value
}
