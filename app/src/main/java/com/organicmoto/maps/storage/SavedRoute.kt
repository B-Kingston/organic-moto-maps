package com.organicmoto.maps.storage

/**
 * A route the user saved from the route carousel, as one row of the
 * `saved_routes` table.
 *
 * [geometry] holds the full driven path as an encoded polyline
 * ([PolylineCodec]) so the exact saved shape — not just its endpoints —
 * survives process death and app updates.
 *
 * [complexity], [maxRoadSharePercent] and [blockUnpaved] record the routing
 * controls at save time. Loading a saved route restores them into the live
 * controls, so the loaded route starts in the same editable state as a fresh
 * plan.
 *
 * [id] is assigned by the database. Constructing instances for insertion may
 * leave it at 0; stores ignore it on insert and return the real id.
 */
data class SavedRoute(
    val id: Long,
    val fromName: String,
    val fromLat: Double,
    val fromLon: Double,
    val toName: String,
    val toLat: Double,
    val toLon: Double,
    val distanceMeters: Double,
    val durationMillis: Long,
    val complexity: Float,
    val maxRoadSharePercent: Float,
    val blockUnpaved: Boolean,
    val geometry: String,
    val createdAtMillis: Long,
) {
    /** Endpoints as [GeoPoint]s, ready for routing. */
    val endpoints: Pair<GeoPoint, GeoPoint>
        get() = GeoPoint(fromLat, fromLon) to GeoPoint(toLat, toLon)

    /**
     * The stored path. Decodes [geometry] on every call; hold on to the result
     * instead of calling this in a loop.
     */
    fun decodedPoints(): List<GeoPoint> = PolylineCodec.decode(geometry)
}

/** A free-text note a user attached to a [SavedRoute]; one `saved_route_comments` row. */
data class SavedRouteComment(
    val id: Long,
    val routeId: Long,
    val text: String,
    val createdAtMillis: Long,
)

/** A [SavedRoute] together with its comment count, for menu badges. */
data class SavedRouteSummary(
    val route: SavedRoute,
    val commentCount: Int,
)

/**
 * Everything needed to persist one route, captured from the live planning
 * state at save time. The repository validates drafts before they reach a
 * store; stores receive fully formed [SavedRoute]s.
 */
data class SavedRouteDraft(
    val fromName: String,
    val from: GeoPoint,
    val toName: String,
    val to: GeoPoint,
    val distanceMeters: Double,
    val durationMillis: Long,
    val complexity: Float,
    val maxRoadSharePercent: Float,
    val blockUnpaved: Boolean,
    val points: List<GeoPoint>,
)
