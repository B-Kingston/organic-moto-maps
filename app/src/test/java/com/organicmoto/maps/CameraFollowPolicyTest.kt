package com.organicmoto.maps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraFollowPolicyTest {

    @Test
    fun stationaryLocationJitterDoesNotRestartTheCameraAnimation() {
        assertFalse(
            shouldFollowCamera(
                cameraLat = 0.0,
                cameraLon = 0.0,
                cameraZoom = 16.5,
                targetLat = 0.00005,
                targetLon = 0.0,
                desiredZoom = 16.5,
                minimumMovementMeters = followMovementThresholdMeters(0.0),
            ),
        )
    }

    @Test
    fun accumulatedMovementOrZoomBandChangeMovesTheCamera() {
        assertTrue(
            shouldFollowCamera(
                cameraLat = 0.0,
                cameraLon = 0.0,
                cameraZoom = 16.5,
                targetLat = 0.0002,
                targetLon = 0.0,
                desiredZoom = 16.5,
                minimumMovementMeters = followMovementThresholdMeters(0.0),
            ),
        )
        assertTrue(
            shouldFollowCamera(
                cameraLat = 0.0,
                cameraLon = 0.0,
                cameraZoom = 15.0,
                targetLat = 0.0,
                targetLon = 0.0,
                desiredZoom = 15.5,
                minimumMovementMeters = 15.0,
            ),
        )
    }

    @Test
    fun movingThresholdGrowsWithSpeedWithinTheConfiguredBounds() {
        assertEquals(15.0, followMovementThresholdMeters(0.5), 0.0)
        assertEquals(10.0, followMovementThresholdMeters(20.0), 0.0)
        assertEquals(30.0, followMovementThresholdMeters(100.0), 0.0)
    }

    @Test
    fun guidanceCameraStaysCloseAndKeepsTheRiderLowInAForwardTiltedView() {
        assertEquals(17.0, guidanceZoomFor(0.0), 0.0)
        assertEquals(13.0, guidanceZoomFor(35.0), 0.0)
        assertTrue(GUIDANCE_TILT_DEGREES in 35.0..60.0)
        assertEquals(0.68, GUIDANCE_RIDER_VERTICAL_FRACTION, 0.0)
        assertEquals(0.36, GUIDANCE_TOP_PADDING_FRACTION, 1e-9)
        assertEquals(
            GUIDANCE_RIDER_VERTICAL_FRACTION,
            (1.0 + GUIDANCE_TOP_PADDING_FRACTION) / 2.0,
            1e-9,
        )
    }

    @Test
    fun guidanceBearingFallsBackToLastCourseAndHandlesNorthWraparound() {
        val tracker = GuidanceBearingTracker()
        assertEquals(0.0, tracker.resolve(Double.NaN, 0.0), 0.0)
        assertEquals(358.0, tracker.resolve(-2.0, 120.0), 0.0)
        assertEquals(358.0, tracker.resolve(Double.NaN, 120.0), 0.0)
        assertEquals(4.0, tracker.resolve(364.0, 120.0), 0.0)
        assertEquals(6.0, bearingDistanceDegrees(358.0, 4.0), 1e-9)
        assertTrue(guidanceOrientationNeedsUpdate(0.0, 15.0, 0.0))
        assertTrue(guidanceOrientationNeedsUpdate(0.0, 0.0, GUIDANCE_TILT_DEGREES - 3.0))
    }

    @Test
    fun aMapDragReleasesFollowButAZoomGestureDoesNotCountAsAPan() {
        assertTrue(isManualPan(0.0, 0.0, 16.0, 0.0002, 0.0, 16.0))
        assertFalse(isManualPan(0.0, 0.0, 16.0, 0.0002, 0.0, 16.5))
        assertFalse(isManualPan(0.0, 0.0, 16.0, 0.00001, 0.0, 16.0))
    }
}
