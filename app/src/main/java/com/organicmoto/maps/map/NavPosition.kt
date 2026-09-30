package com.organicmoto.maps.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.hypot
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

/**
 * Guidance rider image: a freestanding faceted chevron that points along the
 * rider's course. It replaces the disc-plus-arrow glyph for navigation only;
 * ordinary location tracking keeps the [NAV_FOLLOW_IMAGE_ID] look.
 */
internal const val NAV_CHEVRON_IMAGE_ID = "nav-guidance-chevron"

/**
 * Guidance marker footprint, proportioned from the navigation reference
 * (104 x 96) and expressed in dp so every screen density gets the same
 * physical marker size.
 */
internal const val NAV_CHEVRON_WIDTH_DP = 44f
internal const val NAV_CHEVRON_HEIGHT_DP = 41f

/** Transparent space around the chevron that carries its contact shadow. */
private const val CHEVRON_PADDING_DP = 3f

/** Where the centre notch rises from the swept wing line. */
private const val CHEVRON_NOTCH_FRACTION = 0.52f

/** Narrow lower bevel facets along the bottom edge of each face. */
private const val CHEVRON_BEVEL_FRACTION = 0.10f

private val CHEVRON_LEFT_FACE_COLOR = 0xFF29B6F6.toInt()
private val CHEVRON_RIGHT_FACE_COLOR = 0xFF1D4ED8.toInt()
private val CHEVRON_BEVEL_COLOR = 0xFF0B2B6B.toInt()
private const val CHEVRON_SHADOW_COLOR = 0x40000000

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
 * Guidance variant of [updateNavPosition]: the faceted chevron rider marker,
 * drawn at the given display [density] so its physical size is screen
 * independent.
 */
fun MapLibreMap.updateGuidancePosition(
    lat: Double,
    lon: Double,
    bearingDeg: Double,
    density: Float,
) {
    updateNavMarker(lat, lon, bearingDeg, guidance = true, density = density)
}

private fun MapLibreMap.updateNavMarker(
    lat: Double,
    lon: Double,
    bearingDeg: Double,
    guidance: Boolean,
    density: Float,
) {
    if (lat.isNaN() || lon.isNaN()) return
    val imageId = if (guidance) NAV_CHEVRON_IMAGE_ID else NAV_FOLLOW_IMAGE_ID
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
                if (guidance) buildGuidanceChevronBitmap(density) else buildFollowMarkerBitmap(),
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

/** Draws the faceted guidance chevron at [density] scale. */
internal fun buildGuidanceChevronBitmap(density: Float): Bitmap {
    val scale = if (density.isFinite() && density > 0f) density else 1f
    val padding = CHEVRON_PADDING_DP * scale
    val contentWidth = NAV_CHEVRON_WIDTH_DP * scale
    val contentHeight = NAV_CHEVRON_HEIGHT_DP * scale
    val width = (contentWidth + padding * 2f).roundToInt().coerceAtLeast(1)
    val height = (contentHeight + padding * 2f).roundToInt().coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    // MapLibre derives an added image's pixel ratio from its bitmap density,
    // so tagging the bitmap keeps the marker's physical (dp) size constant.
    bitmap.density = (scale * 160f).roundToInt().coerceAtLeast(1)
    val canvas = Canvas(bitmap)
    val left = padding
    val top = padding
    val right = width - padding
    val bottom = height - padding
    val centerX = (left + right) / 2f
    val notchY = top + contentHeight * CHEVRON_NOTCH_FRACTION

    drawContactShadow(canvas, centerX, top, contentWidth, contentHeight)

    val apex = PointF(centerX, top)
    val leftWing = PointF(left, bottom)
    val notch = PointF(centerX, notchY)
    val rightWing = PointF(right, bottom)

    drawChevronFace(canvas, apex, leftWing, notch, CHEVRON_LEFT_FACE_COLOR, bottom - top)
    drawChevronFace(canvas, apex, rightWing, notch, CHEVRON_RIGHT_FACE_COLOR, bottom - top)
    return bitmap
}

/** Soft radial contact shadow under the chevron; fades out inside the bitmap. */
private fun drawContactShadow(
    canvas: Canvas,
    centerX: Float,
    top: Float,
    contentWidth: Float,
    contentHeight: Float,
) {
    val centerY = top + contentHeight * 0.62f
    val radius = contentWidth * 0.58f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            centerX,
            centerY,
            radius,
            intArrayOf(CHEVRON_SHADOW_COLOR, 0x00000000),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
    }
    canvas.drawCircle(centerX, centerY, radius, paint)
}

/**
 * One folded face of the chevron plus its narrow lower bevel. The bevel strip
 * is clipped to the face so the wing tip stays sharp.
 */
private fun drawChevronFace(
    canvas: Canvas,
    apex: PointF,
    wing: PointF,
    notch: PointF,
    faceColor: Int,
    contentHeight: Float,
) {
    val face = Path().apply {
        moveTo(apex.x, apex.y)
        lineTo(wing.x, wing.y)
        lineTo(notch.x, notch.y)
        close()
    }
    canvas.drawPath(face, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = faceColor })

    val offset = bevelOffset(wing, notch, apex, contentHeight)
    val bevel = Path().apply {
        moveTo(wing.x, wing.y)
        lineTo(notch.x, notch.y)
        lineTo(notch.x + offset.first, notch.y + offset.second)
        lineTo(wing.x + offset.first, wing.y + offset.second)
        close()
    }
    canvas.save()
    canvas.clipPath(face)
    canvas.drawPath(
        bevel,
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CHEVRON_BEVEL_COLOR },
    )
    canvas.restore()
}

/**
 * Perpendicular offset from the wing -> notch edge toward the face interior,
 * so the drawn strip is a parallel bevel inside the fold.
 */
private fun bevelOffset(
    wing: PointF,
    notch: PointF,
    apex: PointF,
    contentHeight: Float,
): Pair<Float, Float> {
    val edgeX = notch.x - wing.x
    val edgeY = notch.y - wing.y
    val length = hypot(edgeX, edgeY)
    if (length <= 0f) return 0f to 0f
    var normalX = edgeY / length
    var normalY = -edgeX / length
    val midX = (wing.x + notch.x) / 2f
    val midY = (wing.y + notch.y) / 2f
    val towardInterior = (apex.x - midX) * normalX + (apex.y - midY) * normalY
    if (towardInterior < 0f) {
        normalX = -normalX
        normalY = -normalY
    }
    val thickness = contentHeight * CHEVRON_BEVEL_FRACTION
    return normalX * thickness to normalY * thickness
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
