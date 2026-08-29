package com.organicmoto.maps.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point

private const val POSITION_SOURCE_ID = "nav-position"
private const val POSITION_LAYER_ID = "nav-position-dot"
private const val POSITION_BEARING_PROPERTY = "nav_bearing"

/**
 * The guidance position marker: one GeoJSON point source plus a symbol layer
 * whose icon rotates with the display bearing. The dot is drawn only while
 * navigation is active and a fix exists; [hidePosition] removes it.
 *
 * Layers are created lazily inside the style callback, matching the pattern
 * used by [drawRoutes], so the calls are safe before the style loads.
 */
fun MapLibreMap.updateNavPosition(lat: Double, lon: Double, bearingDeg: Double) {
    if (lat.isNaN() || lon.isNaN()) return
    getStyle { style ->
        val feature = Feature.fromGeometry(Point.fromLngLat(lon, lat)).apply {
            addNumberProperty(POSITION_BEARING_PROPERTY, if (bearingDeg.isNaN()) 0f else bearingDeg.toFloat())
        }
        val source = style.getSourceAs<GeoJsonSource>(POSITION_SOURCE_ID)
        if (source != null) {
            source.setGeoJson(feature)
        } else {
            style.addSource(GeoJsonSource(POSITION_SOURCE_ID).apply { setGeoJson(feature) })
        }
        if (style.getImage(POSITION_IMAGE_ID) == null) {
            style.addImage(POSITION_IMAGE_ID, buildNavArrowBitmap())
        }
        if (style.getLayer(POSITION_LAYER_ID) == null) {
            style.addLayer(
                SymbolLayer(POSITION_LAYER_ID, POSITION_SOURCE_ID).withProperties(
                    PropertyFactory.iconImage(POSITION_IMAGE_ID),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                ),
            )
        }
    }
}

private const val POSITION_IMAGE_ID = "nav-position-arrow"

/**
 * Draws the navigation arrow bitmap (a filled chevron in a translucent
 * disc) the same way the app's other icons are hand-drawn: plain Canvas
 * primitives, no asset dependency.
 */
private fun buildNavArrowBitmap(): Bitmap {
    val size = 48
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val center = size / 2f
    val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1565C0.toInt()
        style = Paint.Style.FILL
    }
    canvas.drawCircle(center, center, center - 1f, disc)
    val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
    }
    val path = android.graphics.Path().apply {
        moveTo(center, 8f)
        lineTo(center + 11f, center + 12f)
        lineTo(center, center + 5f)
        lineTo(center - 11f, center + 12f)
        close()
    }
    canvas.drawPath(path, arrow)
    return bitmap
}

fun MapLibreMap.hideNavPosition() {
    getStyle { style ->
        style.removeLayer(POSITION_LAYER_ID)
        style.removeSource(POSITION_SOURCE_ID)
    }
}
