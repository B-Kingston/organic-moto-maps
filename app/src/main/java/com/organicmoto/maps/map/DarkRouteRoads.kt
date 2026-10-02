package com.organicmoto.maps.map

import com.graphhopper.ResponsePath
import org.maplibre.android.style.expressions.Expression
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import kotlin.math.pow

/** Matches ride-dark-style.json, including overzoom above the archive's z14 limit. */
internal enum class RideRoadWidth(val layerId: String, val stops: List<Pair<Int, Double>>) {
    SERVICE("ride-service-roads", listOf(13 to 1.5, 14 to 2.5, 16 to 5.0, 18 to 9.0, 20 to 24.0)),
    LOCAL("ride-local-roads", listOf(10 to 1.0, 12 to 2.0, 14 to 4.0, 16 to 9.0, 18 to 18.0, 20 to 48.0)),
    MAJOR("ride-major-roads", listOf(6 to 1.0, 10 to 2.0, 12 to 3.0, 14 to 6.0, 16 to 13.0, 18 to 26.0, 20 to 64.0));

    fun widthAt(zoom: Double): Double {
        if (zoom <= stops.first().first) return stops.first().second
        for ((low, high) in stops.zipWithNext()) {
            if (zoom <= high.first) {
                val fraction = (1.2.pow(zoom - low.first) - 1) / (1.2.pow(high.first - low.first) - 1)
                return low.second + fraction * (high.second - low.second)
            }
        }
        return stops.last().second
    }

    companion object {
        fun forClass(roadClass: String): RideRoadWidth = when (roadClass.lowercase()) {
            "service", "track" -> SERVICE
            "tertiary", "residential", "unclassified", "living_street", "road" -> LOCAL
            // Missing details (e.g. a synthetic route) must still cover the widest road.
            else -> MAJOR
        }
    }
}

/** Road layers queried by the debug renderer probe to verify B&W basemap ink. */
internal val DARK_RIDE_BASEMAP_PROBE_LAYERS: List<String> =
    RideRoadWidth.entries.map(RideRoadWidth::layerId)

private val WIDTH_ZOOMS = RideRoadWidth.entries.flatMap { it.stops.map { stop -> stop.first } }.distinct().sorted()
private fun widthProperty(zoom: Int) = "ride_width_z$zoom"

internal fun darkRouteWidthExpression(): Expression = Expression.interpolate(
    Expression.exponential(1.2),
    Expression.zoom(),
    *WIDTH_ZOOMS.map { Expression.stop(it, Expression.get(widthProperty(it))) }.toTypedArray(),
)

/** Path-detail indices share boundary vertices; only the ridden segments become white. */
internal fun darkRouteFeatures(path: ResponsePath, geometry: LineString, index: Int): List<Feature> {
    val coordinates = geometry.coordinates()
    val details = path.pathDetails["road_class"].orEmpty()
    val valid = details.isNotEmpty() && details.first().first == 0 &&
        details.last().last == coordinates.lastIndex &&
        details.all { it.first >= 0 && it.last > it.first && it.last <= coordinates.lastIndex } &&
        details.zipWithNext().all { (before, after) -> before.last == after.first }
    val segments = if (valid) {
        details.map { detail ->
            LineString.fromLngLats(coordinates.subList(detail.first, detail.last + 1)) to
                RideRoadWidth.forClass(detail.value.toString())
        }
    } else {
        listOf(geometry to RideRoadWidth.MAJOR)
    }
    return segments.map { (line, width) ->
        Feature.fromGeometry(line).apply {
            addNumberProperty("route_index", index)
            addStringProperty("route_color", "#FFFFFF")
            // A 1 dp shoulder on either side avoids a grey fringe from tile simplification/AA.
            WIDTH_ZOOMS.forEach { zoom -> addNumberProperty(widthProperty(zoom), width.widthAt(zoom.toDouble()) + 2.0) }
        }
    }
}
