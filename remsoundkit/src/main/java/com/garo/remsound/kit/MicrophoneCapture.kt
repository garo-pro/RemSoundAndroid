package com.garo.remsound.kit

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlin.math.max

/** One selectable capture input, as shown in the microphone picker. */
data class AudioInputDevice(val id: String, val name: String)

/**
 * Microphone capture for the send path: an [AudioRecord] at 48 kHz read on its own thread in
 * 10 ms units and handed straight to [AudioSendEngine].
 *
 * The Apple port needs a lock-free ring between a realtime render block and a drain thread
 * because iOS clamps `installTap` buffers to ~100 ms. Android has no such clamp — `AudioRecord`
 * is a blocking read on a thread we own, so that thread *is* the drain thread and the ring
 * would only add a hop. What carries over is the rule it existed for: **never poll the input
 * device list on a timer.** Enumeration is audio-server IPC and glitches live playback, so the
 * list is refreshed only on an [AudioDeviceCallback] and at capture start.
 */
class MicrophoneCapture(private val context: Context) {
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile
    private var capturing = false

    private var preferredInputId: String? = null
    private var deviceCallback: AudioDeviceCallback? = null

    /** Interleaved stereo float at 48 kHz, on the capture thread. */
    var onSamples: ((samples: FloatArray, frames: Int) -> Unit)? = null

    /** Fired when the hardware input set changes — the picker refreshes from this, never a timer. */
    var onInputsChanged: (() -> Unit)? = null

    val isRunning: Boolean get() = capturing

    /**
     * Size of the most recent read, in ms — the capture cadence diagnostic. ~10 ms means smooth
     * packet pacing; a much larger figure would mean burst sending is back.
     */
    @Volatile
    var captureChunkMs: Double = 0.0
        private set

    /** Frames the device reported it could not deliver. */
    @Volatile
    var captureDroppedFrames: Long = 0
        private set

    private val audioManager: AudioManager?
        get() = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    fun startWatchingInputs() {
        if (deviceCallback != null) return
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                onInputsChanged?.invoke()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                onInputsChanged?.invoke()
            }
        }
        audioManager?.registerAudioDeviceCallback(callback, null)
        deviceCallback = callback
    }

    fun stopWatchingInputs() {
        deviceCallback?.let { audioManager?.unregisterAudioDeviceCallback(it) }
        deviceCallback = null
    }

    fun availableInputs(): List<AudioInputDevice> {
        val manager = audioManager ?: return emptyList()
        return manager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { it.isSource }
            .map { AudioInputDevice(id = it.id.toString(), name = describe(it)) }
            .distinctBy { it.id }
    }

    fun setPreferredInput(id: String?) {
        preferredInputId = id
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    // The first line of the body is the permission check lint cannot see through: capture never
    // opens without RECORD_AUDIO, and the caller is told so as an error line.
    @SuppressLint("MissingPermission")
    @Throws(IllegalStateException::class)
    fun start() {
        if (capturing) return
        if (!hasPermission()) throw IllegalStateException("Microphone permission has not been granted")

        val minBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        if (minBytes <= 0) throw IllegalStateException("This device cannot capture 48 kHz float audio")
        // Four capture chunks of headroom, floored at the device minimum: enough that a late
        // read never loses audio, small enough that a hiccup shows up as a late packet rather
        // than a silently buffered burst.
        val bufferBytes = max(minBytes, FRAME_SAMPLES * BYTES_PER_FLOAT * 4)

        val newRecord = AudioRecord.Builder()
            // VOICE_RECOGNITION rather than MIC or VOICE_COMMUNICATION: it is the source that
            // asks the platform NOT to apply AEC/AGC/noise suppression, which is what a music
            // and general-audio stream wants. VOICE_COMMUNICATION would run the phone-call
            // processing chain over it.
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(bufferBytes)
            .build()

        applyPreferredDevice(newRecord)

        if (newRecord.state != AudioRecord.STATE_INITIALIZED) {
            newRecord.release()
            throw IllegalStateException("The microphone could not be opened")
        }
        newRecord.startRecording()
        record = newRecord
        capturing = true

        val captureThread = Thread({ captureLoop(newRecord) }, "RemSound.Capture").apply {
            priority = Thread.MAX_PRIORITY
            isDaemon = true
        }
        thread = captureThread
        captureThread.start()
    }

    fun stop() {
        capturing = false
        thread = null
        val current = record
        record = null
        try {
            current?.stop()
        } catch (_: IllegalStateException) {
            // Already stopped; releasing is all that is left.
        }
        current?.release()
        captureChunkMs = 0.0
    }

    /**
     * Point the record at the user's chosen input. A selection that is not present on this
     * device is deliberately left alone rather than cleared — capture falls through to the
     * default input, and the picker labels the dangling selection instead of hiding it.
     */
    private fun applyPreferredDevice(target: AudioRecord) {
        val id = preferredInputId ?: return
        val manager = audioManager ?: return
        val device = manager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.id.toString() == id } ?: return
        target.preferredDevice = device
    }

    private fun captureLoop(source: AudioRecord) {
        val mono = FloatArray(FRAME_SAMPLES)
        val stereo = FloatArray(FRAME_SAMPLES * 2)
        while (capturing) {
            val read = try {
                source.read(mono, 0, FRAME_SAMPLES, AudioRecord.READ_BLOCKING)
            } catch (_: IllegalStateException) {
                -1
            }
            if (read <= 0) {
                if (read < 0) captureDroppedFrames += FRAME_SAMPLES
                continue
            }
            captureChunkMs = read * 1000.0 / SAMPLE_RATE
            // Mono duplicated to both channels, matching the send engine's stereo wire format.
            for (i in 0 until read) {
                stereo[i * 2] = mono[i]
                stereo[i * 2 + 1] = mono[i]
            }
            onSamples?.invoke(stereo, read)
        }
    }

    private fun describe(device: AudioDeviceInfo): String {
        val kind = when (device.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in microphone"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB audio"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth headset"
            AudioDeviceInfo.TYPE_TELEPHONY -> "Telephony"
            AudioDeviceInfo.TYPE_BUS -> "Audio bus"
            else -> "Input"
        }
        val product = device.productName?.toString()?.trim().orEmpty()
        return if (product.isEmpty() || product == kind) kind else "$kind — $product"
    }

    companion object {
        const val SAMPLE_RATE = 48_000

        /** 480 samples = 10 ms at 48 kHz — one Opus frame per read. */
        const val FRAME_SAMPLES = 480
        private const val BYTES_PER_FLOAT = 4
    }
}
