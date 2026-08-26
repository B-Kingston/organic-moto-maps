package com.organicmoto.maps.geocoding

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random
import kotlin.system.measureTimeMillis

class SearchEngineJvmTest {

    @Test
    fun exactTokenMatchesRankAbovePrefixAndSubstringMatches() = runTest {
        val index = parse(buildIndex(
            docs = listOf(
                TestDoc("Cafe", GeocoderIndex.TYPE_POI, 2, 150, 0, 0, -1, ""),
                TestDoc("Cafe Central", GeocoderIndex.TYPE_POI, 2, 150, 0, 0, -1, ""),
                TestDoc("Cafehouse", GeocoderIndex.TYPE_POI, 2, 150, 0, 0, -1, ""),
            ),
            terms = mapOf("cafe" to listOf(0, 1, 2)),
        ))
        val results = engine(index).search("cafe ", limit = 3)
        assertEquals(listOf("Cafe", "Cafe Central", "Cafehouse"), results.map { it.name })
    }

    @Test
    fun blankQueriesAndNonPositiveLimitsReturnEmpty() = runTest {
        val search = engine(parse(buildIndex(
            listOf(TestDoc("Cafe", 0, 0, 1, 0, 0, -1, "")),
            mapOf("cafe" to listOf(0)),
        )))
        assertTrue(search.search("   ").isEmpty())
        assertTrue(search.search("cafe", limit = 0).isEmpty())
        assertTrue(search.search("cafe", limit = -1).isEmpty())
    }

    @Test
    fun coordinateQueriesReturnOneCoordinateResult() = runTest {
        val search = engine(emptySearchIndex())
        val comma = search.search("-27.4698, 153.0251")
        assertEquals(1, comma.size)
        assertEquals(GeocodeResultType.COORDINATE, comma.single().type)
        assertEquals(100.0, comma.single().score, 0.0)
        val semicolon = search.search("27;153")
        assertEquals(1, semicolon.size)
        assertEquals(GeocodeResultType.COORDINATE, semicolon.single().type)
    }

    @Test
    fun pivotDistanceReordersEqualNameCandidates() = runTest {
        val search = engine(parse(buildIndex(
            docs = listOf(
                TestDoc("Brisbane", GeocoderIndex.TYPE_POI, 0, 200, -280000000, 1500000000, -1, ""),
                TestDoc("Brisbane", GeocoderIndex.TYPE_POI, 0, 200, -270000000, 1530000000, -1, ""),
            ),
            terms = mapOf("brisbane" to listOf(0, 1)),
        )))
        val results = search.search("brisbane ", pivotLat = -27.0, pivotLon = 153.0, limit = 2)
        assertEquals(-27.0, results.first().lat, 1e-7)
        assertEquals(-28.0, results[1].lat, 1e-7)
    }

    @Test
    fun unicodeAndLongNamesDoNotCrashAndRemainDeterministic() = runTest {
        val docs = listOf(
            TestDoc("🏍️ Cafe", 0, 0, 1, 0, 0, -1, ""),
            TestDoc("東京駅", 1, 0, 1, 0, 0, -1, ""),
            TestDoc("مقهى", 0, 0, 1, 0, 0, -1, ""),
            TestDoc("Café", 0, 0, 1, 0, 0, -1, ""),
            TestDoc("A\u200BB", 1, 0, 1, 0, 0, -1, ""),
            TestDoc("Line\nBreak", 1, 0, 1, 0, 0, -1, ""),
            TestDoc("x".repeat(10_000), 1, 0, 1, 0, 0, -1, ""),
        )
        val terms = mapOf(
            "cafe" to listOf(0, 3),
            "東京駅" to listOf(1),
            "مقهى" to listOf(2),
            "a" to listOf(4),
            "b" to listOf(4),
            "line" to listOf(5),
            "break" to listOf(5),
            "x" to listOf(6),
        )
        val first = engine(parse(buildIndex(docs, terms)))
        val second = engine(parse(buildIndex(docs, terms)))
        val queries = listOf("🏍️", "東京", "مقهى", "cafe", "A\u200BB", "Line\n", "x".repeat(500))
        queries.forEach { query ->
            assertEquals(first.search(query), second.search(query))
        }
    }

    @Test
    fun seededFuzzQueriesAreFastAndReproducible() = runTest {
        val docs = listOf(
            TestDoc("Brisbane Cafe", 0, 2, 180, -274000000, 1530000000, -1, "Brisbane"),
            TestDoc("Queen Street", 1, 0, 160, -274000000, 1530000000, -1, "Brisbane"),
            TestDoc("Brisbane", 2, 0, 235, -274000000, 1530000000, -1, ""),
        )
        val indexBytes = buildIndex(
            docs,
            mapOf(
                "brisbane" to listOf(0, 2),
                "cafe" to listOf(0),
                "queen" to listOf(1),
                "street" to listOf(1),
            ),
        )
        val queries = fuzzQueries(seed = 1337, count = 500)
        val first = engine(parse(indexBytes))
        val second = engine(parse(indexBytes))
        var firstResults: List<List<GeocodeResult>> = emptyList()
        val elapsed = measureTimeMillis {
            firstResults = queries.map { query -> first.search(query) }
        }
        val secondResults = queries.map { query -> second.search(query) }
        assertTrue("500 searches took ${elapsed}ms", elapsed < 5_000)
        assertEquals(firstResults, secondResults)
    }

    private fun engine(index: GeocoderIndex): SearchEngine =
        SearchEngine(index, clockMs = { 0L }, logTiming = {})

    private fun parse(bytes: ByteArray): GeocoderIndex =
        GeocoderIndex.parse(java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN))

    private fun emptySearchIndex(): GeocoderIndex = parse(
        buildIndex(
            listOf(TestDoc("Empty", 2, 0, 1, 0, 0, -1, "")),
            mapOf("empty" to listOf(0)),
        ),
    )

    private fun fuzzQueries(seed: Int, count: Int): List<String> {
        val random = Random(seed)
        val alphabet = "abcXYZ 0123,-;\u200B\n🏍️東京مقهى"
        return List(count) {
            when (it % 11) {
                0 -> "Brisbane"
                1 -> "queen street"
                2 -> "-27.4698,153.0251"
                else -> buildString(random.nextInt(0, 60)) {
                    repeat(random.nextInt(0, 60)) { append(alphabet[random.nextInt(alphabet.length)]) }
                }
            }
        }
    }
}
