package com.organicmoto.maps.routing.navigation

import com.graphhopper.util.Instruction

/** A turn arrow painted on (or implied for) one lane. */
enum class LaneArrow { LEFT, STRAIGHT, RIGHT, UTURN }

/**
 * Lane recommendation for one maneuver: the lanes of the road being left, in
 * left-to-right order in the direction of travel, with the lane(s) the rider
 * should be in for the turn flagged.
 *
 * Built from the `moto_lanes` edge value that `tools/gh/MotoGraphImport.java`
 * stores per travel direction (see its KDoc for the wire format). Only roads
 * with two or more lanes produce guidance.
 *
 * [marked] is true when the lanes come from OSM `turn:lanes` arrows; false
 * when only the lane count is known and the recommendation is the outermost
 * lane on the turn side for the country's driving side.
 */
data class LaneGuidance(
    val lanes: List<Lane>,
    val marked: Boolean,
    val leftHandTraffic: Boolean,
) {
    /** [arrows] is empty for a lane without a known/painted arrow. */
    data class Lane(val arrows: Set<LaneArrow>, val recommended: Boolean)

    val recommendedIndices: List<Int>
        get() = lanes.indices.filter { lanes[it].recommended }

    /** Plain-language summary, e.g. "use the right lane of 3" or "use lanes 2 and 3 of 3". */
    fun description(): String {
        val picks = recommendedIndices
        val count = lanes.size
        val text = when {
            picks.size == count -> "any lane"
            picks.size == 1 && picks[0] == 0 -> "the left lane"
            picks.size == 1 && picks[0] == count - 1 -> "the right lane"
            picks.size == 1 -> "lane ${picks[0] + 1}"
            else -> "lanes " + picks.dropLast(1).joinToString(", ") { "${it + 1}" } +
                " and ${picks.last() + 1}"
        }
        val source = if (marked) "" else ", suggested"
        return "use $text of $count$source"
    }

    /** Parsed `moto_lanes` value before a maneuver picks lanes from it. */
    data class Road(
        /** Per lane, left to right; null when only the lane count is known. */
        val arrows: List<Set<LaneArrow>?>,
        val leftHandTraffic: Boolean,
    ) {
        val laneCount: Int get() = arrows.size
        val marked: Boolean get() = arrows.any { it != null }
    }

    companion object {
        /** How far before a maneuver an unknown approach segment may borrow lanes from upstream. */
        const val LOOKBACK_M = 150.0

        /** Parses one direction's `moto_lanes` value; null for missing or malformed data. */
        fun parse(value: String?): Road? {
            if (value == null || value.length < 2 || value[1] != ':') return null
            val leftHand = when (value[0]) {
                'L' -> true
                'R' -> false
                else -> return null
            }
            val arrows = value.substring(2).split('|').map { token ->
                if (token == "?") {
                    null
                } else {
                    token.map { c ->
                        when (c) {
                            'l' -> LaneArrow.LEFT
                            's' -> LaneArrow.STRAIGHT
                            'r' -> LaneArrow.RIGHT
                            'u' -> LaneArrow.UTURN
                            else -> return null
                        }
                    }.toSet()
                }
            }
            // A road is either fully count-only or fully marked.
            if (arrows.any { it == null } && arrows.any { it != null }) return null
            return Road(arrows, leftHand)
        }

        /**
         * Picks lanes for GraphHopper maneuver [sign] on [road]. Returns null
         * for single-lane roads, maneuvers without a lane choice (continue,
         * roundabouts, arrival), and marked lanes that contradict the turn.
         */
        fun recommend(road: Road?, sign: Int): LaneGuidance? {
            road ?: return null
            if (road.laneCount < 2) return null
            val movement = Movement.of(sign, road.leftHandTraffic) ?: return null
            val count = road.laneCount
            val picks: Set<Int> = if (road.marked) {
                val arrows = road.arrows.map { it.orEmpty() }
                val direct = arrows.indices.filter { movement.primary in arrows[it] }
                val secondary = movement.secondary
                val relaxed = if (direct.isEmpty() && secondary != null) {
                    arrows.indices.filter { secondary in arrows[it] }
                } else {
                    emptyList()
                }
                when {
                    direct.isNotEmpty() -> direct.toSet()
                    relaxed.isNotEmpty() -> relaxed.toSet()
                    else -> {
                        // An unmarked outer lane conventionally carries the
                        // remaining movement; a marked one contradicts the turn.
                        val outer = if (movement.towardLeft) 0 else count - 1
                        if (arrows[outer].isEmpty()) setOf(outer) else return null
                    }
                }
            } else {
                setOf(if (movement.towardLeft) 0 else count - 1)
            }
            val lanes = road.arrows.mapIndexed { index, arrows ->
                val shown = arrows ?: if (index in picks) setOf(movement.primary) else emptySet()
                Lane(shown, index in picks)
            }
            return LaneGuidance(lanes, road.marked, road.leftHandTraffic)
        }

        /**
         * Guidance for the maneuver at [turnVertex], reading the lanes of the
         * segment that enters it. When that segment carries no lane data, up
         * to [LOOKBACK_M] of earlier segments are searched (never past
         * [previousTurnVertex]); a segment known to have one lane stops the
         * search, since the rider has already left the multi-lane road.
         *
         * [segmentLanes] holds the raw `moto_lanes` value for segment
         * v → v+1 at index v.
         */
        fun forTurn(
            segmentLanes: List<String?>,
            cumulativeDistanceM: DoubleArray,
            turnVertex: Int,
            previousTurnVertex: Int,
            sign: Int,
        ): LaneGuidance? {
            if (turnVertex < 1 || segmentLanes.isEmpty()) return null
            val turnOffset = cumulativeDistanceM[turnVertex.coerceAtMost(cumulativeDistanceM.size - 1)]
            var segment = (turnVertex - 1).coerceAtMost(segmentLanes.size - 1)
            while (segment >= 0 && segment >= previousTurnVertex) {
                if (turnOffset - cumulativeDistanceM[segment + 1] > LOOKBACK_M) return null
                val road = parse(segmentLanes[segment])
                if (road != null) return recommend(road, sign)
                segment--
            }
            return null
        }
    }

    /**
     * Lane movement for a GraphHopper sign. [secondary] is accepted when no
     * lane carries [primary]: a keep/slight maneuver along a fork whose lanes
     * are only marked "through".
     */
    private data class Movement(val primary: LaneArrow, val secondary: LaneArrow?, val towardLeft: Boolean) {
        companion object {
            fun of(sign: Int, leftHandTraffic: Boolean): Movement? = when (sign) {
                Instruction.TURN_SHARP_LEFT, Instruction.TURN_LEFT ->
                    Movement(LaneArrow.LEFT, null, towardLeft = true)
                Instruction.TURN_SLIGHT_LEFT, Instruction.KEEP_LEFT ->
                    Movement(LaneArrow.LEFT, LaneArrow.STRAIGHT, towardLeft = true)
                Instruction.TURN_SHARP_RIGHT, Instruction.TURN_RIGHT ->
                    Movement(LaneArrow.RIGHT, null, towardLeft = false)
                Instruction.TURN_SLIGHT_RIGHT, Instruction.KEEP_RIGHT ->
                    Movement(LaneArrow.RIGHT, LaneArrow.STRAIGHT, towardLeft = false)
                Instruction.U_TURN_LEFT -> Movement(LaneArrow.UTURN, LaneArrow.LEFT, towardLeft = true)
                Instruction.U_TURN_RIGHT -> Movement(LaneArrow.UTURN, LaneArrow.RIGHT, towardLeft = false)
                // A U-turn crosses the oncoming traffic: right in left-hand traffic.
                Instruction.U_TURN_UNKNOWN -> if (leftHandTraffic) {
                    Movement(LaneArrow.UTURN, LaneArrow.RIGHT, towardLeft = false)
                } else {
                    Movement(LaneArrow.UTURN, LaneArrow.LEFT, towardLeft = true)
                }
                else -> null
            }
        }
    }
}
