package com.organicmoto.maps.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Contract tests for [GpxParser] and [GpxGeometry]: GPX 1.1 tracks, GPX 1.0
 * routes, waypoint lists, malformed input, and the thinning invariant.
 */
class GpxParserTest {

    private fun parse(xml: String): GpxParser.GpxRoute =
        GpxParser.parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))

    @Test
    fun parsesTrackWithElevationAndTimeChildren() {
        val route = parse(
            """<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
                 <metadata><name>Test ride</name></metadata>
                 <trk><trkseg>
                   <trkpt lat="-27.0" lon="152.5"><ele>100</ele><time>2026-01-01T00:00:00Z</time></trkpt>
                   <trkpt lat="-27.1" lon="152.6"></trkpt>
                 </trkseg></trk>
               </gpx>"""
        )
        assertEquals("Test ride", route.name)
        assertEquals(listOf(GeoPoint(-27.0, 152.5), GeoPoint(-27.1, 152.6)), route.points)
    }

    @Test
    fun parsesMultipleTrackSegmentsInOrder() {
        val route = parse(
            """<gpx version="1.1">
                 <trk>
                   <trkseg><trkpt lat="1.0" lon="10.0"/><trkpt lat="1.1" lon="10.1"/></trkseg>
                   <trkseg><trkpt lat="1.2" lon="10.2"/></trkseg>
                 </trk>
               </gpx>"""
        )
        assertEquals(3, route.points.size)
        assertEquals(GeoPoint(1.2, 10.2), route.points.last())
    }

    @Test
    fun fallsBackToRoutePointsThenWaypoints() {
        val rte = parse(
            """<gpx version="1.0"><rte>
                 <rtept lat="2.0" lon="20.0"/><rtept lat="2.5" lon="20.5"/>
               </rte></gpx>"""
        )
        assertEquals(listOf(GeoPoint(2.0, 20.0), GeoPoint(2.5, 20.5)), rte.points)

        val wpt = parse(
            """<gpx version="1.1">
                 <wpt lat="3.0" lon="30.0"><name>Start</name></wpt>
                 <wpt lat="3.5" lon="30.5"><name>End</name></wpt>
               </gpx>"""
        )
        assertEquals(listOf(GeoPoint(3.0, 30.0), GeoPoint(3.5, 30.5)), wpt.points)
    }

    @Test
    fun rejectsNonGpxAndEmptyDocuments() {
        assertThrows(GpxParser.GpxParseException::class.java) {
            parse("<html><body>Not a GPX</body></html>")
        }
        assertThrows(GpxParser.GpxParseException::class.java) {
            parse("""<gpx version="1.1"></gpx>""")
        }
    }

    @Test
    fun rejectsInvalidCoordinates() {
        assertThrows(GpxParser.GpxParseException::class.java) {
            parse("""<gpx version="1.1"><trk><trkseg><trkpt lat="95.0" lon="0.0"/><trkpt lat="1.0" lon="0.0"/></trkseg></trk></gpx>""")
        }
        assertThrows(GpxParser.GpxParseException::class.java) {
            parse("""<gpx version="1.1"><trk><trkseg><trkpt lat="abc" lon="0.0"/><trkpt lat="1.0" lon="0.0"/></trkseg></trk></gpx>""")
        }
    }

    @Test
    fun routingEndpointsPassThroughOpenTrack() {
        val pts = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.5, 0.5), GeoPoint(1.0, 1.0))
        assertEquals(GeoPoint(0.0, 0.0) to GeoPoint(1.0, 1.0), GpxGeometry.routingEndpoints(pts))
    }

    @Test
    fun routingEndpointsResolvesLoopAntipode() {
        // Closed loop: start == end; the farthest point (~11 km west) wins.
        val far = GeoPoint(0.0, -0.1)
        val pts = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.01, 0.01), far, GeoPoint(0.0, 0.0))
        val (from, to) = GpxGeometry.routingEndpoints(pts)
        assertEquals(GeoPoint(0.0, 0.0), from)
        assertEquals(far, to)
    }

    @Test
    fun blankMetadataNameYieldsNull() {
        val route = parse(
            """<gpx version="1.1"><metadata><name>   </name></metadata>
               <trk><trkseg><trkpt lat="1.0" lon="1.0"/><trkpt lat="1.1" lon="1.1"/></trkseg></trk></gpx>"""
        )
        assertNull(route.name)
    }

    @Test
    fun haversineMatchesKnownDistance() {
        // Brisbane CBD to Ipswich is roughly 30 km; assert the model is sane.
        val d = GpxGeometry.haversineMeters(GeoPoint(-27.4698, 153.0251), GeoPoint(-27.6129, 152.7599))
        assertTrue("distance $d", d in 25_000.0..35_000.0)
    }

    @Test
    fun thinKeepsEndpointsAndSpacing() {
        val dense = List(1001) { i -> GeoPoint(-27.0 + i * 1e-5, 152.0) } // ~1.1 m steps
        val thinned = GpxGeometry.thin(dense, minSpacingMeters = 20.0)
        assertEquals(GeoPoint(-27.0, 152.0), thinned.first())
        assertEquals(dense.last(), thinned.last())
        for (i in 1 until thinned.size) {
            assertTrue(GpxGeometry.haversineMeters(thinned[i - 1], thinned[i]) >= 20.0)
        }
    }

    @Test
    fun lengthMetersIsSumOfSegments() {
        val pts = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 1.0), GeoPoint(0.0, 2.0))
        val oneDegree = GpxGeometry.haversineMeters(GeoPoint(0.0, 0.0), GeoPoint(0.0, 1.0))
        assertEquals(oneDegree * 2, GpxGeometry.lengthMeters(pts), 1e-6)
    }
}
