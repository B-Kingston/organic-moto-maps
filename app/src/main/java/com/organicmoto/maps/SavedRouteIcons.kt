package com.organicmoto.maps

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Hand-drawn vector icons for the route-storage UI. The app ships no icon
 * artifact (see SettingsCogIcon, StartDotIcon, FinishFlagIcon), so these
 * follow the same Canvas convention and stay dependency-free.
 */

private val ICON_TINT = Color(0xFF616161)

/** Classic bookmark ribbon; [filled] swaps the saved state to solid. */
@Composable
fun BookmarkIcon(filled: Boolean, tint: Color = ICON_TINT, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val width = size.width
        val height = size.height
        val path = Path().apply {
            moveTo(width * 0.25f, height * 0.12f)
            lineTo(width * 0.75f, height * 0.12f)
            lineTo(width * 0.75f, height * 0.88f)
            lineTo(width * 0.5f, height * 0.68f)
            lineTo(width * 0.25f, height * 0.88f)
            close()
        }
        if (filled) {
            drawPath(path, color = tint)
        } else {
            drawPath(
                path,
                color = tint,
                style = Stroke(width = 2.dp.toPx(), join = StrokeJoin.Round),
            )
        }
    }
}

/** Waste-basket: lid line, hanging handle, tapered body. */
@Composable
fun TrashIcon(tint: Color = ICON_TINT, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val width = size.width
        val height = size.height
        val lidY = height * 0.26f
        val bodyTop = lidY + height * 0.08f
        val bottom = height * 0.9f
        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawLine(
            color = tint,
            start = Offset(width * 0.18f, lidY),
            end = Offset(width * 0.82f, lidY),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(width * 0.5f, height * 0.1f),
            end = Offset(width * 0.5f, lidY),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
        val body = Path().apply {
            moveTo(width * 0.26f, bodyTop)
            lineTo(width * 0.33f, bottom)
            lineTo(width * 0.67f, bottom)
            lineTo(width * 0.74f, bodyTop)
        }
        drawPath(body, color = tint, style = stroke)
        drawLine(
            color = tint,
            start = Offset(width * 0.5f, bodyTop + height * 0.06f),
            end = Offset(width * 0.5f, bottom - height * 0.08f),
            strokeWidth = 1.5f.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

/** Round-cap check mark, used for confirmations and comment submission. */
@Composable
fun CheckIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val width = size.width
        val height = size.height
        drawLine(
            color = tint,
            start = Offset(width * 0.2f, height * 0.55f),
            end = Offset(width * 0.42f, height * 0.78f),
            strokeWidth = 2.4f.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(width * 0.42f, height * 0.78f),
            end = Offset(width * 0.82f, height * 0.24f),
            strokeWidth = 2.4f.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

/** Small speech bubble for comment-count badges. */
@Composable
fun CommentBubbleIcon(tint: Color = ICON_TINT, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val width = size.width
        val height = size.height
        val path = Path().apply {
            addRoundRect(
                RoundRect(
                    left = 0f,
                    top = 0f,
                    right = width,
                    bottom = height * 0.72f,
                    cornerRadius = CornerRadius(width * 0.3f, width * 0.3f),
                )
            )
            moveTo(width * 0.3f, height * 0.68f)
            lineTo(width * 0.3f, height * 0.95f)
            lineTo(width * 0.55f, height * 0.72f)
        }
        drawPath(path, color = tint, style = Stroke(width = 1.6f.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** Downward chevron; callers rotate the modifier for the expanded state. */
@Composable
fun ChevronIcon(tint: Color = ICON_TINT, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val width = size.width
        val height = size.height
        drawLine(
            color = tint,
            start = Offset(width * 0.25f, height * 0.38f),
            end = Offset(width * 0.5f, height * 0.62f),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(width * 0.5f, height * 0.62f),
            end = Offset(width * 0.75f, height * 0.38f),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}
