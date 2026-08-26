package com.organicmoto.maps.map

import com.graphhopper.ResponsePath
import com.graphhopper.util.PointList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString

class RouteCollectionTest {

    @Test
    fun `every route becomes a feature with ordered coordinates`() {
        val routes = listOf(
            path(listOf(-27.0 to 153.0, -27.1 to 153.1, -27.2 to 153.2)),
            path(listOf(-28.0 to 152.0, -28.1 to 152.1)),
        )
        val collection = buildRouteFeatureCollection(routes, focusedIndex = 1)
        assertEquals(routes.size, collection.features().orEmpty().size)
        collection.features().orEmpty().forEachIndexed { index, feature ->
            val line = feature.geometry() as LineString
            assertEquals(
                routes[index].points.map { it.lat to it.lon },
                line.coordinates().map { it.latitude() to it.longitude() },
            )
            assertEquals(index, feature.getNumberProperty("route_index").toInt())
        }
    }

    @Test
    fun `focused route gets selected visual properties`() {
        val collection = buildRouteFeatureCollection(
            listOf(path(listOf(0.0 to 0.0, 1.0 to 1.0)), path(listOf(2.0 to 2.0, 3.0 to 3.0))),
            focusedIndex = 1,
        )
        val first = collection.features().orEmpty()[0]
        val second = collection.features().orEmpty()[1]
        assertEquals(5.0, first.getNumberProperty("route_width").toDouble(), 0.0)
        assertEquals(0.68, first.getNumberProperty("route_opacity").toDouble(), 1e-6)
        assertEquals(1.0, first.getNumberProperty("route_sort").toDouble(), 0.0)
        assertEquals(8.0, second.getNumberProperty("route_width").toDouble(), 0.0)
        assertEquals(1.0, second.getNumberProperty("route_opacity").toDouble(), 0.0)
        assertEquals(2.0, second.getNumberProperty("route_sort").toDouble(), 0.0)
    }

    @Test
    fun `empty routes produce an empty collection`() {
        val collection: FeatureCollection = buildRouteFeatureCollection(emptyList(), focusedIndex = 10)
        assertTrue(collection.features().orEmpty().isEmpty())
    }

    private fun path(points: List<Pair<Double, Double>>): ResponsePath =
        ResponsePath().setPoints(PointList().apply { points.forEach { (lat, lon) -> add(lat, lon) } })
}
