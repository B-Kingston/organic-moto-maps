package com.organicmoto.maps.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteSimilarityTest {

    @Test
    fun `haversine matches the known length of one degree at the equator`() {
        val meters = RouteSimilarity.haversineMeters(GeoPoint(0.0, 0.0), GeoPoint(0.0, 1.0))
        assertEquals(ONE_DEGREE_EQUATOR_METERS, meters, ONE_DEGREE_EQUATOR_METERS * 0.01)

        val meridian = RouteSimilarity.haversineMeters(GeoPoint(0.0, 0.0), GeoPoint(1.0, 0.0))
        assertEquals(ONE_DEGREE_EQUATOR_METERS, meridian, ONE_DEGREE_EQUATOR_METERS * 0.01)
    }

    @Test
    fun `identical polylines score essentially zero`() {
        val shape = listOf(GeoPoint(-27.0, 152.0), GeoPoint(-27.5, 152.6), GeoPoint(-28.0, 153.0))
        val score = RouteSimilarity.meanDistanceMeters(shape, shape)
        assertTrue("expected ~0 but was $score", score < 1.0)
    }

    @Test
    fun `a corridor shifted by 0_01 degrees scores around a kilometre`() {
        val reference = straightLine(latOffset = 0.0)
        val shifted = straightLine(latOffset = 0.01) // ~1.11 km north
        val score = RouteSimilarity.meanDistanceMeters(reference, shifted)
        assertTrue("expected ~1100 m but was $score", score in 900.0..1_300.0)
    }

    @Test
    fun `best match picks the candidate sharing the stored corridor`() {
        val stored = straightLine(latOffset = 0.0)
        val candidates = listOf(
            straightLine(latOffset = 0.05),
            straightLine(latOffset = 0.0), // the real match
            straightLine(latOffset = -0.08),
        )
        assertEquals(1, RouteSimilarity.bestMatchIndex(stored, candidates))
    }

    @Test
    fun `ties keep the lowest index`() {
        val stored = straightLine(latOffset = 0.0)
        val candidates = listOf(straightLine(latOffset = 0.02), straightLine(latOffset = 0.02))
        assertEquals(0, RouteSimilarity.bestMatchIndex(stored, candidates))
    }

    @Test
    fun `empty inputs have no match`() {
        val shape = straightLine(0.0)
        assertEquals(null, RouteSimilarity.bestMatchIndex(emptyList(), listOf(shape)))
        assertEquals(null, RouteSimilarity.bestMatchIndex(shape, emptyList()))
        assertEquals(null, RouteSimilarity.bestMatchIndex(emptyList(), emptyList()))
    }

    @Test
    fun `empty candidates rank below real ones rather than winning by default`() {
        val score = RouteSimilarity.meanDistanceMeters(straightLine(0.0), emptyList())
        assertEquals(Double.MAX_VALUE, score, 0.0)
    }

    private fun straightLine(latOffset: Double): List<GeoPoint> =
        (0..10).map { step -> GeoPoint(-27.0 + latOffset + step * 0.05, 150.0 + step * 0.05) }

    private companion object {
        const val ONE_DEGREE_EQUATOR_METERS = 111_195.0
    }
}
