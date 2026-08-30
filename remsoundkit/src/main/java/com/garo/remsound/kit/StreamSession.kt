package com.garo.remsound.kit

import kotlin.math.floor
import kotlin.math.max

/**
 * Per-sender decode pipeline, mirroring the Windows `StreamSession`: PCM frame assembly +
 * decrypt + int24→float, or Opus decrypt + decode with single-packet FEC recovery. All work
 * runs on the network receive thread; the only cross-thread interaction is writing decoded
 * floats into the [SessionPlayout].
 */
internal class StreamSession(
    val endpoint: UdpEndpoint,
    val streamId: Int,
    val format: AudioFormatInfo,
    private val playout: SessionPlayout,
    private val decryptor: AudioDecryptor,
    /**
     * Packet-level telemetry sink (loss / reorder / arrival gaps / sender Opus mode).
     * Measurement only — the decode path below behaves identically with or without it.
     */
    private val diagnostics: StreamDiagnostics?,
) {
    private val pcmAssembler = PcmFrameAssembler()
    private val opusDecoder: OpusStreamDecoder? =
        if (format.codec == AudioTransportCodec.OPUS) {
            OpusStreamDecoder(format.sampleRate, format.channels).takeIf { it.isValid }
        } else {
            null
        }
    private var expectedNextSequence: Int? = null
    private val arrivals = ArrivalTracker()

    // Reused scratch buffers — steady-state allocation-free decode path.
    private var floatScratch = FloatArray(4096)
    private var stereoScratch = FloatArray(8192)
    private var shortScratch = ShortArray(4096)

    /**
     * PCM passthrough mode on the Windows sender can put the capture device's native rate on
     * the wire (44.1 k etc.); Opus is always 48 k. Resample anything non-48k.
     */
    private val resampler: LinearResampler? =
        if (format.sampleRate != SessionPlayout.MIX_SAMPLE_RATE && format.sampleRate > 0) {
            LinearResampler(format.sampleRate, SessionPlayout.MIX_SAMPLE_RATE)
        } else {
            null
        }

    val lastWriteTime: Long get() = playout.lastWriteTime

    fun matchesFormat(other: AudioFormatInfo): Boolean = format.matchesIdentityOf(other)

    /**
     * Decode one audio payload and hand the result to playout. Malformed or undecryptable
     * payloads are dropped silently — a wrong password is surfaced from the format packet's
     * fingerprint, never as garbage audio.
     */
    fun handleAudioPayload(sequence: Int, payload: ByteArray, offset: Int, length: Int) {
        diagnostics?.let { arrivals.record(sequence, it) }
        when (format.codec) {
            AudioTransportCodec.PCM -> handlePcm(payload, offset, length)
            AudioTransportCodec.OPUS -> handleOpus(sequence, payload, offset, length)
        }
    }

    // ---- PCM ----

    private fun handlePcm(payload: ByteArray, offset: Int, length: Int) {
        val sub = RemPcmFrame.readSubHeader(payload, offset, length) ?: return
        val partOffset = offset + RemPcmFrame.SUB_HEADER_SIZE
        val partLength = length - RemPcmFrame.SUB_HEADER_SIZE
        if (partLength < 0) return
        val assembledLength = pcmAssembler.assemble(
            payload,
            partOffset,
            partLength,
            sub.frameId,
            sub.partIndex,
            sub.totalParts,
        )
        if (assembledLength < 0) return // pending or dropped-by-policy — not an error

        // The reassembled frame is ciphertext — decrypt, then unpack int24 LE to float.
        val plain = decryptor.tryDecrypt(pcmAssembler.assembled, 0, assembledLength)
        if (plain == null) {
            diagnostics?.recordDecryptFailure()
            return
        }

        val sampleCount = plain.size / 3
        if (sampleCount == 0) return
        if (floatScratch.size < sampleCount) floatScratch = FloatArray(sampleCount)
        PcmPack.int24LeToFloat(plain, 0, plain.size, floatScratch)
        emit(floatScratch, sampleCount)
    }

    // ---- Opus ----

    private fun handleOpus(sequence: Int, payload: ByteArray, offset: Int, length: Int) {
        val decoder = opusDecoder ?: return
        val plain = decryptor.tryDecrypt(payload, offset, length)
        if (plain == null) {
            diagnostics?.recordDecryptFailure()
            return
        }
        // The first plaintext byte is the Opus TOC — it says which internal mode the sender's
        // encoder actually chose, and therefore whether inband FEC can exist at all.
        if (plain.isNotEmpty()) diagnostics?.recordOpusToc(plain[0])

        // Floor at 120 samples = 2.5 ms, libopus's RESTRICTED_LOWDELAY minimum, so a malformed
        // format packet cannot undersize the decode buffer.
        val frameSize = max(120, format.frameSamplesPerChannel)
        val needed = frameSize * format.channels
        if (shortScratch.size < needed) shortScratch = ShortArray(needed)

        // Single-packet gap: this packet carries FEC redundancy for the one we missed. Decode
        // the FEC frame first (so audio stays in order), then the current frame.
        val expected = expectedNextSequence
        val useFec = expected != null && ((sequence - expected).toLong() and 0xFFFF_FFFFL) == 1L
        if (useFec) {
            val fecCount = decoder.decode(plain, 0, plain.size, frameSize, true, shortScratch)
            if (fecCount > 0) emitShorts(fecCount)
        }

        val decoded = decoder.decode(plain, 0, plain.size, frameSize, false, shortScratch)
        if (decoded <= 0) return
        emitShorts(decoded)
        expectedNextSequence = sequence + 1
    }

    private fun emitShorts(samplesPerChannel: Int) {
        val total = samplesPerChannel * format.channels
        if (floatScratch.size < total) floatScratch = FloatArray(total)
        for (i in 0 until total) {
            floatScratch[i] = shortScratch[i] / 32768.0f
        }
        emit(floatScratch, total)
    }

    /** Common tail: up/down-mix to stereo, resample to 48 k if needed, hand to playout. */
    private fun emit(samples: FloatArray, sampleCount: Int) {
        var stereo = samples
        var frames = sampleCount / max(1, format.channels)

        when (format.channels) {
            2 -> Unit
            1 -> {
                if (stereoScratch.size < sampleCount * 2) stereoScratch = FloatArray(sampleCount * 2)
                for (i in 0 until sampleCount) {
                    stereoScratch[i * 2] = samples[i]
                    stereoScratch[i * 2 + 1] = samples[i]
                }
                stereo = stereoScratch
                frames = sampleCount
            }
            // The wire protocol is mono/stereo only — anything else is malformed.
            else -> return
        }

        val resamplerRef = resampler
        if (resamplerRef != null) {
            // The resampler writes into stereoScratch, so a mono input already living there
            // must be copied out first — otherwise input and output alias.
            val input = if (stereo === stereoScratch) stereo.copyOf(frames * 2) else stereo
            val outFrames = resamplerRef.process(input, frames) { needed ->
                if (stereoScratch.size < needed) stereoScratch = FloatArray(needed)
                stereoScratch
            }
            playout.write(stereoScratch, outFrames)
        } else {
            playout.write(stereo, frames)
        }
    }
}

/**
 * Stateful linear-interpolation stereo resampler for the (uncommon) PCM-passthrough case where
 * the wire rate is not 48 kHz. Linear is audibly adequate here; the Windows receiver uses a WDL
 * resampler, and this can be upgraded if a non-48k sender is in regular use.
 */
internal class LinearResampler(inputRate: Int, outputRate: Int) {
    private val ratio: Double = inputRate.toDouble() / outputRate.toDouble()
    private var position = 0.0
    private var prevL = 0f
    private var prevR = 0f
    private var primed = false

    /**
     * Returns the number of output frames written. Output is interleaved stereo, obtained from
     * [outputProvider], which is handed the required float count so the caller can grow its
     * own reusable buffer.
     */
    fun process(input: FloatArray, inputFrames: Int, outputProvider: (Int) -> FloatArray): Int {
        if (inputFrames <= 0) return 0
        val maxOut = ((inputFrames + 2) / ratio).toInt() + 2
        val output = outputProvider(maxOut * 2)
        if (!primed) {
            prevL = input[0]
            prevR = input[1]
            primed = true
        }

        var out = 0
        // `position` is the fractional read cursor in input frames, where -1 refers to the
        // carried-over previous frame.
        while (true) {
            val idx = floor(position).toInt()
            if (idx >= inputFrames - 1) {
                // Need the next packet; carry the last frame and rebase the cursor.
                position -= inputFrames
                prevL = input[(inputFrames - 1) * 2]
                prevR = input[(inputFrames - 1) * 2 + 1]
                break
            }
            val frac = (position - idx).toFloat()
            val l0: Float
            val r0: Float
            if (idx < 0) {
                l0 = prevL
                r0 = prevR
            } else {
                l0 = input[idx * 2]
                r0 = input[idx * 2 + 1]
            }
            val l1 = input[(idx + 1) * 2]
            val r1 = input[(idx + 1) * 2 + 1]
            output[out * 2] = l0 + (l1 - l0) * frac
            output[out * 2 + 1] = r0 + (r1 - r0) * frac
            out++
            position += ratio
        }
        return out
    }
}
