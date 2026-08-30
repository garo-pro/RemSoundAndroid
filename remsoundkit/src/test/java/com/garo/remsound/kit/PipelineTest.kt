package com.garo.remsound.kit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** PCM reassembly, int24 unpacking, and the auto-tune's pure decision function. */
class PipelineTest {

    @Test
    fun assemblerReturnsTheFrameOnlyWhenTheLastPartLands() {
        val assembler = PcmFrameAssembler()
        val part = ByteArray(10) { it.toByte() }

        assertEquals(-1, assembler.assemble(part, 0, 10, frameId = 7, partIndex = 0, totalParts = 3))
        assertEquals(-1, assembler.assemble(part, 0, 10, frameId = 7, partIndex = 1, totalParts = 3))
        assertEquals(30, assembler.assemble(part, 0, 10, frameId = 7, partIndex = 2, totalParts = 3))
        assertEquals(0L, assembler.rejectionCount)
    }

    @Test
    fun assemblerDropsAFrameWhenAPartIsMissed() {
        val assembler = PcmFrameAssembler()
        val part = ByteArray(10)
        assembler.assemble(part, 0, 10, frameId = 1, partIndex = 0, totalParts = 3)
        // Part 1 never arrives; part 2 must be rejected rather than silently concatenated.
        assertEquals(-1, assembler.assemble(part, 0, 10, frameId = 1, partIndex = 2, totalParts = 3))
        assertEquals(1L, assembler.rejectionCount)
    }

    @Test
    fun assemblerCountsADiscardedPartialWhenANewFrameStarts() {
        val assembler = PcmFrameAssembler()
        val part = ByteArray(10)
        assembler.assemble(part, 0, 10, frameId = 1, partIndex = 0, totalParts = 2)
        assembler.assemble(part, 0, 10, frameId = 2, partIndex = 0, totalParts = 2)
        assertEquals(1L, assembler.discardedPartialCount)
    }

    @Test
    fun int24LittleEndianUnpacksWithSignExtension() {
        // 0x000000 = 0, 0x7FFFFF = +full scale, 0x800000 = -full scale.
        val source = byteArrayOf(
            0x00, 0x00, 0x00,
            0xFF.toByte(), 0xFF.toByte(), 0x7F,
            0x00, 0x00, 0x80.toByte(),
        )
        val out = FloatArray(3)
        assertEquals(3, PcmPack.int24LeToFloat(source, 0, source.size, out))
        assertEquals(0f, out[0], 0f)
        assertEquals(1f, out[1], 0.0001f)
        assertEquals(-1f, out[2], 0.0001f)
    }

    // ---- Latency auto-tune ----

    private fun input(
        samples: List<LatencyAutoTune.Sample>,
        current: Int = 80,
        frameMs: Int = 10,
        underruns: Long = 0,
        deferring: Boolean = false,
    ) = LatencyAutoTune.Input(
        samples = samples,
        frameMs = frameMs,
        currentTargetMs = current,
        minTargetMs = ReceiverSettings.MIN_TARGET_LATENCY_MS,
        maxTargetMs = ReceiverSettings.MAX_TARGET_LATENCY_MS,
        tuneBlockingUnderrunDelta = underruns,
        deferring = deferring,
    )

    @Test
    fun autoTuneHoldsWithoutEnoughHistory() {
        val decision = LatencyAutoTune.decide(input(listOf(LatencyAutoTune.Sample(10, 5))))
        assertEquals(LatencyAutoTune.Decision.Hold(LatencyAutoTune.HoldReason.NotEnoughHistory), decision)
    }

    @Test
    fun autoTuneHoldsWhileDeferringAndAfterUnderruns() {
        val samples = List(10) { LatencyAutoTune.Sample(10, 5) }
        assertEquals(
            LatencyAutoTune.Decision.Hold(LatencyAutoTune.HoldReason.DeferringToRecentChange),
            LatencyAutoTune.decide(input(samples, deferring = true)),
        )
        assertEquals(
            LatencyAutoTune.Decision.Hold(LatencyAutoTune.HoldReason.UnderrunsSinceLastTick(3)),
            LatencyAutoTune.decide(input(samples, underruns = 3)),
        )
    }

    /**
     * A single transient must not drive the recommendation: the tuner takes the SECOND highest
     * gap in the window. One 1000 ms second among quiet ones is the exact case that drove
     * upstream's buffer to its cap for traffic that was fine.
     */
    @Test
    fun autoTuneIgnoresALoneTransient() {
        val samples = List(14) { LatencyAutoTune.Sample(10, 5) } + LatencyAutoTune.Sample(1000, 5)
        val decision = LatencyAutoTune.decide(input(samples, current = 80))
        // second-highest gap 10 + render 5 + margin 5 = 20, so it walks DOWN, 5 ms per tick.
        assertEquals(LatencyAutoTune.Decision.Retarget(75), decision)
    }

    @Test
    fun autoTuneRaisesInOneStepButLowersGradually() {
        val jittery = List(15) { LatencyAutoTune.Sample(120, 8) }
        // Raise: 120 + 8 + 5 = 133, applied in full.
        assertEquals(LatencyAutoTune.Decision.Retarget(133), LatencyAutoTune.decide(input(jittery, current = 40)))

        val calm = List(15) { LatencyAutoTune.Sample(8, 4) }
        // Lower: recommendation is far below, but only 5 ms comes off per tick.
        assertEquals(LatencyAutoTune.Decision.Retarget(195), LatencyAutoTune.decide(input(calm, current = 200)))
    }

    @Test
    fun autoTuneNeverRecommendsBelowTheCodecFloorOrAboveItsCap() {
        val calm = List(15) { LatencyAutoTune.Sample(0, 2) }
        // A 60 ms frame floors the target at ceil(1.5 * 60) = 90.
        var current = 200
        repeat(40) {
            val decision = LatencyAutoTune.decide(input(calm, current = current, frameMs = 60))
            if (decision is LatencyAutoTune.Decision.Retarget) current = decision.ms
        }
        assertEquals(90, current)

        val awful = List(15) { LatencyAutoTune.Sample(900, 50) }
        val capped = LatencyAutoTune.decide(input(awful, current = 80))
        assertEquals(LatencyAutoTune.Decision.Retarget(LatencyAutoTune.RECOMMENDATION_CAP_MS), capped)
    }

    @Test
    fun autoTuneHoldsInsideTheHysteresisBand() {
        // second-highest 10 + render 5 + margin 5 = 20; from 22 that is a 2 ms move.
        val samples = List(15) { LatencyAutoTune.Sample(10, 5) }
        val decision = LatencyAutoTune.decide(input(samples, current = 22))
        assertTrue(decision is LatencyAutoTune.Decision.Hold)
        assertEquals(
            LatencyAutoTune.HoldReason.WithinHysteresis(20),
            (decision as LatencyAutoTune.Decision.Hold).reason,
        )
    }

    @Test
    fun latencyIsClampedToTheControlRange() {
        assertEquals(ReceiverSettings.MIN_TARGET_LATENCY_MS, ReceiverSettings.clampLatency(0))
        assertEquals(ReceiverSettings.MAX_TARGET_LATENCY_MS, ReceiverSettings.clampLatency(9999))
        assertEquals(80, ReceiverSettings.clampLatency(80))
        assertNotEquals(0, ReceiverSettings.DEFAULT_TARGET_LATENCY_MS)
    }
}
