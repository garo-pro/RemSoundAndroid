package com.garo.remsound.kit

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/**
 * Plays the app's cue sounds — peers connecting and disconnecting (the same WAVs the Windows
 * app ships), plus confirmation of the actions a user can trigger from anywhere: receiving
 * on/off, sending on/off, and saving a profile.
 *
 * Cues are an accessibility feature: a screen-reader user hears what happened without having to
 * poll the UI, which matters most when the change came from outside the app (a headset press,
 * the notification) and there is no focused control to announce it.
 */
class CuePlayer(context: Context, resources: Map<Cue, Int>) {
    enum class Cue {
        CONNECT,
        DISCONNECT,
        RECEIVE_ON,
        RECEIVE_OFF,
        SEND_ON,
        SEND_OFF,
        PROFILE_SAVED,
    }

    private val pool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    private val soundIds: Map<Cue, Int> =
        resources.mapValues { (_, resId) -> pool.load(context, resId, 1) }

    var enabled = true

    fun play(cue: Cue) {
        if (!enabled) return
        val id = soundIds[cue] ?: return
        pool.play(id, 1f, 1f, 1, 0, 1f)
    }

    fun release() {
        pool.release()
    }
}
