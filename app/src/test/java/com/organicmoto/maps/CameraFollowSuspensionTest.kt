package com.organicmoto.maps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraFollowSuspensionTest {

    @Test
    fun cameraLockRemainsSuspendedForTheWholeGestureAndCooldown() {
        val started = CameraFollowSuspension().gestureStarted()
        assertTrue(started.isSuspended(nowElapsedRealtimeMs = 10_000L))
        assertEquals(Long.MAX_VALUE, started.remainingDelayMs(nowElapsedRealtimeMs = 10_000L))

        val ended = started.gestureEnded(nowElapsedRealtimeMs = 10_250L)
        assertTrue(ended.isSuspended(nowElapsedRealtimeMs = 10_250L))
        assertTrue(ended.isSuspended(nowElapsedRealtimeMs = 11_749L))
        assertFalse(ended.isSuspended(nowElapsedRealtimeMs = 11_750L))
        assertEquals(1L, ended.remainingDelayMs(nowElapsedRealtimeMs = 11_749L))
        assertEquals(0L, ended.remainingDelayMs(nowElapsedRealtimeMs = 11_750L))
    }

    @Test
    fun eachNewGestureRestartsTheFullCooldownFromItsOwnEnd() {
        val firstEnd = CameraFollowSuspension()
            .gestureStarted()
            .gestureEnded(nowElapsedRealtimeMs = 1_000L)
        assertEquals(500L, firstEnd.remainingDelayMs(nowElapsedRealtimeMs = 2_000L))

        val secondEnd = firstEnd
            .gestureStarted()
            .gestureEnded(nowElapsedRealtimeMs = 2_100L)
        assertTrue(secondEnd.isSuspended(nowElapsedRealtimeMs = 3_599L))
        assertFalse(secondEnd.isSuspended(nowElapsedRealtimeMs = 3_600L))
    }

    @Test
    fun anElapsedCooldownCanBeClearedBeforeTheNextLockCycle() {
        val ended = CameraFollowSuspension().gestureEnded(nowElapsedRealtimeMs = 50L)
        assertEquals(CameraFollowSuspension(), ended.cooldownElapsed())
        assertFalse(ended.cooldownElapsed().isSuspended(nowElapsedRealtimeMs = 50L))
    }
}
