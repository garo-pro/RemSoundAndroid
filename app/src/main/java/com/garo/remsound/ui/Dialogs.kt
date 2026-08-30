package com.garo.remsound.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.garo.remsound.kit.ReceiverController
import kotlinx.coroutines.delay

/**
 * "About": what the app is, its version, and links to the three source repositories — this
 * Android port, the Apple port it was ported from, and the official Windows app.
 */
@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val version = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("About") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("RemSound", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Receive and send RemSound audio on your Android device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                version?.let {
                    Text(
                        "Version $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                Text(
                    "Source code",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
                RepoLink(
                    title = "RemSound for Android",
                    detail = "this app",
                    url = "https://github.com/garo-pro/RemSoundAndroid",
                    accessibleName = "RemSound for Android source code on GitHub",
                )
                RepoLink(
                    title = "RemSound for Apple",
                    detail = "jonathans859/RemSoundApple — the port this one follows",
                    url = "https://github.com/jonathans859/RemSoundApple",
                    accessibleName = "RemSound for Apple source code on GitHub",
                )
                RepoLink(
                    title = "RemSound for Windows",
                    detail = "Ednunp/RemSound — the official app",
                    url = "https://github.com/Ednunp/RemSound",
                    accessibleName = "Official RemSound for Windows source code on GitHub",
                )

                Text(
                    "This app is an open-source companion to the Windows RemSound app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun RepoLink(title: String, detail: String, url: String, accessibleName: String) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                }
            }
            .padding(vertical = 8.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "$accessibleName. Opens in your browser"
            },
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The technical panel, reached from the Diagnostics button on the Connectivity tab.
 *
 * It exists so the main screen can stay short. These lines are genuinely useful — they are what
 * tell a jittering link apart from a lossy one — but there are a dozen of them and they rewrite
 * themselves every second, which makes the connection list tedious to swipe through when all you
 * wanted was "am I connected". The counters run whether or not this is open, so it always shows
 * real history rather than starting from zero.
 */
@Composable
fun DiagnosticsDialog(controller: ReceiverController, onDismiss: () -> Unit) {
    val details by controller.diagnosticDetails.collectAsStateWithLifecycle()
    var copied by remember { mutableStateOf(false) }

    // The button says "Copied" for a moment: a copy has no other feedback, and a screen reader
    // gets the spoken confirmation from the controller's announcement.
    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Diagnostics") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (details.isEmpty()) {
                    Text(
                        "No measurements yet. These appear once audio is playing.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    for (line in details) {
                        Text(
                            line,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 3.dp),
                        )
                    }
                    Text(
                        "Everything here covers the last minute and refreshes every second.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                TextButton(
                    onClick = {
                        controller.copyConnectionReport()
                        copied = true
                    },
                    modifier = Modifier.semantics {
                        contentDescription = "Copy all details. Copies the connection status " +
                            "from the previous screen and all of these measurements as text"
                    },
                ) {
                    Text(if (copied) "Copied" else "Copy all details")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
