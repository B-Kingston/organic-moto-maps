package com.organicmoto.maps.map

import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point

private const val GPX_PREVIEW_SOURCE_ID = "gpx-preview-point"
private const val GPX_PREVIEW_CASING_ID = "gpx-preview-point-casing"
private const val GPX_PREVIEW_DOT_ID = "gpx-preview-point-dot"

/** Shows the milestone currently slotted into the centre of the preview list. */
fun MapLibreMap.showGpxPreviewMarker(lat: Double, lon: Double) {
    val feature = Feature.fromGeometry(Point.fromLngLat(lon, lat))
    getStyle { style ->
        val source = style.getSourceAs<GeoJsonSource>(GPX_PREVIEW_SOURCE_ID)
        if (source != null) {
            source.setGeoJson(feature)
        } else {
            style.addSource(GeoJsonSource(GPX_PREVIEW_SOURCE_ID, feature))
        }
        if (style.getLayer(GPX_PREVIEW_CASING_ID) == null) {
            style.addLayer(
                CircleLayer(GPX_PREVIEW_CASING_ID, GPX_PREVIEW_SOURCE_ID).withProperties(
                    PropertyFactory.circleColor("#FFFFFF"),
                    PropertyFactory.circleRadius(11f),
                    PropertyFactory.circleOpacity(0.96f),
                ),
            )
        }
        if (style.getLayer(GPX_PREVIEW_DOT_ID) == null) {
            style.addLayer(
                CircleLayer(GPX_PREVIEW_DOT_ID, GPX_PREVIEW_SOURCE_ID).withProperties(
                    PropertyFactory.circleColor("#249CF2"),
                    PropertyFactory.circleRadius(7f),
                ),
            )
        }
    }
}

fun MapLibreMap.hideGpxPreviewMarker() {
    getStyle { style ->
        style.removeLayer(GPX_PREVIEW_DOT_ID)
        style.removeLayer(GPX_PREVIEW_CASING_ID)
        style.removeSource(GPX_PREVIEW_SOURCE_ID)
    }
}
