package com.garo.remsound.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.garo.remsound.kit.ReceiverController

/**
 * The main receiver UI — the same shape as the Apple port's shared SwiftUI view, so the two apps
 * are the same app: a title bar with a persistent About button, and four tabs — **Connectivity**
 * (status, connection, peers, add peer), **Send & Receive** (receive toggle, microphone send,
 * password), **Audio** (playback options), **Profiles** (saved snapshots).
 *
 * Built screen-reader-first, like the original: every control carries an explicit name and state,
 * status lines are plain sentences, and the tab items expose live state (traffic rates, "Muted",
 * the applied profile) so they can be read without opening the tab.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiverRootScreen(
    controller: ReceiverController,
    onRequestMicrophonePermission: () -> Unit,
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var showingAbout by remember { mutableStateOf(false) }
    var showingDiagnostics by remember { mutableStateOf(false) }

    val trafficSummary by controller.trafficSummary.collectAsStateWithLifecycle()
    val isMuted by controller.isMuted.collectAsStateWithLifecycle()
    val appliedProfile by controller.appliedProfile.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("RemSound") },
                actions = {
                    IconButton(
                        onClick = { showingAbout = true },
                        modifier = Modifier.semantics {
                            contentDescription =
                                "About RemSound. App information and links to the RemSound source code"
                        },
                    ) {
                        Icon(Icons.Outlined.Info, contentDescription = null)
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                TabItem(
                    label = "Connectivity",
                    icon = Icons.Filled.Wifi,
                    selected = selectedTab == 0,
                    // Like the Apple port's tab-bar accessibility value: the live traffic rates
                    // are readable without opening the tab.
                    state = trafficSummary,
                    onClick = { selectedTab = 0 },
                )
                TabItem(
                    label = "Send and receive",
                    icon = Icons.Filled.SwapVert,
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                )
                TabItem(
                    label = "Audio",
                    icon = Icons.AutoMirrored.Filled.VolumeUp,
                    selected = selectedTab == 2,
                    state = if (isMuted) "Muted" else null,
                    onClick = { selectedTab = 2 },
                )
                TabItem(
                    label = "Profiles",
                    icon = Icons.Filled.Bookmark,
                    selected = selectedTab == 3,
                    state = appliedProfile?.let { "${it.name} applied" },
                    onClick = { selectedTab = 3 },
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            when (selectedTab) {
                0 -> ConnectivityTab(controller) { showingDiagnostics = true }
                1 -> SendReceiveTab(controller, onRequestMicrophonePermission)
                2 -> AudioTab(controller)
                else -> ProfilesTab(controller)
            }
        }
    }

    if (showingAbout) {
        AboutDialog(onDismiss = { showingAbout = false })
    }
    if (showingDiagnostics) {
        DiagnosticsDialog(controller, onDismiss = { showingDiagnostics = false })
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TabItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    state: String? = null,
) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(icon, contentDescription = null) },
        label = { Text(label) },
        modifier = Modifier.semantics {
            contentDescription = label
            if (!state.isNullOrEmpty()) stateDescription = state
        },
    )
}

// ---- Connectivity ----

@Composable
private fun ConnectivityTab(controller: ReceiverController, onOpenDiagnostics: () -> Unit) {
    val statusSummary by controller.statusSummary.collectAsStateWithLifecycle()
    val lastError by controller.lastError.collectAsStateWithLifecycle()
    val connectionDetails by controller.connectionDetails.collectAsStateWithLifecycle()
    val peers by controller.peers.collectAsStateWithLifecycle()
    var newPeerHost by remember { mutableStateOf("") }

    FormSection(header = "Status") {
        FormText(
            text = statusSummary,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { contentDescription = "Status: $statusSummary" },
        )
        lastError?.let { error ->
            FormText(
                text = error,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { contentDescription = "Error: $error" },
            )
        }
    }

    if (connectionDetails.isNotEmpty()) {
        FormSection(header = "Connection") {
            for (line in connectionDetails) {
                FormText(line, style = MaterialTheme.typography.bodyMedium)
            }
            // The technical material lives behind this button rather than in the list above: it
            // is a dozen lines that rewrite every second, and a screen-reader user swiping
            // through "am I connected" should not have to pass all of it.
            FormTextButton(
                label = "Diagnostics",
                onClick = onOpenDiagnostics,
                hint = "Opens packet timing, buffer depth and traffic measurements, " +
                    "and a button to copy them",
            )
        }
    }

    FormSection(
        header = "Peers",
        footer = "Tick a peer to hear its audio. Audio plays only from peers you have selected.",
    ) {
        if (peers.isEmpty()) {
            FormText(
                "No peers yet. Peers on the same network appear automatically; add an address " +
                    "below for Tailscale or the relay.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        for (peer in peers) {
            PeerRow(
                name = peer.name,
                statusText = peer.statusText,
                selected = peer.isSelected,
                onSelectedChange = { controller.setPeerSelected(peer, it) },
                onRemove = peer.manualPeerId?.let { id -> { controller.removeManualPeer(id) } },
            )
        }
    }

    FormSection(header = "Add a peer by address") {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = newPeerHost,
                onValueChange = { newPeerHost = it },
                label = { Text("Address or hostname") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.weight(1f).semantics {
                    contentDescription = "Address or hostname. A LAN IP, Tailscale IP, or the " +
                        "RemSound relay hostname. The standard port is used automatically."
                },
            )
            TextButton(
                onClick = {
                    controller.addManualPeer(newPeerHost)
                    newPeerHost = ""
                },
                enabled = newPeerHost.isNotBlank(),
            ) {
                Text("Add")
            }
        }
    }
}

// ---- Send & Receive ----

@Composable
private fun SendReceiveTab(
    controller: ReceiverController,
    onRequestMicrophonePermission: () -> Unit,
) {
    val receiveEnabled by controller.receiveEnabled.collectAsStateWithLifecycle()
    val sendEnabled by controller.sendEnabled.collectAsStateWithLifecycle()
    val sendStatus by controller.sendStatus.collectAsStateWithLifecycle()
    val password by controller.password.collectAsStateWithLifecycle()
    val microphones by controller.availableMicrophones.collectAsStateWithLifecycle()
    val selectedMicrophoneId by controller.selectedMicrophoneId.collectAsStateWithLifecycle()

    FormSection(
        header = "Receive",
        footer = "Receiving and sending are independent — either can be on without the other.",
    ) {
        FormToggle(
            label = "Receive audio",
            checked = receiveEnabled,
            onCheckedChange = { controller.setReceiveEnabled(it) },
            hint = "Plays audio from RemSound senders. Turning this off keeps peers connected " +
                "and sending available.",
        )
    }

    FormSection(
        header = "Send",
        footer = "Audio goes to the peers you have ticked on the Connectivity tab; they must " +
            "also allow this device in their RemSound app. Using Bluetooth headphones' " +
            "microphone lowers their playback quality while sending.",
    ) {
        FormToggle(
            label = "Send microphone",
            checked = sendEnabled,
            onCheckedChange = { enabled ->
                if (enabled) onRequestMicrophonePermission() else controller.setSendEnabled(false)
            },
            hint = "Streams this device's microphone, encrypted, to the peers selected on the " +
                "Connectivity tab",
        )

        // A chosen input stays selectable while it is absent so the picker does not silently jump
        // selections — it is unplugged, or the profile naming it was saved elsewhere. Capture
        // falls back to the default input either way, so the label says so rather than leaving a
        // mystery selection.
        val options = buildList {
            add(null as String? to "System default")
            for (mic in microphones) add(mic.id as String? to mic.name)
            val selected = selectedMicrophoneId
            if (selected != null && microphones.none { it.id == selected }) {
                add(selected as String? to "Saved input, not available here — sending from the default input")
            }
        }
        FormMenuPicker(
            label = "Microphone",
            selected = selectedMicrophoneId,
            options = options,
            onSelect = { controller.setSelectedMicrophoneId(it) },
            hint = "Which microphone or input to send from",
        )

        if (sendStatus.isNotEmpty()) {
            FormText(sendStatus, style = MaterialTheme.typography.bodyMedium)
        }
    }

    FormSection(
        header = "Password",
        footer = "All audio is encrypted end to end. Use the same password as the RemSound " +
            "profile on the sending computer.",
    ) {
        OutlinedTextField(
            value = password,
            onValueChange = { controller.setPassword(it) },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .semantics {
                    contentDescription = "Password. Must match the password set on the sending " +
                        "computer. Audio stays silent until the passwords match."
                },
        )
    }
}
