package com.garo.remsound.kit

import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.view.KeyEvent
import androidx.core.content.IntentCompat

/**
 * Headset / lock-screen transport: a [MediaSession] whose play-pause routes to **receiving**,
 * not to a mute. The sender should see an honest CanReceive, so a press really does stop
 * receiving rather than silently discarding audio that keeps arriving.
 *
 * Two halves are both required and neither is optional, exactly as on the Apple side:
 * registered transport callbacks AND published playback state plus metadata. With no
 * now-playing item the system has nothing to arbitrate and the press goes to another app.
 * Keeping the state as `STATE_PAUSED` (rather than clearing it) while paused is what keeps the
 * *resume* press coming to us; [AudioOutput] never stopping is what keeps the session active
 * underneath it.
 *
 * State is pushed only on change — never on the 1 Hz tick. [reassert] re-publishes the item on
 * the edges that can win the slot back: the app coming to the foreground, and playback
 * recovering after something else had stopped it.
 */
class RemoteTransportControls(private val context: Context) {
    private var session: MediaSession? = null

    var onPlay: (() -> Unit)? = null
    var onPause: (() -> Unit)? = null
    var onToggle: (() -> Unit)? = null

    /** The last routed command and when — surfaced in the Diagnostics panel. */
    @Volatile
    var lastCommand: Command? = null
        private set

    data class Command(val name: String, val atMillis: Long)

    private var lastCommandAtMillis = 0L
    private var isPlaying = true
    private var detail = "Receiving audio"

    private val callback = object : MediaSession.Callback() {
        /**
         * A headset's single button sends one key for both directions, and which of `onPlay` /
         * `onPause` the default dispatcher picks depends on the state the system believes we
         * published. The Apple port learned the hard way that trusting that reading makes the
         * button one-way (AirPods send `pause` for every stem press, in every state), so the
         * direction is decided here instead: play-pause and headset-hook always toggle. Every
         * other key falls through to the default dispatch.
         */
        override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
            val event = IntentCompat.getParcelableExtra(
                mediaButtonIntent,
                Intent.EXTRA_KEY_EVENT,
                KeyEvent::class.java,
            )
            if (event != null && event.action == KeyEvent.ACTION_DOWN) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                        handle("play-pause") { onToggle?.invoke() }
                        return true
                    }
                }
            }
            return super.onMediaButtonEvent(mediaButtonIntent)
        }

        override fun onPlay() {
            handle("play") { onPlay?.invoke() }
        }

        override fun onPause() {
            handle("pause") { onPause?.invoke() }
        }

        override fun onStop() {
            // A stop is a pause plus an immediate re-publish: a routed stop would otherwise end
            // the now-playing session and there would be nothing left to resume into.
            handle("stop") {
                onPause?.invoke()
                reassert()
            }
        }

        override fun onSkipToNext() {
            // Registered but deliberately inert — a headset double-press is "next track", and
            // silently doing nothing is better than doing something surprising.
            lastCommand = Command("next", System.currentTimeMillis())
        }

        override fun onSkipToPrevious() {
            lastCommand = Command("previous", System.currentTimeMillis())
        }
    }

    /**
     * One physical press can produce two commands (a stop *and* a pause). A short gate coalesces
     * them so the press moves the state once — keep it if you touch this.
     */
    private fun handle(name: String, action: () -> Unit) {
        val now = System.currentTimeMillis()
        lastCommand = Command(name, now)
        if (now - lastCommandAtMillis < COMMAND_COALESCE_MS) return
        lastCommandAtMillis = now
        action()
    }

    fun activate() {
        if (session != null) return
        val newSession = MediaSession(context, "RemSound").apply {
            setCallback(callback)
            isActive = true
        }
        session = newSession
        publish()
    }

    fun deactivate() {
        session?.apply {
            isActive = false
            release()
        }
        session = null
    }

    /** Push the receive state. Change-gated, so this is cheap to call from a setter. */
    fun update(isPlaying: Boolean, detail: String) {
        if (this.isPlaying == isPlaying && this.detail == detail) return
        this.isPlaying = isPlaying
        this.detail = detail
        publish()
    }

    /**
     * Re-publish the now-playing item to take the transport back after something else held it.
     * Blind by necessity — there is no API to ask who owns the media buttons — so it fires
     * whether or not the slot was lost.
     */
    fun reassert() {
        publish()
    }

    private fun publish() {
        val current = session ?: return
        current.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "RemSound")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, detail)
                // No duration on purpose: declaring one puts a scrubber on a live stream that
                // cannot be scrubbed.
                .build(),
        )
        current.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_STOP or
                        PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS,
                )
                .setState(
                    if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    1.0f,
                )
                .build(),
        )
    }

    /** The platform session token, for the foreground-service notification's media style. */
    val sessionToken: MediaSession.Token? get() = session?.sessionToken

    private companion object {
        const val COMMAND_COALESCE_MS = 500L
    }
}
