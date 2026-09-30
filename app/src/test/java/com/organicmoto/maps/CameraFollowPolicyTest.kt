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
    fun guidanceZoomBandsFollowTheSpeedBoundaries() {
        assertEquals(18.0, guidanceZoomFor(0.0), 0.0)
        assertEquals(18.0, guidanceZoomFor(1.0), 0.0)
        assertEquals(18.0, guidanceZoomFor(6.999), 0.0)
        assertEquals(17.0, guidanceZoomFor(7.0), 0.0)
        assertEquals(17.0, guidanceZoomFor(13.999), 0.0)
        assertEquals(16.0, guidanceZoomFor(14.0), 0.0)
        assertEquals(16.0, guidanceZoomFor(21.999), 0.0)
        assertEquals(15.0, guidanceZoomFor(22.0), 0.0)
        assertEquals(15.0, guidanceZoomFor(30.999), 0.0)
        assertEquals(14.0, guidanceZoomFor(31.0), 0.0)
        assertEquals(14.0, guidanceZoomFor(80.0), 0.0)
    }

    @Test
    fun guidanceZoomNeverIncreasesAsTheRiderSpeedsUp() {
        var previous = guidanceZoomFor(0.0)
        var speed = 0.0
        while (speed <= 60.0) {
            val zoom = guidanceZoomFor(speed)
            assertTrue(
                "zoom rose from $previous to $zoom at $speed m/s",
                zoom <= previous,
            )
            previous = zoom
            speed += 0.25
        }
        assertEquals(14.0, previous, 0.0)
    }

    @Test
    fun invalidSpeedKeepsTheClosestBandInsteadOfZoomingOut() {
        val closest = guidanceZoomFor(0.0)
        assertEquals(closest, guidanceZoomFor(Double.NaN), 0.0)
        assertEquals(closest, guidanceZoomFor(Double.POSITIVE_INFINITY), 0.0)
        assertEquals(closest, guidanceZoomFor(Double.NEGATIVE_INFINITY), 0.0)
        assertEquals(closest, guidanceZoomFor(-1.0), 0.0)
        val stationaryThreshold = followMovementThresholdMeters(0.0)
        assertEquals(stationaryThreshold, followMovementThresholdMeters(Double.NaN), 0.0)
        assertEquals(stationaryThreshold, followMovementThresholdMeters(-4.0), 0.0)
    }

    @Test
    fun guidanceCameraStaysCloseAndKeepsTheRiderLowInAForwardTiltedView() {
        assertTrue(
            "guidance tilt must sit in the described forward band",
            GUIDANCE_TILT_DEGREES in 55.0..60.0,
        )
        assertEquals(0.70, GUIDANCE_RIDER_VERTICAL_FRACTION, 1e-9)
        assertEquals(0.40, GUIDANCE_TOP_PADDING_FRACTION, 1e-9)
        assertEquals(
            GUIDANCE_RIDER_VERTICAL_FRACTION,
            (1.0 + GUIDANCE_TOP_PADDING_FRACTION) / 2.0,
            1e-9,
        )
        // The highway band must stay closer than the old zoom 13 so the road
        // ahead still fills the frame.
        assertTrue(guidanceZoomFor(35.0) > 13.0)
        assertTrue(guidanceZoomFor(0.0) > 17.0)
    }

    @Test
    fun queuedDebugFixesOwnGuidanceWhileTheRunnerIsDriving() {
        // The visual runner's fixes must beat the emulator's parked live
        // provider; release builds (no debug logs) always follow live GPS.
        assertTrue(debugFixOwnsGuidance(debugLogs = true, sinceDebugFixMs = 0))
        assertTrue(debugFixOwnsGuidance(debugLogs = true, sinceDebugFixMs = 15_000))
        assertFalse(
            debugFixOwnsGuidance(debugLogs = true, sinceDebugFixMs = DEBUG_FIX_AUTHORITY_MS + 1),
        )
        assertFalse(debugFixOwnsGuidance(debugLogs = false, sinceDebugFixMs = 0))
        // No injection (including immediately after device boot), or reset
        // after END, must never suppress the live provider.
        assertFalse(debugFixOwnsGuidance(debugLogs = true, sinceDebugFixMs = null))
        assertFalse(debugFixOwnsGuidance(debugLogs = true, sinceDebugFixMs = -1))
        assertTrue(debugFixOwnsGuidance(debugLogs = true, sinceDebugFixMs = DEBUG_FIX_AUTHORITY_MS))
        assertFalse(debugFixOwnsGuidance(debugLogs = true, sinceDebugFixMs = 60_000))
    }

    @Test
    fun guidanceBearingFallsBackToLastCourseAndHandlesNorthWraparound() {
        val tracker = GuidanceBearingTracker()
        assertEquals(0.0, tracker.resolve(Double.NaN, 0.0), 0.0)
        assertEquals(358.0, tracker.resolve(-2.0, 120.0), 0.0)
        assertEquals(358.0, tracker.resolve(Double.NaN, 120.0), 0.0)
        assertEquals(4.0, tracker.resolve(364.0, 120.0), 0.0)
        assertEquals(4.0, tracker.resolve(Double.NaN, 90.0), 0.0)
        assertEquals(358.0, tracker.resolve(718.0, 0.0), 0.0)
        assertEquals(6.0, bearingDistanceDegrees(358.0, 4.0), 1e-9)
        assertEquals(1.0, bearingDistanceDegrees(359.5, 0.5), 1e-9)
        assertEquals(1.0, bearingDistanceDegrees(0.5, 359.5), 1e-9)
        assertTrue(guidanceOrientationNeedsUpdate(0.0, 15.0, 0.0))
        assertTrue(guidanceOrientationNeedsUpdate(0.0, 0.0, GUIDANCE_TILT_DEGREES - 3.0))
        assertFalse(guidanceOrientationNeedsUpdate(359.0, 2.0, GUIDANCE_TILT_DEGREES))
        assertTrue(guidanceOrientationNeedsUpdate(0.0, 0.0, Double.NaN))
    }

    @Test
    fun aMapDragReleasesFollowButAZoomGestureDoesNotCountAsAPan() {
        assertTrue(isManualPan(0.0, 0.0, 16.0, 0.0002, 0.0, 16.0))
        assertFalse(isManualPan(0.0, 0.0, 16.0, 0.0002, 0.0, 16.5))
        assertFalse(isManualPan(0.0, 0.0, 16.0, 0.00001, 0.0, 16.0))
    }
}
