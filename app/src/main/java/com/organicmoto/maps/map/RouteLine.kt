package com.organicmoto.maps.map

import android.graphics.PointF
import android.graphics.RectF
import com.graphhopper.ResponsePath
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

private const val ROUTE_SOURCE_ID = "route"
private const val ROUTE_LINE_ID = "route-line"
private const val ROUTE_CASING_ID = "route-casing"
private const val ROUTE_HIT_ID = "route-hit"
private const val ROUTE_INDEX_PROPERTY = "route_index"
private const val ROUTE_COLOR_PROPERTY = "route_color"
private const val ROUTE_OPACITY_PROPERTY = "route_opacity"
private const val ROUTE_CASING_OPACITY_PROPERTY = "route_casing_opacity"
private const val ROUTE_WIDTH_PROPERTY = "route_width"
private const val ROUTE_CASING_WIDTH_PROPERTY = "route_casing_width"
private const val ROUTE_HIT_WIDTH_PROPERTY = "route_hit_width"
private const val ROUTE_SORT_PROPERTY = "route_sort"
private const val ROUTE_CASING_COLOR = "#FFFFFF"
private const val ROUTE_CASING_OPACITY = 0.82f
private const val ROUTE_HIT_OPACITY = 0.01f
private const val ROUTE_HIT_WIDTH = 22f
private const val ROUTE_SELECTED_WIDTH = 8f
private const val ROUTE_UNSELECTED_WIDTH = 5f
private const val ROUTE_SELECTED_OPACITY = 1f
private const val ROUTE_UNSELECTED_OPACITY = 0.68f
private const val ROUTE_SELECTED_SORT = 2f
private const val ROUTE_UNSELECTED_SORT = 1f
private const val ROUTE_SELECTED_CASING_WIDTH = 11f
private const val ROUTE_UNSELECTED_CASING_WIDTH = 8f
private const val FIT_PADDING = 100

private val ROUTE_COLORS = listOf("#FF5500", "#00897B", "#6750A4")

internal fun routeColorHex(index: Int): String =
    ROUTE_COLORS[index.coerceIn(0, ROUTE_COLORS.lastIndex)]

private fun LineLayer.setRouteProperties(
    color: Expression,
    opacity: Expression,
    width: Expression,
    sort: Expression,
) {
    setProperties(
        PropertyFactory.lineColor(color),
        PropertyFactory.lineOpacity(opacity),
        PropertyFactory.lineWidth(width),
        PropertyFactory.lineSortKey(sort),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
    )
}

private fun casingLayer(): LineLayer =
    LineLayer(ROUTE_CASING_ID, ROUTE_SOURCE_ID).withProperties(
        PropertyFactory.lineColor(ROUTE_CASING_COLOR),
        PropertyFactory.lineOpacity(Expression.get(ROUTE_CASING_OPACITY_PROPERTY)),
        PropertyFactory.lineWidth(Expression.get(ROUTE_CASING_WIDTH_PROPERTY)),
        PropertyFactory.lineSortKey(Expression.get(ROUTE_SORT_PROPERTY)),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
    )

private fun visualLayer(): LineLayer =
    LineLayer(ROUTE_LINE_ID, ROUTE_SOURCE_ID).withProperties(
        PropertyFactory.lineColor(Expression.get(ROUTE_COLOR_PROPERTY)),
        PropertyFactory.lineOpacity(Expression.get(ROUTE_OPACITY_PROPERTY)),
        PropertyFactory.lineWidth(Expression.get(ROUTE_WIDTH_PROPERTY)),
        PropertyFactory.lineSortKey(Expression.get(ROUTE_SORT_PROPERTY)),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
    )

private fun hitLayer(): LineLayer =
    LineLayer(ROUTE_HIT_ID, ROUTE_SOURCE_ID).withProperties(
        PropertyFactory.lineColor(Expression.get(ROUTE_COLOR_PROPERTY)),
        PropertyFactory.lineOpacity(ROUTE_HIT_OPACITY),
        PropertyFactory.lineWidth(Expression.get(ROUTE_HIT_WIDTH_PROPERTY)),
        PropertyFactory.lineSortKey(Expression.get(ROUTE_SORT_PROPERTY)),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
    )

fun MapLibreMap.drawRoutes(routes: List<ResponsePath>, focusedIndex: Int) {
    if (routes.isEmpty()) {
        clearRoutes()
        return
    }
    val safeFocusedIndex = focusedIndex.coerceIn(0, routes.lastIndex)
    val features = routes.mapIndexed { index, path ->
        val selected = index == safeFocusedIndex
        val line = LineString.fromLngLats(
            path.points.map { Point.fromLngLat(it.lon, it.lat) }
        )
        Feature.fromGeometry(line).apply {
            addNumberProperty(ROUTE_INDEX_PROPERTY, index)
            addStringProperty(ROUTE_COLOR_PROPERTY, routeColorHex(index))
            addNumberProperty(
                ROUTE_OPACITY_PROPERTY,
                if (selected) ROUTE_SELECTED_OPACITY else ROUTE_UNSELECTED_OPACITY,
            )
            addNumberProperty(ROUTE_CASING_OPACITY_PROPERTY, ROUTE_CASING_OPACITY)
            addNumberProperty(
                ROUTE_WIDTH_PROPERTY,
                if (selected) ROUTE_SELECTED_WIDTH else ROUTE_UNSELECTED_WIDTH,
            )
            addNumberProperty(
                ROUTE_CASING_WIDTH_PROPERTY,
                if (selected) ROUTE_SELECTED_CASING_WIDTH else ROUTE_UNSELECTED_CASING_WIDTH,
            )
            addNumberProperty(ROUTE_HIT_WIDTH_PROPERTY, ROUTE_HIT_WIDTH)
            addNumberProperty(
                ROUTE_SORT_PROPERTY,
                if (selected) ROUTE_SELECTED_SORT else ROUTE_UNSELECTED_SORT,
            )
        }
    }
    val collection = FeatureCollection.fromFeatures(features)
    getStyle { style ->
        val source = style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE_ID)
        if (source != null) {
            source.setGeoJson(collection)
        } else {
            style.addSource(GeoJsonSource(ROUTE_SOURCE_ID).apply { setGeoJson(collection) })
        }

        val casing = style.getLayerAs<LineLayer>(ROUTE_CASING_ID)
        if (casing == null) {
            style.addLayer(casingLayer())
        } else {
            casing.setRouteProperties(
                Expression.literal(ROUTE_CASING_COLOR),
                Expression.get(ROUTE_CASING_OPACITY_PROPERTY),
                Expression.get(ROUTE_CASING_WIDTH_PROPERTY),
                Expression.get(ROUTE_SORT_PROPERTY),
            )
        }

        val visual = style.getLayerAs<LineLayer>(ROUTE_LINE_ID)
        if (visual == null) {
            style.addLayer(visualLayer())
        } else {
            visual.setRouteProperties(
                Expression.get(ROUTE_COLOR_PROPERTY),
                Expression.get(ROUTE_OPACITY_PROPERTY),
                Expression.get(ROUTE_WIDTH_PROPERTY),
                Expression.get(ROUTE_SORT_PROPERTY),
            )
        }

        val hit = style.getLayerAs<LineLayer>(ROUTE_HIT_ID)
        if (hit == null) {
            style.addLayer(hitLayer())
        } else {
            hit.setRouteProperties(
                Expression.get(ROUTE_COLOR_PROPERTY),
                Expression.literal(ROUTE_HIT_OPACITY),
                Expression.get(ROUTE_HIT_WIDTH_PROPERTY),
                Expression.get(ROUTE_SORT_PROPERTY),
            )
        }
    }
}

fun MapLibreMap.clearRoutes() {
    getStyle { style ->
        style.removeLayer(ROUTE_HIT_ID)
        style.removeLayer(ROUTE_LINE_ID)
        style.removeLayer(ROUTE_CASING_ID)
        style.removeSource(ROUTE_SOURCE_ID)
    }
}

fun MapLibreMap.fitBounds(paths: Iterable<ResponsePath>) {
    val builder = LatLngBounds.Builder()
    var hasPoint = false
    paths.forEach { path ->
        path.points.forEach { point ->
            builder.include(LatLng(point.lat, point.lon))
            hasPoint = true
        }
    }
    if (hasPoint) animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), FIT_PADDING))
}

fun MapLibreMap.findRouteIndexAt(screenPoint: PointF, hitRadiusPx: Float): Int? = runCatching {
    val bounds = RectF(
        screenPoint.x - hitRadiusPx,
        screenPoint.y - hitRadiusPx,
        screenPoint.x + hitRadiusPx,
        screenPoint.y + hitRadiusPx,
    )
    queryRenderedFeatures(bounds, ROUTE_HIT_ID, ROUTE_LINE_ID)
        .mapNotNull { feature ->
            if (!feature.hasProperty(ROUTE_INDEX_PROPERTY)) return@mapNotNull null
            val routeIndex = feature.getNumberProperty(ROUTE_INDEX_PROPERTY)?.toInt()
                ?: return@mapNotNull null
            val sort = feature.getNumberProperty(ROUTE_SORT_PROPERTY)?.toDouble() ?: 0.0
            routeIndex to sort
        }
        .maxByOrNull { it.second }
        ?.first
}.getOrNull()
