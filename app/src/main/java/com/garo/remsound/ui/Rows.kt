package com.garo.remsound.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/**
 * A peer row: a switch whose label is the peer's name and live status.
 *
 * Row operations follow the rule the Apple port arrived at the hard way — a long-press menu is a
 * touch convenience a screen reader cannot reliably reach, so every operation is ALSO an explicit
 * custom accessibility action on the element the screen reader focuses. Both, never only one.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PeerRow(
    name: String,
    statusText: String,
    selected: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    onRemove: (() -> Unit)?,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val hint = if (selected) {
        "Selected. Double tap to stop receiving from this peer."
    } else {
        "Not selected. Double tap to receive audio from this peer."
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onSelectedChange(!selected) },
                onLongClick = { if (onRemove != null) menuExpanded = true },
            )
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "$name, $statusText. $hint"
                stateDescription = if (selected) "Selected" else "Not selected"
                if (onRemove != null) {
                    customActions = listOf(CustomAccessibilityAction("Remove peer") {
                        onRemove()
                        true
                    })
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Text(
                statusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = selected, onCheckedChange = onSelectedChange)
        if (onRemove != null) {
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text("Remove peer") },
                    onClick = {
                        menuExpanded = false
                        onRemove()
                    },
                )
            }
        }
    }
}

/**
 * A profile row: tapping applies it; update, rename and delete are a long-press menu plus the
 * matching custom accessibility actions (see [PeerRow] for why both).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProfileRow(
    name: String,
    summary: String,
    isApplied: Boolean,
    onApply: () -> Unit,
    onUpdate: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val appliedPrefix = if (isApplied) "currently applied, " else ""
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onApply, onLongClick = { menuExpanded = true })
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "$name, $appliedPrefix$summary. Double tap to apply this " +
                    "profile. Updating, renaming, and deleting are available as actions."
                customActions = listOf(
                    CustomAccessibilityAction("Save current settings to this profile") {
                        onUpdate()
                        true
                    },
                    CustomAccessibilityAction("Rename") {
                        onRename()
                        true
                    },
                    CustomAccessibilityAction("Delete profile") {
                        onDelete()
                        true
                    },
                )
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            if (isApplied) {
                Text(
                    "Currently applied",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            DropdownMenuItem(
                text = { Text("Save current settings to this profile") },
                onClick = {
                    menuExpanded = false
                    onUpdate()
                },
            )
            DropdownMenuItem(
                text = { Text("Rename…") },
                onClick = {
                    menuExpanded = false
                    onRename()
                },
            )
            DropdownMenuItem(
                text = { Text("Delete profile") },
                onClick = {
                    menuExpanded = false
                    onDelete()
                },
            )
        }
    }
}
