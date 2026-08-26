package com.organicmoto.maps.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class PolylineCodecTest {

    @Test
    fun `encodes the canonical three-point example`() {
        val points = listOf(
            GeoPoint(38.5, -120.2),
            GeoPoint(40.7, -120.95),
            GeoPoint(43.252, -126.453),
        )
        assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@", PolylineCodec.encode(points))
    }

    @Test
    fun `decodes the canonical example back onto the same grid`() {
        val decoded = PolylineCodec.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        val expected = listOf(
            38.5 to -120.2,
            40.7 to -120.95,
            43.252 to -126.453,
        )
        assertEquals(expected.size, decoded.size)
        expected.forEachIndexed { index, (lat, lon) ->
            assertEquals(lat, decoded[index].lat, 1e-9)
            assertEquals(lon, decoded[index].lon, 1e-9)
        }
    }

    @Test
    fun `round-trips a long Queensland-style track within quantisation error`() {
        val random = Random(seed = 42)
        val points = List(500) {
            GeoPoint(
                lat = -29.0 + random.nextDouble() * 18.0,
                lon = 138.0 + random.nextDouble() * 16.0,
            )
        }
        val decoded = PolylineCodec.decode(PolylineCodec.encode(points))
        assertEquals(points.size, decoded.size)
        points.zip(decoded).forEach { (original, restored) ->
            assertTrue(abs(original.lat - restored.lat) <= QUANTISATION_LIMIT)
            assertTrue(abs(original.lon - restored.lon) <= QUANTISATION_LIMIT)
        }
    }

    @Test
    fun `round-trips a single point`() {
        val point = listOf(GeoPoint(-27.4679, 153.0281))
        val decoded = PolylineCodec.decode(PolylineCodec.encode(point))
        assertEquals(1, decoded.size)
        assertEquals(-27.4679, decoded[0].lat, 1e-9)
        assertEquals(153.0281, decoded[0].lon, 1e-9)
    }

    @Test
    fun `handles empty input symmetrically`() {
        assertEquals("", PolylineCodec.encode(emptyList()))
        assertTrue(PolylineCodec.decode("").isEmpty())
    }

    @Test
    fun `keeps large negative deltas exact across the antimeridian convention`() {
        // Delta of exactly one degree south-west from a previous coordinate.
        val points = listOf(GeoPoint(-20.0, 149.0), GeoPoint(-21.0, 148.0))
        val decoded = PolylineCodec.decode(PolylineCodec.encode(points))
        assertEquals(-21.0, decoded[1].lat, 1e-9)
        assertEquals(148.0, decoded[1].lon, 1e-9)
    }

    @Test
    fun `rejects a coordinate wider than a Long`() {
        // Every '_' carries the continuation bit with a zero payload, so a
        // long enough run never terminates within Long precision.
        assertThrows(IllegalArgumentException::class.java) {
            PolylineCodec.decode("_".repeat(14))
        }
    }

    @Test
    fun `rejects a string that stops mid-coordinate`() {
        assertThrows(IllegalArgumentException::class.java) {
            PolylineCodec.decode("_p~iF~ps") // trailing chunks keep asking for more
        }
    }

    @Test
    fun `rejects characters outside the encoding alphabet`() {
        assertThrows(IllegalArgumentException::class.java) {
            PolylineCodec.decode("_p iF")
        }
    }

    @Test
    fun `round-trips coordinates next to the antimeridian`() {
        val points = listOf(
            GeoPoint(0.0, 179.99999),
            GeoPoint(0.00001, -179.99999),
            GeoPoint(-0.00001, 179.99995),
        )
        val decoded = PolylineCodec.decode(PolylineCodec.encode(points))
        assertEquals(points.size, decoded.size)
        points.zip(decoded).forEach { (expected, actual) ->
            assertTrue(abs(expected.lat - actual.lat) <= QUANTISATION_LIMIT)
            assertTrue(abs(expected.lon - actual.lon) <= QUANTISATION_LIMIT)
        }
    }

    @Test
    fun `round-trips latitude and longitude boundaries on the encoding grid`() {
        val points = listOf(
            GeoPoint(-90.0, -180.0),
            GeoPoint(90.0, 180.0),
            GeoPoint(0.0, 0.0),
        )
        val decoded = PolylineCodec.decode(PolylineCodec.encode(points))
        assertEquals(points.size, decoded.size)
        points.zip(decoded).forEach { (expected, actual) ->
            assertEquals(expected.lat, actual.lat, 0.0)
            assertEquals(expected.lon, actual.lon, 0.0)
        }
    }

    @Test
    fun `round-trips seeded random geometries within quantisation`() {
        val random = Random(7)
        repeat(200) {
            val points = List(random.nextInt(1, 40)) {
                GeoPoint(
                    -90.0 + random.nextDouble() * 180.0,
                    -180.0 + random.nextDouble() * 360.0,
                )
            }
            val decoded = PolylineCodec.decode(PolylineCodec.encode(points))
            assertEquals(points.size, decoded.size)
            points.zip(decoded).forEach { (expected, actual) ->
                assertTrue(abs(expected.lat - actual.lat) <= QUANTISATION_LIMIT)
                assertTrue(abs(expected.lon - actual.lon) <= QUANTISATION_LIMIT)
            }
        }
    }

    private companion object {
        /** One quantum is 5e-6 degrees; allow a hair more for binary rounding. */
        const val QUANTISATION_LIMIT = 5.1e-6
    }
}
