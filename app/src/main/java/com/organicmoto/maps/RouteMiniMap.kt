package com.organicmoto.maps

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.organicmoto.maps.storage.GeoPoint
import com.organicmoto.maps.storage.PolylineCodec

/**
 * Pure projection math behind the saved-route mini-map banner. Fits a
 * polyline into a pixel box, preserving shape: an equirectangular projection
 * whose longitude axis is compressed by cos(mean latitude) so east-west and
 * north-south metres occupy comparable space at riding latitudes.
 *
 * Returns one [Offset] per input point, in draw order, inside
 * `[0,width]x[0,height]` with [paddingPx] margins on the tight axes.
 */
object MiniMapLayout {

    /**
     * Degenerate inputs collapse gracefully: an empty list maps to an empty
     * list, and a zero-extent route (single point, or all points equal)
     * centres every point at the middle of the box. Padding larger than half
     * the box collapses the usable area the same way rather than throwing.
     */
    fun fit(
        points: List<GeoPoint>,
        widthPx: Float,
        heightPx: Float,
        paddingPx: Float,
    ): List<Offset> {
        if (points.isEmpty()) return emptyList()
        var latMin = Double.MAX_VALUE
        var latMax = -Double.MAX_VALUE
        var lonMin = Double.MAX_VALUE
        var lonMax = -Double.MAX_VALUE
        for (point in points) {
            if (point.lat < latMin) latMin = point.lat
            if (point.lat > latMax) latMax = point.lat
            if (point.lon < lonMin) lonMin = point.lon
            if (point.lon > lonMax) lonMax = point.lon
        }
        val latCenter = (latMin + latMax) / 2.0
        val lonCenter = (lonMin + lonMax) / 2.0
        // Longitude degrees shrink with latitude; clamp away from zero so a
        // hypothetical polar route degrades to a flat projection, not NaNs.
        val longitudeScale = Math.cos(Math.toRadians(latCenter)).coerceIn(0.05, 1.0)
        val xSpan = (lonMax - lonMin) * longitudeScale
        val ySpan = latMax - latMin
        val usableWidth = (widthPx - 2 * paddingPx).coerceAtLeast(0f)
        val usableHeight = (heightPx - 2 * paddingPx).coerceAtLeast(0f)

        var scale = Double.POSITIVE_INFINITY
        if (xSpan > 0.0) scale = minOf(scale, usableWidth / xSpan)
        if (ySpan > 0.0) scale = minOf(scale, usableHeight / ySpan)
        if (scale.isInfinite()) {
            // Nothing varies (or no room): every point sits dead centre.
            return List(points.size) { Offset(widthPx / 2f, heightPx / 2f) }
        }

        return points.map { point ->
            Offset(
                x = (widthPx / 2f + (point.lon - lonCenter) * longitudeScale * scale).toFloat(),
                y = (heightPx / 2f - (point.lat - latCenter) * scale).toFloat(),
            )
        }
    }
}

private val BANNER_BACKGROUND = Color(0xFFE8EDF2)
private val ROUTE_STROKE = Color(0xFF249CF2)
private val START_MARKER = Color(0xFF1E96F0)
private val END_MARKER = Color(0xFF303030)

/**
 * Small static map banner for a saved route: the stored polyline stroked on
 * a muted panel with start and finish dots. Draws entirely from the decoded
 * [geometry] — no tiles, no network, safe inside a scrolling list.
 */
@Composable
fun RouteMiniMap(geometry: String, modifier: Modifier = Modifier) {
    val points = remember(geometry) { PolylineCodec.decode(geometry) }
    Canvas(modifier = modifier) {
        drawRect(color = BANNER_BACKGROUND)
        if (points.isEmpty()) return@Canvas
        val fitted = MiniMapLayout.fit(points, size.width, size.height, 5.dp.toPx())
        if (fitted.size == 1) {
            drawCircle(color = END_MARKER, radius = 3.dp.toPx(), center = fitted.first())
            return@Canvas
        }
        val path = Path()
        fitted.forEachIndexed { index, offset ->
            if (index == 0) path.moveTo(offset.x, offset.y) else path.lineTo(offset.x, offset.y)
        }
        drawPath(
            path = path,
            color = ROUTE_STROKE,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        drawCircle(color = START_MARKER, radius = 3.dp.toPx(), center = fitted.first())
        drawCircle(color = END_MARKER, radius = 3.dp.toPx(), center = fitted.last())
    }
}
