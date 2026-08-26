package com.organicmoto.maps.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PointParserTest {

    @Test
    fun validCoordinateFormsParseExactly() {
        val inputs = listOf(
            "-27.4698, 153.0251" to (-27.4698 to 153.0251),
            "-27.4698 153.0251" to (-27.4698 to 153.0251),
            "0,0" to (0.0 to 0.0),
            "-90,180" to (-90.0 to 180.0),
            "90,-180" to (90.0 to -180.0),
            ("-27.4698" + Char(9) + "153.0251") to (-27.4698 to 153.0251),
            ("-27.4698" + Char(10) + "153.0251") to (-27.4698 to 153.0251),
        )
        inputs.forEach { (input, expected) ->
            val point = PointParser.parse(input)
            assertEquals(expected.first, point.lat, 0.0)
            assertEquals(expected.second, point.lon, 0.0)
        }
    }
    @Test
    fun invalidCoordinatesUseTheDocumentedErrorForms() {
        val invalid = listOf(
            "-91,153" to "Latitude out of range: -91.0",
            "27,181" to "Longitude out of range: 181.0",
            "NaN,NaN" to "Latitude out of range: NaN",
            "Infinity,0" to "Latitude out of range: Infinity",
            "." to "Expected \"lat,lon\" (or \"lat lon\") but got \".\"",
            "," to "Invalid latitude in \",\"",
            "-" to "Expected \"lat,lon\" (or \"lat lon\") but got \"-\"",
            "27" to "Expected \"lat,lon\" (or \"lat lon\") but got \"27\"",
            "27," to "Invalid longitude in \"27,\"",
            ",153" to "Invalid latitude in \",153\"",
            "27;153" to "Expected \"lat,lon\" (or \"lat lon\") but got \"27;153\"",
            "1,2,3" to "Expected \"lat,lon\" (or \"lat lon\") but got \"1,2,3\"",
            "" to "Expected \"lat,lon\" (or \"lat lon\") but got \"\"",
        )
        invalid.forEach { (input, expected) ->
            val failure = runCatching { PointParser.parse(input) }.exceptionOrNull()
            assertTrue("$input returned $failure", failure is IllegalArgumentException)
            assertEquals(expected, failure?.message)
        }
    }
}
