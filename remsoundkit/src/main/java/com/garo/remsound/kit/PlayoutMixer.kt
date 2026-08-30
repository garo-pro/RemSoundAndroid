package com.garo.remsound.kit

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.tanh

/**
 * Optional make-up gain applied on top of the volume control, for senders whose stream is
 * simply quiet at 100 %.
 *
 * Discrete decibel steps rather than a longer volume slider: a 0–400 % slider is 80 swipes
 * wide under a screen reader, and folding "normal volume" together with "gain that can
 * distort" into one control hides the fact that the top of the range is not free. The mixer's
 * soft limiter still catches the peaks, so a boost cannot clip — it compresses instead.
 */
enum class VolumeBoost(val db: Int) {
    OFF(0),
    PLUS_3(3),
    PLUS_6(6),
    PLUS_12(12);

    /** Linear gain for this decibel step (0 dB = 1.0, +6 dB ≈ 2.0, +12 dB ≈ 4.0). */
    val gain: Float
        get() = if (db == 0) 1f else 10.0.pow(db / 20.0).toFloat()

    /** Plain label — a screen reader reads "+6 dB" as "plus 6 D B", which is what it is. */
    val displayName: String
        get() = if (db == 0) "Off" else "+$db dB"

    companion object {
        fun fromDb(db: Int): VolumeBoost = entries.firstOrNull { it.db == db } ?: OFF
    }
}

/**
 * Multi-source mix bus, mirroring the Windows `PlayoutEngine`: one [SessionPlayout] per active
 * stream, all summed at render time, then volume / mute / soft limiter. [render] runs on the
 * audio thread; session add/remove takes a short lock and swaps an immutable snapshot array
 * the render thread iterates.
 */
class PlayoutMixer {
    private val lock = Any()
    private val sessions = mutableMapOf<SessionKey, SessionPlayout>()

    @Volatile
    private var snapshot: List<SessionPlayout> = emptyList()

    /** 0…1 linear volume, applied post-mix. */
    @Volatile
    var volume: Float = 1.0f

    /**
     * Extra make-up gain multiplied in with [volume], ahead of the limiter, so a boost
     * compresses rather than clips.
     */
    @Volatile
    var boost: VolumeBoost = VolumeBoost.OFF

    @Volatile
    var isMuted = false

    /** Target latency in ms applied to every (current and future) session buffer. */
    var targetLatencyMs = 80
        private set

    private data class SessionKey(val endpoint: UdpEndpoint, val streamId: Int)

    fun setTargetLatencyMs(ms: Int, drainOnLower: Boolean = true) {
        val all = synchronized(lock) {
            targetLatencyMs = ReceiverSettings.clampLatency(ms)
            snapshot
        }
        for (session in all) {
            session.setTargetLatencyMs(targetLatencyMs, drainOnLower)
        }
    }

    internal fun getOrCreateSession(endpoint: UdpEndpoint, streamId: Int): SessionPlayout =
        synchronized(lock) {
            val key = SessionKey(endpoint, streamId)
            sessions[key]?.let { return@synchronized it }
            val playout = SessionPlayout(endpoint, streamId, targetLatencyMs)
            sessions[key] = playout
            snapshot = sessions.values.toList()
            playout
        }

    internal fun removeSession(endpoint: UdpEndpoint, streamId: Int) = synchronized(lock) {
        sessions.remove(SessionKey(endpoint, streamId))?.let { retireCounters(it) }
        snapshot = sessions.values.toList()
    }

    internal fun removeAllSessions() = synchronized(lock) {
        for (session in sessions.values) retireCounters(session)
        sessions.clear()
        snapshot = emptyList()
    }

    // Glitch counters of removed sessions are folded into these so the cumulative totals the
    // status UI diffs against never run backwards when a stream ends or is superseded.
    private var retiredUnderruns = 0L
    private var retiredTrimFires = 0L
    private var retiredTuneBlocking = 0L
    private var retiredDeviceGulp = 0L
    private var retiredConcealedFrames = 0L

    private fun retireCounters(session: SessionPlayout) {
        val counters = session.glitchCounters
        retiredUnderruns += counters.underruns
        retiredTrimFires += counters.trims
        retiredTuneBlocking += counters.tuneBlocking
        retiredDeviceGulp += counters.deviceGulp
        retiredConcealedFrames += counters.concealedFrames
    }

    data class GlitchTotals(
        val underruns: Long,
        val trims: Long,
        val tuneBlocking: Long,
        val deviceGulp: Long,
        val concealedMs: Long,
    )

    /**
     * Cumulative underrun / click-trim counts across all sessions, past and present.
     * [GlitchTotals.tuneBlocking] and [GlitchTotals.deviceGulp] partition the short reads by
     * cause — the auto-tune gates on the first and must ignore the second.
     */
    val glitchTotals: GlitchTotals
        get() {
            var underruns: Long
            var trims: Long
            var tuneBlocking: Long
            var deviceGulp: Long
            var concealedFrames: Long
            val all: List<SessionPlayout>
            synchronized(lock) {
                all = snapshot
                underruns = retiredUnderruns
                trims = retiredTrimFires
                tuneBlocking = retiredTuneBlocking
                deviceGulp = retiredDeviceGulp
                concealedFrames = retiredConcealedFrames
            }
            for (session in all) {
                val counters = session.glitchCounters
                underruns += counters.underruns
                trims += counters.trims
                tuneBlocking += counters.tuneBlocking
                deviceGulp += counters.deviceGulp
                concealedFrames += counters.concealedFrames
            }
            return GlitchTotals(
                underruns,
                trims,
                tuneBlocking,
                deviceGulp,
                concealedFrames * 1000 / SessionPlayout.MIX_SAMPLE_RATE,
            )
        }

    // Render-callback period, measured rather than assumed. The auto-tune adds the observed
    // callback gap to the network gap, so a chunky or late-scheduled output device cannot be
    // mistaken for network jitter. Peak since the last drain, in ms.
    private var peakRenderGapMs = 0
    private var lastRenderNs = 0L

    /** Read and reset the peak render-callback interval. UI side, once per tick. */
    fun drainPeakRenderGapMs(): Int = synchronized(lock) {
        val peak = peakRenderGapMs
        peakRenderGapMs = 0
        peak
    }

    /**
     * Called at the top of [render] on the audio thread. Uses the same lock the snapshot read
     * below takes, so this adds no extra synchronisation to the callback.
     */
    private fun noteRenderCallback() {
        val nowNs = System.nanoTime()
        synchronized(lock) {
            if (lastRenderNs != 0L) {
                val gapMs = ((nowNs - lastRenderNs) / 1_000_000).toInt()
                if (gapMs > peakRenderGapMs) peakRenderGapMs = gapMs
            }
            lastRenderNs = nowNs
        }
    }

    val activeSessionCount: Int
        get() = synchronized(lock) { sessions.size }

    /** Worst-case buffered duration across sessions, for the status UI. */
    val currentBufferMs: Int
        get() = snapshot.maxOfOrNull { it.bufferedMs } ?: 0

    /**
     * Render [frames] stereo frames of mixed audio into [output] (interleaved float32).
     * Called from the audio render thread.
     */
    fun render(output: FloatArray, frames: Int) {
        noteRenderCallback()
        val sampleCount = frames * SessionPlayout.MIX_CHANNELS
        java.util.Arrays.fill(output, 0, sampleCount, 0f)

        val all = snapshot
        // No active sessions: the bus is already silence from the zero-fill above, so skip both
        // the mix and the per-sample gain/limiter loop. This runs on the render thread hundreds
        // of times a second (and around the clock — the track never stops), so avoiding a full
        // pass over known-zero output every callback is worth the early return.
        if (all.isEmpty()) return
        for (session in all) {
            session.readAdd(output, frames)
        }

        if (isMuted) {
            java.util.Arrays.fill(output, 0, sampleCount, 0f)
            return
        }

        val gain = volume * boost.gain
        for (i in 0 until sampleCount) {
            var sample = output[i] * gain
            val magnitude = abs(sample)
            if (magnitude > LIMITER_THRESHOLD) {
                val excess = (magnitude - LIMITER_THRESHOLD) / LIMITER_KNEE
                val limited = LIMITER_THRESHOLD + LIMITER_KNEE * tanh(excess)
                sample = if (sample < 0) -limited else limited
            }
            output[i] = sample
        }
    }

    private companion object {
        // Soft limiter: below the threshold samples pass untouched; above it a tanh knee
        // compresses the excess so summation peaks asymptote to ±1 instead of hard-clipping.
        const val LIMITER_THRESHOLD = 0.9f
        const val LIMITER_KNEE = 1.0f - LIMITER_THRESHOLD
    }
}
