package com.garo.remsound.kit

/** Transport codec announced in a Format packet. */
enum class AudioTransportCodec(val wireValue: Int) {
    PCM(1),
    OPUS(2);

    companion object {
        fun fromWire(value: Int): AudioTransportCodec? = entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * Which render lane a stream is tagged for. The Windows sender's BothIndependent mode emits
 * two parallel streams tagged `wasapiLane` / `asioLane`; this receiver has a single output,
 * so the tag only matters for session identity (two lanes from one peer must coexist) — all
 * lanes mix into the one output.
 */
enum class RenderRoute(val wireValue: Int) {
    MIXED(0),
    WASAPI_LANE(1),
    ASIO_LANE(2);

    companion object {
        /** Unknown lane values clamp to [MIXED] — forward compatibility, never a reject. */
        fun fromWire(value: Int): RenderRoute = entries.firstOrNull { it.wireValue == value } ?: MIXED
    }
}

/**
 * Audio format announcement carried in every Format packet, mirroring
 * `RemSound.Core.AudioFormatInfo`. Note [frameSamplesPerChannel] is an exact sample count at
 * [sampleRate] (v3.0 wire change) — NOT milliseconds.
 */
data class AudioFormatInfo(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val encoding: Int,
    val blockAlign: Int,
    val averageBytesPerSecond: Int,
    val codec: AudioTransportCodec = AudioTransportCodec.PCM,
    val frameSamplesPerChannel: Int = 480,
    val lane: RenderRoute = RenderRoute.MIXED,
) {
    /** Human-friendly frame duration; may be fractional (2.5 ms at 48 kHz / 120 samples). */
    val frameDurationMs: Double
        get() = if (sampleRate > 0) frameSamplesPerChannel * 1000.0 / sampleRate else 0.0

    /**
     * Session identity for format-change detection — the same fields the Windows receiver's
     * `StreamSession.MatchesFormat` compares.
     */
    fun matchesIdentityOf(other: AudioFormatInfo): Boolean =
        codec == other.codec &&
            sampleRate == other.sampleRate &&
            channels == other.channels &&
            frameSamplesPerChannel == other.frameSamplesPerChannel

    val displayDescription: String
        get() {
            val encodingName = when (encoding) {
                1 -> "PCM"
                3 -> "IEEE float"
                else -> "encoding $encoding"
            }
            val codecName = if (codec == AudioTransportCodec.OPUS) {
                String.format(" over Opus (%.2f ms)", frameDurationMs)
            } else {
                ""
            }
            return "$sampleRate Hz, $channels channel(s), $bitsPerSample-bit $encodingName$codecName"
        }
}
