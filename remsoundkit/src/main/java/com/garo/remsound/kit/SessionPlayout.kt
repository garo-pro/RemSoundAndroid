package com.garo.remsound.kit

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * One incoming stream's playout state: a ring buffer of 48 kHz interleaved stereo floats plus
 * arming / concealment / trim state. Producer is the network thread ([write]), consumer is the
 * audio render thread ([readAdd]).
 *
 * A deliberately simplified port of the Windows `SessionPlayout`:
 *  * Same arming model — playback starts only once the buffer holds the target latency, and
 *    re-arms after a sustained underrun, so startup and recovery are click-free.
 *  * Same underrun concealment principle — the edges of a gap are faded over ~0.7 ms instead
 *    of slamming to zero (the click lives in the edge, not the silence).
 *  * Same click-trim — if the buffer creeps past target + margin (burst arrival, clock drift),
 *    oldest audio is dropped back to just above target with a fade at the seam.
 *  * NOT ported: the fixed-ratio drift resampler. At the latencies this receiver targets, the
 *    trim + re-arm path bounds drift instead.
 */
internal class SessionPlayout(
    val endpoint: UdpEndpoint,
    val streamId: Int,
    targetLatencyMs: Int,
    capacitySeconds: Double = 2.0,
) {
    private val lock = Any()
    private val capacityFrames: Int = (capacitySeconds * MIX_SAMPLE_RATE).toInt()
    private val ring = FloatArray(capacityFrames * MIX_CHANNELS)
    private var head = 0 // read position (frames)
    private var tail = 0 // write position (frames)
    private var count = 0 // buffered frames

    private var armed = false
    private var targetFrames = max(1, targetLatencyMs * MIX_SAMPLE_RATE / 1000)
    private var consecutiveEmptyReads = 0
    private var fadeInPending = true
    private var lastSampleL = 0f
    private var lastSampleR = 0f

    /**
     * Largest single write in frames — the codec's packet size as observed at the buffer.
     * Floors the trim margin so the natural packet-arrival sawtooth never false-trims.
     */
    private var largestWriteFrames = 0

    private var underruns = 0L
    private var trimFireCount = 0L
    private var droppedFrames = 0L

    // Cause-split of short reads, mirroring the Windows receiver's fix. The plain `underruns`
    // total cannot tell apart two events that both come up short:
    //   * PRODUCER starvation — the ring is draining below target because decoded audio is not
    //     arriving fast enough. More buffer genuinely helps: the auto-tune must not lower.
    //   * DEVICE gulp — the render callback came late or asked for an oversized block, so an
    //     otherwise on-target ring momentarily cannot fill it. More buffer never cures that
    //     pattern, it only adds permanent latency.
    // Upstream records that gating the tuner on the undifferentiated total pinned the target
    // high forever, because a steady trickle of inaudible gulps made every tick skip.
    private var tuneBlockingUnderruns = 0L
    private var deviceGulpUnderruns = 0L

    /**
     * Frames of audio that could not be produced — the *duration* of the damage, where the
     * counters above are only its frequency. These diverge sharply: raising the buffer on a
     * jittery link left the underrun count unchanged while making every dropout much shorter,
     * so a count alone reports "no improvement" for a change the listener hears clearly.
     */
    private var concealedFrames = 0L

    /**
     * Low-pass-filtered buffer-level error in frames (negative = running below target). Same
     * 2 s time constant as upstream's `filteredErrorFrames`.
     */
    private var filteredErrorFrames = 0.0
    private var lastErrorSampleNs = 0L

    /** Wall-clock time of the last write — drives idle-session pruning. */
    var lastWriteTime: Long = System.currentTimeMillis()
        private set

    val bufferedMs: Int
        get() = synchronized(lock) { count * 1000 / MIX_SAMPLE_RATE }

    /**
     * Snapshot of the glitch counters under the buffer lock (1 Hz status UI). The cause-split
     * pair rides along so the auto-tune reads one consistent set.
     */
    data class GlitchCounters(
        val underruns: Long,
        val trims: Long,
        val tuneBlocking: Long,
        val deviceGulp: Long,
        val concealedFrames: Long,
    )

    val glitchCounters: GlitchCounters
        get() = synchronized(lock) {
            GlitchCounters(underruns, trimFireCount, tuneBlockingUnderruns, deviceGulpUnderruns, concealedFrames)
        }

    fun setTargetLatencyMs(ms: Int, drainOnLower: Boolean = true) = synchronized(lock) {
        val newTarget = max(1, ms * MIX_SAMPLE_RATE / 1000)
        if (drainOnLower && newTarget < targetFrames && count > newTarget) {
            dropOldestLocked(count - newTarget)
            fadeInPending = true
        }
        targetFrames = newTarget
    }

    /** Producer side: append interleaved stereo floats (network thread). */
    fun write(samples: FloatArray, frames: Int) = synchronized(lock) {
        lastWriteTime = System.currentTimeMillis()
        largestWriteFrames = max(largestWriteFrames, frames)

        var toWrite = frames
        if (count + toWrite > capacityFrames) {
            // Overflow — drop oldest so fresh audio wins (matches the ring's DropOldest).
            dropOldestLocked(count + toWrite - capacityFrames)
            fadeInPending = true
        }
        if (toWrite > capacityFrames) toWrite = capacityFrames

        var src = 0
        var remaining = toWrite
        while (remaining > 0) {
            val chunk = min(remaining, capacityFrames - tail)
            System.arraycopy(samples, src, ring, tail * MIX_CHANNELS, chunk * MIX_CHANNELS)
            src += chunk * MIX_CHANNELS
            tail = (tail + chunk) % capacityFrames
            remaining -= chunk
        }
        count += toWrite

        // Click-trim: the buffer crept past target + margin (burst arrival or a sender clock
        // running fast). Margin and drop-to point mirror the Windows defaults (smoothness knob
        // = 3): the margin clears the packet-arrival sawtooth with a wide jitter pad, and the
        // trim keeps a cushion ABOVE target rather than cutting to bare target. VPN/WAN
        // delivery is stall-then-burst — trimming the late backlog all the way to target
        // discards exactly the audio that would have covered the next stall; the Windows
        // source records that failure as "20 underruns/sec for the rest of the session".
        val msFrames = MIX_SAMPLE_RATE / 1000
        val margin = max(largestWriteFrames * 4 + 4 * msFrames, 15 * msFrames) + 8 * msFrames
        if (armed && count > targetFrames + margin) {
            val keepFrames = targetFrames + largestWriteFrames * 2 + 5 * msFrames
            if (count > keepFrames) {
                dropOldestLocked(count - keepFrames)
                trimFireCount++
                fadeInPending = true
            }
        }
    }

    /**
     * Track where the buffer is sitting relative to target, low-pass filtered. Sampled on the
     * render thread at the callback rate; `dt` is measured rather than assumed so the time
     * constant holds whatever the IO buffer size is.
     */
    private fun updateErrorFilterLocked() {
        val nowNs = System.nanoTime()
        val previous = lastErrorSampleNs
        lastErrorSampleNs = nowNs
        if (previous == 0L) return
        val dtSec = (nowNs - previous) / 1_000_000_000.0
        val errorFrames = (count - targetFrames).toDouble()
        val alpha = dtSec / (ERROR_FILTER_TIME_CONSTANT_SEC + dtSec)
        filteredErrorFrames = (1 - alpha) * filteredErrorFrames + alpha * errorFrames
    }

    /**
     * Split a short read into "the producer is behind" (more buffer helps) and "the device
     * gulped" (more buffer is pure added latency). See the counter declarations.
     */
    private fun classifyShortReadLocked(empty: Boolean) {
        val starveFrames = (TUNE_STARVE_MS * MIX_SAMPLE_RATE / 1000).toDouble()
        if (empty || filteredErrorFrames <= -starveFrames) {
            tuneBlockingUnderruns++
        } else {
            deviceGulpUnderruns++
        }
    }

    private fun dropOldestLocked(frames: Int) {
        val n = min(frames, count)
        head = (head + n) % capacityFrames
        count -= n
        droppedFrames += n
    }

    // Per-session render scratch. Fades must shape only THIS session's contribution, so the
    // fade happens here before summing into the shared mix buffer. Render-thread only.
    private var renderScratch = FloatArray(4096 * MIX_CHANNELS)

    /**
     * Consumer side: mix-add up to [frames] stereo frames into [output] (render thread).
     * [output] must hold `frames * 2` floats; existing content is summed into, not replaced.
     */
    fun readAdd(output: FloatArray, frames: Int) = synchronized(lock) {
        if (renderScratch.size < frames * MIX_CHANNELS) {
            renderScratch = FloatArray(frames * MIX_CHANNELS)
        }
        java.util.Arrays.fill(renderScratch, 0, frames * MIX_CHANNELS, 0f)

        updateErrorFilterLocked()

        if (!armed) {
            if (count >= targetFrames) {
                armed = true
                consecutiveEmptyReads = 0
                fadeInPending = true
            } else {
                return@synchronized // silence until armed
            }
        }

        val available = min(count, frames)
        if (available < frames) {
            classifyShortReadLocked(available == 0)
            concealedFrames += (frames - available)
        }
        if (available == 0) {
            // Full underrun. Fade the tail edge of the previous audio into the silence so the
            // gap edge is smooth, then count and (eventually) disarm.
            underruns++
            consecutiveEmptyReads++
            if (consecutiveEmptyReads <= MAX_CONSECUTIVE_EMPTIES) {
                applyFadeOutEdge(renderScratch, 0, frames)
                addScratch(output, frames)
            }
            if (consecutiveEmptyReads >= MAX_CONSECUTIVE_EMPTIES) {
                armed = false // re-arm at target; concealment must not run forever
            }
            return@synchronized
        }

        if (consecutiveEmptyReads > 0) {
            fadeInPending = true
            consecutiveEmptyReads = 0
        }

        var produced = 0
        var remaining = available
        while (remaining > 0) {
            val chunk = min(remaining, capacityFrames - head)
            System.arraycopy(
                ring,
                head * MIX_CHANNELS,
                renderScratch,
                produced * MIX_CHANNELS,
                chunk * MIX_CHANNELS,
            )
            head = (head + chunk) % capacityFrames
            produced += chunk
            remaining -= chunk
        }
        count -= available

        if (fadeInPending) {
            applyFadeIn(renderScratch, min(CONCEAL_FADE_FRAMES, available))
            fadeInPending = false
        }

        val lastFrame = (available - 1) * MIX_CHANNELS
        lastSampleL = renderScratch[lastFrame]
        lastSampleR = renderScratch[lastFrame + 1]

        if (available < frames) {
            // Partial read — smooth the boundary into the trailing silence, and fade the
            // resume edge back in. Without the fade-in the next callback restarts at full
            // amplitude against the faded-to-zero gap, an audible tick on every jitter hiccup.
            underruns++
            applyFadeOutEdge(renderScratch, available * MIX_CHANNELS, frames - available)
            fadeInPending = true
        }
        addScratch(output, frames)
    }

    private fun addScratch(output: FloatArray, frames: Int) {
        for (i in 0 until frames * MIX_CHANNELS) {
            output[i] += renderScratch[i]
        }
    }

    /** Short cosine ramp from the last real samples down to zero at the start of a gap. */
    private fun applyFadeOutEdge(output: FloatArray, offset: Int, frames: Int) {
        val fade = min(CONCEAL_FADE_FRAMES, frames)
        if (fade <= 0 || (lastSampleL == 0f && lastSampleR == 0f)) return
        for (i in 0 until fade) {
            val g = (0.5 * (1 + cos(Math.PI * (i + 1) / fade))).toFloat()
            output[offset + i * MIX_CHANNELS] += lastSampleL * g
            output[offset + i * MIX_CHANNELS + 1] += lastSampleR * g
        }
        lastSampleL = 0f
        lastSampleR = 0f
    }

    /** Matching ramp up when audio resumes after an arm / gap / trim seam. */
    private fun applyFadeIn(output: FloatArray, frames: Int) {
        if (frames <= 0) return
        for (i in 0 until frames) {
            val g = (0.5 * (1 - cos(Math.PI * (i + 1) / frames))).toFloat()
            output[i * MIX_CHANNELS] *= g
            output[i * MIX_CHANNELS + 1] *= g
        }
    }

    companion object {
        const val MIX_SAMPLE_RATE = 48000
        const val MIX_CHANNELS = 2

        private const val CONCEAL_FADE_FRAMES = 32 // ~0.67 ms at 48 kHz
        private const val MAX_CONSECUTIVE_EMPTIES = 8
        private const val ERROR_FILTER_TIME_CONSTANT_SEC = 2.0

        /**
         * Deficit past which a short read counts as producer starvation rather than a gulp —
         * beyond the on-target jitter, far short of a real stall.
         */
        private const val TUNE_STARVE_MS = 3
    }
}
