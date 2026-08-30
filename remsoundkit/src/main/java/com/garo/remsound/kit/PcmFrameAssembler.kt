package com.garo.remsound.kit

/**
 * Assembles multi-part PCM transport frames back into a single contiguous payload.
 * Direct port of the Windows `PcmFrameAssembler`: parts must arrive in order; a missed part
 * drops the whole frame rather than waiting (at 10 ms cadence, waiting is worse than one
 * dropped frame).
 */
class PcmFrameAssembler {
    private var pendingFrameId = 0
    private var pendingPartIndex = 0 // index of the NEXT expected part
    private var pendingTotalParts = 0
    private var assemblyBuffer = ByteArray(8192)
    private var assemblyWritten = 0

    var rejectionCount = 0L
        private set
    var discardedPartialCount = 0L
        private set

    /**
     * Feed one part. Returns the completed frame's length when the last part lands (the bytes
     * live in [assembled], valid until the next call), or -1 while pending or when the frame
     * was dropped (drops are counted, not errors).
     */
    fun assemble(
        part: ByteArray,
        partOffset: Int,
        partLength: Int,
        frameId: Int,
        partIndex: Int,
        totalParts: Int,
    ): Int {
        if (totalParts == 0) {
            rejectionCount++
            return -1
        }

        if (partIndex == 0) {
            // New frame starts — if a partial was waiting, its audio is lost.
            if (pendingTotalParts != 0 && assemblyWritten > 0) {
                discardedPartialCount++
            }
            pendingFrameId = frameId
            pendingPartIndex = 0
            pendingTotalParts = totalParts
            assemblyWritten = 0
        } else if (frameId != pendingFrameId ||
            partIndex != pendingPartIndex ||
            totalParts != pendingTotalParts
        ) {
            // We missed the start, or this belongs to a different frame. Discard.
            assemblyWritten = 0
            pendingTotalParts = 0
            rejectionCount++
            return -1
        }

        if (assemblyWritten + partLength > assemblyBuffer.size) {
            assemblyWritten = 0
            pendingTotalParts = 0
            rejectionCount++
            return -1
        }

        System.arraycopy(part, partOffset, assemblyBuffer, assemblyWritten, partLength)
        assemblyWritten += partLength
        pendingPartIndex++

        if (pendingPartIndex == pendingTotalParts) {
            val length = assemblyWritten
            pendingTotalParts = 0
            assemblyWritten = 0
            return length
        }
        return -1
    }

    /** The reassembly buffer. Only the length returned by [assemble] is meaningful. */
    val assembled: ByteArray get() = assemblyBuffer
}

/**
 * Packed signed 24-bit little-endian PCM → float conversion (the PCM wire format).
 * Mirrors `RemSound.Core.PcmPack.Int24LEToFloat`.
 */
object PcmPack {
    fun int24LeToFloat(source: ByteArray, offset: Int, length: Int, destination: FloatArray): Int {
        val sampleCount = length / 3
        var j = offset
        for (i in 0 until sampleCount) {
            val packed = (source[j].toInt() and 0xFF) or
                ((source[j + 1].toInt() and 0xFF) shl 8) or
                ((source[j + 2].toInt() and 0xFF) shl 16)
            // Sign-extend from bit 23.
            val signed = (packed shl 8) shr 8
            destination[i] = signed / 8_388_607.0f
            j += 3
        }
        return sampleCount
    }
}
