package com.garo.remsound.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.garo.remsound.kit.ReceiverController
import com.garo.remsound.kit.ReceiverProfile
import com.garo.remsound.kit.StartupProfileChoice

/**
 * Saved configuration snapshots — the Apple port's Profiles tab, minus the iCloud section: there
 * is no Android equivalent that carries the profile passwords end-to-end encrypted, and the
 * password-never-in-the-synced-blob rule is the whole reason that design works. Profiles here are
 * local to the device.
 */
@Composable
fun ProfilesTab(controller: ReceiverController) {
    val profiles by controller.profiles.collectAsStateWithLifecycle()
    val appliedProfile by controller.appliedProfile.collectAsStateWithLifecycle()
    val startupProfile by controller.startupProfile.collectAsStateWithLifecycle()
    var newProfileName by remember { mutableStateOf("") }
    // One shared rename prompt for whichever row triggered it, rather than a dialog per row: a
    // row can disappear under its own open menu, taking the dialog with it.
    var renamingProfileId by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }

    FormSection(
        header = "Saved profiles",
        footer = "Applying a profile replaces the peer list and selection, password, receive " +
            "and send switches, microphone, and maximum delay. Volume and the other audio " +
            "options are not touched. The profile your current settings match is marked as " +
            "currently applied; changing any of its settings removes the mark.",
    ) {
        if (profiles.isEmpty()) {
            FormText(
                "No profiles yet. Set up peers, password, and toggles the way you like, then " +
                    "save the setup below.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        for (profile in profiles) {
            ProfileRow(
                name = profile.name,
                summary = profileSummary(profile),
                isApplied = appliedProfile?.id == profile.id,
                onApply = { controller.applyProfile(profile.id) },
                onUpdate = { controller.updateProfile(profile.id) },
                onRename = {
                    renameText = profile.name
                    renamingProfileId = profile.id
                },
                onDelete = { controller.deleteProfile(profile.id) },
            )
        }
    }

    FormSection(
        header = "Save current configuration",
        footer = "Saves the peers, password, receive and send switches, microphone, and maximum " +
            "delay as they are right now. Using an existing profile's name updates that profile.",
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = newProfileName,
                onValueChange = { newProfileName = it },
                label = { Text("Profile name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    controller.saveProfile(newProfileName)
                    newProfileName = ""
                },
                enabled = newProfileName.isNotBlank(),
            ) {
                Text("Save")
            }
        }
    }

    FormSection(
        header = "At launch",
        footer = "The profile is applied exactly as saved — if it was saved with microphone " +
            "sending on, sending starts with the app.",
    ) {
        val options = buildList {
            add(StartupProfileChoice.Off as StartupProfileChoice to "No profile — settings as you left them")
            add(StartupProfileChoice.LastApplied as StartupProfileChoice to "Last applied profile")
            for (profile in profiles) {
                add(StartupProfileChoice.Fixed(profile.id) as StartupProfileChoice to profile.name)
            }
        }
        FormMenuPicker(
            label = "Apply at launch",
            selected = startupProfile,
            options = options,
            onSelect = { controller.setStartupProfile(it) },
            hint = "Which profile the app applies each time it starts",
        )
    }

    val renamingId = renamingProfileId
    if (renamingId != null) {
        AlertDialog(
            onDismissRequest = { renamingProfileId = null },
            title = { Text("Rename profile") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("Profile name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    controller.renameProfile(renamingId, renameText)
                    renamingProfileId = null
                }) {
                    Text("Rename")
                }
            },
            dismissButton = {
                TextButton(onClick = { renamingProfileId = null }) { Text("Cancel") }
            },
        )
    }
}

/**
 * Plain-sentence row detail, doubling as the screen-reader description — it mirrors what applying
 * the profile will do without opening it.
 */
private fun profileSummary(profile: ReceiverProfile): String {
    val parts = mutableListOf<String>()
    parts.add("receive ${if (profile.receiveEnabled) "on" else "off"}")
    parts.add("send ${if (profile.sendEnabled) "on" else "off"}")
    val peerCount = profile.manualPeers.size
    if (peerCount > 0) parts.add("$peerCount saved peer${if (peerCount == 1) "" else "s"}")
    // With auto-tune on the stored delay is only where the tuner starts, so say so rather than
    // promising a value the profile will not hold to.
    parts.add(
        if (profile.autoTuneLatencyEnabled) {
            "delay adjusted automatically from ${profile.targetLatencyMs} ms"
        } else {
            "${profile.targetLatencyMs} ms delay"
        },
    )
    return parts.joinToString(", ")
}
