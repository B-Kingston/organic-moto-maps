package com.organicmoto.maps.storage

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for [SavedRouteRepository] against an in-memory store. The
 * same contract is asserted against real SQLite by the instrumented suite —
 * the interface KDoc defines it once for both implementations.
 */
class SavedRouteRepositoryTest {

    private val store = InMemorySavedRouteStore()
    private val repository = SavedRouteRepository(store)

    @Test
    fun `save assigns fresh ids and stores decodable geometry`() = runTest {
        val first = repository.save(draft())
        val second = repository.save(draft())
        assertTrue(first.id > 0 && second.id == first.id + 1)

        val summaries = repository.summaries()
        assertEquals(2, summaries.size)
        val stored = summaries.last().route
        assertEquals(first.id, stored.id)
        assertEquals("Brisbane", stored.fromName)
        val decoded = stored.decodedPoints()
        assertEquals(draftPoints.size, decoded.size)
        draftPoints.zip(decoded).forEach { (expected, actual) ->
            assertEquals(expected.lat, actual.lat, 1e-4)
            assertEquals(expected.lon, actual.lon, 1e-4)
        }
    }

    @Test
    fun `save trims endpoint names`() = runTest {
        val saved = repository.save(draft(fromName = "  Brisbane  ", toName = "Gold Coast "))
        assertEquals("Brisbane", saved.fromName)
        assertEquals("Gold Coast", saved.toName)
    }

    @Test
    fun `summaries list newest routes first`() = runTest {
        val older = repository.save(draft())
        val newer = repository.save(draft(fromName = "Toowoomba"))
        val order = repository.summaries().map { it.route.id }
        assertEquals(listOf(newer.id, older.id), order)
    }

    @Test
    fun `validation rejects malformed drafts without touching storage`() = runTest {
        val rejections = listOf(
            draft(fromName = "   "),
            draft(toName = ""),
            draft(distanceMeters = Double.NaN),
            draft(distanceMeters = -1.0),
            draft(durationMillis = -5L),
            draft(complexity = Float.NaN),
            draft(maxRoadSharePercent = Float.NaN),
            draft(from = GeoPoint(95.0, 153.0)),
            draft(to = GeoPoint(-27.0, 200.0)),
            draft(points = listOf(GeoPoint(-27.0, 153.0))),
        )
        rejections.forEach { candidate -> assertSaveRejected(candidate) }
        assertTrue(repository.summaries().isEmpty())
    }

    @Test
    fun `comments are trimmed, ordered oldest first, and counted`() = runTest {
        val routeId = repository.save(draft()).id

        val first = repository.addComment(routeId, "Great run through the ranges")
        assertEquals("Great run through the ranges", first.text)

        val second = repository.addComment(routeId, "  Watch the gravel on the descent ")
        assertEquals("Watch the gravel on the descent", second.text)

        val comments = repository.comments(routeId)
        assertEquals(listOf(first.id, second.id), comments.map { it.id })

        val summary = repository.summaries().single()
        assertEquals(2, summary.commentCount)
    }

    @Test
    fun `blank comments are rejected`() = runTest {
        val routeId = repository.save(draft()).id
        assertCommentRejected(routeId, "   ")
    }

    @Test
    fun `commenting on an unknown route fails like the foreign key does`() = runTest {
        assertCommentRejected(999L, "orphan")
    }

    /** Runs a suspending save and requires [IllegalArgumentException]. */
    private fun assertSaveRejected(candidate: SavedRouteDraft) = runTest {
        val failure = runCatching { repository.save(candidate) }.exceptionOrNull()
        assertTrue(
            "expected IllegalArgumentException but was $failure",
            failure is IllegalArgumentException,
        )
    }

    /** Runs a suspending comment insert and requires [IllegalArgumentException]. */
    private fun assertCommentRejected(routeId: Long, text: String) = runTest {
        val failure = runCatching { repository.addComment(routeId, text) }.exceptionOrNull()
        assertTrue(
            "expected IllegalArgumentException but was $failure",
            failure is IllegalArgumentException,
        )
    }

    @Test
    fun `deleting a route cascades to its comments`() = runTest {
        val routeId = repository.save(draft()).id
        repository.addComment(routeId, "note")

        repository.delete(routeId)

        assertTrue(repository.summaries().isEmpty())
        assertTrue(repository.comments(routeId).isEmpty())
    }

    private fun draft(
        fromName: String = "Brisbane",
        toName: String = "Gold Coast",
        from: GeoPoint = draftPoints.first(),
        to: GeoPoint = draftPoints.last(),
        distanceMeters: Double = 72_000.0,
        durationMillis: Long = 5_400_000L,
        complexity: Float = 1f,
        maxRoadSharePercent: Float = 70f,
        blockUnpaved: Boolean = false,
        points: List<GeoPoint> = draftPoints,
    ) = SavedRouteDraft(
        fromName = fromName,
        from = from,
        toName = toName,
        to = to,
        distanceMeters = distanceMeters,
        durationMillis = durationMillis,
        complexity = complexity,
        maxRoadSharePercent = maxRoadSharePercent,
        blockUnpaved = blockUnpaved,
        points = points,
    )

    private companion object {
        val draftPoints = listOf(
            GeoPoint(-27.4679, 153.0281),
            GeoPoint(-27.9500, 153.2100),
            GeoPoint(-28.0700, 153.4300),
        )
    }
}

/**
 * In-memory [SavedRouteStore] mirroring the documented contract — including
 * the unknown-route rejection that SQLite enforces with its foreign key — so
 * repository logic runs on the plain JVM.
 */
private class InMemorySavedRouteStore : SavedRouteStore {

    private var nextId = 0L
    private val routes = LinkedHashMap<Long, SavedRoute>()
    private val commentsByRoute = HashMap<Long, MutableList<SavedRouteComment>>()

    override fun insertRoute(route: SavedRoute): Long {
        val id = ++nextId
        routes[id] = route.copy(id = id)
        return id
    }

    override fun summaries(): List<SavedRouteSummary> =
        routes.values
            .sortedWith(
                compareByDescending<SavedRoute> { it.createdAtMillis }.thenByDescending { it.id }
            )
            .map { route ->
                SavedRouteSummary(route, commentsByRoute[route.id].orEmpty().size)
            }

    override fun deleteRoute(id: Long) {
        routes.remove(id)
        commentsByRoute.remove(id)
    }

    override fun comments(routeId: Long): List<SavedRouteComment> =
        commentsByRoute[routeId].orEmpty()
            .sortedWith(compareBy({ it.createdAtMillis }, { it.id }))

    override fun insertComment(comment: SavedRouteComment): Long {
        require(routes.containsKey(comment.routeId)) {
            "Cannot comment on route ${comment.routeId}: no such saved route"
        }
        val id = ++nextId
        commentsByRoute.getOrPut(comment.routeId) { mutableListOf() }.add(comment.copy(id = id))
        return id
    }
}
