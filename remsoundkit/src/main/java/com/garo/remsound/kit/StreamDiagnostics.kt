package com.garo.remsound.kit

/**
 * Which internal mode the sender's Opus encoder is running in, decoded from a packet's TOC
 * byte. This matters for loss resilience: **inband FEC (LBRR) is a SILK feature**, so a
 * CELT-only stream carries no redundancy no matter what the encoder's FEC flag was set to —
 * and RESTRICTED_LOWDELAY (what both this port and the Windows sender ask for) forces
 * CELT-only, as does any high bitrate. Reported so that question is answered by observation
 * instead of by reading encoder flags that may be inert.
 */
enum class OpusPacketMode {
    UNKNOWN,
    SILK,
    HYBRID,
    CELT;

    /** Plain sentence for the status panel — a screen reader reads this verbatim. */
    val displayDescription: String
        get() = when (this) {
            UNKNOWN -> "unknown"
            SILK -> "SILK, so inband error correction is possible"
            HYBRID -> "hybrid, so inband error correction is possible"
            CELT -> "CELT only, so inband error correction is unavailable"
        }

    companion object {
        /**
         * RFC 6716 §3.1: the TOC byte's top 5 bits are the configuration number.
         * 0–11 SILK-only, 12–15 hybrid, 16–31 CELT-only.
         */
        fun fromToc(toc: Byte): OpusPacketMode {
            val config = (toc.toInt() and 0xFF) ushr 3
            return when {
                config < 12 -> SILK
                config < 16 -> HYBRID
                else -> CELT
            }
        }
    }
}

/** Cumulative packet-level counters, snapshotted for the UI. */
data class StreamDiagnosticsSnapshot(
    val audioPacketsReceived: Long = 0,
    /**
     * Sum of forward sequence gaps — packets the sender emitted that never arrived (or
     * arrived so late they were counted lost first, and then again as [packetsLate]).
     */
    val packetsLost: Long = 0,
    /** Arrived with a sequence older than one already seen: network reordering. */
    val packetsLate: Long = 0,
    /** Same sequence seen twice. */
    val packetsDuplicate: Long = 0,
    /** Forward jump too large to be plausible loss — sender restart, not a gap. */
    val resyncs: Long = 0,
    /** Payload that failed AES-GCM open: wrong password, or a corrupted datagram. */
    val decryptFailures: Long = 0,
    /**
     * Inter-arrival gaps binned by size. These size the jitter buffer directly: a buffer
     * smaller than the observed gap cannot survive it.
     */
    val gapsOver30ms: Long = 0,
    val gapsOver60ms: Long = 0,
    val gapsOver100ms: Long = 0,
    val opusMode: OpusPacketMode = OpusPacketMode.UNKNOWN,
)

/**
 * Engine-wide packet-level telemetry, written from the network receive thread by every
 * [StreamSession] and read from the UI thread by the status panel.
 *
 * Deliberately aggregate rather than per-session: sessions are created, superseded and pruned
 * constantly (streamId rotation, idle timeout), so per-session counters would need the same
 * retire-into-a-total bookkeeping [PlayoutMixer] does for its glitch counts. One long-lived
 * object that outlives every session avoids that entirely.
 *
 * This is measurement only — nothing here feeds back into decode or playout behaviour.
 */
class StreamDiagnostics {
    private val lock = Any()
    private var stats = StreamDiagnosticsSnapshot()

    /**
     * Largest inter-arrival gap since the last [drainPeakGapMs]. A peak, not a running total,
     * so the reader takes it and resets rather than diffing.
     */
    private var peakGapMs = 0

    fun snapshot(): StreamDiagnosticsSnapshot = synchronized(lock) { stats }

    /**
     * Read and reset the peak inter-arrival gap. The status panel calls this once per refresh
     * tick and keeps its own sliding window of the results.
     */
    fun drainPeakGapMs(): Int = synchronized(lock) {
        val peak = peakGapMs
        peakGapMs = 0
        peak
    }

    fun reset() = synchronized(lock) {
        stats = StreamDiagnosticsSnapshot()
        peakGapMs = 0
    }

    // ---- Network thread ----

    internal fun recordArrival(gapMs: Int?) = synchronized(lock) {
        var next = stats.copy(audioPacketsReceived = stats.audioPacketsReceived + 1)
        if (gapMs != null) {
            if (gapMs > peakGapMs) peakGapMs = gapMs
            next = when {
                gapMs > 100 -> next.copy(
                    gapsOver100ms = next.gapsOver100ms + 1,
                    gapsOver60ms = next.gapsOver60ms + 1,
                    gapsOver30ms = next.gapsOver30ms + 1,
                )
                gapMs > 60 -> next.copy(
                    gapsOver60ms = next.gapsOver60ms + 1,
                    gapsOver30ms = next.gapsOver30ms + 1,
                )
                gapMs > 30 -> next.copy(gapsOver30ms = next.gapsOver30ms + 1)
                else -> next
            }
        }
        stats = next
    }

    internal fun recordLost(count: Int) = synchronized(lock) {
        stats = stats.copy(packetsLost = stats.packetsLost + count)
    }

    internal fun recordLate() = synchronized(lock) {
        stats = stats.copy(packetsLate = stats.packetsLate + 1)
    }

    internal fun recordDuplicate() = synchronized(lock) {
        stats = stats.copy(packetsDuplicate = stats.packetsDuplicate + 1)
    }

    internal fun recordResync() = synchronized(lock) {
        stats = stats.copy(resyncs = stats.resyncs + 1)
    }

    internal fun recordDecryptFailure() = synchronized(lock) {
        stats = stats.copy(decryptFailures = stats.decryptFailures + 1)
    }

    internal fun recordOpusToc(toc: Byte) {
        val mode = OpusPacketMode.fromToc(toc)
        synchronized(lock) { stats = stats.copy(opusMode = mode) }
    }
}

/**
 * Per-session arrival bookkeeping: classifies each packet's sequence against the previous one
 * and measures the wall gap between arrivals. Network-thread only (the receive socket runs one
 * blocking-receive thread), so no locking here — only the shared [StreamDiagnostics] sink is
 * synchronised.
 *
 * Unlike the Apple port there is no kernel delivery timestamp available through the JDK's
 * datagram API, so the gap is timed on the receive thread. Under Android's background
 * throttling that measures our own scheduling as much as the network — the diagnostics panel
 * says "thread-timed" for exactly this reason.
 */
internal class ArrivalTracker {
    private var lastSequence: Int? = null
    private var lastLocalNs = 0L

    fun record(sequence: Int, diagnostics: StreamDiagnostics) {
        val nowNs = System.nanoTime()
        val gapMs = if (lastLocalNs == 0L) null else ((nowNs - lastLocalNs) / 1_000_000).toInt()
        lastLocalNs = nowNs
        diagnostics.recordArrival(gapMs)

        val last = lastSequence
        if (last == null) {
            lastSequence = sequence
            return
        }

        // Unsigned wrap is intentional — sequence is a uint32 counter that rolls over.
        val delta = (sequence - last).toLong() and 0xFFFF_FFFFL
        when {
            delta == 0L -> diagnostics.recordDuplicate()
            delta == 1L -> lastSequence = sequence // in order
            delta <= MAX_PLAUSIBLE_GAP -> {
                diagnostics.recordLost((delta - 1).toInt())
                lastSequence = sequence
            }
            delta > 0xFFFF_FFFFL / 2 -> {
                // Sequence went backwards: a reordered packet overtaken by a newer one. Keep
                // `lastSequence` at the newer value so the next in-order packet is not then
                // counted as a huge gap.
                diagnostics.recordLate()
            }
            else -> {
                diagnostics.recordResync()
                lastSequence = sequence
            }
        }
    }

    private companion object {
        /**
         * Forward jumps beyond this are treated as a sender restart, not as lost packets —
         * without it, a streamId reuse or counter reset would report millions of "lost".
         */
        const val MAX_PLAUSIBLE_GAP = 500L
    }
}
