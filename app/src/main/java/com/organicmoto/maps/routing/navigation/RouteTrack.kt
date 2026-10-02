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
        /** Signed route deflection at the maneuver, in degrees; right is positive. */
        val turnAngleDeg: Double? = null,
        /** Chosen exit number for a GraphHopper roundabout instruction, when known. */
        val roundaboutExitNumber: Int? = null,
        /** True for clockwise circulation, false for counterclockwise, null when ambiguous. */
        val roundaboutClockwise: Boolean? = null,
        /** Recommended lane(s) on the road being left, when it has two or more lanes. */
        val lanes: LaneGuidance? = null,
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

    /**
     * Model time in seconds the plan budgets for travelling [distanceM],
     * using the route's average model pace. Deliberately coarse: it feeds a
     * smoothed live delta, not a per-segment replay. NaN when unusable.
     */
    fun timeForDistanceM(distanceM: Double): Double {
        if (distanceM.isNaN() || distanceM <= 0.0) return Double.NaN
        val avgPace = totalDistanceM / totalTimeS.coerceAtLeast(1e-6)
        if (avgPace <= 0.0 || avgPace.isNaN() || avgPace.isInfinite()) return Double.NaN
        return distanceM / avgPace
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
        return upcomingTurns(offsetM, 1).firstOrNull()
    }

    /** Upcoming turns in route order, with each distance measured from [offsetM]. */
    fun upcomingTurns(offsetM: Double, limit: Int): List<Pair<TurnNode, Double>> {
        if (limit <= 0) return emptyList()
        return turns.asSequence()
            .mapNotNull { turn ->
                val turnOffset = cumulativeDistanceM[turn.vertexIndex.coerceIn(0, lastIndex)]
                if (turnOffset >= offsetM - TURN_SNAP_TOLERANCE_M) {
                    turn to (turnOffset - offsetM).coerceAtLeast(0.0)
                } else {
                    null
                }
            }
            .take(limit)
            .toList()
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
            instructionTimesS: List<Double>,
            instructionRoundaboutExitNumbers: List<Int?> = List(instructionSigns.size) { null },
            instructionRoundaboutClockwise: List<Boolean?> = List(instructionSigns.size) { null },
            /** Raw `moto_lanes` value of segment v -> v+1 at index v; empty when unknown. */
            segmentLanes: List<String?> = emptyList(),
        ): RouteTrack {
            val n = latitudes.size
            require(n >= 2) { "a route track needs at least two vertices" }
            require(longitudes.size == n) { "coordinate arrays must align" }
            require(instructionNames.size == instructionSigns.size &&
                instructionPointCounts.size == instructionSigns.size &&
                instructionTimesS.size == instructionSigns.size &&
                instructionRoundaboutExitNumbers.size == instructionSigns.size &&
                instructionRoundaboutClockwise.size == instructionSigns.size
            ) { "instruction fields must align" }
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
                require(points >= 0) { "instruction point counts must be non-negative" }
                val end = (cursor + points).coerceAtMost(n)
                val time = instructionTimesS[i]
                val first = cursor
                // GraphHopper omits an instruction's final adjacent point;
                // that vertex is the first point of the next instruction.
                // Consequently P instruction points cover P route segments.
                val segmentEnd = end.coerceAtMost(n - 1)
                var segLenSum = 0.0
                val segmentCount = (segmentEnd - first).coerceAtLeast(0)
                val segLens = DoubleArray(segmentCount)
                for (v in first until segmentEnd) {
                    segLens[v - first] = cumulativeDistance[v + 1] - cumulativeDistance[v]
                    segLenSum += segLens[v - first]
                }
                for (v in first until segmentEnd) {
                    val share = if (segLenSum > 0.0) {
                        segLens[v - first] / segLenSum
                    } else {
                        1.0 / segmentCount
                    }
                    segmentTime[v] += time * share
                }
                // A maneuver sign happens at the START of instruction i, i.e.
                // at the vertex where the previous instruction ended. Skip the
                // synthetic first instruction (depart / continue at origin).
                if (i > 0 && isRealTurn(instructionSigns[i])) {
                    val vertex = cursor.coerceAtMost(n - 1)
                    val lanes = LaneGuidance.forTurn(
                        segmentLanes, cumulativeDistance, vertex,
                        previousTurnVertex = turns.lastOrNull()?.vertexIndex ?: 0,
                        sign = instructionSigns[i],
                    )
                    turns += TurnNode(
                        vertexIndex = vertex,
                        sign = instructionSigns[i],
                        streetName = instructionNames[i],
                        turnAngleDeg = turnAngleAt(latitudes, longitudes, vertex),
                        roundaboutExitNumber = instructionRoundaboutExitNumbers[i],
                        roundaboutClockwise = instructionRoundaboutClockwise[i],
                        lanes = lanes,
                    )
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

        /**
         * Measures the route's direction change from the closest distinct
         * geometry vertices either side of a maneuver. GraphHopper signs
         * classify the instruction; this angle preserves how gently the road
         * actually bends for the HUD arrow.
         */
        private fun turnAngleAt(
            latitudes: DoubleArray,
            longitudes: DoubleArray,
            vertex: Int,
        ): Double? {
            var before = vertex - 1
            while (before >= 0 && GeoMath.haversineM(
                    latitudes[before], longitudes[before], latitudes[vertex], longitudes[vertex],
                ) < MIN_TURN_BEARING_SAMPLE_M
            ) {
                before--
            }
            var after = vertex + 1
            while (after < latitudes.size && GeoMath.haversineM(
                    latitudes[vertex], longitudes[vertex], latitudes[after], longitudes[after],
                ) < MIN_TURN_BEARING_SAMPLE_M
            ) {
                after++
            }
            if (before < 0 || after >= latitudes.size) return null
            val incoming = GeoMath.bearingDeg(
                latitudes[before], longitudes[before], latitudes[vertex], longitudes[vertex],
            )
            val outgoing = GeoMath.bearingDeg(
                latitudes[vertex], longitudes[vertex], latitudes[after], longitudes[after],
            )
            return GeoMath.signedBearingDeltaDeg(incoming, outgoing)
        }

        private const val MIN_TURN_BEARING_SAMPLE_M = 0.5
    }
}
