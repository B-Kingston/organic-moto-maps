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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

private val SAVE_ACTION_TINT = Color(0xFF249CF2)
private val SAVE_ACTION_BACKGROUND = Color(0xFFEAF4FE)
private val CONFIRMED_GREEN = Color(0xFF188038)

/**
 * Distance from the anchor card's top edge up to the bubble's bottom edge.
 * Covers one bubble height (~52 dp) plus a small gap.
 */
private val BUBBLE_LIFT = 58.dp

/** How long the "Saved" confirmation lingers before the bubble dismisses itself. */
private const val CONFIRM_LINGER_MS = 900L

/**
 * The modern save element for a route card: a floating bubble anchored just
 * above the card, showing the route metrics and a bookmark action. Long-press
 * opens it ([visible]); tapping the bookmark saves and flips the bubble to a
 * brief "Saved" confirmation before dismissing through [onDismiss]. Tapping
 * anywhere outside dismisses immediately.
 *
 * Stateless by design: the caller owns [visible] so recomposition of the
 * surrounding card never resurrects a dismissed bubble.
 */
@Composable
fun SaveRouteBubble(
    visible: Boolean,
    metrics: String,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    var confirmed by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        if (visible) confirmed = false
    }
    LaunchedEffect(confirmed) {
        if (confirmed) {
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
                targetState = confirmed,
                label = "saveRouteBubble",
            ) { isConfirmed ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                ) {
                    if (isConfirmed) {
                        CheckIcon(tint = CONFIRMED_GREEN)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Saved",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = CONFIRMED_GREEN,
                        )
                    } else {
                        Column {
                            Text(
                                text = "Save this route",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF303030),
                            )
                            Text(
                                text = metrics,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF8A000000),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        SaveBookmarkButton(onClick = {
                            onSave()
                            confirmed = true
                        })
                    }
                }
            }
        }
    }
}

/** Circular accent button holding the bookmark glyph. */
@Composable
private fun SaveBookmarkButton(onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(SAVE_ACTION_BACKGROUND)
            .semantics { contentDescription = "Save route" }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        BookmarkIcon(filled = false, tint = SAVE_ACTION_TINT)
    }
}
