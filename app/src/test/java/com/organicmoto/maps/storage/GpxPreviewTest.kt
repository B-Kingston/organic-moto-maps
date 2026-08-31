package com.organicmoto.maps.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GpxPreviewTest {

    @Test
    fun samplesByDistanceAndAlwaysKeepsEndpoints() {
        val points = listOf(
            GeoPoint(0.0, 0.0),
            GeoPoint(0.0, 0.00001), // dense recorder burst near the start
            GeoPoint(0.0, 0.09),
            GeoPoint(0.0, 0.18),
            GeoPoint(0.0, 0.27),
        )

        val milestones = GpxPreview.milestones(points, maxMilestones = 4)

        assertEquals(4, milestones.size)
        assertEquals(points.first(), milestones.first().point)
        assertEquals(points.last(), milestones.last().point)
        assertEquals(0.0, milestones.first().progress, 0.0)
        assertEquals(1.0, milestones.last().progress, 0.0)
        assertTrue(milestones.zipWithNext().all { (a, b) -> b.distanceMeters >= a.distanceMeters })
    }

    @Test
    fun shortRideStillOffersStartMiddleAndFinish() {
        val points = listOf(
            GeoPoint(-27.0, 153.0),
            GeoPoint(-27.001, 153.001),
            GeoPoint(-27.002, 153.002),
        )

        assertEquals(3, GpxPreview.milestones(points).size)
    }

    @Test
    fun twoPointRideInterpolatesADistinctMiddleMilestone() {
        val points = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.01))

        val milestones = GpxPreview.milestones(points)

        assertEquals(3, milestones.size)
        assertEquals(0.005, milestones[1].point.lon, 1e-6)
        assertEquals(0.5, milestones[1].progress, 0.0)
    }
}
