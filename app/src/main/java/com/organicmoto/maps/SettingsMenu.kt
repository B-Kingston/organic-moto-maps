package com.organicmoto.maps

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.organicmoto.maps.media.GloveTarget

/** Expanded settings card width, pill column included. */
private val SETTINGS_CARD_WIDTH = 300.dp

/** Pill column width; it stays visible as the card's tab. */
private val SETTINGS_CARD_TAB_WIDTH = 48.dp

/** Body top edge measured from the card's top: the pill's 4 dp pad + cog. */
private val SETTINGS_CARD_TAB_HEIGHT = 52.dp

/** Radius of the card's outer corners. */
private val SETTINGS_CARD_CORNER = 20.dp

/** Concave fillet flaring the card's top edge into the tab's sides. */
private val SETTINGS_CARD_FILLET = 16.dp

/** Menu row height; comfortably above the 48 dp minimum touch target. */
private val SETTINGS_ROW_HEIGHT = 52.dp

/** Padding below the last menu row. */
private val SETTINGS_CARD_BOTTOM_PADDING = 8.dp

/**
 * Map-side action rail for planning settings and saved rides. Collapsed, it is
 * a compact 48 dp pill; tapping the cog expands it into the settings card and
 * the pill becomes the card's tab. The card's right edge stays flush with the
 * pill, and concave fillets flare from the card's top edge into the tab's
 * sides, so the menu reads as growing out of the button. Both pill buttons
 * keep full 48 dp touch targets in the upper-right reach zone while open.
 */
@Composable
internal fun RouteActionsPill(
    onLoadMap: () -> Unit,
    onImportGpx: () -> Unit,
    onRouteSettings: () -> Unit,
    onMapsSettings: () -> Unit,
    onVoiceSettings: () -> Unit,
    voiceGuidanceEnabled: Boolean,
    voiceSpeechStatus: OfflineSpeechStatus,
    onOpenSavedRoutes: () -> Unit,
    settingsMenuExpanded: Boolean,
    onSettingsMenuExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    rideMode: Boolean = false,
    darkRideMapEnabled: Boolean = false,
    onDarkRideMapToggle: () -> Unit = {},
    monochrome: Boolean = false,
) {
    val soundStateDescription = when {
        !voiceGuidanceEnabled -> "Disabled"
        voiceSpeechStatus is OfflineSpeechStatus.Ready -> "English offline voice ready"
        voiceSpeechStatus is OfflineSpeechStatus.Checking -> "Checking for an English offline voice"
        voiceSpeechStatus is OfflineSpeechStatus.NotStarted -> "English offline voice not checked yet"
        else -> "English offline voice unavailable"
    }
    if (rideMode) {
        // Paint a planner-sized rail inside independent, larger hit boxes.
        // The transparent margin is part of each button, not the map behind it.
        Box(
            modifier = modifier.semantics { contentDescription = "Ride settings" },
            contentAlignment = Alignment.Center,
        ) {
            val rideShape = RoundedCornerShape(28.dp)
            Surface(
                color = if (monochrome) Color.White else Color.White.copy(alpha = 0.96f),
                shape = rideShape,
                shadowElevation = 5.dp,
                modifier = Modifier
                    .size(width = SETTINGS_CARD_TAB_WIDTH, height = GloveTarget * 2 - 16.dp)
                    .border(1.dp, Color(0x22000000), rideShape),
            ) {}
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                RideSettingsButton(
                    contentDescription = NavigationHudFormat.voiceSettingsDescription(
                        enabled = voiceGuidanceEnabled,
                        statusDescription = soundStateDescription,
                    ),
                    stateDescription = soundStateDescription,
                    onClick = onVoiceSettings,
                ) {
                    VoiceGuidanceIcon(
                        enabled = voiceGuidanceEnabled && voiceSpeechStatus is OfflineSpeechStatus.Ready,
                        color = RideSurfaceColor,
                    )
                }
                RideSettingsButton(
                    contentDescription = NavigationHudFormat.darkRideMapDescription(darkRideMapEnabled),
                    stateDescription = if (darkRideMapEnabled) "on" else "off",
                    onClick = onDarkRideMapToggle,
                ) {
                    DarkRideMapIcon(enabled = darkRideMapEnabled, color = RideSurfaceColor)
                }
            }
        }
        return
    }
    val shape = remember(settingsMenuExpanded) {
        if (settingsMenuExpanded) {
            SettingsCardShape(
                tabWidth = SETTINGS_CARD_TAB_WIDTH,
                tabTopRadius = SETTINGS_CARD_TAB_WIDTH / 2,
                tabHeight = SETTINGS_CARD_TAB_HEIGHT,
                bodyCornerRadius = SETTINGS_CARD_CORNER,
                neckFilletRadius = SETTINGS_CARD_FILLET,
            )
        } else {
            RoundedCornerShape(28.dp)
        }
    }
    Surface(
        color = Color.White.copy(alpha = 0.96f),
        shape = shape,
        shadowElevation = 5.dp,
        modifier = modifier
            .border(1.dp, Color(0x22000000), shape)
            .semantics { contentDescription = "Route actions" },
    ) {
        Row {
            if (settingsMenuExpanded) {
                Column(
                    modifier = Modifier
                        .width(SETTINGS_CARD_WIDTH - SETTINGS_CARD_TAB_WIDTH)
                        .padding(top = SETTINGS_CARD_TAB_HEIGHT, bottom = SETTINGS_CARD_BOTTOM_PADDING),
                ) {
                    SettingsMenuItem(label = "Route settings") {
                        onSettingsMenuExpandedChange(false)
                        onRouteSettings()
                    }
                    SettingsMenuItem(label = "Load map file") {
                        onSettingsMenuExpandedChange(false)
                        onLoadMap()
                    }
                    SettingsMenuItem(label = "Maps") {
                        onSettingsMenuExpandedChange(false)
                        onMapsSettings()
                    }
                    SettingsMenuItem(label = "Import GPX route") {
                        onSettingsMenuExpandedChange(false)
                        onImportGpx()
                    }
                    SettingsMenuItem(
                        label = "Sound settings",
                        stateDescription = soundStateDescription,
                    ) {
                        onSettingsMenuExpandedChange(false)
                        onVoiceSettings()
                    }
                }
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    // The divider fills its width, so the tab column's width
                    // must be pinned or it would swallow the card's body.
                    .width(SETTINGS_CARD_TAB_WIDTH)
                    .padding(vertical = 4.dp),
            ) {
                SettingsPillButton(
                    contentDescription = "Planning settings",
                    onClick = { onSettingsMenuExpandedChange(!settingsMenuExpanded) },
                ) { SettingsCogIcon() }
                HorizontalDivider(
                    color = Color(0x1A000000),
                    thickness = 1.dp,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                SettingsPillButton(
                    contentDescription = "Saved routes",
                    onClick = onOpenSavedRoutes,
                ) { BookmarkIcon(filled = false) }
            }
        }
    }
}

/**
 * Ride toggle target: the visible icon remains 24 dp, while the complete
 * 64 dp button is easy to hit with gloves. Its monochrome foreground keeps
 * state legible without adding a blue accent over the B&W map.
 */
@Composable
private fun RideSettingsButton(
    contentDescription: String,
    stateDescription: String,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(GloveTarget)
            .semantics {
                this.contentDescription = contentDescription
                this.stateDescription = stateDescription
            },
    ) {
        icon()
    }
}

@Composable
private fun SettingsPillButton(
    contentDescription: String,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(48.dp)
            .semantics {
                this.contentDescription = contentDescription
            },
    ) {
        icon()
    }
}

@Composable
private fun SettingsMenuItem(
    label: String,
    modifier: Modifier = Modifier,
    stateDescription: String? = null,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = SETTINGS_ROW_HEIGHT)
            .clickable(onClick = onClick)
            .then(
                if (stateDescription == null) {
                    Modifier
                } else {
                    Modifier.semantics {
                        contentDescription = label
                        this.stateDescription = stateDescription
                    }
                }
            )
            .padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = Color(0xFF1F1F1F),
        )
    }
}

/**
 * Silhouette of the expanded settings card: a rounded body whose top edge
 * flares into the settings tab through concave fillets. The tab is flush with
 * the card's right edge, so the rail's right border stays one straight line
 * from the tab's top rounding down to the card's bottom corner.
 */
private class SettingsCardShape(
    private val tabWidth: Dp,
    private val tabTopRadius: Dp,
    private val tabHeight: Dp,
    private val bodyCornerRadius: Dp,
    private val neckFilletRadius: Dp,
) : Shape {

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val tab = with(density) { tabWidth.toPx() }
        val tabTop = with(density) { tabTopRadius.toPx() }
        val bodyTop = with(density) { tabHeight.toPx() }
        val corner = with(density) { bodyCornerRadius.toPx() }
        val fillet = with(density) { neckFilletRadius.toPx() }

        val tabLeft = (size.width - tab).coerceAtLeast(corner)
        val filletRun = fillet.coerceAtMost(tabLeft - corner)
        val bodyArc = corner.coerceAtMost(size.height - bodyTop)
        val path = Path().apply {
            moveTo(0f, bodyTop + bodyArc)
            quadraticTo(0f, bodyTop, corner, bodyTop)
            lineTo(tabLeft - filletRun, bodyTop)
            quadraticTo(tabLeft, bodyTop, tabLeft, bodyTop - filletRun)
            lineTo(tabLeft, tabTop)
            quadraticTo(tabLeft, 0f, tabLeft + tabTop, 0f)
            lineTo(size.width - tabTop, 0f)
            quadraticTo(size.width, 0f, size.width, tabTop)
            lineTo(size.width, size.height - bodyArc)
            quadraticTo(size.width, size.height, size.width - bodyArc, size.height)
            lineTo(corner, size.height)
            quadraticTo(0f, size.height, 0f, size.height - bodyArc)
            close()
        }
        return Outline.Generic(path)
    }
}
