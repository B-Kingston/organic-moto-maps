package com.organicmoto.maps.routing.navigation

/**
 * Immutable guidance-ready view of one GraphHopper route: the geometry plus
 * pre-computed cumulative distances, cumulative model travel times, and turn
 * nodes derived from GraphHopper's instruction list.
 *
 * Everything here is pure JVM with no Android imports so it can be built and
 * exercised in unit tests from a [com.graphhopper.ResponsePath].
 *
 * Distances are metres, times are seconds. Model times come from GraphHopper
 * per-instruction durations, so remaining-time matches the ETA the user was
 * quoted at planning time rather than a wall-clock guess.
 */
class RouteTrack(
    val latitudes: DoubleArray,
    val longitudes: DoubleArray,
    /** Cumulative distance in metres at each vertex; cumulative[0] == 0. */
    val cumulativeDistanceM: DoubleArray,
    /** Cumulative travel time in seconds at each vertex; cumulative[0] == 0. */
    val cumulativeTimeS: DoubleArray,
    /** Total driven distance in metres. */
    val totalDistanceM: Double,
    /** Total model travel time in seconds. */
    val totalTimeS: Double,
    /** Turns along the route, ordered by vertex index ascending. */
    val turns: List<TurnNode>,
) {
    init {
        require(latitudes.size == longitudes.size) { "coordinate arrays must align" }
        require(latitudes.size >= 2) { "a route track needs at least two vertices" }
        require(cumulativeDistanceM.size == latitudes.size) { "distance cache must cover every vertex" }
        require(cumulativeTimeS.size == latitudes.size) { "time cache must cover every vertex" }
    }

    /** One navigation turn: the maneuver at vertex [vertexIndex]. */
    data class TurnNode(
        val vertexIndex: Int,
        /** GraphHopper instruction sign; see [com.graphhopper.util.Instruction]. */
        val sign: Int,
        /** Human road/street name for the maneuver, may be empty. */
        val streetName: String,
    )

    val vertexCount: Int get() = latitudes.size

    /**
     * Distance in metres along the track between two vertex indices,
     * inclusive-exclusive, both clamped to the track.
     */
    fun distanceBetweenM(fromIndex: Int, toIndex: Int): Double {
        val a = fromIndex.coerceIn(0, lastIndex)
        val b = toIndex.coerceIn(0, lastIndex)
        return cumulativeDistanceM[b] - cumulativeDistanceM[a]
    }

    /** Remaining model time in seconds from fractional offset [offsetM] (metres along the track). */
    fun remainingTimeS(offsetM: Double): Double {
        val (index, fraction) = locate(offsetM)
        val base = cumulativeTimeS[index]
        val next = cumulativeTimeS[(index + 1).coerceAtMost(lastIndex)]
        return totalTimeS - (base + fraction * (next - base))
    }

    /** Remaining distance in metres from fractional offset [offsetM]. */
    fun remainingDistanceM(offsetM: Double): Double = (totalDistanceM - offsetM).coerceAtLeast(0.0)

    /**
     * Locates [offsetM] along the track: the segment start index and the
     * fraction (0..1) travelled into that segment.
     */
    fun locate(offsetM: Double): Pair<Int, Double> {
        val clamped = offsetM.coerceIn(0.0, totalDistanceM)
        // Linear scan from a binary-searched seed; segments are short so the
        // residual walk is a handful of steps.
        var lo = 0
        var hi = lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (cumulativeDistanceM[mid] <= clamped) lo = mid else hi = mid - 1
        }
        val segLen = cumulativeDistanceM[(lo + 1).coerceAtMost(lastIndex)] - cumulativeDistanceM[lo]
        return if (segLen <= 0.0) lo to 0.0 else lo to ((clamped - cumulativeDistanceM[lo]) / segLen).coerceIn(0.0, 1.0)
    }

    /**
     * The next turn at or after fractional offset [offsetM], with its
     * distance in metres from that offset, or null when none remains.
     */
    fun nextTurn(offsetM: Double): Pair<TurnNode, Double>? {
        for (turn in turns) {
            val turnOffset = cumulativeDistanceM[turn.vertexIndex.coerceIn(0, lastIndex)]
            if (turnOffset >= offsetM - TURN_SNAP_TOLERANCE_M) {
                return turn to (turnOffset - offsetM).coerceAtLeast(0.0)
            }
        }
        return null
    }

    private val lastIndex: Int get() = latitudes.size - 1

    companion object {
        /** A turn within this distance of the current offset already counts as reached. */
        const val TURN_SNAP_TOLERANCE_M = 15.0

        /**
         * Builds a track from GraphHopper path geometry and instructions.
         * Instruction vertex offsets come from cumulative instruction point
         * counts; each instruction's points are a contiguous slice of the
         * path geometry.
         */
        fun fromPath(
            latitudes: DoubleArray,
            longitudes: DoubleArray,
            instructionSigns: List<Int>,
            instructionNames: List<String>,
            instructionPointCounts: List<Int>,
            instructionDistancesM: List<Double>,
            instructionTimesS: List<Double>,
        ): RouteTrack {
            val n = latitudes.size
            // Exact cumulative geometric distances: the distance cache must
            // reflect real geometry so remaining-distance math is honest.
            val cumulativeDistance = DoubleArray(n)
            for (v in 1 until n) {
                cumulativeDistance[v] = cumulativeDistance[v - 1] + GeoMath.haversineM(
                    latitudes[v - 1], longitudes[v - 1], latitudes[v], longitudes[v],
                )
            }
            val totalDistance = cumulativeDistance[n - 1]
            val totalTime = instructionTimesS.sum()
            // Distribute per-instruction time across the segments the
            // instruction covers, proportionally to each segment's geometric
            // length. Instruction points are contiguous path-vertex slices.
            val cumulativeTime = DoubleArray(n)
            val segmentTime = DoubleArray(n) // time attributable to segment v (v -> v+1)
            val turns = mutableListOf<TurnNode>()
            var cursor = 0
            for (i in instructionSigns.indices) {
                val points = instructionPointCounts[i]
                val end = (cursor + points).coerceAtMost(n)
                val time = instructionTimesS[i]
                val first = cursor
                var last = cursor
                while (last < end - 1) last++
                // Segments [first, last) carry this instruction's time.
                var segLenSum = 0.0
                val segLens = DoubleArray(last - first)
                for (v in first until last) {
                    segLens[v - first] = cumulativeDistance[v + 1] - cumulativeDistance[v]
                    segLenSum += segLens[v - first]
                }
                for (v in first until last) {
                    val share = if (segLenSum > 0.0) segLens[v - first] / segLenSum else 1.0 / (last - first)
                    segmentTime[v] += time * share
                }
                // A maneuver sign happens at the START of instruction i, i.e.
                // at the vertex where the previous instruction ended. Skip the
                // synthetic first instruction (depart / continue at origin).
                if (i > 0 && isRealTurn(instructionSigns[i])) {
                    turns += TurnNode(cursor.coerceAtMost(n - 1), instructionSigns[i], instructionNames[i])
                }
                cursor = end
            }
            for (v in 1 until n) {
                cumulativeTime[v] = cumulativeTime[v - 1] + segmentTime[v - 1]
            }
            // Scale so the last vertex lands exactly on totalTime.
            if (cumulativeTime[n - 1] > 0.0) {
                val drift = totalTime / cumulativeTime[n - 1]
                for (v in 0 until n) cumulativeTime[v] *= drift
            }
            return RouteTrack(
                latitudes, longitudes, cumulativeDistance, cumulativeTime,
                totalDistance, totalTime, turns,
            )
        }

        /** GraphHopper signs that represent an actual maneuver worth announcing. */
        fun isRealTurn(sign: Int): Boolean = when (sign) {
            com.graphhopper.util.Instruction.CONTINUE_ON_STREET,
            com.graphhopper.util.Instruction.FINISH,
            com.graphhopper.util.Instruction.REACHED_VIA,
            com.graphhopper.util.Instruction.IGNORE,
            -> false
            else -> true
        }
    }
}
