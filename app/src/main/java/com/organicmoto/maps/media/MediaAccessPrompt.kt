package com.organicmoto.maps.media

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/** Startup only: access is optional; riding controls never send users to Settings. */
@Composable
internal fun MediaAccessPrompt(onEnable: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enable media player control?") },
        text = {
            Text("Notification access lets you control your media player during a ride. " +
                "Notification contents are not read. Phone volume and limited system media keys work without it.")
        },
        confirmButton = { TextButton(onClick = onEnable) { Text("Enable notification access") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}
