package com.organicmoto.maps.map

import com.graphhopper.ResponsePath
import com.graphhopper.util.PointList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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
        val collection = buildRouteFeatureCollection(routes, RouteGeometryCache())
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
    fun `route source carries static unselected styling`() {
        val collection = buildRouteFeatureCollection(
            listOf(path(listOf(0.0 to 0.0, 1.0 to 1.0)), path(listOf(2.0 to 2.0, 3.0 to 3.0))),
            RouteGeometryCache(),
        )
        val first = collection.features().orEmpty()[0]
        val second = collection.features().orEmpty()[1]
        assertEquals(5.0, first.getNumberProperty("route_width").toDouble(), 0.0)
        assertEquals(0.68, first.getNumberProperty("route_opacity").toDouble(), 1e-6)
        assertEquals(1.0, first.getNumberProperty("route_sort").toDouble(), 0.0)
        assertEquals(5.0, second.getNumberProperty("route_width").toDouble(), 0.0)
        assertEquals(0.68, second.getNumberProperty("route_opacity").toDouble(), 1e-6)
        assertEquals(1.0, second.getNumberProperty("route_sort").toDouble(), 0.0)
    }

    @Test
    fun `route selection reuses cached line geometry for the same route set`() {
        val routes = listOf(
            path(listOf(-27.0 to 153.0, -27.1 to 153.1, -27.2 to 153.2)),
            path(listOf(-28.0 to 152.0, -28.1 to 152.1)),
        )
        val cache = RouteGeometryCache()

        val first = buildRouteFeatureCollection(routes, cache)
        val nextSelection = buildRouteFeatureCollection(routes, cache)

        first.features().orEmpty().zip(nextSelection.features().orEmpty()).forEach { (before, after) ->
            assertSame(before.geometry(), after.geometry())
        }
        assertTrue(cache.sourceNeedsRefresh(routes, sourceExists = false))
        cache.markSourceCurrent(routes)
        assertFalse(cache.sourceNeedsRefresh(routes, sourceExists = true))
        assertTrue(cache.sourceNeedsRefresh(routes, sourceExists = false))
        val changedRoutes = routes.toList() + path(listOf(-26.0 to 151.0, -26.1 to 151.1))
        assertTrue(cache.sourceNeedsRefresh(changedRoutes, sourceExists = true))
    }

    @Test
    fun `dark guidance source keeps only the focused route in bright white`() {
        val routes = listOf(
            path(listOf(-27.0 to 153.0, -27.1 to 153.1)),
            path(listOf(-28.0 to 152.0, -28.1 to 152.1)),
        )

        val collection = buildRouteFeatureCollection(
            routes,
            RouteGeometryCache(),
            focusedIndex = 1,
            darkGuidanceMode = true,
        )
        val feature = collection.features().orEmpty().single()

        assertEquals(1, feature.getNumberProperty("route_index").toInt())
        assertEquals("#FFFFFF", feature.getStringProperty("route_color"))
        assertEquals(
            routes[1].points.map { it.lat to it.lon },
            (feature.geometry() as LineString).coordinates().map { it.latitude() to it.longitude() },
        )
    }

    @Test
    fun `empty routes produce an empty collection`() {
        val collection: FeatureCollection = buildRouteFeatureCollection(emptyList(), RouteGeometryCache())
        assertTrue(collection.features().orEmpty().isEmpty())
    }

    private fun path(points: List<Pair<Double, Double>>): ResponsePath =
        ResponsePath().setPoints(PointList().apply { points.forEach { (lat, lon) -> add(lat, lon) } })
}
