package com.garo.remsound.kit

import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusEncoder

/**
 * Opus codec wrappers over Concentus, a pure-Java port of libopus.
 *
 * Concentus rather than an NDK build of libopus: the whole receive/send path then compiles
 * and unit-tests on a plain JVM runner with no NDK and no prebuilt `.so` in the repo, which
 * is what keeps CI a single Gradle invocation. It exposes the one thing this port actually
 * needs from the raw C API — `decode_fec`, the flag that recovers a single lost packet from
 * the next packet's inband redundancy, exactly as the Windows receiver does.
 */
internal class OpusStreamDecoder(val sampleRate: Int, val channels: Int) {
    private val decoder: OpusDecoder? = try {
        OpusDecoder(sampleRate, channels)
    } catch (_: Exception) {
        null
    }

    val isValid: Boolean get() = decoder != null

    /**
     * Decode one packet into interleaved int16 samples. [frameSize] is samples per channel.
     * [fec] true decodes the redundancy for the PREVIOUS (lost) packet instead of this one.
     * Returns the decoded samples-per-channel count, or -1 on decode failure.
     */
    fun decode(packet: ByteArray, offset: Int, length: Int, frameSize: Int, fec: Boolean, output: ShortArray): Int {
        val dec = decoder ?: return -1
        return try {
            val decoded = dec.decode(packet, offset, length, output, 0, frameSize, fec)
            if (decoded > 0) decoded else -1
        } catch (_: Exception) {
            -1
        }
    }
}

/**
 * Thin wrapper over the Concentus encoder, configured like the Windows sender's
 * `OpusEncoderState`: 48 kHz stereo, RESTRICTED_LOWDELAY, 192 kbps VBR, complexity 10, inband
 * FEC with a 10 % packet-loss bias.
 */
internal class OpusStreamEncoder(val frameSizePerChannel: Int = 480) {
    private val encoder: OpusEncoder? = try {
        OpusEncoder(SAMPLE_RATE, CHANNELS, OpusApplication.OPUS_APPLICATION_RESTRICTED_LOWDELAY).apply {
            bitrate = BITRATE
            complexity = 10
            useVBR = true
            useInbandFEC = true
            packetLossPercent = 10
        }
    } catch (_: Exception) {
        null
    }

    private val packetScratch = ByteArray(4000)
    private val pcmScratch = ShortArray(frameSizePerChannel * CHANNELS)

    val isValid: Boolean get() = encoder != null

    /**
     * Encode one frame of interleaved stereo float (`frameSizePerChannel * 2` samples).
     * Returns the encoded length, with the bytes in [encoded] (valid until the next call), or
     * -1 on failure.
     */
    fun encode(interleaved: FloatArray): Int {
        val enc = encoder ?: return -1
        // Concentus encodes from int16; the capture path is float, so convert in place into a
        // reused scratch buffer rather than allocating on the audio path.
        for (i in pcmScratch.indices) {
            val clamped = interleaved[i].coerceIn(-1f, 1f)
            pcmScratch[i] = (clamped * 32767f).toInt().toShort()
        }
        return try {
            val written = enc.encode(pcmScratch, 0, frameSizePerChannel, packetScratch, 0, packetScratch.size)
            if (written > 0) written else -1
        } catch (_: Exception) {
            -1
        }
    }

    val encoded: ByteArray get() = packetScratch

    companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
        const val BITRATE = 192_000
    }
}
