package com.organicmoto.maps.map

import com.graphhopper.ResponsePath
import com.graphhopper.util.PointList
import com.graphhopper.util.details.PathDetail
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.geojson.LineString

class DarkRouteRoadsTest {
    @Test
    fun widthsMatchEveryShippedRoadStopAndCoverOverzoom() {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "app/src/main/assets/ride-dark-style.json").exists() }
        val layers = JSONObject(File(root, "app/src/main/assets/ride-dark-style.json").readText()).getJSONArray("layers")
        RideRoadWidth.entries.forEach { width ->
            val layer = (0 until layers.length()).map { layers.getJSONObject(it) }
                .first { it.getString("id") == width.layerId }
            val function = layer.getJSONObject("paint").getJSONObject("line-width")
            assertEquals(1.2, function.getDouble("base"), 0.0)
            val stops = function.getJSONArray("stops")
            assertEquals(stops.length(), width.stops.size)
            width.stops.forEachIndexed { index, (zoom, value) ->
                assertEquals(zoom, stops.getJSONArray(index).getInt(0))
                assertEquals(value, stops.getJSONArray(index).getDouble(1), 0.0)
                assertEquals(value, width.widthAt(zoom.toDouble()), 1e-9)
            }
            assertTrue(width.widthAt(18.0) > width.widthAt(16.0))
        }
    }

    @Test
    fun roadClassChangesSplitWhiteRouteWithoutGapsOrChangingItsPath() {
        val path = path().apply {
            addPathDetails(mapOf("road_class" to listOf(detail("service", 0, 1), detail("residential", 1, 2), detail("primary", 2, 3))))
        }
        val features = buildRouteFeatureCollection(listOf(path), RouteGeometryCache(), darkGuidanceMode = true).features()!!
        assertEquals(3, features.size)
        features.zip(RideRoadWidth.entries).forEach { (feature, width) ->
            assertEquals("#FFFFFF", feature.getStringProperty("route_color"))
            assertEquals(width.widthAt(18.0) + 2, feature.getNumberProperty("ride_width_z18").toDouble(), 1e-9)
            assertEquals(0, feature.getNumberProperty("route_index").toInt())
        }
        val lines = features.map { (it.geometry() as LineString).coordinates() }
        lines.zipWithNext().forEach { (before, after) -> assertEquals(before.last(), after.first()) }
        assertEquals(path.points.size(), lines.first().size + lines.drop(1).sumOf { it.size - 1 })
    }

    @Test
    fun missingOrMalformedDetailsRetainTheWholeRouteWithSafeWidth() {
        for (details in listOf(emptyList(), listOf(detail("service", 1, 3)), listOf(detail("primary", 0, 7)))) {
            val path = path().apply { addPathDetails(mapOf("road_class" to details)) }
            val feature = buildRouteFeatureCollection(listOf(path), RouteGeometryCache(), darkGuidanceMode = true).features()!!.single()
            assertEquals(4, (feature.geometry() as LineString).coordinates().size)
            assertEquals(28.0, feature.getNumberProperty("ride_width_z18").toDouble(), 0.0)
        }
    }

    @Test
    fun graphHopperClassesMapToTheBasemapRoadWidths() {
        listOf("motorway", "trunk", "primary", "secondary").forEach { assertEquals(RideRoadWidth.MAJOR, RideRoadWidth.forClass(it)) }
        listOf("tertiary", "residential", "unclassified", "living_street").forEach { assertEquals(RideRoadWidth.LOCAL, RideRoadWidth.forClass(it)) }
        listOf("service", "track").forEach { assertEquals(RideRoadWidth.SERVICE, RideRoadWidth.forClass(it)) }
    }

    private fun path() = ResponsePath().setPoints(PointList().apply {
        repeat(4) { add(-27.0 + it * 0.001, 153.0) }
    })

    private fun detail(value: String, first: Int, last: Int) = PathDetail(value).apply {
        setFirst(first)
        setLast(last)
    }
}
