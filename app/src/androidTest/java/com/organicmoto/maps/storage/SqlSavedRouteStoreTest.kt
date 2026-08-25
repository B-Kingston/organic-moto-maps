package com.organicmoto.maps.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the [SavedRouteStore] contract against real device SQLite, including
 * the foreign-key cascade that an in-memory fake can only imitate.
 */
@RunWith(AndroidJUnit4::class)
class SqlSavedRouteStoreTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store: SqlSavedRouteStore

    @Before
    fun resetDatabase() {
        context.deleteDatabase(SAVED_ROUTES_DB_NAME)
        store = SqlSavedRouteStore(context)
    }

    @Test
    fun insertsAndReadsBackEveryColumn() {
        val id = store.insertRoute(savedRoute(fromName = "Brisbane"))
        val summary = store.summaries().single()

        assertEquals(id, summary.route.id)
        assertEquals("Brisbane", summary.route.fromName)
        assertEquals(-27.4679, summary.route.fromLat, 1e-9)
        assertEquals(153.0281, summary.route.fromLon, 1e-9)
        assertEquals("Byron Bay", summary.route.toName)
        assertEquals(-28.6438, summary.route.toLat, 1e-9)
        assertEquals(153.6096, summary.route.toLon, 1e-9)
        assertEquals(190_000.0, summary.route.distanceMeters, 1e-9)
        assertEquals(10_800_000L, summary.route.durationMillis)
        assertEquals(2f, summary.route.complexity)
        assertEquals(65f, summary.route.maxRoadSharePercent)
        assertTrue(summary.route.blockUnpaved)
        assertEquals(ENCODED_GEOMETRY, summary.route.geometry)
        assertEquals(CREATED_AT, summary.route.createdAtMillis)
        assertEquals(0, summary.commentCount)
    }

    @Test
    fun summariesSortNewestFirstAndCountComments() {
        val older = store.insertRoute(savedRoute(createdOffsetMinutes = 0))
        val newer = store.insertRoute(savedRoute(createdOffsetMinutes = 10))
        store.insertComment(SavedRouteComment(0L, older, "curvy!", CREATED_AT))

        val summaries = store.summaries()
        assertEquals(listOf(newer, older), summaries.map { it.route.id })
        assertEquals(0, summaries[0].commentCount)
        assertEquals(1, summaries[1].commentCount)
    }

    @Test
    fun commentsListOldestFirst() {
        val routeId = store.insertRoute(savedRoute())
        store.insertComment(SavedRouteComment(0L, routeId, "first", CREATED_AT))
        store.insertComment(SavedRouteComment(0L, routeId, "second", CREATED_AT + 5_000))

        assertEquals(listOf("first", "second"), store.comments(routeId).map { it.text })
    }

    @Test
    fun insertingCommentOnUnknownRouteFails() {
        assertThrows(IllegalStateException::class.java) {
            store.insertComment(SavedRouteComment(0L, routeId = 4242L, "orphan", CREATED_AT))
        }
    }

    @Test
    fun deletingRouteCascadesToComments() {
        val routeId = store.insertRoute(savedRoute())
        store.insertComment(SavedRouteComment(0L, routeId, "note", CREATED_AT))

        store.deleteRoute(routeId)

        assertTrue(store.summaries().isEmpty())
        assertTrue(store.comments(routeId).isEmpty())
    }

    @Test
    fun dataSurvivesReopeningDatabaseFile() {
        val routeId = store.insertRoute(savedRoute())

        // A fresh helper instance over the same file proves disk persistence,
        // not just connection state.
        val reopened = SqlSavedRouteStore(context)
        assertEquals(routeId, reopened.summaries().single().route.id)
    }

    private fun savedRoute(
        fromName: String = "Brisbane",
        createdOffsetMinutes: Long = 0,
    ) = SavedRoute(
        id = 0L,
        fromName = fromName,
        fromLat = -27.4679,
        fromLon = 153.0281,
        toName = "Byron Bay",
        toLat = -28.6438,
        toLon = 153.6096,
        distanceMeters = 190_000.0,
        durationMillis = 10_800_000L,
        complexity = 2f,
        maxRoadSharePercent = 65f,
        blockUnpaved = true,
        geometry = ENCODED_GEOMETRY,
        createdAtMillis = CREATED_AT + createdOffsetMinutes * 60_000,
    )

    private companion object {
        const val CREATED_AT = 1_756_000_000_000L

        /** Two-point Brisbane → Byron-ish line; content is opaque to SQL. */
        const val ENCODED_GEOMETRY = "_p~iF~ps|U_ulLnnqC"
    }
}
