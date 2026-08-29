package com.organicmoto.maps.routing.navigation

/**
 * Forward-window route matcher over a [RouteTrack], ported in spirit from
 * Organic Maps' FollowedPolyline: projection always searches FORWARD from the
 * current position on the route, never backward, so a parallel road already
 * passed cannot recapture the rider.
 *
 * The matcher tracks a fractional offset (metres along the track) and the
 * vertex index of the matched segment. It reports both the projected offset
 * and the perpendicular miss distance so the session can decide whether the
 * rider is still on the route.
 */
class PolylineMatcher(private val track: RouteTrack) {

    /** Vertex index of the current segment start. */
    var currentSegmentIndex: Int = 0
        private set

    /** Current offset along the track in metres. */
    var currentOffsetM: Double = 0.0
        private set

    /** Perpendicular distance in metres from the latest fix to the route. */
    var lastMissM: Double = Double.POSITIVE_INFINITY
        private set

    /**
     * Projects [fix] onto the track, searching at most [windowSegments]
     * segments forward from the current position plus one segment back
     * (to absorb intra-segment back-and-forth within the same street).
     *
     * @return the projected offset in metres, or null when no segment in the
     * window yields a projection within [maxSnapM] of the fix.
     */
    fun project(fix: GpsFix, windowSegments: Int, maxSnapM: Double): Double? {
        val n = track.vertexCount
        val from = currentSegmentIndex.coerceAtLeast(0)
        // One segment back handles the common case of stopping right at a
        // vertex boundary where the forward-only search would jump a street.
        val back = if (from > 0) from - 1 else 0
        val to = (from + windowSegments).coerceAtMost(n - 1)
        var bestIndex = -1
        var bestT = 0.0
        var bestDist = Double.POSITIVE_INFINITY
        for (i in back until to) {
            val (dist, t) = GeoMath.segmentDistanceM(
                fix.lat, fix.lon,
                track.latitudes[i], track.longitudes[i],
                track.latitudes[i + 1], track.longitudes[i + 1],
            )
            if (dist < bestDist) {
                bestDist = dist
                bestIndex = i
                bestT = t
            }
        }
        lastMissM = bestDist
        if (bestIndex < 0 || bestDist > maxSnapM) return null
        currentSegmentIndex = bestIndex
        val segStart = track.cumulativeDistanceM[bestIndex]
        val segLen = track.cumulativeDistanceM[bestIndex + 1] - segStart
        currentOffsetM = segStart + bestT * segLen
        return currentOffsetM
    }

    /**
     * Bearing of the matched segment in degrees clockwise from north.
     */
    fun matchedSegmentBearingDeg(): Double = GeoMath.bearingDeg(
        track.latitudes[currentSegmentIndex], track.longitudes[currentSegmentIndex],
        track.latitudes[currentSegmentIndex + 1], track.longitudes[currentSegmentIndex + 1],
    )

    /**
     * Position of the current projection. Returns lat, lon.
     */
    fun projectedPosition(): Pair<Double, Double> {
        val i = currentSegmentIndex
        val segStart = track.cumulativeDistanceM[i]
        val segLen = track.cumulativeDistanceM[i + 1] - segStart
        val t = if (segLen <= 0.0) 0.0 else ((currentOffsetM - segStart) / segLen).coerceIn(0.0, 1.0)
        return (track.latitudes[i] + t * (track.latitudes[i + 1] - track.latitudes[i])) to
            (track.longitudes[i] + t * (track.longitudes[i + 1] - track.longitudes[i]))
    }

    fun reset() {
        currentSegmentIndex = 0
        currentOffsetM = 0.0
        lastMissM = Double.POSITIVE_INFINITY
    }
}
