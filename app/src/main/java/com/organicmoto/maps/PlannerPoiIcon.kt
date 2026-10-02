package com.organicmoto.maps

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp

/**
 * Blue POI pin based on the supplied SVG marker. It marks the planner's
 * current-location shortcut, while the surrounding IconButton provides the
 * glove-sized touch target.
 */
@Composable
internal fun CurrentLocationPoiIcon() {
    Canvas(Modifier.size(24.dp)) {
        val unitScale = size.height / 100f
        val pin = Path().apply {
            moveTo(50.002f, 0f)
            cubicTo(30.763f, 0f, 15f, 15.718f, 15f, 34.902f)
            cubicTo(15f, 42.334f, 17.374f, 49.242f, 21.392f, 54.921f)
            lineTo(45.73f, 96.994f)
            cubicTo(49.139f, 101.447f, 51.405f, 100.601f, 54.24f, 96.759f)
            lineTo(81.083f, 51.076f)
            cubicTo(81.625f, 50.095f, 82.05f, 49.05f, 82.421f, 47.984f)
            cubicTo(84.08f, 44.1f, 85f, 39.63f, 85f, 34.902f)
            cubicTo(85f, 15.718f, 69.24f, 0f, 50.002f, 0f)
            close()
        }
        withTransform({ scale(unitScale, unitScale, pivot = Offset.Zero) }) {
            drawPath(path = pin, color = Color(0xFF249CF2))
        }
        drawCircle(
            color = Color.White,
            radius = 18.597f * unitScale,
            center = Offset(50f * unitScale, 34.902f * unitScale),
        )
    }
}
