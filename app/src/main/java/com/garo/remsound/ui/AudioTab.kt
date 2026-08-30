package com.garo.remsound.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.garo.remsound.kit.ReceiverController
import com.garo.remsound.kit.ReceiverSettings
import com.garo.remsound.kit.VolumeBoost
import kotlin.math.roundToInt

/**
 * Playback options — the Apple port's Audio tab, control for control: volume, extra volume,
 * mute, maximum delay, automatic delay, sound effects, exclusive audio, and the headset
 * transport toggle.
 */
@Composable
fun AudioTab(controller: ReceiverController) {
    val volume by controller.volume.collectAsStateWithLifecycle()
    val volumeBoost by controller.volumeBoost.collectAsStateWithLifecycle()
    val isMuted by controller.isMuted.collectAsStateWithLifecycle()
    val targetLatencyMs by controller.targetLatencyMs.collectAsStateWithLifecycle()
    val autoTune by controller.autoTuneLatencyEnabled.collectAsStateWithLifecycle()
    val autoTuneNote by controller.lastAutoTuneNote.collectAsStateWithLifecycle()
    val cuesEnabled by controller.cuesEnabled.collectAsStateWithLifecycle()
    val exclusiveAudio by controller.exclusiveAudio.collectAsStateWithLifecycle()
    val headsetControls by controller.headsetTransportControls.collectAsStateWithLifecycle()

    FormSection(
        header = "Playback",
        footer = "With \"Don't mix with other sounds\" on, whichever starts playing — RemSound " +
            "or another app — stops the other, and that is also what keeps RemSound running " +
            "while the screen is off. With it off, RemSound plays alongside other apps, but " +
            "Android may throttle it once the screen has been off for a while.",
    ) {
        FormSlider(
            label = "Volume",
            value = volume,
            valueRange = 0f..1f,
            steps = 19,
            onValueChange = { controller.setVolume(it) },
            spokenValue = "${(volume * 100).roundToInt()} percent",
        )

        FormMenuPicker(
            label = "Extra volume",
            selected = volumeBoost,
            options = VolumeBoost.entries.map { it to it.displayName },
            onSelect = { controller.setVolumeBoost(it) },
            hint = "Makes a quiet sender louder. The limiter keeps peaks from clipping, but a " +
                "large boost on already-loud audio will distort.",
        )

        FormToggle(
            label = "Mute",
            checked = isMuted,
            onCheckedChange = { controller.setMuted(it) },
        )

        FormSlider(
            label = "Maximum delay",
            value = targetLatencyMs.toFloat(),
            valueRange = ReceiverSettings.MIN_TARGET_LATENCY_MS.toFloat()..
                ReceiverSettings.MAX_TARGET_LATENCY_MS.toFloat(),
            steps = (ReceiverSettings.MAX_TARGET_LATENCY_MS - ReceiverSettings.MIN_TARGET_LATENCY_MS) / 5 - 1,
            onValueChange = { controller.setTargetLatencyMs(it.roundToInt()) },
            spokenValue = "$targetLatencyMs milliseconds",
            trailing = "$targetLatencyMs ms",
            hint = "Lower is faster but needs a steadier network. The Windows app default is " +
                "80 milliseconds.",
        )

        FormToggle(
            label = "Adjust delay automatically",
            checked = autoTune,
            onCheckedChange = { controller.setAutoTuneLatencyEnabled(it) },
            hint = "Raises the delay when the network is unsteady and lowers it again when the " +
                "network settles, instead of holding the value above",
        )
        if (autoTune && autoTuneNote != null) {
            FormText(
                autoTuneNote!!,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        FormToggle(
            label = "Sound effects",
            checked = cuesEnabled,
            onCheckedChange = { controller.setCuesEnabled(it) },
            hint = "Plays a sound when a peer connects or disconnects, when receiving or " +
                "sending is turned on or off, and when a profile is saved",
        )

        FormToggle(
            label = "Don't mix with other sounds",
            checked = exclusiveAudio,
            onCheckedChange = { controller.setExclusiveAudio(it) },
            hint = "When on, RemSound interrupts other apps' audio and is interrupted by them, " +
                "which also helps keep RemSound running while the screen is off. When off, " +
                "RemSound and other apps' sound can play at the same time.",
        )

        FormToggle(
            label = "Pause with headset or lock screen",
            checked = headsetControls,
            onCheckedChange = { controller.setHeadsetTransportControls(it) },
            hint = "When on, pressing the button on a headset, or the play and pause button on " +
                "the lock screen or in the notification, stops receiving audio; pressing it " +
                "again starts receiving again.",
        )
        // Not disabled, just explained: the preference is worth keeping for whenever sole
        // control goes back on, and a dimmed switch says less than the sentence.
        if (headsetControls && !exclusiveAudio) {
            FormText(
                "These buttons need sole control of the audio, so they do nothing while " +
                    "\"Don't mix with other sounds\" is off — the system sends them to " +
                    "whichever app has the sound.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
