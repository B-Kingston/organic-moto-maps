package com.organicmoto.maps

import org.junit.Assert.assertEquals
import org.junit.Test

/** Contract of the motorsport delta formatting used by the HUD cell. */
class NavigationHudDeltaFormatTest {

    @Test
    fun `nan renders as dash`() {
        assertEquals("—", NavigationHudFormat.deltaText(Double.NaN))
    }

    @Test
    fun `lost time carries a plus sign`() {
        assertEquals("+12.3 s", NavigationHudFormat.deltaText(12.34))
        assertEquals("+1.0 s", NavigationHudFormat.deltaText(1.0))
    }

    @Test
    fun `gained time carries a minus sign`() {
        assertEquals("-1.0 s", NavigationHudFormat.deltaText(-1.0))
        assertEquals("-35.7 s", NavigationHudFormat.deltaText(-35.68))
    }

    @Test
    fun `near zero collapses to plain seconds`() {
        assertEquals("0.0 s", NavigationHudFormat.deltaText(0.0))
        assertEquals("0.0 s", NavigationHudFormat.deltaText(0.04))
        assertEquals("0.0 s", NavigationHudFormat.deltaText(-0.04))
    }
}
