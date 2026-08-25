package com.organicmoto.maps.storage

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The app-facing API for saved routes. Validates input at the boundary,
 * stamps creation times, encodes geometry, and keeps all SQLite work on
 * [Dispatchers.IO] so callers can invoke it straight from the UI layer.
 *
 * Business rules:
 * - A draft needs non-blank endpoint names, valid coordinates, finite
 *   non-negative metrics, and at least two path points.
 * - Comment text is trimmed and must not be blank after trimming.
 */
class SavedRouteRepository(store: SavedRouteStore) {

    constructor(context: Context) : this(SqlSavedRouteStore(context.applicationContext))

    private val store = store

    suspend fun save(draft: SavedRouteDraft): SavedRoute = withContext(Dispatchers.IO) {
        validate(draft)
        val stored = SavedRoute(
            id = 0L,
            fromName = draft.fromName.trim(),
            fromLat = draft.from.lat,
            fromLon = draft.from.lon,
            toName = draft.toName.trim(),
            toLat = draft.to.lat,
            toLon = draft.to.lon,
            distanceMeters = draft.distanceMeters,
            durationMillis = draft.durationMillis,
            complexity = draft.complexity.coerceAtLeast(0f),
            maxRoadSharePercent = draft.maxRoadSharePercent,
            blockUnpaved = draft.blockUnpaved,
            geometry = PolylineCodec.encode(draft.points),
            createdAtMillis = System.currentTimeMillis(),
        )
        stored.copy(id = store.insertRoute(stored))
    }

    suspend fun summaries(): List<SavedRouteSummary> = withContext(Dispatchers.IO) {
        store.summaries()
    }

    suspend fun delete(routeId: Long) = withContext(Dispatchers.IO) {
        store.deleteRoute(routeId)
    }

    suspend fun comments(routeId: Long): List<SavedRouteComment> = withContext(Dispatchers.IO) {
        store.comments(routeId)
    }

    suspend fun addComment(routeId: Long, rawText: String): SavedRouteComment =
        withContext(Dispatchers.IO) {
            val text = rawText.trim()
            require(text.isNotEmpty()) { "Comment is empty" }
            val comment = SavedRouteComment(
                id = 0L,
                routeId = routeId,
                text = text,
                createdAtMillis = System.currentTimeMillis(),
            )
            comment.copy(id = store.insertComment(comment))
        }

    private fun validate(draft: SavedRouteDraft) {
        require(draft.fromName.isNotBlank()) { "From name is blank" }
        require(draft.toName.isNotBlank()) { "To name is blank" }
        requireValidPoint("From", draft.from)
        requireValidPoint("To", draft.to)
        require(draft.points.size >= 2) { "A saved route needs at least two path points" }
        require(draft.points.all(::isFinitePoint)) { "Path contains a non-finite coordinate" }
        require(draft.distanceMeters.isFinite() && draft.distanceMeters >= 0.0) {
            "Distance must be finite and non-negative"
        }
        require(draft.durationMillis >= 0L) { "Duration must be non-negative" }
        require(draft.maxRoadSharePercent.isFinite()) { "Road share must be finite" }
        require(draft.complexity.isFinite() && draft.complexity >= 0f) {
            "Complexity must be finite and non-negative"
        }
    }

    /** Rejects swapped or corrupt coordinates at the boundary. */
    private fun requireValidPoint(label: String, point: GeoPoint) {
        require(point.lat.isFinite() && point.lat in -90.0..90.0) {
            "$label latitude ${point.lat} is out of range"
        }
        require(point.lon.isFinite() && point.lon in -180.0..180.0) {
            "$label longitude ${point.lon} is out of range"
        }
    }

    private fun isFinitePoint(point: GeoPoint): Boolean =
        point.lat.isFinite() && point.lon.isFinite()
}
