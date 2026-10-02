package com.organicmoto.maps

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.organicmoto.maps.routing.navigation.VoiceDistanceUnit
import com.organicmoto.maps.routing.navigation.VoiceGuidanceSettings

@Composable
internal fun VoiceGuidanceSettingsDialog(
    current: VoiceGuidanceSettings,
    unit: VoiceDistanceUnit,
    speechStatus: OfflineSpeechStatus,
    onDismiss: () -> Unit,
    onApply: (VoiceGuidanceSettings) -> Unit,
) {
    var draftEnabled by remember(current.enabled) { mutableStateOf(current.enabled) }
    var draftInterval by remember(current.intervalMeters, unit) {
        mutableStateOf(
            unit.fromMetres(current.intervalMeters)
                .coerceIn(unit.minimumInterval, unit.maximumInterval).toFloat(),
        )
    }
    var draftPreviewCount by remember(current.previewCount) {
        mutableStateOf(current.previewCount.coerceIn(1, 5).toFloat())
    }
    val intervalSteps = (unit.maximumInterval - unit.minimumInterval) / unit.intervalStep - 1
    val intervalLabel = unit.intervalLabel(unit.toMetres(draftInterval.toInt()))
    val previewCount = draftPreviewCount.toInt().coerceIn(1, 5)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { SettingsScreenHeader(title = "Voice guidance", onBack = onDismiss) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Spoken turn guidance", fontWeight = FontWeight.Medium)
                        Text(
                            "Uses an installed English offline speech voice while riding.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF8A000000),
                        )
                    }
                    Switch(
                        checked = draftEnabled,
                        onCheckedChange = { draftEnabled = it },
                        modifier = Modifier
                            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            .semantics {
                                contentDescription = "Spoken turn guidance"
                            },
                    )
                }
                Text(
                    text = speechStatus.description(),
                    style = MaterialTheme.typography.bodySmall,
                    color = when (speechStatus) {
                        OfflineSpeechStatus.NotStarted -> Color(0xFF8A000000)
                        OfflineSpeechStatus.Checking -> Color(0xFF8A000000)
                        is OfflineSpeechStatus.Ready -> Color(0xFF26734D)
                        is OfflineSpeechStatus.Unavailable -> Color(0xFF9B2C2C)
                    },
                    modifier = Modifier.padding(top = 8.dp),
                )
                HorizontalDivider(Modifier.padding(vertical = 14.dp))
                Text("Countdown every $intervalLabel", fontWeight = FontWeight.Medium)
                Text(
                    "The next-turn countdown starts within ten intervals. Each prompt names the road.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF8A000000),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .requiredHeightIn(min = 48.dp)
                        .semantics(mergeDescendants = true) {
                            contentDescription = "Announcement interval every $intervalLabel"
                        },
                ) {
                    Slider(
                        value = draftInterval,
                        onValueChange = { draftInterval = it },
                        valueRange = unit.minimumInterval.toFloat()..unit.maximumInterval.toFloat(),
                        steps = intervalSteps,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Preview the next $previewCount turns", fontWeight = FontWeight.Medium)
                Text(
                    "The preview is repeated when the next maneuver changes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF8A000000),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .requiredHeightIn(min = 48.dp)
                        .semantics(mergeDescendants = true) {
                            contentDescription = "Preview next $previewCount maneuvers"
                        },
                ) {
                    Slider(
                        value = draftPreviewCount,
                        onValueChange = { draftPreviewCount = it },
                        valueRange = 1f..5f,
                        steps = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onApply(
                        VoiceGuidanceSettings(
                            enabled = draftEnabled,
                            intervalMeters = unit.toMetres(draftInterval.toInt()),
                            previewCount = previewCount,
                        ),
                    )
                },
            ) { Text("APPLY") }
        },
    )
}

private fun OfflineSpeechStatus.description(): String = when (this) {
    OfflineSpeechStatus.NotStarted -> "Voice availability has not been checked yet."
    OfflineSpeechStatus.Checking -> "Checking for an installed English offline voice…"
    is OfflineSpeechStatus.Ready -> "English offline voice ready: $voiceName"
    is OfflineSpeechStatus.Unavailable -> "Unavailable: $reason"
}
