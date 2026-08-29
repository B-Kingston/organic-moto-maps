package com.organicmoto.maps.routing.navigation

/**
 * A single GPS fix reduced to what the guidance engine consumes.
 * Plain data so JVM tests can drive the whole engine without Android.
 */
data class GpsFix(
    val lat: Double,
    val lon: Double,
    /** Speed in metres per second; negative when the device reports none. */
    val speedMps: Double = -1.0,
    /** Horizontal accuracy in metres; NaN when unknown. */
    val accuracyM: Double = Double.NaN,
    /** Fix timestamp in milliseconds; used for extrapolation eligibility. */
    val timestampMs: Long,
) {
    val hasSpeed: Boolean get() = speedMps >= 0.0
    val hasAccuracy: Boolean get() = !accuracyM.isNaN()
}

/**
 * Rolling direction estimate from recent fixes, ported in spirit from Organic
 * Maps' PositionAccumulator: keep roughly the last kMinTrackLengthM metres of
 * movement, discard segments shorter than kMinValidSegmentLengthM (GPS jitter)
 * and reset the whole track on a jump longer than kMaxValidSegmentLengthM
 * (teleport / fix glitch).
 *
 * The direction it reports seeds rerouting so a fresh route continues the
 * rider's actual heading on divided carriageways instead of U-turning.
 */
class DirectionAccumulator {
    private val points = ArrayDeque<DoubleArray>() // [lat, lon]
    private var trackLengthM = 0.0

    fun push(fix: GpsFix) {
        val last = points.lastOrNull()
        if (last == null) {
            points.addLast(doubleArrayOf(fix.lat, fix.lon))
            return
        }
        val dist = GeoMath.haversineM(last[0], last[1], fix.lat, fix.lon)
        when {
            dist < MIN_VALID_SEGMENT_M -> return // jitter, ignore entirely
            dist > MAX_VALID_SEGMENT_M -> { // discontinuity: restart track
                points.clear()
                trackLengthM = 0.0
                points.addLast(doubleArrayOf(fix.lat, fix.lon))
                return
            }
        }
        points.addLast(doubleArrayOf(fix.lat, fix.lon))
        trackLengthM += dist
        // Trim from the head while the remaining track still exceeds the
        // minimum useful length.
        while (points.size >= 2 && trackLengthM > MIN_TRACK_LENGTH_M) {
            val head = points.first()
            val next = points.elementAt(1)
            val seg = GeoMath.haversineM(head[0], head[1], next[0], next[1])
            if (trackLengthM - seg < MIN_TRACK_LENGTH_M) break
            points.removeFirst()
            trackLengthM -= seg
        }
    }

    /** Estimated heading in degrees clockwise from north, or NaN when unknown. */
    fun headingDeg(): Double {
        val first = points.firstOrNull() ?: return Double.NaN
        val last = points.lastOrNull() ?: return Double.NaN
        if (points.size < 2) return Double.NaN
        return GeoMath.bearingDeg(first[0], first[1], last[0], last[1])
    }

    fun clear() {
        points.clear()
        trackLengthM = 0.0
    }

    companion object {
        const val MIN_TRACK_LENGTH_M = 70.0
        const val MIN_VALID_SEGMENT_M = 10.0
        const val MAX_VALID_SEGMENT_M = 80.0
    }
}
