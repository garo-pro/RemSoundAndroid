package com.garo.remsound.kit

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.max

/**
 * The playback device: one [AudioTrack] in streaming float mode, fed from a dedicated render
 * thread that pulls mixed frames out of [PlayoutMixer].
 *
 * Design notes, and where they differ from the Apple port:
 *  * **The track never stops while the receiver is running.** Stopping and restarting it costs
 *    a device route negotiation each time and would drop the audio-focus claim that keeps the
 *    stream alive with the screen off; the mixer renders silence cheaply when nothing is
 *    playing (its no-session early return).
 *  * **Audio focus is the Android equivalent of the iOS session category.** Requesting
 *    `AUDIOFOCUS_GAIN` pauses other apps and makes RemSound the natural owner of the media
 *    buttons; with "Don't mix with other sounds" off the app never requests focus at all, so
 *    it plays alongside whatever else is running and the buttons go to that app instead.
 *  * **Buffer size follows demand.** With nothing flowing the render block is enlarged so the
 *    thread wakes far less often (battery); it drops back to the low-latency block the moment
 *    audio or capture appears.
 */
class AudioOutput(private val context: Context, private val mixer: PlayoutMixer) {
    private var track: AudioTrack? = null
    private var renderThread: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var lowLatencyDemand = false

    @Volatile
    private var exclusiveAudio = true

    private var focusRequest: Any? = null
    private var audioManager: AudioManager? = null

    /** Last thing the output reported (start, stop, focus change, device change). */
    var onDiagnostic: ((String) -> Unit)? = null

    /**
     * Fired when a stopped track was restarted — the moment the app becomes eligible to own
     * the media buttons again (see `ReceiverController.reclaimTransportControls`).
     */
    var onPlaybackRecovered: (() -> Unit)? = null

    /** Whether another app currently holds audio focus, i.e. we were interrupted. */
    @Volatile
    private var interrupted = false

    /** Output latency the device reports, in ms. Best effort — 0 when unavailable. */
    var reportedOutputLatencyMs: Double = 0.0
        private set

    fun start() {
        if (running) return
        running = true
        requestFocus()
        openTrack()
        val thread = Thread({ renderLoop() }, "RemSound.Render").apply {
            priority = Thread.MAX_PRIORITY
            isDaemon = true
        }
        renderThread = thread
        thread.start()
        onDiagnostic?.invoke("output started")
    }

    fun stop() {
        running = false
        renderThread = null
        closeTrack()
        abandonFocus()
        onDiagnostic?.invoke("output stopped")
    }

    /**
     * Mirrors the iOS "Don't mix with other sounds" switch. On means RemSound asks for
     * exclusive audio focus, which pauses other apps and keeps the media buttons pointed here;
     * off means it never asks, so its audio plays alongside whatever else is running.
     */
    fun setExclusiveAudio(exclusive: Boolean) {
        if (exclusiveAudio == exclusive) return
        exclusiveAudio = exclusive
        if (!running) return
        abandonFocus()
        requestFocus()
    }

    /**
     * Battery: the render thread wakes once per block, so when nothing is flowing — no playout
     * session AND the mic is not capturing — that cadence is pure wakeup cost against a silent
     * bus. The block grows; it shrinks back the moment audio appears. The track itself keeps
     * running throughout.
     */
    fun setLowLatencyDemand(demand: Boolean) {
        lowLatencyDemand = demand
    }

    /**
     * Called from the 1 Hz tick. The focus-change listener covers the ordinary case, but an app
     * that pauses without abandoning focus never sends one, so this re-checks whether the track
     * is alive and restarts it if not. Reads nothing expensive, and only while interrupted.
     */
    fun pollInterruptionRecovery() {
        if (!running || !interrupted) return
        val current = track
        if (current != null && current.playState == AudioTrack.PLAYSTATE_PLAYING) {
            interrupted = false
            return
        }
        resumeTrack()
    }

    private fun renderLoop() {
        var scratch = FloatArray(BLOCK_FRAMES_LOW_LATENCY * SessionPlayout.MIX_CHANNELS)
        while (running) {
            val current = track
            if (current == null || current.playState != AudioTrack.PLAYSTATE_PLAYING) {
                // The device went away (route change, media reset) or focus was lost. Sleeping
                // briefly and retrying is the whole recovery path — Android gives no callback
                // for a track that simply stopped producing.
                Thread.sleep(20)
                if (running) resumeTrack()
                continue
            }
            val frames = if (lowLatencyDemand) BLOCK_FRAMES_LOW_LATENCY else BLOCK_FRAMES_IDLE
            val needed = frames * SessionPlayout.MIX_CHANNELS
            if (scratch.size < needed) scratch = FloatArray(needed)
            mixer.render(scratch, frames)
            val written = try {
                current.write(scratch, 0, needed, AudioTrack.WRITE_BLOCKING)
            } catch (_: IllegalStateException) {
                -1
            }
            if (written < 0) {
                interrupted = true
                onDiagnostic?.invoke("write failed ($written) — restarting the track")
                resumeTrack()
            }
        }
    }

    private fun openTrack() {
        val minBytes = AudioTrack.getMinBufferSize(
            SessionPlayout.MIX_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        // Three low-latency blocks, floored at the device minimum: enough for the render thread
        // to stay ahead without stacking latency the jitter buffer is already accounting for.
        val bufferBytes = max(
            minBytes,
            BLOCK_FRAMES_LOW_LATENCY * SessionPlayout.MIX_CHANNELS * BYTES_PER_FLOAT * 3,
        )
        val builder = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(SessionPlayout.MIX_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
        builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        val newTrack = builder.build()
        newTrack.play()
        track = newTrack
        reportedOutputLatencyMs =
            bufferBytes.toDouble() / (SessionPlayout.MIX_CHANNELS * BYTES_PER_FLOAT) *
            1000.0 / SessionPlayout.MIX_SAMPLE_RATE
    }

    private fun closeTrack() {
        val current = track
        track = null
        try {
            current?.pause()
            current?.flush()
            current?.stop()
        } catch (_: IllegalStateException) {
            // Already dead; releasing below is all that is left to do.
        }
        current?.release()
    }

    private fun resumeTrack() {
        try {
            closeTrack()
            openTrack()
            interrupted = false
            onDiagnostic?.invoke("playback restarted")
            onPlaybackRecovered?.invoke()
        } catch (e: Exception) {
            // Reported, not fatal: during a phone call every attempt fails and the render loop
            // simply retries. An unconditional "recovered" line would claim a recovery that
            // never happened, which is exactly what the diagnostics panel must not do.
            interrupted = true
            onDiagnostic?.invoke("playback restart failed: ${e.message}")
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                interrupted = true
                onDiagnostic?.invoke("audio focus lost")
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                onDiagnostic?.invoke("audio focus regained")
                if (running) resumeTrack()
            }
            else -> Unit
        }
    }

    private fun requestFocus() {
        if (!exclusiveAudio) return
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        audioManager = manager
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        focusRequest = request
        manager.requestAudioFocus(request)
    }

    private fun abandonFocus() {
        val manager = audioManager ?: return
        val request = focusRequest
        if (request is AudioFocusRequest) {
            manager.abandonAudioFocusRequest(request)
        }
        focusRequest = null
    }

    private companion object {
        const val BYTES_PER_FLOAT = 4

        /** ~5 ms at 48 kHz — the low-latency render block. */
        const val BLOCK_FRAMES_LOW_LATENCY = 240

        /** ~40 ms — the idle block, so a silent bus wakes the render thread 8x less often. */
        const val BLOCK_FRAMES_IDLE = 1920
    }
}
