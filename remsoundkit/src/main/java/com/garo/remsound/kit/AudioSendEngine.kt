package com.garo.remsound.kit

import kotlin.random.Random

/**
 * Outbound audio stream — the equivalent of one Windows `SenderLane`, Opus-only (no PCM path,
 * no multi-lane modes: one mixed-lane 48 kHz stereo stream).
 *
 * Hot path: [submit] is called from the capture thread with 48 kHz interleaved stereo float;
 * samples accumulate into fixed 10 ms Opus frames, each frame is encoded, encrypted
 * (`nonce || tag || ciphertext`, mandatory — no password means nothing is sent), and emitted to
 * every target endpoint through [transport]. A Format packet is re-announced every 250 ms on
 * the same stream, matching the Windows sender's cadence, so receivers can open the session at
 * any time.
 *
 * Configuration ([setKeyMaterial], [setTargets], [start], [stop]) comes from the UI thread; a
 * single lock serialises it against the capture thread. At ~100 frames/sec the lock is
 * uncontended noise.
 */
class AudioSendEngine {
    private val lock = Any()

    // Pushed by the app; read on the capture thread.
    private var audioKey: ByteArray? = null
    private var audioFingerprint: ByteArray? = null
    private var targets: List<UdpEndpoint> = emptyList()
    private var running = false

    // Capture-thread state, all touched under the lock.
    private val encryptor = AudioEncryptor()
    private var encoder: OpusStreamEncoder? = null
    private val accumulator = FloatArray(OPUS_FRAME_SAMPLES_PER_CHANNEL * CHANNELS)
    private var accumulatorWritten = 0
    private var audioSequence = 0
    private var formatSequence = 0
    private var streamId = 1
    private var lastFormatSentAt = 0L

    /**
     * Sends one datagram to one endpoint. Wired by the app to the receiver engine's audio
     * socket so outbound audio shares the NAT pinhole heartbeats and inbound audio use.
     */
    var transport: ((data: ByteArray, endpoint: UdpEndpoint) -> Boolean)? = null

    val isRunning: Boolean get() = synchronized(lock) { running }

    /** True when packets are actually leaving: running, key set, and at least one target. */
    val isSending: Boolean
        get() = synchronized(lock) { running && audioKey != null && targets.isNotEmpty() }

    fun setKeyMaterial(key: ByteArray?, fingerprint: ByteArray?) = synchronized(lock) {
        audioKey = key
        audioFingerprint = fingerprint
    }

    /**
     * Replace the destination endpoints — one per peer (sending the same stream to two
     * addresses of one machine would open two doubled-up sessions on its receiver).
     */
    fun setTargets(endpoints: List<UdpEndpoint>) = synchronized(lock) {
        targets = endpoints
    }

    /**
     * Begin a fresh outbound stream: new random streamId (receivers key sessions on it),
     * counters reset, immediate format announce on the next submitted buffer.
     */
    fun start() = synchronized(lock) {
        if (running) return@synchronized
        val newEncoder = OpusStreamEncoder(OPUS_FRAME_SAMPLES_PER_CHANNEL)
        encoder = newEncoder.takeIf { it.isValid }
        streamId = Random.nextInt(1, 0xFFFF)
        audioSequence = 0
        formatSequence = 0
        accumulatorWritten = 0
        lastFormatSentAt = 0
        running = encoder != null
    }

    fun stop() = synchronized(lock) {
        running = false
        encoder = null
        accumulatorWritten = 0
    }

    // ---- Hot path (capture thread) ----

    /** Feed 48 kHz interleaved stereo float. [frameCount] is sample frames (L+R pairs). */
    fun submit(samples: FloatArray, frameCount: Int) {
        if (frameCount <= 0) return
        synchronized(lock) {
            val activeEncoder = encoder ?: return
            if (!running || audioKey == null || targets.isEmpty()) return
            encryptor.ensureKey(audioKey)
            if (!encryptor.hasKey) return

            sendFormatIfDue()

            val frameFloats = activeEncoder.frameSizePerChannel * CHANNELS
            val totalFloats = frameCount * CHANNELS
            var index = 0
            while (index < totalFloats) {
                val copy = minOf(frameFloats - accumulatorWritten, totalFloats - index)
                System.arraycopy(samples, index, accumulator, accumulatorWritten, copy)
                accumulatorWritten += copy
                index += copy
                if (accumulatorWritten == frameFloats) {
                    emitFrame(activeEncoder)
                    accumulatorWritten = 0
                }
            }
        }
    }

    private fun emitFrame(activeEncoder: OpusStreamEncoder) {
        val encodedLength = activeEncoder.encode(accumulator)
        if (encodedLength <= 0) return
        val ciphertext = encryptor.tryEncrypt(activeEncoder.encoded, 0, encodedLength) ?: return
        audioSequence++
        val packet = RemPacket.writeHeader(RemPacketType.AUDIO, streamId, audioSequence) + ciphertext
        sendToAll(packet)
    }

    private fun sendFormatIfDue() {
        val now = System.currentTimeMillis()
        if (now - lastFormatSentAt < FORMAT_RESEND_INTERVAL_MS) return
        lastFormatSentAt = now

        // The same field values the Windows sender announces for Opus (bits/blockAlign describe
        // the pre-encode PCM; receivers key the session off codec + rate + frame size).
        val format = AudioFormatInfo(
            sampleRate = OpusStreamEncoder.SAMPLE_RATE,
            channels = CHANNELS,
            bitsPerSample = 16,
            encoding = 1,
            blockAlign = 4,
            averageBytesPerSecond = OpusStreamEncoder.BITRATE,
            codec = AudioTransportCodec.OPUS,
            frameSamplesPerChannel = OPUS_FRAME_SAMPLES_PER_CHANNEL,
            lane = RenderRoute.MIXED,
        )
        formatSequence++
        val packet = RemPacket.writeHeader(RemPacketType.FORMAT, streamId, formatSequence) +
            RemPacket.writeFormatPayload(format, audioFingerprint)
        sendToAll(packet)
    }

    private fun sendToAll(packet: ByteArray) {
        val send = transport ?: return
        for (target in targets) {
            send(packet, target)
        }
    }

    companion object {
        /** 480 samples = 10 ms at 48 kHz — the Windows sender's default Opus frame. */
        const val OPUS_FRAME_SAMPLES_PER_CHANNEL = 480
        const val CHANNELS = 2
        private const val FORMAT_RESEND_INTERVAL_MS = 250L
    }
}
