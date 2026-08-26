package com.organicmoto.maps.geocoding

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

class GeocoderIndexCorruptionTest {

    @Test(timeout = 5_000)
    fun seededMutationsNeverEscapeAsUnexpectedExceptions() {
        val source = validBytes()
        val docsOffset = intAt(source, 20)
        val termsOffset = intAt(source, 24)
        val postingsOffset = intAt(source, 28)
        val sections = listOf(
            0 until 32,
            32 until docsOffset,
            docsOffset until termsOffset,
            termsOffset until postingsOffset,
            postingsOffset until source.size,
        )
        for (seed in 1..50) {
            val random = Random(seed)
            sections.forEachIndexed { sectionIndex, section ->
                val flipped = source.copyOf()
                val position = random.nextInt(section.first, section.last + 1)
                flipped[position] = (flipped[position].toInt() xor (1 shl random.nextInt(8))).toByte()
                assertOnlyIllegalState("flip seed=$seed section=$sectionIndex") { exercise(flipped) }

                val cut = random.nextInt(section.first, section.last + 2)
                assertOnlyIllegalState("truncate seed=$seed section=$sectionIndex") {
                    exercise(source.copyOf(cut))
                }
            }
        }
    }

    private fun exercise(bytes: ByteArray) {
        val index = GeocoderIndex.parse(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN))
        for (docId in 0 until index.docCount) {
            index.docType(docId)
            index.doc(docId)
        }
        for (termId in 0 until index.termCount) {
            val (term, postings) = index.termAt(termId)
            index.findTerm(term)
            check(postings.all { it in 0 until index.docCount })
        }
    }

    private fun assertOnlyIllegalState(label: String, block: () -> Unit) {
        try {
            block()
        } catch (failure: Throwable) {
            assertTrue(
                "$label unexpected corruption exception: ${failure::class.qualifiedName}",
                failure is IllegalStateException,
            )
        }
    }

    private fun validBytes(): ByteArray = buildIndex(
        docs = listOf(
            TestDoc("Cafe", 0, 4, 200, -274698000, 1530251000, 0, "Brisbane"),
            TestDoc("Ann Street", 1, 1, 150, -274700000, 1530250000, 0, "Brisbane"),
            TestDoc("Brisbane", 2, 0, 235, -272000000, 1530000000, -1, ""),
        ),
        terms = mapOf(
            "ann" to listOf(1),
            "brisbane" to listOf(1, 2),
            "cafe" to listOf(0),
            "street" to listOf(1),
        ),
    )

    private fun intAt(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(offset)
}
