package com.organicmoto.maps.geocoding

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/** Small valid geocoder.dat writer used by JVM parser and search tests. */
data class TestDoc(
    val name: String,
    val type: Int,
    val subType: Int,
    val rank: Int,
    val latE7: Int,
    val lonE7: Int,
    val localityId: Int,
    val city: String,
)

fun buildIndex(docs: List<TestDoc>, terms: Map<String, List<Int>>): ByteArray {
    require(docs.isNotEmpty())
    require(terms.isNotEmpty())
    val docBytes = docs.map { doc ->
        val out = ByteArrayOutputStream()
        out.write(doc.type)
        out.write(doc.subType)
        out.write(doc.rank)
        writeInt(out, doc.latE7)
        writeInt(out, doc.lonE7)
        writeInt(out, doc.localityId)
        writeString(out, doc.name)
        val tokens = SearchText.tokenize(SearchText.normalize(doc.name)).take(255)
        require(tokens.size <= 255)
        out.write(tokens.size)
        tokens.forEach { token -> writeString(out, token) }
        writeString(out, doc.city)
        out.toByteArray()
    }
    val docCount = docs.size
    val sortedTerms = terms.entries
        .map { (term, ids) ->
            val bytes = term.toByteArray(StandardCharsets.UTF_8)
            bytes to ids.distinct().sorted()
        }
        .sortedWith { left, right -> compareUnsigned(left.first, right.first) }
    val docsOffset = 32 + 4 * docCount
    val termsOffset = docsOffset + docBytes.sumOf { it.size }
    val termBytes = sortedTerms.sumOf { (bytes, _) -> 2 + bytes.size + 8 }
    val postingsOffset = termsOffset + termBytes
    val postingBlobs = sortedTerms.map { (_, ids) ->
        val out = ByteArrayOutputStream()
        var previous = 0
        ids.forEach { id ->
            require(id in docs.indices)
            writeVarUInt(out, id - previous)
            previous = id
        }
        out.toByteArray()
    }
    val totalSize = postingsOffset + postingBlobs.sumOf { it.size }
    val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
    buffer.put(GeocoderIndex.MAGIC.toByteArray(StandardCharsets.US_ASCII))
    buffer.putInt(docCount)
    buffer.putInt(sortedTerms.size)
    buffer.putInt(docs.count { it.type == GeocoderIndex.TYPE_LOCALITY })
    buffer.putInt(docsOffset)
    buffer.putInt(termsOffset)
    buffer.putInt(postingsOffset)
    var docOffset = docsOffset
    docBytes.forEach { bytes ->
        buffer.putInt(docOffset)
        docOffset += bytes.size
    }
    docBytes.forEach(buffer::put)
    var relativePostingOffset = 0
    sortedTerms.forEachIndexed { index, (bytes, ids) ->
        buffer.putShort(bytes.size.toShort())
        buffer.put(bytes)
        buffer.putInt(relativePostingOffset)
        buffer.putInt(ids.size)
        relativePostingOffset += postingBlobs[index].size
    }
    postingBlobs.forEach(buffer::put)
    return buffer.array()
}

private fun writeString(out: ByteArrayOutputStream, value: String) {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    require(bytes.size <= 0xFFFF)
    writeShort(out, bytes.size)
    out.write(bytes)
}

private fun writeShort(out: ByteArrayOutputStream, value: Int) {
    out.write(value and 0xFF)
    out.write((value ushr 8) and 0xFF)
}

private fun writeInt(out: ByteArrayOutputStream, value: Int) {
    out.write(value and 0xFF)
    out.write((value ushr 8) and 0xFF)
    out.write((value ushr 16) and 0xFF)
    out.write((value ushr 24) and 0xFF)
}

private fun writeVarUInt(out: ByteArrayOutputStream, value: Int) {
    require(value >= 0)
    var current = value
    while (current >= 0x80) {
        out.write((current and 0x7F) or 0x80)
        current = current ushr 7
    }
    out.write(current)
}

private fun compareUnsigned(left: ByteArray, right: ByteArray): Int {
    val common = minOf(left.size, right.size)
    for (index in 0 until common) {
        val comparison = (left[index].toInt() and 0xFF) - (right[index].toInt() and 0xFF)
        if (comparison != 0) return comparison
    }
    return left.size - right.size
}
