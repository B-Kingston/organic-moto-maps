package com.organicmoto.maps

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val SAVE_ACTION_TINT = Color(0xFF249CF2)
private val SAVE_ACTION_BACKGROUND = Color(0xFFEAF4FE)
private val CONFIRMED_GREEN = Color(0xFF188038)
private val FAILED_RED = Color(0xFFC5221F)

/** Distance from the anchor card's top edge up to the bubble's bottom edge. */
private val BUBBLE_LIFT = 58.dp

/** How long the "Saved" confirmation lingers before the bubble dismisses itself. */
private const val CONFIRM_LINGER_MS = 900L

/** Bubble lifecycle: prompt → saving → saved (auto-dismiss) or failed (retry). */
private enum class SaveState { PROMPT, SAVING, SAVED, FAILED }

/**
 * The modern save element for a route card: a floating bubble anchored just
 * above the card, showing the route metrics and a bookmark action.
 *
 * The bookmark reflects the REAL save outcome: [onSave] is suspending and
 * returns whether the row was persisted. Only a `true` result flips the bubble
 * to the green "Saved" confirmation; a failure keeps the bubble open with a
 * red message so the user can retry instead of believing a save happened that
 * silently stored nothing. The action is disabled while a save is in flight,
 * which also prevents double-tap duplicate rows. Long-press opens the bubble
 * ([visible]); tapping anywhere outside dismisses immediately.
 *
 * Stateless by design: the caller owns [visible] so recomposition of the
 * surrounding card never resurrects a dismissed bubble.
 */
@Composable
fun SaveRouteBubble(
    visible: Boolean,
    metrics: String,
    onSave: suspend () -> Boolean,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    var state by remember { mutableStateOf(SaveState.PROMPT) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(visible) {
        if (visible) state = SaveState.PROMPT
    }
    LaunchedEffect(state) {
        if (state == SaveState.SAVED) {
            delay(CONFIRM_LINGER_MS)
            onDismiss()
        }
    }

    val liftPx = with(LocalDensity.current) { BUBBLE_LIFT.roundToPx() }
    Popup(
        alignment = Alignment.TopCenter,
        offset = IntOffset(0, -liftPx),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color.White,
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
        ) {
            Crossfade(
                targetState = state,
                label = "saveRouteBubble",
            ) { current ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                ) {
                    when (current) {
                        SaveState.SAVED -> {
                            CheckIcon(tint = CONFIRMED_GREEN)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Saved",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = CONFIRMED_GREEN,
                            )
                        }
                        else -> {
                            Column {
                                Text(
                                    text = when (current) {
                                        SaveState.FAILED -> "Couldn't save this route"
                                        else -> "Save this route"
                                    },
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (current == SaveState.FAILED) FAILED_RED else Color(0xFF303030),
                                )
                                Text(
                                    text = metrics,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF8A000000),
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            SaveBookmarkButton(
                                enabled = current != SaveState.SAVING,
                                onClick = {
                                    state = SaveState.SAVING
                                    scope.launch {
                                        state = if (onSave()) SaveState.SAVED else SaveState.FAILED
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Circular accent button holding the bookmark glyph; a spinner while saving. */
@Composable
private fun SaveBookmarkButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(if (enabled) SAVE_ACTION_BACKGROUND else Color(0xFFF0F0F0))
            .semantics { contentDescription = "Save route" }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
    ) {
        if (enabled) {
            BookmarkIcon(filled = false, tint = SAVE_ACTION_TINT)
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = SAVE_ACTION_TINT,
            )
        }
    }
}
