package com.organicmoto.maps.geocoding

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlin.math.min

private const val TAG = "OrganicMoto.GeocoderIndex"

/**
 * Read-only, memory-mapped reader for the offline geocoder index
 * (`assets/geocoder/geocoder.dat`, built by :geocoder-tool; the binary format
 * is fixed and documented in tools/geocoder/README.md).
 *
 * Thread-safe: every public read operates on a fresh [ByteBuffer.duplicate],
 * so concurrent queries never share mutable buffer state.
 */
class GeocoderIndex private constructor() {

    private lateinit var state: State

    private class State(
        val buf: ByteBuffer,
        val docsOffset: Int,
        val termsOffset: Int,
        val postingsOffset: Int,
        val docOffsets: IntArray,
        val dictOffsets: IntArray,
        val localityCount: Int,
    )

    /** Number of feature documents in the index. */
    val docCount: Int get() = state.docOffsets.size

    /** Number of terms in the (sorted) dictionary. */
    val termCount: Int get() = state.dictOffsets.size

    /** Number of locality documents (city/town/village/...). */
    val localityCount: Int get() = state.localityCount

    /**
     * One indexed feature: [type] is [TYPE_POI], [TYPE_STREET] or
     * [TYPE_LOCALITY]; [subType]/[rank] classify it further; [lat]/[lon] are
     * degrees; [localityId] is the owning locality doc id ([NO_LOCALITY] if
     * none); [nameTokens] are the normalized tokens of [name] for ranking.
     */
    data class Doc(
        val id: Int,
        val type: Int,
        val subType: Int,
        val rank: Int,
        val lat: Double,
        val lon: Double,
        val localityId: Int,
        val name: String,
        val nameTokens: List<String>,
        val cityName: String,
    )

    /** Returns the document with the given id. Throws on out-of-range or corrupt data. */
    fun doc(id: Int): Doc {
        if (id < 0 || id >= docCount) {
            throw IllegalStateException("doc id $id out of range (docCount=$docCount)")
        }
        val b = buf()
        val o = state.docOffsets[id]
        if (o < state.docsOffset || o >= state.termsOffset) {
            throw IllegalStateException("doc offset $o outside record range for doc $id")
        }
        checkRead(b, o, 15)
        b.position(o)
        val type = b.get().toInt() and 0xFF
        val subType = b.get().toInt() and 0xFF
        val rank = b.get().toInt() and 0xFF
        val latE7 = b.getInt()
        val lonE7 = b.getInt()
        val localityU32 = b.getInt().toLong() and 0xFFFFFFFFL
        val localityId = if (localityU32 == 0xFFFFFFFFL) NO_LOCALITY else localityU32.toInt()
        val nameLen = b.getShort().toInt() and 0xFFFF
        checkRead(b, b.position(), nameLen)
        val name = utf8(b, nameLen)
        val tokenCount = b.get().toInt() and 0xFF
        val tokens = ArrayList<String>(tokenCount)
        repeat(tokenCount) {
            val len = b.getShort().toInt() and 0xFFFF
            checkRead(b, b.position(), len)
            tokens.add(utf8(b, len))
        }
        val cityLen = b.getShort().toInt() and 0xFFFF
        checkRead(b, b.position(), cityLen)
        val city = utf8(b, cityLen)
        return Doc(id, type, subType, rank, latE7 / 1e7, lonE7 / 1e7, localityId, name, tokens, city)
    }

    /**
     * Returns the decoded posting list of the term exactly matching [bytes],
     * or null if the term is absent from the dictionary.
     */
    fun findTerm(bytes: ByteArray): IntArray? {
        var lo = 0
        var hi = termCount
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            val c = compareTerm(mid, bytes)
            if (c < 0) {
                lo = mid + 1
            } else if (c > 0) {
                hi = mid
            } else {
                val (_, relOffset, count) = entryAt(mid)
                return decodePostings(relOffset, count)
            }
        }
        return null
    }

    /**
     * Dictionary-index range [lo, hi) of every term whose bytes start with
     * [prefix] (the dictionary is sorted by unsigned lexicographic byte order).
     */
    fun termRange(prefix: ByteArray): IntRange {
        val lo = lowerBound(prefix)
        val hi = upperBoundKey(prefix)?.let { lowerBound(it) } ?: termCount
        return lo until hi
    }

    /** Returns the term bytes and decoded posting list of dictionary entry [dictIndex]. */
    fun termAt(dictIndex: Int): Pair<ByteArray, IntArray> {
        if (dictIndex < 0 || dictIndex >= termCount) {
            throw IllegalStateException("term index $dictIndex out of range (termCount=$termCount)")
        }
        val (bytes, relOffset, count) = entryAt(dictIndex)
        return bytes to decodePostings(relOffset, count)
    }

    // --- internals -------------------------------------------------------------

    private fun lowerBound(key: ByteArray): Int {
        var lo = 0
        var hi = termCount
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (compareTerm(mid, key) < 0) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Smallest byte array strictly greater than every array starting with [prefix], or null if none. */
    private fun upperBoundKey(prefix: ByteArray): ByteArray? {
        var i = prefix.size - 1
        while (i >= 0 && (prefix[i].toInt() and 0xFF) == 0xFF) i--
        if (i < 0) return null
        val out = prefix.copyOf(i + 1)
        out[i] = ((out[i].toInt() and 0xFF) + 1).toByte()
        return out
    }

    private fun compareTerm(dictIndex: Int, key: ByteArray): Int {
        val b = buf()
        val o = state.termsOffset + state.dictOffsets[dictIndex]
        checkRead(b, o, 2)
        b.position(o)
        val len = b.getShort().toInt() and 0xFFFF
        checkRead(b, b.position(), len)
        val n = min(len, key.size)
        for (i in 0 until n) {
            val x = b.get().toInt() and 0xFF
            val y = key[i].toInt() and 0xFF
            if (x != y) return x - y
        }
        return len - key.size
    }

    private fun entryAt(dictIndex: Int): Triple<ByteArray, Int, Int> {
        val b = buf()
        val o = state.termsOffset + state.dictOffsets[dictIndex]
        checkRead(b, o, 2)
        b.position(o)
        val len = b.getShort().toInt() and 0xFFFF
        checkRead(b, b.position(), len + 8)
        val bytes = ByteArray(len)
        b.get(bytes)
        val relOffset = b.getInt()
        val count = b.getInt()
        return Triple(bytes, relOffset, count)
    }

    private fun decodePostings(relOffset: Int, count: Int): IntArray {
        val out = IntArray(count)
        val b = buf()
        var o = state.postingsOffset + relOffset
        var prev = 0
        for (i in 0 until count) {
            var v = 0
            var shift = 0
            while (true) {
                checkRead(b, o, 1)
                val byte = b.get(o).toInt() and 0xFF
                o++
                v = v or ((byte and 0x7F) shl shift)
                if (byte and 0x80 == 0) break
                shift += 7
                if (shift > 35) {
                    throw IllegalStateException("corrupt geocoder index: overlong varint at offset ${o - 1}")
                }
            }
            prev += v
            out[i] = prev
        }
        return out
    }

    private fun utf8(b: ByteBuffer, len: Int): String {
        val bytes = ByteArray(len)
        b.get(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    /**
     * Fresh little-endian view of the mapped buffer. [ByteBuffer.duplicate]
     * does NOT inherit the source buffer's byte order (it resets to
     * BIG_ENDIAN), so every read must go through this helper.
     */
    private fun buf(): ByteBuffer = state.buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)

    private fun checkRead(b: ByteBuffer, pos: Int, len: Int) {
        if (pos < 0 || len < 0 || pos.toLong() + len > b.capacity()) {
            throw IllegalStateException(
                "corrupt geocoder index: read of $len bytes at offset $pos exceeds file size ${b.capacity()}"
            )
        }
    }

    companion object {
        /**
         * Magic + format-version marker of the index. The trailing "01" IS the
         * format version: it must match exactly what :geocoder-tool emits.
         * [parse] rejects any file whose magic differs (stale cache, mismatched
         * reader/builder, or random data), which doubles as the version check —
         * bump MAGIC in lockstep with the tool whenever the layout changes.
         */
        const val MAGIC = "OMGEO01\n"
        const val TYPE_POI = 0
        const val TYPE_STREET = 1
        const val TYPE_LOCALITY = 2
        const val NO_LOCALITY = -1

        private const val HEADER_SIZE = 32

        /**
         * Loads the offline geocoder index, copying it out of APK assets on
         * first use (or when the cached copy fails validation). Blocking; call
         * on Dispatchers.IO.
         *
         * The asset copy is atomic (temp file + fsync + rename), so an
         * interrupted first copy can never leave a partial file at the final
         * path. A cached file that fails version/content validation — a partial
         * write from an older build, corruption, or a format-version mismatch —
         * is deleted and re-copied from the packaged asset exactly once; if the
         * packaged asset itself is invalid, the failure is thrown with a clear
         * message instead of silently caching garbage.
         */
        fun load(context: Context): GeocoderIndex {
            val appContext = context.applicationContext
            val file = File(File(appContext.filesDir, "geocoder"), "geocoder.dat")
            val started = SystemClock.elapsedRealtime()
            // The cache survives APK upgrades, so format validation alone is not
            // enough: a valid index from an older extract must also be replaced.
            val assetFingerprint = packagedAssetFingerprint(appContext)
            if (file.isFile) {
                try {
                    if (fileFingerprint(file) == assetFingerprint) {
                        return parseFile(file).also { logLoaded(it, started, file) }
                    }
                    Log.i(TAG, "cached geocoder.dat differs from the packaged index; replacing it")
                } catch (e: Exception) {
                    // Partial/corrupt/wrong-version cache (e.g. a partial copy
                    // left by an older, non-atomic writer): drop it and recover
                    // from the packaged asset.
                    Log.w(TAG, "cached geocoder.dat failed validation (${e.message}); re-copying from assets")
                    file.delete()
                }
            } else {
                Log.i(TAG, "geocoder.dat missing — copying from APK assets")
            }
            copyFromAssets(appContext, file, started)
            return try {
                check(fileFingerprint(file) == assetFingerprint) {
                    "packaged geocoder index changed while it was being copied"
                }
                parseFile(file).also { logLoaded(it, started, file) }
            } catch (e: Exception) {
                file.delete()
                throw e
            }
        }

        private fun packagedAssetFingerprint(context: Context): String {
            val input = try {
                context.assets.open("geocoder/geocoder.dat")
            } catch (e: Exception) {
                throw IllegalStateException(
                    "geocoder index asset missing; rebuild it with :geocoder-tool " +
                        "and place it in app/src/main/assets/geocoder", e
                )
            }
            return input.use { stream -> digestHex(stream) }
        }

        private fun fileFingerprint(file: File): String =
            FileInputStream(file).use { digestHex(it) }

        private fun digestHex(input: java.io.InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun parseFile(file: File): GeocoderIndex {
            val buf = FileChannel.open(file.toPath(), StandardOpenOption.READ).use { ch ->
                ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()).order(ByteOrder.LITTLE_ENDIAN)
            }
            return parse(buf)
        }

        private fun logLoaded(index: GeocoderIndex, started: Long, file: File) {
            Log.i(
                TAG,
                "GeocoderIndex mapped in ${SystemClock.elapsedRealtime() - started} ms: " +
                    "${index.docCount} docs, ${index.termCount} terms, ${index.localityCount} localities " +
                    "(${file.length()} bytes)"
            )
        }

        /**
         * Atomically copies geocoder.dat from the packaged asset into [file]:
         * bytes are written to `geocoder.dat.tmp` in the same directory, fsynced,
         * then renamed over the target. A rename inside one directory is atomic,
         * so the final path only ever contains a complete file; a leftover temp
         * file from an interrupted attempt is discarded and the copy retried on
         * the next launch.
         */
        private fun copyFromAssets(appContext: Context, file: File, started: Long) {
            val dir = file.parentFile ?: throw IllegalStateException("geocoder cache directory missing")
            dir.mkdirs()
            val input = try {
                appContext.assets.open("geocoder/geocoder.dat")
            } catch (e: Exception) {
                throw IllegalStateException(
                    "geocoder index asset missing; rebuild it with :geocoder-tool " +
                        "and place it in app/src/main/assets/geocoder", e
                )
            }
            val tmp = File(dir, "geocoder.dat.tmp")
            tmp.delete() // discard any partial temp from an interrupted previous copy
            try {
                input.use { src ->
                    tmp.outputStream().use { dst -> src.copyTo(dst) }
                }
                // fsync so the rename below cannot be reordered before a crash.
                FileChannel.open(tmp.toPath(), StandardOpenOption.WRITE).use { it.force(true) }
                try {
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
                } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } catch (e: Exception) {
                tmp.delete()
                throw IllegalStateException("failed to copy geocoder index from assets", e)
            }
            Log.i(
                TAG,
                "Copied geocoder.dat from assets: ${file.length()} bytes in " +
                    "${SystemClock.elapsedRealtime() - started} ms"
            )
        }

        private fun parse(buf: ByteBuffer): GeocoderIndex {
            if (buf.capacity() < HEADER_SIZE) {
                throw IllegalStateException("geocoder index too small: ${buf.capacity()} bytes")
            }
            val magic = MAGIC.toByteArray(StandardCharsets.US_ASCII)
            for (i in magic.indices) {
                if (buf.get(i) != magic[i]) {
                    throw IllegalStateException(
                        "not a geocoder index (bad magic/version \"$MAGIC\"); rebuild with :geocoder-tool"
                    )
                }
            }
            val docCount = buf.getInt(8)
            val termCount = buf.getInt(12)
            val localityCount = buf.getInt(16)
            val docsOffset = buf.getInt(20)
            val termsOffset = buf.getInt(24)
            val postingsOffset = buf.getInt(28)
            if (docCount <= 0 || termCount <= 0 || localityCount < 0 || localityCount > docCount) {
                throw IllegalStateException(
                    "corrupt geocoder index header: docCount=$docCount termCount=$termCount localityCount=$localityCount"
                )
            }
            if (docsOffset != HEADER_SIZE + 4 * docCount) {
                throw IllegalStateException(
                    "corrupt geocoder index header: docsOffset=$docsOffset expected=${HEADER_SIZE + 4 * docCount}"
                )
            }
            // docsOffset <= termsOffset <= postingsOffset <= capacity after this
            // check, so all absolute-offset reads below are in bounds.
            if (termsOffset < docsOffset || postingsOffset < termsOffset || postingsOffset > buf.capacity()) {
                throw IllegalStateException(
                    "corrupt geocoder index header: docs=$docsOffset terms=$termsOffset postings=$postingsOffset file=${buf.capacity()}"
                )
            }
            val docOffsets = IntArray(docCount)
            for (i in 0 until docCount) {
                val o = buf.getInt(HEADER_SIZE + 4 * i)
                if (o < docsOffset || o >= termsOffset) {
                    throw IllegalStateException(
                        "corrupt geocoder index: doc $i offset $o outside record range [$docsOffset, $termsOffset)"
                    )
                }
                docOffsets[i] = o
            }
            val dictOffsets = IntArray(termCount)
            var o = termsOffset
            for (i in 0 until termCount) {
                if (o + 2 > postingsOffset) {
                    throw IllegalStateException("corrupt geocoder index: term dict entry $i overruns the dictionary")
                }
                val len = buf.getShort(o).toInt() and 0xFFFF
                if (o.toLong() + 2 + len + 8 > postingsOffset) {
                    throw IllegalStateException("corrupt geocoder index: term dict entry $i overruns the dictionary")
                }
                dictOffsets[i] = o - termsOffset
                o += 2 + len + 8
            }
            if (o != postingsOffset) {
                throw IllegalStateException(
                    "corrupt geocoder index: term dict ends at $o but postingsOffset=$postingsOffset"
                )
            }
            return GeocoderIndex().also {
                it.state = State(buf, docsOffset, termsOffset, postingsOffset, docOffsets, dictOffsets, localityCount)
            }
        }
    }
}
