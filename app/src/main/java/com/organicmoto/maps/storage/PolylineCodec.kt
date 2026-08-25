package com.organicmoto.maps.storage

/**
 * One point on the Earth surface, in degrees. This is the vocabulary type of
 * the storage package: the codec, the similarity matcher, and the mini-map
 * banner all speak [GeoPoint], so none of them needs GraphHopper or Android.
 */
data class GeoPoint(val lat: Double, val lon: Double)

/**
 * Encodes and decodes polylines in the Google Maps encoded-polyline format
 * with the standard 1e5 precision (~1 m quantisation error).
 *
 * The format stores each coordinate rounded to 1e-5 degrees as a signed
 * variable-length delta against the previous coordinate (first point: delta
 * against zero). Chars occupy codes 63..126, so the encoding is plain ASCII
 * and fits a SQLite TEXT column unchanged.
 *
 * Round-trip guarantee: `decode(encode(p))` reproduces every coordinate to
 * within 5e-6 degrees of the original value.
 */
object PolylineCodec {

    /** Coordinate quantum in degrees; one unit is 1e-5 degrees. */
    private const val QUANTUM = 1e5

    /** First valid char code of the encoding alphabet. */
    private const val CHAR_OFFSET = 63

    fun encode(points: List<GeoPoint>): String {
        val out = StringBuilder(points.size * 4)
        var previousLat = 0L
        var previousLon = 0L
        for (point in points) {
            val lat = quantize(point.lat)
            val lon = quantize(point.lon)
            appendSigned(out, lat - previousLat)
            appendSigned(out, lon - previousLon)
            previousLat = lat
            previousLon = lon
        }
        return out.toString()
    }

    /**
     * Decodes [encoded] back to its points.
     *
     * @throws IllegalArgumentException when the string stops mid-coordinate,
     *   contains a char outside the alphabet, or encodes a delta wider than a
     *   Long. Callers treat a decode failure as corrupt stored data.
     */
    fun decode(encoded: String): List<GeoPoint> {
        val points = ArrayList<GeoPoint>()
        var index = 0
        var lat = 0L
        var lon = 0L
        while (index < encoded.length) {
            val dLat = readSigned(encoded, index).also { index = it.nextIndex }.value
            val dLon = readSigned(encoded, index).also { index = it.nextIndex }.value
            lat += dLat
            lon += dLon
            points.add(GeoPoint(lat / QUANTUM, lon / QUANTUM))
        }
        return points
    }

    /** Rounds degrees onto the 1e-5 grid as a Long delta unit. */
    private fun quantize(degrees: Double): Long = Math.round(degrees * QUANTUM)

    /** Zig-zags [delta] and emits it as base-32 chunks, least significant first. */
    private fun appendSigned(out: StringBuilder, delta: Long) {
        var chunk = (delta shl 1) xor (delta shr 63)
        do {
            var fiveBits = (chunk and 0x1FL).toInt()
            chunk = chunk ushr 5
            if (chunk > 0L) fiveBits = fiveBits or 0x20
            out.append((fiveBits + CHAR_OFFSET).toChar())
        } while (chunk > 0L)
    }

    private class SignedRead(val value: Long, val nextIndex: Int)

    private fun readSigned(encoded: String, startIndex: Int): SignedRead {
        var chunk = 0L
        var shift = 0
        var index = startIndex
        while (true) {
            require(index < encoded.length) {
                "Truncated polyline: coordinate continues past end of input"
            }
            val code = encoded[index].code
            require(code in CHAR_OFFSET..CHAR_OFFSET + 0x3F) {
                "Invalid polyline char code $code at index $index"
            }
            val fiveBits = code - CHAR_OFFSET
            chunk = chunk or ((fiveBits and 0x1F).toLong() shl shift)
            index++
            if (fiveBits and 0x20 == 0) break
            shift += 5
            require(shift <= 60) { "Polyline coordinate at index $startIndex overflows a Long" }
        }
        return SignedRead(value = (chunk ushr 1) xor -(chunk and 1L), nextIndex = index)
    }
}
