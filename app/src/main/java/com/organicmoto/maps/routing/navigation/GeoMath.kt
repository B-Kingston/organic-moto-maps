package com.organicmoto.maps.routing.navigation

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.abs

/** Pure geographic math for the guidance engine. Plain JVM, no Android. */
internal object GeoMath {
    private const val EARTH_RADIUS_M = 6371008.8

    /** Great-circle distance in metres between two lat/lon points (degrees). */
    fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dPhi = Math.toRadians(lat2 - lat1)
        val dLambda = Math.toRadians(lon2 - lon1)
        val a = sin(dPhi / 2) * sin(dPhi / 2) +
            cos(phi1) * cos(phi2) * sin(dLambda / 2) * sin(dLambda / 2)
        return 2 * EARTH_RADIUS_M * atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    /**
     * Initial bearing from point 1 to point 2, degrees clockwise from north
     * in [0, 360).
     */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLambda = Math.toRadians(lon2 - lon1)
        val y = sin(dLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLambda)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Absolute angle difference between two bearings in degrees, in [0, 180]. */
    fun bearingDeltaDeg(a: Double, b: Double): Double {
        val d = abs(a - b) % 360.0
        return if (d > 180.0) 360.0 - d else d
    }

    /**
     * Signed shortest change from [fromDeg] to [toDeg], where positive means
     * clockwise/right and negative means counter-clockwise/left.
     */
    fun signedBearingDeltaDeg(fromDeg: Double, toDeg: Double): Double {
        val delta = (toDeg - fromDeg + 540.0) % 360.0 - 180.0
        return if (delta == -180.0) 180.0 else delta
    }

    /** Shortest squared distance from [p] to segment [a]-[b] in a local
     * equirectangular approximation (metres). Also returns the projection
     * parameter t in [0,1]. */
    fun segmentDistanceM(
        pLat: Double, pLon: Double,
        aLat: Double, aLon: Double,
        bLat: Double, bLon: Double,
    ): Pair<Double, Double> {
        // Local flat projection around the segment midpoint; distances here
        // are well under a kilometre so the approximation error is negligible.
        val midLat = Math.toRadians((aLat + bLat) / 2.0)
        val mPerDegLat = 111_132.0
        val mPerDegLon = cos(midLat) * 111_320.0
        val ax = aLon * mPerDegLon; val ay = aLat * mPerDegLat
        val bx = bLon * mPerDegLon; val by = bLat * mPerDegLat
        val px = pLon * mPerDegLon; val py = pLat * mPerDegLat
        val dx = bx - ax; val dy = by - ay
        val lenSq = dx * dx + dy * dy
        val t = if (lenSq <= 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / lenSq).coerceIn(0.0, 1.0)
        val cx = ax + t * dx; val cy = ay + t * dy
        val ex = px - cx; val ey = py - cy
        return Math.sqrt(ex * ex + ey * ey) to t
    }

    /** Circumference-independent heading from a small lat/lon delta (degrees). */
    fun headingFromDeltaDeg(dLatDeg: Double, dLonDeg: Double, atLatDeg: Double): Double {
        val y = dLonDeg * cos(Math.toRadians(atLatDeg))
        val x = dLatDeg
        return if (y == 0.0 && x == 0.0) Double.NaN else (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Degrees to radians without importing PI twice. */
    fun deg2rad(d: Double): Double = d * PI / 180.0
}
