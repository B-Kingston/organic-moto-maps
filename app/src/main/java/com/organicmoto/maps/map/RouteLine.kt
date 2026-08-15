package com.organicmoto.maps.map

import com.graphhopper.ResponsePath
import com.graphhopper.util.shapes.GHPoint3D
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

private const val ROUTE_SOURCE_ID = "route"
private const val ROUTE_LINE_ID = "route-line"
private const val ROUTE_COLOR = "#FF5500"
private const val ROUTE_WIDTH = 5f
private const val FIT_PADDING = 100

fun MapLibreMap.drawRoute(path: ResponsePath) {
    val line = LineString.fromLngLats(path.points.map { Point.fromLngLat(it.lon, it.lat) })
    getStyle { style ->
        val feature = Feature.fromGeometry(line)
        val existing = style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE_ID)
        if (existing != null) {
            existing.setGeoJson(feature)
        } else {
            style.addSource(GeoJsonSource(ROUTE_SOURCE_ID).apply { setGeoJson(feature) })
        }
        if (style.getLayer(ROUTE_LINE_ID) == null) {
            style.addLayer(
                LineLayer(ROUTE_LINE_ID, ROUTE_SOURCE_ID).withProperties(
                    PropertyFactory.lineColor(ROUTE_COLOR),
                    PropertyFactory.lineWidth(ROUTE_WIDTH)
                )
            )
        }
    }
}

fun MapLibreMap.fitBounds(points: Iterable<GHPoint3D>) {
    if (!points.any()) return
    val builder = LatLngBounds.Builder()
    points.forEach { builder.include(LatLng(it.lat, it.lon)) }
    animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), FIT_PADDING))
}
