package com.garo.remsound.kit

import kotlin.math.abs
import kotlin.math.ceil

/**
 * Continuous latency auto-tune — a port of the Windows receiver's "Continuous auto-tune
 * latency" (`MainForm.TickRoute`), which walks the playout target to whatever the link
 * currently needs instead of leaving it pinned wherever the user last set it.
 *
 * Why it exists: the delay control is labelled "Maximum delay" but has always behaved as a
 * fixed arm target. On a jittery path that is the worst of both worlds — the buffer re-arms at
 * the same value that just failed, so it sits permanently at the edge, while the trim margin
 * lets the real occupancy float far above the number on the slider anyway.
 *
 * Deliberately NOT ported: the per-route (WASAPI/ASIO lane) split, which needs Windows' dual
 * render backends. This port has a single mixed output, so there is one tuner.
 *
 * [decide] is pure: it takes a snapshot of the observed state and returns a decision, which
 * keeps the algorithm testable without an audio device or a network — CI has neither.
 */
object LatencyAutoTune {
    // Constants mirrored verbatim from upstream. Changing any of them makes this port behave
    // differently from the Windows receiver on the same link, so treat them as a contract.

    /** Floor for the measured render-callback period, so a first sample cannot read as 0 ms. */
    const val RENDER_PERIOD_FLOOR_MS = 2

    /** Headroom added on top of the observed jitter. */
    const val SAFETY_MARGIN_MS = 5

    /** Do not move the target for less than this — stops the value twitching every tick. */
    const val HYSTERESIS_MS = 5

    /** Ceiling on what the tuner may recommend on its own. */
    const val RECOMMENDATION_CAP_MS = 200

    /**
     * Raises are applied in full and immediately; lowering is rate-limited to this per tick, so
     * recovering latency after a bad patch is gradual and inaudible.
     */
    const val MAX_DECREASE_PER_TICK_MS = 5

    /** How many of the most recent per-second samples the recommendation looks at. */
    const val LOOKBACK_SECONDS = 15

    /** How much history is retained (upstream keeps a minute and looks back over 15 s of it). */
    const val HISTORY_SECONDS = 60

    /** Default seconds between ticks. Upstream's default and the value in use on Windows. */
    const val DEFAULT_INTERVAL_SEC = 5

    /**
     * One per-second observation. [arrivalGapMs] is the worst packet inter-arrival gap seen in
     * that second; [renderGapMs] the worst render-callback period.
     */
    data class Sample(val arrivalGapMs: Int, val renderGapMs: Int)

    sealed interface HoldReason {
        data object NotEnoughHistory : HoldReason
        data class UnderrunsSinceLastTick(val count: Long) : HoldReason
        data object DeferringToRecentChange : HoldReason
        data class WithinHysteresis(val recommended: Int) : HoldReason
    }

    sealed interface Decision {
        /** Nothing to do, with the reason — surfaced in diagnostics, and what the tests assert. */
        data class Hold(val reason: HoldReason) : Decision

        /** Move the target to this many ms. */
        data class Retarget(val ms: Int) : Decision
    }

    /** Everything the decision depends on, gathered by the caller. */
    data class Input(
        val samples: List<Sample>,
        /** Frame duration of the active stream, rounded up. The codec floor derives from it. */
        val frameMs: Int,
        val currentTargetMs: Int,
        val minTargetMs: Int,
        val maxTargetMs: Int,
        /**
         * New tune-blocking underruns since the previous tick. Device-gulp short reads must NOT
         * be counted here — upstream found that gating on the undifferentiated total pinned the
         * target high forever, because inaudible gulps made every tick skip.
         */
        val tuneBlockingUnderrunDelta: Long,
        /** True while a user change or a fresh session is still inside its deferral window. */
        val deferring: Boolean,
    )

    fun decide(input: Input): Decision {
        if (input.samples.size < 2) return Decision.Hold(HoldReason.NotEnoughHistory)
        if (input.deferring) return Decision.Hold(HoldReason.DeferringToRecentChange)
        if (input.tuneBlockingUnderrunDelta > 0) {
            return Decision.Hold(HoldReason.UnderrunsSinceLastTick(input.tuneBlockingUnderrunDelta))
        }

        val window = input.samples.takeLast(LOOKBACK_SECONDS)
        // Second-highest, not the peak. A single transient — one bad second from an OS or driver
        // hiccup that never recurs — used to drive the whole recommendation upstream (a lone
        // 1046 ms gap drove the buffer to the 200 ms cap and shed a burst of trims). Requiring
        // the jitter to show up in two separate seconds filters that out while still honouring
        // sustained jitter at full speed.
        val observedGap = secondHighest(window.map { it.arrivalGapMs }, floor = 0)
        val observedRender = secondHighest(window.map { it.renderGapMs }, floor = RENDER_PERIOD_FLOOR_MS)

        // The codec floor: a target below one packet cannot work, since the buffer is fed a
        // whole frame at a time. 1.5x leaves room for the arrival sawtooth.
        val codecFloor = ceil(1.5 * input.frameMs).toInt()
        val jitterBased = observedGap + observedRender + SAFETY_MARGIN_MS
        val recommended = maxOf(codecFloor, jitterBased)
        val capped = minOf(recommended, RECOMMENDATION_CAP_MS)

        val current = input.currentTargetMs
        // Raise in one step — the buffer is already failing, waiting costs audio. Lower slowly.
        val target = if (capped > current) capped else maxOf(capped, current - MAX_DECREASE_PER_TICK_MS)
        val clamped = minOf(maxOf(target, input.minTargetMs), input.maxTargetMs)
        if (abs(clamped - current) < HYSTERESIS_MS) {
            return Decision.Hold(HoldReason.WithinHysteresis(capped))
        }
        return Decision.Retarget(clamped)
    }

    /**
     * Second-largest value, falling back to the largest when there is only one, and never below
     * [floor]. Matches upstream's inline peak/second tracking.
     */
    private fun secondHighest(values: List<Int>, floor: Int): Int {
        var peak = floor
        var second = floor
        for (value in values) {
            if (value > peak) {
                second = peak
                peak = value
            } else if (value > second) {
                second = value
            }
        }
        return if (values.size >= 2) second else peak
    }
}
