package com.organicmoto.maps.storage

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedRouteStoreTruthfulnessTest {

    @Test
    fun savePropagatesStoreFailure() = runTest {
        val failure = runCatching { repository().save(draft()) }.exceptionOrNull()
        assertIllegalState(failure)
    }

    @Test
    fun addCommentPropagatesStoreFailure() = runTest {
        val failure = runCatching { repository().addComment(1L, "note") }.exceptionOrNull()
        assertIllegalState(failure)
    }

    @Test
    fun deletePropagatesStoreFailure() = runTest {
        val failure = runCatching { repository().delete(1L) }.exceptionOrNull()
        assertIllegalState(failure)
    }

    private fun repository(): SavedRouteRepository = SavedRouteRepository(FailingSavedRouteStore())

    private fun assertIllegalState(failure: Throwable?) {
        assertTrue("expected store failure but was $failure", failure is IllegalStateException)
    }

    private fun draft() = SavedRouteDraft(
        fromName = "Brisbane",
        from = GeoPoint(-27.0, 153.0),
        toName = "Toowoomba",
        to = GeoPoint(-27.5, 151.9),
        distanceMeters = 100_000.0,
        durationMillis = 3_600_000L,
        complexity = 0f,
        maxRoadSharePercent = 70f,
        blockUnpaved = false,
        points = listOf(GeoPoint(-27.0, 153.0), GeoPoint(-27.5, 151.9)),
    )
}

private class FailingSavedRouteStore : SavedRouteStore {
    override fun insertRoute(route: SavedRoute): Long = error("insert route failed")
    override fun summaries(): List<SavedRouteSummary> = error("summaries failed")
    override fun deleteRoute(id: Long): Unit = error("delete route failed")
    override fun comments(routeId: Long): List<SavedRouteComment> = error("comments failed")
    override fun insertComment(comment: SavedRouteComment): Long = error("insert comment failed")
}
