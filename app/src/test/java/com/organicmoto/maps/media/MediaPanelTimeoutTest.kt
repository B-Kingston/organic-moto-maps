package com.organicmoto.maps.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPanelTimeoutTest {

    @Test
    fun openingStartsAFullFifteenSecondInactivityWindow() {
        val timer = MediaPanelInactivityTimer(openedAtMillis = 1_000L)

        assertEquals(1f, timer.remaining(1_000L), 0f)
        assertEquals(0.5f, timer.remaining(8_500L), 0f)
        assertFalse(timer.isExpired(15_999L))
        assertTrue(timer.isExpired(16_000L))
    }

    @Test
    fun panelInteractionRestartsTheWindowFromItsLatestTime() {
        val timer = MediaPanelInactivityTimer(openedAtMillis = 1_000L)
        timer.recordInteraction(9_000L)

        assertEquals(1f, timer.remaining(9_000L), 0f)
        assertEquals(0.5f, timer.remaining(16_500L), 0f)
        assertFalse(timer.isExpired(23_999L))
        assertTrue(timer.isExpired(24_000L))
    }

    @Test
    fun readingProgressAndExpiryDoesNotRestartTheWindow() {
        val timer = MediaPanelInactivityTimer(openedAtMillis = 1_000L)

        assertEquals(0.5f, timer.remaining(8_500L), 0f)
        assertFalse(timer.isExpired(15_999L))
        assertTrue(timer.isExpired(16_000L))
    }
}
