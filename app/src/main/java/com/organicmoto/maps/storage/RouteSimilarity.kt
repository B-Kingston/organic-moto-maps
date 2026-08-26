package com.organicmoto.maps.storage

import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.asin
import kotlin.math.PI

/**
 * Compares a stored route shape against freshly routed candidates so loading
 * a saved route can re-select the matching alternative when the routing set
 * differs slightly from save time.
 *
 * Score = mean great-circle distance from sampled reference points to the
 * nearest candidate vertex. This is an approximation — vertex proximity, not
 * true point-to-segment distance — but OSM geometry segments are short next
 * to the corridor differences (kilometres) this matcher separates, so strided
 * sampling at the documented caps is sufficient and cheap.
 */
internal object RouteSimilarity {

    /**
     * Mean distance in metres from sampled [reference] points to the nearest
     * vertex of [candidate]. Larger = more different. Empty inputs return
     * [Double.MAX_VALUE] so real candidates rank above junk.
     */
    fun meanDistanceMeters(
        reference: List<GeoPoint>,
        candidate: List<GeoPoint>,
        referenceSampleCap: Int = DEFAULT_SAMPLE_CAP,
        candidateVertexCap: Int = DEFAULT_VERTEX_CAP,
    ): Double {
        if (reference.isEmpty() || candidate.isEmpty()) return Double.MAX_VALUE
        var total = 0.0
        var count = 0
        forEachStrided(reference, referenceSampleCap) { point ->
            total += minDistanceMeters(point, candidate, candidateVertexCap)
            count++
        }
        if (count == 0) return Double.MAX_VALUE
        val forwardEndpoints =
            haversineMeters(reference.first(), candidate.first()) +
                haversineMeters(reference.last(), candidate.last())
        val reverseEndpoints =
            haversineMeters(reference.first(), candidate.last()) +
                haversineMeters(reference.last(), candidate.first())
        val directionPenalty = if (reverseEndpoints < forwardEndpoints) forwardEndpoints else 0.0
        return total / count + directionPenalty
    }

    /**
     * Index of the [candidates] entry most similar to [reference], or null
     * when there is nothing to match against. Ties keep the lowest index.
     */
    fun bestMatchIndex(reference: List<GeoPoint>, candidates: List<List<GeoPoint>>): Int? {
        if (reference.isEmpty() || candidates.isEmpty()) return null
        var bestIndex: Int? = null
        var bestScore = Double.MAX_VALUE
        candidates.forEachIndexed { index, candidate ->
            val score = meanDistanceMeters(reference, candidate)
            if (score < bestScore) {
                bestScore = score
                bestIndex = index
            }
        }
        return bestIndex
    }

    /** Minimum great-circle distance from [point] to any strided vertex of [polyline]. */
    private fun minDistanceMeters(
        point: GeoPoint,
        polyline: List<GeoPoint>,
        vertexCap: Int,
    ): Double {
        var best = Double.MAX_VALUE
        forEachStrided(polyline, vertexCap) { vertex ->
            val distance = haversineMeters(point, vertex)
            if (distance < best) best = distance
        }
        return best
    }

    /**
     * Visits at most [cap] evenly spaced elements, always including the last,
     * so long paths thin out instead of dominating the comparison cost.
     */
    private inline fun forEachStrided(points: List<GeoPoint>, cap: Int, visit: (GeoPoint) -> Unit) {
        if (points.isEmpty()) return
        val stride = ceil(points.size / cap.toDouble()).toInt().coerceAtLeast(1)
        for (index in points.indices step stride) visit(points[index])
        val lastIndex = points.size - 1
        if ((lastIndex % stride) != 0) visit(points[lastIndex])
    }

    /** Great-circle distance in metres on a spherical Earth (mean radius). */
    internal fun haversineMeters(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val sinHalfLat = sin(dLat / 2.0)
        val sinHalfLon = sin(dLon / 2.0)
        val h = sinHalfLat * sinHalfLat + cos(lat1) * cos(lat2) * sinHalfLon * sinHalfLon
        return 2.0 * EARTH_RADIUS_METERS * asin(min(1.0, sqrt(h)))
    }

    private const val EARTH_RADIUS_METERS = 6_371_008.8
    private const val DEFAULT_SAMPLE_CAP = 64
    private const val DEFAULT_VERTEX_CAP = 512
}
