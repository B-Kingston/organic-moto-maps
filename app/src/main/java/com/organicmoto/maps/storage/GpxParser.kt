package com.organicmoto.maps.storage

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import kotlin.math.max

/**
 * Parses GPX 1.0/1.1 documents into an ordered list of [GeoPoint]s.
 *
 * Sources, in priority order:
 *  - every `<trkseg>` of every `<trk>`, concatenated (multi-segment tracks
 *    join; segment gaps are not modelled as route stops);
 *  - otherwise the `<rtept>` points of the first `<rte>` (routed GPX exports
 *    from plotters such as Furkot);
 *  - otherwise the `<wpt>` waypoints (a waypoint list is a valid ride plan).
 *
 * Namespaces are ignored so both the GPX 1.0 and GPX 1.1 namespaces (and
 * namespace-less documents) parse identically. The parser never consults the
 * network: it reads the caller-provided [InputStream] only.
 */
object GpxParser {

    /** One parsed GPX document: the ride line plus the name, when present. */
    data class GpxRoute(val name: String?, val points: List<GeoPoint>)

    /**
     * Parses [input] as GPX.
     *
     * @throws GpxParseException when the stream is not well-formed XML, is not
     *   a GPX document, or contains no usable track/route/waypoint points.
     *   The stream is always fully consumed and closed.
     */
    fun parse(input: InputStream): GpxRoute {
        val parser = try {
            XmlPullParserFactory.newInstance().newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                setInput(input, null)
            }
        } catch (e: Exception) {
            throw GpxParseException("Not readable as GPX: ${e.message ?: "parse error"}", e)
        }

        var docName: String? = null
        val trackPoints = ArrayList<GeoPoint>()
        val routePoints = ArrayList<GeoPoint>()
        val wayPoints = ArrayList<GeoPoint>()

        try {
            var insideTrack = false
            var insideRoute = false
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != XmlPullParser.START_TAG) continue
                when (parser.name) {
                    "gpx" -> {
                        for (i in 0 until parser.attributeCount) {
                            if (parser.getAttributeName(i) == "version" &&
                                !parser.getAttributeValue(i).startsWith("1")
                            ) {
                                throw GpxParseException("Unsupported GPX version ${parser.getAttributeValue(i)}")
                            }
                        }
                    }
                    "trk" -> insideTrack = true
                    "rte" -> insideRoute = true
                    "trkseg" -> check(insideTrack) { "trkseg outside trk" }
                    "trkpt" -> if (insideTrack) trackPoints.add(readPoint(parser))
                    "rtept" -> if (insideRoute) routePoints.add(readPoint(parser))
                    "wpt" -> if (!insideTrack && !insideRoute) wayPoints.add(readPoint(parser))
                    "trk" -> insideTrack = false
                    "rte" -> insideRoute = false
                    "name" -> if (docName == null && !insideTrack && !insideRoute) {
                        // readPoint advances past END_TAG, so a name child
                        // captured here must be read with nextText().
                        docName = parser.nextText().trim()
                    }
                }
            }
        } catch (e: GpxParseException) {
            throw e
        } catch (e: Exception) {
            throw GpxParseException("Malformed GPX: ${e.message ?: "parse error"}", e)
        } finally {
            try { input.close() } catch (_: Exception) {}
        }

        val points = when {
            trackPoints.isNotEmpty() -> trackPoints
            routePoints.isNotEmpty() -> routePoints
            else -> wayPoints
        }
        if (points.size < 2) {
            throw GpxParseException("GPX contains no ride line (need at least 2 points)")
        }
        return GpxRoute(docName?.takeIf { it.isNotBlank() }, points)
    }

    /** Reads one lat/lon point; child elements (<ele>, <time>, extensions) are skipped. */
    private fun readPoint(parser: XmlPullParser): GeoPoint {
        val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
        val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
        if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
            throw GpxParseException("Point at line ${parser.lineNumber} has invalid lat/lon")
        }
        // Consume the element so its children (elevation, time, heart rate…)
        // are never mistaken for sibling points.
        var depth = parser.depth
        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == depth)) {
            // skip
        }
        return GeoPoint(lat, lon)
    }

    /** Thrown for every parse failure with a user-presentable message. */
    class GpxParseException(message: String, cause: Throwable? = null) : Exception(message, cause)
}

/**
 * Geometric helpers shared by the GPX import path: haversine length in metres
 * (same spherical mean-radius model as [RouteSimilarity]) and point thinning
 * that keeps the encoded polyline small while preserving shape.
 */
object GpxGeometry {

    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Great-circle distance in metres on a spherical Earth (mean radius). */
    fun haversineMeters(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * Math.asin(Math.sqrt(h))
    }

    /** Summed great-circle length of the polyline in metres. */
    fun lengthMeters(points: List<GeoPoint>): Double {
        var total = 0.0
        for (i in 1 until points.size) total += haversineMeters(points[i - 1], points[i])
        return total
    }

    /**
     * Removes points closer than [minSpacingMeters] to the previously kept
     * point; the first and last points are always kept. A GPX track logs a
     * point every few metres; encoding thousands of them inflates storage and
     * slows the similarity match for zero visual gain.
     */
    fun thin(points: List<GeoPoint>, minSpacingMeters: Double = 20.0): List<GeoPoint> {
        if (points.size <= 2) return points
        val out = ArrayList<GeoPoint>(points.size)
        out.add(points.first())
        for (i in 1 until points.size - 1) {
            if (haversineMeters(out.last(), points[i]) >= minSpacingMeters) out.add(points[i])
        }
        // The final point is a hard endpoint; drop kept points that sit
        // closer than the spacing to the end, never the end itself.
        while (out.size > 1 && haversineMeters(out.last(), points.last()) < minSpacingMeters) {
            out.removeAt(out.size - 1)
        }
        out.add(points.last())
        return out
    }

    /** Endpoints closer than this are treated as a closed loop (e.g. a return-to-start ride). */
    const val LOOP_THRESHOLD_METERS = 150.0

    /**
     * Routing endpoints for an imported track. A closed loop (start and end
     * coinciding) cannot route start-to-start; the destination becomes the
     * track point farthest from the start (the loop antipode) so the ride
     * follows the loop as one leg, guided by the full-track preferred
     * geometry. Point-to-point tracks return their own ends unchanged.
     */
    fun routingEndpoints(points: List<GeoPoint>): Pair<GeoPoint, GeoPoint> {
        val first = points.first()
        val last = points.last()
        if (haversineMeters(first, last) >= LOOP_THRESHOLD_METERS) return first to last
        var antipode = last
        var bestDistance = -1.0
        for (p in points) {
            val d = haversineMeters(first, p)
            if (d > bestDistance) {
                bestDistance = d
                antipode = p
            }
        }
        return first to antipode
    }
}
