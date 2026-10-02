package com.organicmoto.maps.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.LinearGradient
import android.graphics.Shader
import kotlin.math.roundToInt
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.PropertyValue
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point

internal const val NAV_POSITION_SOURCE_ID = "nav-position"
internal const val NAV_POSITION_LAYER_ID = "nav-position-dot"
internal const val NAV_POSITION_BEARING_PROPERTY = "nav_bearing"

/** Follow-mode marker image: the ordinary centre-on-me dot, unchanged. */
internal const val NAV_FOLLOW_IMAGE_ID = "nav-position-arrow"

/** Guidance rider image: the supplied rounded arrow with a raised centre ridge. */
internal const val NAV_CHEVRON_IMAGE_ID = "nav-guidance-chevron"
/** Grayscale counterpart used by the black-and-white ride map. */
internal const val NAV_CHEVRON_MONO_IMAGE_ID = "nav-guidance-chevron-mono"

/** Arrow footprint in dp, including the side walls but not the shadow. */
internal const val NAV_CHEVRON_WIDTH_DP = 38f
internal const val NAV_CHEVRON_HEIGHT_DP = 36f

/** Transparent space around the chevron that carries its contact shadow. */
private const val CHEVRON_PADDING_DP = 4f

/**
 * The guidance position marker: one GeoJSON point source plus a symbol layer
 * whose icon is rotated into map space by the `nav_bearing` feature property.
 * With a heading-up camera the chevron points up along the road; the viewport
 * pitch alignment keeps it upright instead of squashing it into the forward
 * tilt. The marker is drawn only while the screen owns a position;
 * [MapLibreMap.hideNavPosition] removes it, and the style (re)load path
 * rebuilds both image and layer.
 *
 * Layers are created lazily inside the style callback, matching the pattern
 * used by [drawRoutes], so the calls are safe before the style loads.
 */
fun MapLibreMap.updateNavPosition(lat: Double, lon: Double, bearingDeg: Double) {
    updateNavMarker(lat, lon, bearingDeg, guidance = false, density = 0f)
}

/**
 * Guidance variant of [updateNavPosition]: the raised arrow rider marker,
 * drawn at the given display [density] so its physical size is screen
 * independent.
 */
fun MapLibreMap.updateGuidancePosition(
    lat: Double,
    lon: Double,
    bearingDeg: Double,
    density: Float,
    monochrome: Boolean = false,
) {
    updateNavMarker(
        lat,
        lon,
        bearingDeg,
        guidance = true,
        density = density,
        monochrome = monochrome,
    )
}

private fun MapLibreMap.updateNavMarker(
    lat: Double,
    lon: Double,
    bearingDeg: Double,
    guidance: Boolean,
    density: Float,
    monochrome: Boolean = false,
) {
    if (lat.isNaN() || lon.isNaN()) return
    val imageId = when {
        !guidance -> NAV_FOLLOW_IMAGE_ID
        monochrome -> NAV_CHEVRON_MONO_IMAGE_ID
        else -> NAV_CHEVRON_IMAGE_ID
    }
    getStyle { style ->
        val feature = Feature.fromGeometry(Point.fromLngLat(lon, lat)).apply {
            addNumberProperty(
                NAV_POSITION_BEARING_PROPERTY,
                if (bearingDeg.isNaN()) 0f else bearingDeg.toFloat(),
            )
        }
        val source = style.getSourceAs<GeoJsonSource>(NAV_POSITION_SOURCE_ID)
        if (source != null) {
            source.setGeoJson(feature)
        } else {
            style.addSource(GeoJsonSource(NAV_POSITION_SOURCE_ID).apply { setGeoJson(feature) })
        }
        if (style.getImage(imageId) == null) {
            style.addImage(
                imageId,
                if (guidance) {
                    buildGuidanceChevronBitmap(density, monochrome)
                } else {
                    buildFollowMarkerBitmap()
                },
            )
        }
        val layer = style.getLayerAs<SymbolLayer>(NAV_POSITION_LAYER_ID)
        if (layer == null) {
            style.addLayer(buildNavPositionLayer(imageId, guidance))
        } else if (layer.getIconImage().value != imageId) {
            // The layer is shared with ordinary location tracking; switching
            // modes swaps the image and its rotation contract in one update.
            layer.setProperties(*navPositionProperties(imageId, guidance))
        }
    }
}

/** Builds the single marker layer with the property set of its current mode. */
internal fun buildNavPositionLayer(imageId: String, guidance: Boolean): SymbolLayer =
    SymbolLayer(NAV_POSITION_LAYER_ID, NAV_POSITION_SOURCE_ID)
        .withProperties(*navPositionProperties(imageId, guidance))

/**
 * Marker layer contract. Guidance rotates the icon into map space with the
 * rider-course property so a heading-up camera keeps it pointing forward, and
 * aligns it to the viewport plane so the forward pitch does not squash it.
 * Ordinary tracking keeps the legacy screen-upright disc marker.
 */
internal fun navPositionProperties(
    imageId: String,
    guidance: Boolean,
): Array<PropertyValue<*>> = arrayOf(
    PropertyFactory.iconImage(imageId),
    PropertyFactory.iconAllowOverlap(true),
    PropertyFactory.iconIgnorePlacement(true),
    if (guidance) {
        PropertyFactory.iconRotate(Expression.get(NAV_POSITION_BEARING_PROPERTY))
    } else {
        PropertyFactory.iconRotate(0f)
    },
    PropertyFactory.iconRotationAlignment(
        if (guidance) {
            Property.ICON_ROTATION_ALIGNMENT_MAP
        } else {
            Property.ICON_ROTATION_ALIGNMENT_VIEWPORT
        },
    ),
    PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT),
)

/** Draws the raised arrow at [density] scale. */
internal fun buildGuidanceChevronBitmap(density: Float): Bitmap =
    buildGuidanceChevronBitmap(density, monochrome = false)

/** Builds the approved 2.5D artwork. The viewport alignment preserves its depth. */
internal fun buildGuidanceChevronBitmap(density: Float, monochrome: Boolean): Bitmap {
    val scale = if (density.isFinite() && density > 0f) density else 1f
    val width = ((NAV_CHEVRON_WIDTH_DP + CHEVRON_PADDING_DP * 2f) * scale).roundToInt()
    val height = ((NAV_CHEVRON_HEIGHT_DP + CHEVRON_PADDING_DP * 2f) * scale).roundToInt()
    val bitmap = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    bitmap.density = (scale * 160f).roundToInt().coerceAtLeast(1)
    val canvas = Canvas(bitmap)
    val unitX = NAV_CHEVRON_WIDTH_DP / 18.34f * scale
    // Bake the approved oblique view into the icon; do not pitch it a second time.
    val unitY = unitX * 0.7667f
    canvas.translate(CHEVRON_PADDING_DP * scale - 2.83f * unitX, CHEVRON_PADDING_DP * scale - 2f * unitY)
    canvas.scale(unitX, unitY)

    // Original map-arrow-up SVG path, including its rounded corners and rear notch.
    val arrow = Path().apply {
        moveTo(3.16496f, 19.5025f)
        lineTo(10.5275f, 2.99281f)
        cubicTo(11.1178f, 1.66906f, 12.8822f, 1.66906f, 13.4725f, 2.99281f)
        lineTo(20.835f, 19.5025f)
        cubicTo(21.5021f, 20.9984f, 20.0209f, 22.5499f, 18.6331f, 21.809f)
        lineTo(12.7294f, 18.657f)
        cubicTo(12.2702f, 18.4118f, 11.7298f, 18.4118f, 11.2706f, 18.657f)
        lineTo(5.36689f, 21.809f)
        cubicTo(3.97914f, 22.5499f, 2.49789f, 20.9984f, 3.16496f, 19.5025f)
        close()
    }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeJoin = Paint.Join.ROUND }
    canvas.save()
    canvas.translate(0f, 1.7f)
    paint.color = if (monochrome) 0xFF535353.toInt() else 0xFF092D68.toInt()
    paint.setShadowLayer(0.9f, 0f, 0.7f, if (monochrome) 0xD9000000.toInt() else 0x73081323)
    canvas.drawPath(arrow, paint)
    paint.clearShadowLayer()
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = if (monochrome) 1f else 0.16f
    paint.color = if (monochrome) 0xFF101010.toInt() else 0xFF061F49.toInt()
    canvas.drawPath(arrow, paint)
    canvas.restore()
    if (monochrome) canvas.drawPath(arrow, paint)

    paint.style = Paint.Style.FILL
    paint.shader = LinearGradient(
        3f, 2f, 21f, 22f,
        if (monochrome) intArrayOf(0xFFFFFFFF.toInt(), 0xFFBCBCBC.toInt())
        else intArrayOf(0xFF7EE4FF.toInt(), 0xFF28B8F4.toInt(), 0xFF1584DB.toInt()),
        if (monochrome) null else floatArrayOf(0f, 0.5f, 1f),
        Shader.TileMode.CLAMP,
    )
    canvas.drawPath(arrow, paint)
    paint.shader = null
    canvas.save()
    canvas.clipPath(arrow)
    val rightFace = Path().apply {
        moveTo(12f, 1f)
        lineTo(22f, 23f)
        lineTo(12f, 18.46f)
        close()
    }
    paint.color = if (monochrome) 0xFF959595.toInt() else 0xFF1965CF.toInt()
    canvas.drawPath(rightFace, paint)
    if (!monochrome) {
        rightFace.rewind()
        rightFace.moveTo(12f, 2.2f)
        rightFace.lineTo(12f, 18.46f)
        rightFace.lineTo(3f, 22f)
        rightFace.close()
        paint.color = 0x4053CDFF
        canvas.drawPath(rightFace, paint)
    }
    canvas.restore()
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 0.16f
    paint.color = if (monochrome) 0xFFF6F6F6.toInt() else 0xFF97E6FF.toInt()
    canvas.drawPath(arrow, paint)
    paint.strokeWidth = 0.18f
    paint.strokeCap = Paint.Cap.ROUND
    paint.color = if (monochrome) 0xFFFFFFFF.toInt() else 0xFFC4F5FF.toInt()
    canvas.drawLine(12f, 2.55f, 12f, 18.46f, paint)
    return bitmap
}

/**
 * Draws the ordinary follow marker (a chevron in a translucent disc) the same
 * way the app's other icons are hand-drawn: plain Canvas primitives, no asset
 * dependency. Non-navigation tracking keeps this exact look.
 */
internal fun buildFollowMarkerBitmap(): Bitmap {
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
    val path = Path().apply {
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
        style.removeLayer(NAV_POSITION_LAYER_ID)
        style.removeSource(NAV_POSITION_SOURCE_ID)
    }
}
