package com.organicmoto.maps.storage

/**
 * Persistence contract for saved routes. Implementations: [SqlSavedRouteStore]
 * (device SQLite) and the in-memory fake in the unit-test sources.
 *
 * Contract shared by every implementation — tests assert it against both:
 * - Every method blocks; callers move work off the main thread.
 * - `insert*` ignores the entity's [SavedRoute.id]/[SavedRouteComment.id],
 *   assigns a fresh positive id, and returns it.
 * - `summaries()` sorts newest route first (createdAt desc, then id desc);
 *   `comments()` sorts oldest first (createdAt asc, then id asc).
 * - `insertComment` throws [IllegalArgumentException] for an unknown
 *   [SavedRouteComment.routeId] (the SQL impl enforces this through a foreign
 *   key; fakes mirror it).
 * - `deleteRoute` also deletes the route's comments (cascade) and does nothing
 *   for an unknown id.
 */
interface SavedRouteStore {
    fun insertRoute(route: SavedRoute): Long
    fun summaries(): List<SavedRouteSummary>
    fun deleteRoute(id: Long)
    fun comments(routeId: Long): List<SavedRouteComment>
    fun insertComment(comment: SavedRouteComment): Long
}
