package com.organicmoto.maps.media

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import android.os.SystemClock
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.organicmoto.maps.RideMutedColor
import com.organicmoto.maps.RideSurfaceColor

/**
 * Glove/riding ergonomics: every primary target is at least this size, so a
 * gloved thumb cannot miss it. The play/pause control is roomier still
 * ([PlayPauseTarget]) because it is the one control reached for mid-ride.
 */
internal val GloveTarget: Dp = 64.dp

/** The primary play/pause control, preferred larger than [GloveTarget]. */
internal val PlayPauseTarget: Dp = 80.dp

/** Visible separation between adjacent targets: no invisible overlapping zones. */
internal val GloveGap: Dp = 12.dp

/**
 * Separate media control center anchored above the ride data bar.
 *
 * It renders the selected player's track, transport, phone-or-session volume,
 * optional player selection, and a 15-second inactivity timer. Panel touch,
 * scroll, and control interactions restart it; media-session observations do
 * not. Every control follows [MediaCenterState] so an unsupported command is
 * honestly disabled rather than faked.
 *
 * When the playback state is genuinely unknown (no session, or a session that
 * reported no `PlaybackState`), the panel shows explicit Pause and Play
 * controls instead of a single toggle whose label would have to lie.
 *
 * [maxHeight], when provided, bounds the card (landscape ride layout) and the
 * content scrolls inside it, so the bar and END button are never covered.
 *
 * The panel has no colour accents to drop: the ride chrome is one white and one
 * near-black ([RideSurfaceColor]) in every mode, so state is always carried by
 * glyph and text.
 */
@Composable
fun MediaControlCenter(
    state: MediaCenterState,
    onCommand: (MediaCommand) -> Unit,
    onVolume: (VolumeDirection) -> Unit,
    onSelectPlayer: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    maxHeight: Dp? = null,
    inactivityTimeoutMillis: Long = MediaPanelTimeoutMillis,
) {
    val remaining = remember { mutableFloatStateOf(1f) }
    val pointerGestureActive = remember { mutableStateOf(false) }
    val timer = remember(inactivityTimeoutMillis) {
        MediaPanelInactivityTimer(
            openedAtMillis = SystemClock.elapsedRealtime(),
            timeoutMillis = inactivityTimeoutMillis,
        )
    }
    val close = rememberUpdatedState(onClose)
    val onPanelInteraction: () -> Unit = remember(timer) {
        {
            timer.recordInteraction(SystemClock.elapsedRealtime())
            remaining.floatValue = 1f
        }
    }
    val latestPanelInteraction = rememberUpdatedState(onPanelInteraction)
    val scrollState = rememberScrollState()
    LaunchedEffect(scrollState) {
        var wasScrolling = scrollState.isScrollInProgress
        snapshotFlow { scrollState.isScrollInProgress }.collect { scrolling ->
            if (scrolling && !wasScrolling) latestPanelInteraction.value()
            wasScrolling = scrolling
        }
    }
    LaunchedEffect(timer) {
        while (true) {
            delay(16)
            val now = SystemClock.elapsedRealtime()
            remaining.floatValue = timer.remaining(now)
            if (timer.isExpired(now) && !pointerGestureActive.value) {
                close.value()
                return@LaunchedEffect
            }
        }
    }
    val onSurface = Color.White
    val muted = RideMutedColor
    // The ride chrome has one accent pair: white on the near-black ink, the same
    // white-fill/dark-ink the map pills use. The primary control inverts it.
    val accent = Color.White
    val onAccent = RideSurfaceColor
    val neutralFill = Color(0xFF3A3A3C)

    Box(
        modifier = modifier
            .fillMaxWidth()
            // Full-bleed like the bar: any inset here shows as a step at the
            // join and breaks the single-silhouette read.
            .padding(
                start = MediaPanelJoin.leadingInset,
                end = MediaPanelJoin.trailingInset,
            ),
        contentAlignment = Alignment.TopStart,
    ) {
        Surface(
            color = RideSurfaceColor,
            // Rounded at the top like the bar, square where it fuses into the
            // bar's edge so there is no notch of map between them.
            shape = RoundedCornerShape(
                topStart = MediaPanelJoin.panelTopCorner,
                topEnd = MediaPanelJoin.panelTopCorner,
            ),
            shadowElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (maxHeight != null) Modifier.heightIn(max = maxHeight) else Modifier,
                )
                .pointerInput(latestPanelInteraction) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.any { it.pressed }) {
                                latestPanelInteraction.value()
                                pointerGestureActive.value = true
                            } else if (pointerGestureActive.value) {
                                latestPanelInteraction.value()
                                pointerGestureActive.value = false
                            }
                        }
                    }
                }
                .semantics { contentDescription = "Media control center" },
        ) {
            Column(
                Modifier
                    .verticalScroll(scrollState)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Header(
                    state = state,
                    onSurface = onSurface,
                    muted = muted,
                    onClose = onClose,
                    remaining = { remaining.floatValue },
                )
                Spacer(Modifier.height(10.dp))
                TransportControls(
                    state = state,
                    onCommand = { command ->
                        onPanelInteraction()
                        onCommand(command)
                    },
                    onSurface = onSurface,
                    accent = accent,
                    onAccent = onAccent,
                    neutralFill = neutralFill,
                )
                Spacer(Modifier.height(10.dp))
                VolumeRow(
                    volume = state.volume,
                    onVolume = { direction ->
                        onPanelInteraction()
                        onVolume(direction)
                    },
                    onSurface = onSurface,
                    muted = muted,
                )
                if (state.canSelectPlayer) {
                    Spacer(Modifier.height(10.dp))
                    PlayerSelector(
                        state = state,
                        onSelectPlayer = { key ->
                            onPanelInteraction()
                            onSelectPlayer(key)
                        },
                        onSurface = onSurface,
                        muted = muted,
                        onPanelInteraction = onPanelInteraction,
                    )
                }
                StatusFooter(
                    state = state,
                    onSurface = onSurface,
                    muted = muted,
                )
            }
        }
    }
}

@Composable
private fun Header(
    state: MediaCenterState,
    onSurface: Color,
    muted: Color,
    onClose: () -> Unit,
    remaining: () -> Float,
) {
    val session = state.selected
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(
                text = when {
                    session != null -> session.displayName
                    state.status == MediaCenterStatus.PERMISSION_NEEDED -> "Media keys"
                    else -> "No player"
                },
                style = MaterialTheme.typography.labelMedium,
                color = muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = session?.trackTitle ?: defaultTitle(state),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = session?.trackSubtitle ?: defaultSubtitle(state),
                style = MaterialTheme.typography.labelSmall,
                color = muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Canvas(
            Modifier.size(16.dp).semantics {
                contentDescription = "Media controls inactivity timer"
                progressBarRangeInfo = ProgressBarRangeInfo(remaining(), 0f..1f)
            },
        ) {
            drawArc(Color.White, startAngle = -90f, sweepAngle = 360f * remaining(), useCenter = true)
        }
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .size(GloveTarget)
                .semantics { contentDescription = "Close media controls" },
        ) {
            CloseGlyph(onSurface)
        }
    }
}

private fun defaultTitle(state: MediaCenterState): String = when (state.status) {
    MediaCenterStatus.PERMISSION_NEEDED -> "Limited media controls"
    MediaCenterStatus.LOADING -> "Checking players"
    else -> "Nothing playing"
}

private fun defaultSubtitle(state: MediaCenterState): String = when (state.status) {
    MediaCenterStatus.PERMISSION_NEEDED -> "Phone volume and system media keys"
    MediaCenterStatus.LOADING -> "Reading active media sessions"
    else -> "Start playback in a media app"
}

/**
 * Transport row. With a known playback state this is a single explicit
 * play-or-pause control between skip buttons; with an unknown state it offers
 * both directions as separate explicit controls (never a toggle that would
 * silently invert), plus the skip buttons on their own row so nothing shrinks
 * below [GloveTarget] on a 320 dp screen at 2x font scale.
 */
@Composable
private fun TransportControls(
    state: MediaCenterState,
    onCommand: (MediaCommand) -> Unit,
    onSurface: Color,
    accent: Color,
    onAccent: Color,
    neutralFill: Color,
) {
    if (state.transportStateKnown) {
        val playing = state.isPlaying
        val playOrPause = if (playing) MediaCommand.PAUSE else MediaCommand.PLAY
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TransportButton(
                command = MediaCommand.PREVIOUS,
                description = "Previous track",
                state = state,
                onCommand = onCommand,
                onSurface = onSurface,
            ) { SkipGlyph(forward = false, color = onSurface) }
            Spacer(Modifier.width(GloveGap))
            PlayPauseButton(
                command = playOrPause,
                description = if (playing) "Pause" else "Play",
                enabled = state.canIssue(playOrPause),
                accent = accent,
                onAccent = onAccent,
                onCommand = onCommand,
                glyphPlaying = playing,
            )
            Spacer(Modifier.width(GloveGap))
            TransportButton(
                command = MediaCommand.NEXT,
                description = "Next track",
                state = state,
                onCommand = onCommand,
                onSurface = onSurface,
            ) { SkipGlyph(forward = true, color = onSurface) }
        }
        return
    }

    // Unknown playback state: explicit actions, each honest about what it sends.
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayPauseButton(
                command = MediaCommand.PAUSE,
                description = "Pause",
                enabled = state.canIssue(MediaCommand.PAUSE),
                accent = accent,
                onAccent = onAccent,
                onCommand = onCommand,
                glyphPlaying = true,
            )
            Spacer(Modifier.width(GloveGap))
            PlayPauseButton(
                command = MediaCommand.PLAY,
                description = "Play",
                enabled = state.canIssue(MediaCommand.PLAY),
                accent = neutralFill,
                onAccent = onSurface,
                onCommand = onCommand,
                glyphPlaying = false,
            )
        }
        Spacer(Modifier.height(GloveGap))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TransportButton(
                command = MediaCommand.PREVIOUS,
                description = "Previous track",
                state = state,
                onCommand = onCommand,
                onSurface = onSurface,
            ) { SkipGlyph(forward = false, color = onSurface) }
            Spacer(Modifier.width(GloveGap))
            TransportButton(
                command = MediaCommand.NEXT,
                description = "Next track",
                state = state,
                onCommand = onCommand,
                onSurface = onSurface,
            ) { SkipGlyph(forward = true, color = onSurface) }
        }
    }
}

@Composable
private fun TransportButton(
    command: MediaCommand,
    description: String,
    state: MediaCenterState,
    onCommand: (MediaCommand) -> Unit,
    onSurface: Color,
    glyph: @Composable () -> Unit,
) {
    val enabled = state.canIssue(command)
    IconButton(
        onClick = { if (enabled) onCommand(command) },
        enabled = enabled,
        modifier = Modifier
            .size(GloveTarget)
            .semantics { contentDescription = description },
    ) {
        glyph()
    }
}

@Composable
private fun PlayPauseButton(
    command: MediaCommand,
    description: String,
    enabled: Boolean,
    accent: Color,
    onAccent: Color,
    onCommand: (MediaCommand) -> Unit,
    glyphPlaying: Boolean,
) {
    Box(
        modifier = Modifier
            .size(PlayPauseTarget)
            .clip(CircleShape)
            .background(if (enabled) accent else Color(0xFF4A4A4C))
            .clickable(enabled = enabled) { onCommand(command) }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        PlayPauseGlyph(playing = glyphPlaying, color = onAccent)
    }
}

@Composable
private fun VolumeRow(
    volume: VolumeState,
    onVolume: (VolumeDirection) -> Unit,
    onSurface: Color,
    muted: Color,
) {
    val canLower = volume.canLower
    val canRaise = volume.canRaise
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = { if (canLower) onVolume(VolumeDirection.DOWN) },
            enabled = canLower,
            modifier = Modifier
                .size(GloveTarget)
                .semantics {
                    contentDescription = "Volume down, ${volume.label}"
                },
        ) {
            VolumeGlyph(up = false, color = onSurface, enabled = canLower)
        }
        Spacer(Modifier.width(GloveGap))
        Column(Modifier.weight(1f)) {
            Text(
                text = volume.label,
                style = MaterialTheme.typography.labelSmall,
                color = muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().height(10.dp)) {
                Canvas(Modifier.fillMaxWidth().height(10.dp)) {
                    drawVolumeLevel(
                        fraction = volume.fraction,
                        color = onSurface,
                        disabled = !volume.readable,
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (volume.fixed) {
                    "Fixed volume"
                } else if (!volume.readable) {
                    "Adjustable"
                } else {
                    "${volume.current} / ${volume.max}"
                },
                style = MaterialTheme.typography.labelSmall,
                color = muted,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(GloveGap))
        IconButton(
            onClick = { if (canRaise) onVolume(VolumeDirection.UP) },
            enabled = canRaise,
            modifier = Modifier
                .size(GloveTarget)
                .semantics {
                    contentDescription = "Volume up, ${volume.label}"
                },
        ) {
            VolumeGlyph(up = true, color = onSurface, enabled = canRaise)
        }
    }
}

@Composable
private fun PlayerSelector(
    state: MediaCenterState,
    onSelectPlayer: (String) -> Unit,
    onSurface: Color,
    muted: Color,
    onPanelInteraction: () -> Unit,
) {
    val scrollState = rememberScrollState()
    LaunchedEffect(scrollState, onPanelInteraction) {
        var wasScrolling = scrollState.isScrollInProgress
        snapshotFlow { scrollState.isScrollInProgress }.collect { scrolling ->
            if (scrolling && !wasScrolling) onPanelInteraction()
            wasScrolling = scrolling
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        state.sessions.forEach { session ->
            val selected = session.key == state.selectedKey
            val background = if (selected) Color.White else Color(0xFF3A3A3C)
            val content = if (selected) RideSurfaceColor else onSurface
            Box(
                modifier = Modifier
                    .padding(end = GloveGap)
                    .height(GloveTarget)
                    .clip(RoundedCornerShape(12.dp))
                    .background(background)
                    .clickable { onSelectPlayer(session.key) }
                    .padding(horizontal = 18.dp)
                    .semantics {
                        contentDescription = "Select player ${session.displayName}"
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = session.displayName,
                    color = content,
                    fontSize = 14.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }
        if (state.sessions.isEmpty()) {
            Text("No players", style = MaterialTheme.typography.labelSmall, color = muted)
        }
    }
}

@Composable
private fun StatusFooter(
    state: MediaCenterState,
    onSurface: Color,
    muted: Color,
) {
    if (state.status == MediaCenterStatus.LOADING) return
    Spacer(Modifier.height(8.dp))
    if (state.usingFallback) {
        Text(
            text = "Using system media keys (limited control)",
            style = MaterialTheme.typography.labelSmall,
            color = muted,
            maxLines = 2,
        )
        if (!state.transportStateKnown) {
            Text(
                text = "Playback state unknown — Play and Pause are explicit",
                style = MaterialTheme.typography.labelSmall,
                color = muted,
                maxLines = 2,
            )
        }
    } else {
        Text(
            text = if (state.isPlaying) "Playing" else "Paused",
            style = MaterialTheme.typography.labelSmall,
            color = onSurface,
            maxLines = 1,
        )
    }
}
