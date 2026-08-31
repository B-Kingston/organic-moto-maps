package com.organicmoto.maps.geocoding

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GeocoderIndexParseTest {

    @Test
    fun rejectsWrongMagicSmallAndTruncatedHeaders() {
        val wrongMagic = validBytes().also { it[0] = 'X'.code.toByte() }
        assertIllegal { parse(wrongMagic) }
        assertIllegal { parse(ByteArray(31)) }
        assertIllegal { parse(ByteArray(7)) }
    }

    @Test
    fun rejectsInvalidHeaderCountsAndOffsets() {
        assertIllegal { parse(validBytes().withInt(8, 0)) }
        assertIllegal { parse(validBytes().withInt(16, 4)) }
        assertIllegal { parse(validBytes().withInt(20, 45)) }
        assertIllegal { parse(validBytes().withInt(24, 10)) }
        assertIllegal { parse(validBytes().withInt(28, 10)) }
        assertIllegal { parse(validBytes().withInt(28, validBytes().size + 1)) }
    }

    @Test
    fun rejectsDocOffsetsDictionaryMismatchAndOverlongVarints() {
        val bytes = validBytes()
        val docsOffset = intAt(bytes, 20)
        assertIllegal { parse(bytes.withInt(32, docsOffset - 1)) }
        assertIllegal { parse(bytes.withInt(28, intAt(bytes, 28) - 1)) }

        val overlong = validBytes()
        val postingsOffset = intAt(overlong, 28)
        repeat(6) { index -> overlong[postingsOffset + index] = 0x80.toByte() }
        val index = parse(overlong)
        val term = index.termRange("c".encodeToByteArray()).first
        assertIllegal { index.termAt(term) }
    }

    @Test
    fun parsesLookupsAndDocumentFields() {
        val index = parse(validBytes())
        assertEquals(3, index.docCount)
        assertEquals(5, index.termCount)
        assertEquals(1, index.localityCount)
        assertArrayEquals(intArrayOf(0), index.findTerm("cafe".encodeToByteArray()))
        assertEquals(null, index.findTerm("missing".encodeToByteArray()))

        val brisbaneRange = index.termRange("bris".encodeToByteArray())
        assertFalse(brisbaneRange.isEmpty())
        val brisbaneTerm = index.termAt(brisbaneRange.first)
        assertEquals("brisbane", brisbaneTerm.first.decodeToString())
        assertArrayEquals(intArrayOf(1, 2), brisbaneTerm.second)
        assertEquals("cafe", index.termAt(index.termRange("c".encodeToByteArray()).first).first.decodeToString())

        assertEquals(GeocoderIndex.TYPE_POI, index.docType(0))
        assertEquals(GeocoderIndex.TYPE_STREET, index.docType(1))
        assertEquals(GeocoderIndex.TYPE_LOCALITY, index.docType(2))
        assertIllegal { index.docType(3) }
        val corruptType = validBytes().also { it[intAt(it, 32)] = 9 }
        assertIllegal { parse(corruptType)?.docType(0) }

        val doc = index.doc(0)
        assertEquals(0, doc.id)
        assertEquals(GeocoderIndex.TYPE_POI, doc.type)
        assertEquals(4, doc.subType)
        assertEquals(200, doc.rank)
        assertEquals(-27.4698, doc.lat, 1e-7)
        assertEquals(153.0251, doc.lon, 1e-7)
        assertEquals(0, doc.localityId)
        assertEquals("Cafe", doc.name)
        assertEquals(listOf("cafe"), doc.nameTokens)
        assertEquals("Brisbane", doc.cityName)
    }

    @Test
    fun batchReverseGeocodePrefersNearbyPoiThenStreetAndLocality() {
        val index = parse(
            buildIndex(
                docs = listOf(
                    TestDoc("Lookout", GeocoderIndex.TYPE_POI, 0, 100, -270000000, 1530000000, -1, "Hilltown"),
                    TestDoc("Range Road", GeocoderIndex.TYPE_STREET, 0, 100, -271000000, 1530000000, -1, "Hilltown"),
                    TestDoc("Hilltown", GeocoderIndex.TYPE_LOCALITY, 0, 100, -272000000, 1530000000, -1, ""),
                ),
                terms = mapOf("place" to listOf(0, 1, 2)),
            ),
        )

        val result = index.reverseGeocode(
            listOf(
                -27.0 to 153.0,
                -27.1 to 153.0,
                -27.2 to 153.0,
            ),
        )

        assertEquals("Lookout", result[0]?.name)
        assertEquals("Range Road", result[1]?.name)
        assertEquals("Hilltown", result[2]?.name)
    }

    private fun parse(bytes: ByteArray): GeocoderIndex =
        GeocoderIndex.parse(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN))

    private fun assertIllegal(block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        assertTrue("expected IllegalStateException but was $failure", failure is IllegalStateException)
    }

    private fun validBytes(): ByteArray = buildIndex(
        docs = listOf(
            TestDoc("Cafe", GeocoderIndex.TYPE_POI, 4, 200, -274698000, 1530251000, 0, "Brisbane"),
            TestDoc("Ann Street", GeocoderIndex.TYPE_STREET, 1, 150, -274700000, 1530250000, 0, "Brisbane"),
            TestDoc("Brisbane", GeocoderIndex.TYPE_LOCALITY, 0, 235, -272000000, 1530000000, -1, ""),
        ),
        terms = mapOf(
            "ann" to listOf(1),
            "brisbane" to listOf(1, 2),
            "cafe" to listOf(0),
            "street" to listOf(1),
            "fuel" to listOf(0),
        ),
    )

    private fun ByteArray.withInt(offset: Int, value: Int): ByteArray =
        copyOf().also { bytes ->
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value)
        }

    private fun intAt(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(offset)
}
