package com.organicmoto.maps

import com.organicmoto.maps.storage.GeoPoint
import com.organicmoto.maps.storage.PolylineCodec
import com.organicmoto.maps.storage.SavedRoute
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedRouteReloadTest {

    @Test
    fun closedSavedRideRestoresShapingPoints() {
        val start = GeoPoint(-27.0, 153.0)
        val shape = listOf(
            start,
            GeoPoint(-27.0, 153.1),
            GeoPoint(-27.1, 153.1),
            start,
        )
        val route = savedRoute(start, start, shape)

        val via = savedRouteViaPoints(route)

        assertTrue(via.isNotEmpty())
        assertTrue(via.any { it.lon > 153.05 })
    }

    @Test
    fun openSavedRideKeepsNormalEndpointRouting() {
        val from = GeoPoint(-27.0, 153.0)
        val to = GeoPoint(-27.1, 153.1)
        assertTrue(savedRouteViaPoints(savedRoute(from, to, listOf(from, to))).isEmpty())
    }

    private fun savedRoute(from: GeoPoint, to: GeoPoint, shape: List<GeoPoint>) = SavedRoute(
        id = 1,
        fromName = "From",
        fromLat = from.lat,
        fromLon = from.lon,
        toName = "To",
        toLat = to.lat,
        toLon = to.lon,
        distanceMeters = 1_000.0,
        durationMillis = 60_000,
        complexity = 0f,
        maxRoadSharePercent = 70f,
        blockUnpaved = false,
        geometry = PolylineCodec.encode(shape),
        createdAtMillis = 1,
    )
}
