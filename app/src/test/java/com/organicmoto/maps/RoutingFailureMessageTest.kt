package com.organicmoto.maps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingFailureMessageTest {

    @Test
    fun explainsOutOfRegionPoints() {
        val message = describeRoutingFailure("Point 0 is out of bounds: 43.7,7.3")
        assertTrue(message.contains("outside the active region"))
    }

    @Test
    fun keepsNoRouteMessagesHonest() {
        assertEquals("No route was found in the active region.", describeRoutingFailure("No route was found"))
    }

    @Test
    fun passesOtherFailuresThrough() {
        assertEquals("Routing failed: boom", describeRoutingFailure("Routing failed: boom"))
    }
}
