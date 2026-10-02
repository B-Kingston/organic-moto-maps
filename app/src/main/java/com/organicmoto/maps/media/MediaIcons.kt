package com.organicmoto.maps.media

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Hand-drawn Canvas media glyphs, matching the app's icon style (no image
 * assets, no material icons). All take an explicit colour so the B&W ride mode
 * can force a monochrome palette.
 */

@Composable
internal fun MediaNoteIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(24.dp)) {
        val u = size.width / 24f
        val stroke = Stroke(width = 1.9f * u, cap = StrokeCap.Round)
        drawLine(color, Offset(14.5f * u, 5f * u), Offset(14.5f * u, 17f * u),
            strokeWidth = stroke.width, cap = StrokeCap.Round)
        drawCircle(color, radius = 3.1f * u, center = Offset(11.6f * u, 17.4f * u))
        drawPath(
            Path().apply {
                moveTo(14.5f * u, 5f * u)
                cubicTo(17.5f * u, 5.2f * u, 19.6f * u, 6.4f * u, 20f * u, 9.6f * u)
            },
            color,
            style = stroke,
        )
    }
}

@Composable
internal fun PlayPauseGlyph(playing: Boolean, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(24.dp)) {
        val u = size.width / 24f
        if (playing) {
            drawRoundRect(
                color = color,
                topLeft = Offset(7.5f * u, 5.5f * u),
                size = Size(3.6f * u, 13f * u),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.2f * u),
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(13.4f * u, 5.5f * u),
                size = Size(3.6f * u, 13f * u),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.2f * u),
            )
        } else {
            drawPath(
                Path().apply {
                    moveTo(8f * u, 5.4f * u)
                    lineTo(18.6f * u, 12f * u)
                    lineTo(8f * u, 18.6f * u)
                    close()
                },
                color,
            )
        }
    }
}

@Composable
internal fun SkipGlyph(forward: Boolean, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(24.dp)) {
        val u = size.width / 24f
        val direction = if (forward) 1f else -1f
        val edgeX = if (forward) 18.5f else 5.5f
        val innerX = if (forward) 6.5f else 17.5f
        val barX = if (forward) 19.4f else 4.6f
        drawPath(
            Path().apply {
                moveTo(innerX * u, 6f * u)
                lineTo(edgeX * u, 12f * u)
                lineTo(innerX * u, 18f * u)
                close()
            },
            color,
        )
        drawLine(
            color,
            Offset(barX * u, 6f * u),
            Offset(barX * u, 18f * u),
            strokeWidth = 2.2f * u,
            cap = StrokeCap.Round,
        )
        // Mark the unused direction faintly so the pair reads as skip controls.
        val ghostX = if (forward) 3.2f else 20.8f
        drawLine(
            color.copy(alpha = color.alpha * 0.35f),
            Offset(ghostX * u, 8f * u),
            Offset(ghostX * u, 16f * u),
            strokeWidth = 1.6f * u,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
internal fun VolumeGlyph(up: Boolean, color: Color, enabled: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier.size(24.dp)) {
        val u = size.width / 24f
        val alpha = if (enabled) 1f else 0.38f
        val tint = color.copy(alpha = color.alpha * alpha)
        val speaker = Path().apply {
            moveTo(3.5f * u, 9f * u)
            lineTo(7f * u, 9f * u)
            lineTo(11.5f * u, 5.5f * u)
            lineTo(11.5f * u, 18.5f * u)
            lineTo(7f * u, 15f * u)
            lineTo(3.5f * u, 15f * u)
            close()
        }
        drawPath(speaker, tint)
        val stroke = Stroke(width = 1.9f * u, cap = StrokeCap.Round)
        if (up) {
            drawArc(
                tint,
                startAngle = -45f, sweepAngle = 90f, useCenter = false,
                topLeft = Offset(11f * u, 6f * u),
                size = Size(11f * u, 11f * u),
                style = stroke,
            )
            drawLine(tint, Offset(17.5f * u, 12f * u), Offset(22.5f * u, 12f * u),
                strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawLine(tint, Offset(20f * u, 9.5f * u), Offset(20f * u, 14.5f * u),
                strokeWidth = stroke.width, cap = StrokeCap.Round)
        } else {
            drawArc(
                tint,
                startAngle = -45f, sweepAngle = 90f, useCenter = false,
                topLeft = Offset(11f * u, 6f * u),
                size = Size(11f * u, 11f * u),
                style = stroke,
            )
            drawLine(tint, Offset(16.5f * u, 12f * u), Offset(21.5f * u, 12f * u),
                strokeWidth = stroke.width, cap = StrokeCap.Round)
        }
    }
}

@Composable
internal fun CloseGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(24.dp)) {
        val u = size.width / 24f
        val stroke = 2f * u
        drawLine(color, Offset(7f * u, 7f * u), Offset(17f * u, 17f * u),
            strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(17f * u, 7f * u), Offset(7f * u, 17f * u),
            strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

/** Small horizontal level bar used by the volume readout. */
internal fun DrawScope.drawVolumeLevel(fraction: Float, color: Color, disabled: Boolean) {
    val u = size.height
    val trackHeight = (u * 0.28f).coerceAtLeast(4f)
    val left = 0f
    val width = size.width
    val top = (size.height - trackHeight) / 2f
    val radius = androidx.compose.ui.geometry.CornerRadius(trackHeight / 2f)
    drawRoundRect(
        color = color.copy(alpha = if (disabled) 0.22f else 0.28f),
        topLeft = Offset(left, top),
        size = Size(width, trackHeight),
        cornerRadius = radius,
    )
    drawRoundRect(
        color = color.copy(alpha = if (disabled) 0.42f else 0.95f),
        topLeft = Offset(left, top),
        size = Size(width * fraction.coerceIn(0f, 1f), trackHeight),
        cornerRadius = radius,
    )
}
