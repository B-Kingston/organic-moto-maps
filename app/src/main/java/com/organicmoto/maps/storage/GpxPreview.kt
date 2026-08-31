package com.organicmoto.maps.storage

import kotlin.math.ceil

/** One evenly spaced, user-facing preview stop along an imported GPX ride. */
data class GpxMilestone(
    val point: GeoPoint,
    val distanceMeters: Double,
    val progress: Double,
    val placeName: String? = null,
    val placeDetail: String? = null,
)

/** Pure geometry used by the GPX preview sheet and its JVM contract tests. */
object GpxPreview {
    const val DEFAULT_MAX_MILESTONES = 12

    /**
     * Samples [points] by along-track distance, always retaining both ends.
     * Sampling by distance rather than source index keeps dense GPS recording
     * bursts from crowding useful milestones out of the preview.
     */
    fun milestones(
        points: List<GeoPoint>,
        maxMilestones: Int = DEFAULT_MAX_MILESTONES,
    ): List<GpxMilestone> {
        require(points.size >= 2) { "A GPX preview needs at least two points" }
        require(maxMilestones >= 2) { "A GPX preview needs at least two milestones" }

        val cumulative = DoubleArray(points.size)
        for (index in 1 until points.size) {
            cumulative[index] = cumulative[index - 1] +
                GpxGeometry.haversineMeters(points[index - 1], points[index])
        }
        val total = cumulative.last()
        if (total <= 0.0) {
            return listOf(
                GpxMilestone(points.first(), 0.0, 0.0),
                GpxMilestone(points.last(), 0.0, 1.0),
            )
        }

        // Short rides do not need twelve near-duplicate labels. Aim for about
        // one preview stop per 10 km, with a useful floor of start/middle/end.
        val count = ceil(total / 10_000.0).toInt().plus(1)
            .coerceIn(3, maxMilestones)
        return List(count) { slot ->
            val progress = slot.toDouble() / (count - 1)
            val target = total * progress
            val found = cumulative.binarySearch(target)
            val point = if (found >= 0) {
                points[found]
            } else {
                val upper = (-found - 1).coerceIn(1, points.lastIndex)
                val lower = upper - 1
                val segmentLength = cumulative[upper] - cumulative[lower]
                val fraction = if (segmentLength <= 0.0) 0.0 else {
                    ((target - cumulative[lower]) / segmentLength).coerceIn(0.0, 1.0)
                }
                GeoPoint(
                    lat = points[lower].lat + fraction * (points[upper].lat - points[lower].lat),
                    lon = points[lower].lon + fraction * (points[upper].lon - points[lower].lon),
                )
            }
            GpxMilestone(point, target, progress)
        }
    }
}
