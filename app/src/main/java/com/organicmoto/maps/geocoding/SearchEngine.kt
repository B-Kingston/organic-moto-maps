package com.organicmoto.maps.geocoding

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

private const val TAG = "OrganicMoto.SearchEngine"

/** Kind of a [SearchEngine.search] result. */
enum class GeocodeResultType { POI, STREET, LOCALITY, COORDINATE }

/** A single place-search result. */
data class GeocodeResult(
    val name: String,
    val subtitle: String,
    val type: GeocodeResultType,
    val lat: Double,
    val lon: Double,
    val score: Double,
)

/**
 * Offline place search engine over a [GeocoderIndex] (Organic-Maps-style pipeline).
 *
 * Pipeline: coordinate query parsing -> normalization / tokenization -> stop-word
 * removal -> synonym + fuzzy term matching -> posting intersection with a locality
 * layer -> linear ranking. No Compose/UI dependencies; callers run it from a
 * coroutine (the controller uses [Dispatchers.IO]). [search] is suspending so the
 * expensive loops can cooperate with cancellation: when the calling coroutine is
 * cancelled (e.g. a newer keystroke restarted the search), the running search
 * aborts instead of grinding to completion on the IO pool.
 */
class SearchEngine(
    private val index: GeocoderIndex,
    private val clockMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val logTiming: (String) -> Unit = { Log.d(TAG, it) },
) {

    /**
     * Searches [query], returning up to [limit] results ranked near (pivotLat, pivotLon).
     *
     * Cooperative with coroutine cancellation: the enclosing [Job] is polled inside
     * the expensive loops and a cancelled search throws [CancellationException].
     */
    suspend fun search(
        query: String,
        pivotLat: Double = -22.575,
        pivotLon: Double = 146.725,
        limit: Int = 8,
    ): List<GeocodeResult> {
        val started = clockMs()
        val job = currentCoroutineContext()[Job]
        val results = searchInternal(query, pivotLat, pivotLon, limit) {
            job == null || job.isActive
        }
        logTiming(
            "Search returned ${results.size} result(s) in ${clockMs() - started} ms",
        )
        return results
    }

    private fun searchInternal(
        query: String,
        pivotLat: Double,
        pivotLon: Double,
        limit: Int,
        isActive: () -> Boolean,
    ): List<GeocodeResult> {
        if (limit <= 0) return emptyList()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        // 1. Coordinates first.
        parseCoordinates(trimmed)?.let { (lat, lon) ->
            return listOf(
                GeocodeResult(
                    name = "${formatCoord(lat)}, ${formatCoord(lon)}",
                    subtitle = "Coordinates",
                    type = GeocodeResultType.COORDINATE,
                    lat = lat, lon = lon, score = 100.0,
                ),
            )
        }

        // 2. Normalize, tokenize (capped), pop the as-you-type prefix.
        val norm = SearchText.normalize(query)
        if (norm.isEmpty()) return emptyList()
        val tokenized = SearchText.tokenize(norm)
        if (tokenized.isEmpty()) return emptyList()
        var tokens =
            if (tokenized.size > SearchText.MAX_TOKENS) tokenized.subList(0, SearchText.MAX_TOKENS) else tokenized
        var prefix: String? = null
        if (!SearchText.isDelimiter(norm.last())) {
            prefix = tokens.last()
            tokens = tokens.dropLast(1)
        }

        // 3. Stop words (never the prefix; keep when every token is a stop word).
        if (tokens.any { it !in STOP_WORDS }) {
            tokens = tokens.filter { it !in STOP_WORDS }
        }
        if (tokens.isEmpty() && prefix == null) return emptyList()

        val allCommon = tokens.isNotEmpty() && tokens.all { SearchText.isStreetSynonym(it) }
        val prefixIsCommon = prefix != null && SearchText.isStreetSynonym(prefix)

        // Intersection pieces: non-common tokens (or the first token when all are common)
        // plus the non-common prefix. Pair = (text, token index in [tokens]; -1 for prefix).
        val pieces = ArrayList<Pair<String, Int>>()
        for (i in tokens.indices) {
            if (allCommon && i != 0) continue
            if (!allCommon && SearchText.isStreetSynonym(tokens[i])) continue
            pieces.add(tokens[i] to i)
        }
        if (prefix != null && !prefixIsCommon) pieces.add(prefix to -1)

        val nW = (index.docCount + 63) ushr 6
        val tokenCache = HashMap<String, DocBits?>()
        val prefixCache = HashMap<String, DocBits?>()
        val pieceBitsets = ArrayList<Triple<Int, DocBits, Boolean>>()
        for ((i, p) in pieces.withIndex()) {
            if (!isActive()) throw CancellationException("geocoder search cancelled")
            val bs =
                if (p.second < 0) prefixCache.getOrPut(p.first) { computePrefixBitset(p.first, nW, isActive) }
                else tokenCache.getOrPut(p.first) { computeTokenBitset(p.first, nW, isActive) }
            if (bs != null) pieceBitsets.add(Triple(i, bs, p.second < 0))
        }
        if (pieceBitsets.isEmpty()) return emptyList()

        // 6a. Direct candidates: intersection of every matched piece bitset.
        val allDocs = DocBits(nW)
        var first = true
        for ((_, bs, _) in pieceBitsets) {
            if (first) {
                allDocs.or(bs)
                first = false
            } else {
                allDocs.and(bs)
            }
        }

        val candidates = HashMap<Int, Candidate>()
        var candCount = 0
        allDocs.forEachSetBit { id ->
            if (!isActive()) throw CancellationException("geocoder search cancelled")
            if (candCount < MAX_CANDIDATES_HARD) {
                candidates[id] = Candidate(id, null, null, false)
                candCount++
            }
        }

        val ctx = SearchContext(
            tokens = tokens,
            prefix = prefix,
            compactQuery = norm.filter { !SearchText.isDelimiter(it) },
            commonTokens = tokens.count { SearchText.isStreetSynonym(it) } + if (prefixIsCommon) 1 else 0,
        )

        // 6b. Locality layer: localities found inside a piece bitset consume their query
        // token(s); the remaining pieces must intersect the locality's own posting list.
        // A single scratch bitset is reused across localities (no per-locality allocation).
        val examined = DocBits(nW)
        val layerScratch = DocBits(nW)
        var scanBudget = LOCALITY_SCAN_BUDGET
        for ((_, bs, _) in pieceBitsets) {
            if (scanBudget <= 0) break
            if (!isActive()) throw CancellationException("geocoder search cancelled")
            val localLocs = ArrayList<Pair<Int, Int>>()
            bs.forEachSetBit { id ->
                if (scanBudget <= 0) return@forEachSetBit
                if (!isActive()) throw CancellationException("geocoder search cancelled")
                if (examined.set(id)) {
                    scanBudget--
                    // docType reads one byte; doc() would decode the whole record
                    // (name + tokens + city strings) for every examined doc.
                    if (index.docType(id) != GeocoderIndex.TYPE_LOCALITY) return@forEachSetBit
                    val d = index.doc(id)
                    localLocs.add(d.id to d.rank)
                }
            }
            if (localLocs.isEmpty()) continue
            localLocs.sortByDescending { it.second }
            val top =
                if (localLocs.size > MAX_LOCALITIES_PER_TOKEN) localLocs.subList(0, MAX_LOCALITIES_PER_TOKEN)
                else localLocs
            for ((lid, _) in top) {
                if (!isActive()) throw CancellationException("geocoder search cancelled")
                val lDoc = index.doc(lid)
                val covered = ArrayList<Int>()
                for (ti in tokens.indices) {
                    val t = tokens[ti]
                    val maxErr = maxErrorsForToken(t)
                    if (lDoc.nameTokens.any { matchesNameToken(t, it, maxErr) }) covered.add(ti)
                }
                val prefixCovered = prefix != null && lDoc.nameTokens.any { startsWithPrefix(it, prefix!!) }
                layerScratch.clear()
                index.findTerm(localityTermBytes(lid))?.let { layerScratch.orPostings(it) }
                layerScratch.set(lid)
                for ((pi2, pbs, isPref) in pieceBitsets) {
                    if (isPref) {
                        if (!prefixCovered) layerScratch.and(pbs)
                    } else {
                        val ti = pieces[pi2].second
                        if (ti !in covered) layerScratch.and(pbs)
                    }
                }
                if (layerScratch.isEmpty) continue
                val coveredArr = covered.toIntArray()
                layerScratch.forEachSetBit { id ->
                    if (!isActive()) throw CancellationException("geocoder search cancelled")
                    if (candidates.size >= MAX_CANDIDATES_HARD && !candidates.containsKey(id)) return@forEachSetBit
                    val prev = candidates[id]
                    when {
                        prev == null -> candidates[id] = Candidate(id, lDoc, coveredArr, prefixCovered)
                        prev.layerDoc == null -> candidates[id] = Candidate(id, lDoc, coveredArr, prefixCovered)
                        coveredArr.size > (prev.coveredTokens?.size ?: 0) ->
                            candidates[id] = Candidate(id, lDoc, coveredArr, prefixCovered)
                    }
                }
            }
        }

        if (candidates.isEmpty()) return emptyList()

        // Pre-filter huge candidate sets with a cheap (rank, distance) proxy.
        var entries = candidates.values.toList()
        if (entries.size > MAX_RANK_CANDIDATES) {
            val keyed = ArrayList<RankedCandidate>(entries.size)
            for (c in entries) {
                if (!isActive()) throw CancellationException("geocoder search cancelled")
                val d = index.doc(c.docId)
                keyed.add(RankedCandidate(c, d.rank, haversineM(d.lat, d.lon, pivotLat, pivotLon)))
            }
            keyed.sortWith(
                compareByDescending<RankedCandidate> { it.rank }.thenBy { it.dist },
            )
            entries = keyed.take(MAX_RANK_CANDIDATES).map { it.candidate }
        }

        // 7. Linear-model ranking.
        val scored = ArrayList<Scored>(entries.size)
        for (c in entries) {
            if (!isActive()) throw CancellationException("geocoder search cancelled")
            val d = index.doc(c.docId)
            scored.add(Scored(d, computeScore(ctx, d, c, pivotLat, pivotLon)))
        }
        scored.sortWith(compareByDescending<Scored> { it.score }.thenBy { it.doc.id })
        return scored.take(limit).map { toResult(it.doc, it.score) }
    }

    // ------------------------------------------------------------------ ranking

    private fun computeScore(
        ctx: SearchContext,
        d: GeocoderIndex.Doc,
        c: Candidate,
        pivotLat: Double,
        pivotLon: Double,
    ): Double {
        val ns = nameScore(ctx, d)
        var cat = ns.category
        val distPivot = haversineM(d.lat, d.lon, pivotLat, pivotLon)
        if (d.type == GeocoderIndex.TYPE_POI) {
            // POI promotion (OM GetNameScore).
            if (cat == NameScoreCategory.FULL_PREFIX) cat = NameScoreCategory.FULL_MATCH
            else if (cat != NameScoreCategory.ZERO && distPivot < WALKING_DIST_M) cat = NameScoreCategory.FULL_MATCH
        }
        var score = cat.value

        var distTerm = distPivot
        val layer = c.layerDoc
        if (layer != null && layerNameScore(ctx, layer) == NameScoreCategory.FULL_MATCH) {
            distTerm = haversineM(d.lat, d.lon, layer.lat, layer.lon)
        }
        score += DISTANCE_WEIGHT * (min(distTerm, MAX_DIST_M) / MAX_DIST_M)
        score += RANK_WEIGHT * (d.rank / 255.0)
        score += typeBonus(d)

        val covered = c.coveredTokens
        var errors = 0
        for (ti in ctx.tokens.indices) {
            if (ns.consumedByDoc[ti]) {
                errors += ns.tokenDists[ti]
            } else if (covered == null || ti !in covered) {
                errors += maxErrorsForToken(ctx.tokens[ti])
            }
        }
        val matchedCount = ctx.tokens.indices.count { ns.consumedByDoc[it] }
        score += ERRORS_WEIGHT * (errors.toDouble() / matchedCount.coerceAtLeast(1).toDouble())
        score += MATCHED_FRACTION_WEIGHT * (ns.matchedNameChars.toDouble() / ns.totalNameChars.toDouble())

        var allUsed = true
        for (ti in ctx.tokens.indices) {
            if (!ns.consumedByDoc[ti] && (covered == null || ti !in covered)) {
                allUsed = false
                break
            }
        }
        val prefixUsed = ctx.prefix == null || ns.prefixConsumedByDoc || (layer != null && c.prefixCoveredByLayer)
        if (allUsed && prefixUsed) score += ALL_TOKENS_USED_WEIGHT
        score += COMMON_TOKENS_WEIGHT * ctx.commonTokens
        return score
    }

    /**
     * Greedy token -> name-token matching (each name token used once; the prefix
     * matches as a prefix of a remaining name token) plus the OM name-score class.
     */
    private fun nameScore(ctx: SearchContext, d: GeocoderIndex.Doc): NameScoreInfo {
        val nameTokens = d.nameTokens
        val used = BooleanArray(nameTokens.size)
        val consumed = BooleanArray(ctx.tokens.size)
        val dists = IntArray(ctx.tokens.size) { -1 }
        var matchedChars = 0
        for (ti in ctx.tokens.indices) {
            val t = ctx.tokens[ti]
            var bestJ = -1
            var bestDist = Int.MAX_VALUE
            val maxErr = maxErrorsForToken(t)
            for (j in nameTokens.indices) {
                if (used[j]) continue
                val nt = nameTokens[j]
                if (nt == t) {
                    bestJ = j
                    bestDist = 0
                    break
                }
                if (maxErr > 0 && nt.isNotEmpty() && t.isNotEmpty() && nt[0] == t[0]) {
                    val dist = levenshteinBounded(t, nt, maxErr)
                    if (dist <= maxErr && dist < bestDist) {
                        bestJ = j
                        bestDist = dist
                    }
                }
            }
            if (bestJ >= 0) {
                used[bestJ] = true
                consumed[ti] = true
                dists[ti] = bestDist
                matchedChars += nameTokens[bestJ].length
            }
        }
        var prefixMatched = false
        val prefix = ctx.prefix
        if (prefix != null) {
            for (j in nameTokens.indices) {
                if (used[j]) continue
                if (startsWithPrefix(nameTokens[j], prefix)) {
                    used[j] = true
                    prefixMatched = true
                    matchedChars += nameTokens[j].length
                    break
                }
            }
        }
        val totalChars = nameTokens.sumOf { it.length }.coerceAtLeast(1)
        val allTokensMatched = ctx.tokens.indices.all { consumed[it] }
        val allNameUsed = used.all { it }
        val prefixOk = prefix == null || prefixMatched
        val category =
            when {
                allTokensMatched && allNameUsed && prefixOk -> NameScoreCategory.FULL_MATCH
                allTokensMatched && prefixOk -> NameScoreCategory.FULL_PREFIX
                ctx.tokens.isNotEmpty() && consumed[0] -> NameScoreCategory.FIRST_MATCH
                prefixMatched -> NameScoreCategory.PREFIX
                substringMatches(ctx, d) -> NameScoreCategory.SUBSTRING
                else -> NameScoreCategory.ZERO
            }
        return NameScoreInfo(category, consumed, dists, prefixMatched, matchedChars, totalChars)
    }

    private fun layerNameScore(ctx: SearchContext, lDoc: GeocoderIndex.Doc): NameScoreCategory =
        ctx.layerScoreCache.getOrPut(lDoc.id) { nameScore(ctx, lDoc).category }

    private fun substringMatches(ctx: SearchContext, d: GeocoderIndex.Doc): Boolean {
        val cq = ctx.compactQuery
        if (cq.isEmpty()) return false
        val cn = ctx.nameCache.getOrPut(d.id) {
            SearchText.normalize(d.name).filter { !SearchText.isDelimiter(it) }
        }
        return cn.contains(cq)
    }

    private fun typeBonus(d: GeocoderIndex.Doc): Double =
        when (d.type) {
            GeocoderIndex.TYPE_LOCALITY -> LOCALITY_TYPE_BONUS.getOrElse(d.subType) { 0.0 }
            GeocoderIndex.TYPE_STREET -> STREET_TYPE_BONUS.getOrElse(d.subType) { 0.0 }
            GeocoderIndex.TYPE_POI -> POI_TYPE_BONUS.getOrElse(d.subType) { 0.0 }
            else -> 0.0
        }

    // ------------------------------------------------------------------- terms

    /**
     * Exact + synonym + fuzzy (first-byte Levenshtein) postings union for a query
     * token. The fuzzy scan is bounded: at most [MAX_FUZZY_TERMS] dictionary
     * entries sharing the token's first byte are examined and the union stops at
     * [MAX_FUZZY_POSTINGS], so a single keystroke can never walk the whole dict.
     */
    private fun computeTokenBitset(t: String, nW: Int, isActive: () -> Boolean): DocBits? {
        val bs = DocBits(nW)
        val tb = t.toByteArray(UTF_8)
        var matched = false
        val exact = index.findTerm(tb)
        if (exact != null) {
            bs.orPostings(exact, MAX_FUZZY_POSTINGS)
            matched = true
        }
        for (s in SYNONYMS[t].orEmpty()) {
            if (!isActive()) throw CancellationException("geocoder search cancelled")
            val syn = index.findTerm(s.toByteArray(UTF_8))
            if (syn != null) {
                bs.orPostings(syn, MAX_FUZZY_POSTINGS)
                matched = true
            }
        }
        val maxErr = maxErrorsForToken(t)
        if (maxErr > 0) {
            val range = index.termRange(byteArrayOf(tb[0]))
            if (!range.isEmpty()) {
                val blockStart = range.first
                val blockSize = range.last - range.first + 1
                // Scan candidates outward from this token's dictionary insertion
                // point (skipping the exact term, already unioned above), not
                // forward from the start of the first-byte block: a block for a
                // common letter holds tens of thousands of terms, and a forward
                // scan capped at MAX_FUZZY_TERMS would only ever reach the
                // alphabetically-first ones, silently missing typos of common
                // words ("cofeee" -> "coffee").
                val origin = (index.lowerBound(tb) - blockStart).coerceIn(0, blockSize)
                var lo = origin
                var hi = origin + (if (exact != null) 1 else 0)
                var scanned = 0
                fun consider(dictIndex: Int) {
                    // Skip oversized blobs without decoding them: termAt decodes
                    // the whole posting list even when one bit would hit the
                    // union cap, so a huge candidate costs a full IntArray
                    // allocation for nothing.
                    if (index.termPostingCount(dictIndex) > MAX_FUZZY_POSTINGS) return
                    val (termBytes, postings) = index.termAt(dictIndex)
                    val termStr = String(termBytes, UTF_8)
                    if (abs(termStr.length - t.length) > maxErr) return
                    if (levenshteinBounded(termStr, t, maxErr) <= maxErr) {
                        bs.orPostings(postings, MAX_FUZZY_POSTINGS)
                        matched = true
                    }
                }
                while (scanned < MAX_FUZZY_TERMS && bs.size < MAX_FUZZY_POSTINGS) {
                    val canDown = lo > 0
                    val canUp = hi < blockSize
                    if (!canDown && !canUp) break
                    if (canDown) {
                        lo--
                        scanned++
                        if (!isActive()) throw CancellationException("geocoder search cancelled")
                        consider(blockStart + lo)
                    }
                    if (canUp && scanned < MAX_FUZZY_TERMS && bs.size < MAX_FUZZY_POSTINGS) {
                        scanned++
                        if (!isActive()) throw CancellationException("geocoder search cancelled")
                        consider(blockStart + hi)
                        hi++
                    }
                }
            }
        }
        return if (matched) bs else null
    }

    /**
     * Union of postings of every term starting with [p] (or one of its synonyms).
     * Bounded both ways: prefixes shorter than [MIN_PREFIX_LEN] are not expanded
     * (a 1-character prefix would scan a huge dictionary slice for junk hits), at
     * most [MAX_PREFIX_TERMS] dictionary entries are scanned, and the union stops
     * at [MAX_PREFIX_POSTINGS] bits.
     */
    private fun computePrefixBitset(p: String, nW: Int, isActive: () -> Boolean): DocBits? {
        if (p.length < MIN_PREFIX_LEN) return null
        val bs = DocBits(nW)
        var matched = false
        var scanned = 0
        for (pre in prefixCandidates(p)) {
            if (scanned >= MAX_PREFIX_TERMS) break
            val range = index.termRange(pre.toByteArray(UTF_8))
            if (range.isEmpty()) continue
            for (i in range) {
                if (!isActive()) throw CancellationException("geocoder search cancelled")
                if (scanned >= MAX_PREFIX_TERMS || bs.size >= MAX_PREFIX_POSTINGS) break
                scanned++
                val (_, postings) = index.termAt(i)
                bs.orPostings(postings, MAX_PREFIX_POSTINGS)
                matched = true
            }
        }
        return if (matched) bs else null
    }

    // ------------------------------------------------------------------ output

    private fun toResult(d: GeocoderIndex.Doc, score: Double): GeocodeResult {
        val label =
            when (d.type) {
                GeocoderIndex.TYPE_POI -> "Point of interest"
                GeocoderIndex.TYPE_STREET -> "Street"
                GeocoderIndex.TYPE_LOCALITY ->
                    when (d.subType) {
                        0 -> "City"
                        1 -> "Town"
                        2 -> "Village"
                        3 -> "Suburb"
                        4 -> "State"
                        else -> "Locality"
                    }
                else -> "Place"
            }
        val subtitle = if (d.cityName.isNotEmpty()) "${d.cityName} · $label" else label
        val type =
            when (d.type) {
                GeocoderIndex.TYPE_POI -> GeocodeResultType.POI
                GeocoderIndex.TYPE_STREET -> GeocodeResultType.STREET
                else -> GeocodeResultType.LOCALITY
            }
        return GeocodeResult(d.name, subtitle, type, d.lat, d.lon, score)
    }

    // ------------------------------------------------------------------ state

    private class SearchContext(
        val tokens: List<String>,
        val prefix: String?,
        val compactQuery: String,
        val commonTokens: Int,
    ) {
        val nameCache = HashMap<Int, String>()
        val layerScoreCache = HashMap<Int, NameScoreCategory>()
    }

    private class Candidate(
        val docId: Int,
        val layerDoc: GeocoderIndex.Doc?,
        val coveredTokens: IntArray?,
        val prefixCoveredByLayer: Boolean,
    )

    /** Pre-filter key: primitive fields (no Triple/boxing churn for huge candidate sets). */
    private class RankedCandidate(val candidate: Candidate, val rank: Int, val dist: Double)

    private class NameScoreInfo(
        val category: NameScoreCategory,
        val consumedByDoc: BooleanArray,
        val tokenDists: IntArray,
        val prefixConsumedByDoc: Boolean,
        val matchedNameChars: Int,
        val totalNameChars: Int,
    )

    private data class Scored(val doc: GeocoderIndex.Doc, val score: Double)

    private enum class NameScoreCategory(val value: Double) {
        ZERO(-0.05),
        SUBSTRING(0.0),
        PREFIX(0.01),
        FIRST_MATCH(0.012),
        FULL_PREFIX(0.018),
        FULL_MATCH(0.02),
    }

    private companion object {
        const val MAX_DIST_M = 2_000_000.0
        const val WALKING_DIST_M = 5000.0
        const val DISTANCE_WEIGHT = -0.48
        const val RANK_WEIGHT = 0.23
        const val ERRORS_WEIGHT = -0.4
        const val MATCHED_FRACTION_WEIGHT = 0.1876736
        const val ALL_TOKENS_USED_WEIGHT = 0.3
        const val COMMON_TOKENS_WEIGHT = -0.05
        const val MAX_RANK_CANDIDATES = 1500
        const val MAX_LOCALITIES_PER_TOKEN = 50
        const val LOCALITY_SCAN_BUDGET = 150_000
        const val MAX_CANDIDATES_HARD = 60_000

        // Dictionary/posting expansion bounds (per keystroke, per token/prefix):
        // short prefixes are not expanded at all, dictionary scans are capped by
        // entry count, and postings unions are capped by bit count.
        const val MIN_PREFIX_LEN = 2
        const val MAX_PREFIX_TERMS = 200
        const val MAX_FUZZY_TERMS = 400
        const val MAX_PREFIX_POSTINGS = 20_000
        const val MAX_FUZZY_POSTINGS = 20_000
    }
}

// ---------------------------------------------------------------------------
// Per-search bitset over doc ids.
// ---------------------------------------------------------------------------

private class DocBits(val wordCount: Int) {
    private val words = LongArray(wordCount)

    // Maintained eagerly by every mutation, so size/isEmpty are O(1) and no
    // per-keystroke full-array popcount scan is needed.
    private var count = 0

    /** Sets the bit; returns true when it was newly set. */
    fun set(id: Int): Boolean {
        val w = id ushr 6
        val m = 1L shl (id and 63)
        return if ((words[w] and m) == 0L) {
            words[w] = words[w] or m
            count++
            true
        } else {
            false
        }
    }

    /**
     * ORs [postings], stopping once the union reaches [maxTotalBits] bits. The
     * caller uses the cap to bound dictionary/posting expansion (exact/synonym
     * matches pass the default and are never capped).
     */
    fun orPostings(postings: IntArray, maxTotalBits: Int = Int.MAX_VALUE) {
        for (id in postings) {
            if (set(id) && count >= maxTotalBits) return
        }
    }

    fun or(other: DocBits) {
        for (i in 0 until wordCount) {
            val w = words[i] or other.words[i]
            count += java.lang.Long.bitCount(w) - java.lang.Long.bitCount(words[i])
            words[i] = w
        }
    }

    fun and(other: DocBits) {
        var c = 0
        for (i in 0 until wordCount) {
            val w = words[i] and other.words[i]
            words[i] = w
            c += java.lang.Long.bitCount(w)
        }
        count = c
    }

    /** Zeros the bitset so it can be reused as a scratch buffer. */
    fun clear() {
        words.fill(0)
        count = 0
    }

    fun isSet(id: Int): Boolean = (words[id ushr 6] and (1L shl (id and 63))) != 0L

    val size: Int
        get() = count

    val isEmpty: Boolean
        get() = count == 0

    fun forEachSetBit(action: (Int) -> Unit) {
        for (w in 0 until wordCount) {
            var v = words[w]
            while (v != 0L) {
                val b = java.lang.Long.numberOfTrailingZeros(v)
                action((w shl 6) or b)
                v = v and (v - 1)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Top-level pure helpers.
// ---------------------------------------------------------------------------

private val UTF_8 = Charsets.UTF_8

private val STOP_WORDS: Set<String> = setOf(
    "the", "a", "an", "of", "in", "on", "at", "near", "by", "and", "to", "for", "with",
    "de", "la", "le", "les", "del", "der", "die", "das", "und", "el", "los", "las",
    "van", "von", "den", "y", "e", "o", "do", "da",
)

private val SYNONYMS: Map<String, List<String>> = mapOf(
    "n" to listOf("north"),
    "s" to listOf("south"),
    "e" to listOf("east"),
    "w" to listOf("west"),
    "nw" to listOf("northwest"),
    "ne" to listOf("northeast"),
    "sw" to listOf("southwest"),
    "se" to listOf("southeast"),
    "st" to listOf("saint", "street"),
    "dr" to listOf("doctor"),
    "ave" to listOf("avenue"),
    "blvd" to listOf("boulevard"),
    "rd" to listOf("road"),
    "hwy" to listOf("highway"),
    "pl" to listOf("place"),
    "sq" to listOf("square"),
    "rt" to listOf("route"),
    "mt" to listOf("mount"),
)

private val LOCALITY_TYPE_BONUS = doubleArrayOf(0.01, 0.01, 0.0, 0.0, 0.0233254, 0.0)
private val STREET_TYPE_BONUS = doubleArrayOf(0.006, 0.005, 0.004, 0.004, 0.0)
private val POI_TYPE_BONUS = doubleArrayOf(0.03, 0.003, 0.01, 0.01, 0.01, 0.01, 0.008, 0.0, -0.01)

/** OM max-errors by token length; all-digit tokens never fuzzy-match. */
private fun maxErrorsForToken(t: String): Int {
    if (t.all { it in '0'..'9' }) return 0
    return when (t.length) {
        in 0..3 -> 0
        in 4..7 -> 1
        else -> 2
    }
}

/** Classic bounded Levenshtein; returns max + 1 when the distance exceeds [max]. */
private fun levenshteinBounded(a: String, b: String, max: Int): Int {
    if (a == b) return 0
    val m = a.length
    val n = b.length
    if (m == 0) return if (n <= max) n else max + 1
    if (n == 0) return if (m <= max) m else max + 1
    if (abs(m - n) > max) return max + 1
    var prev = IntArray(n + 1)
    var curr = IntArray(n + 1)
    for (j in 0..n) prev[j] = j
    for (i in 1..m) {
        curr[0] = i
        var rowMin = i
        val ai = a[i - 1]
        for (j in 1..n) {
            val cost = if (ai == b[j - 1]) 0 else 1
            val v = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
            curr[j] = v
            if (v < rowMin) rowMin = v
        }
        if (rowMin > max) return max + 1
        val tmp = prev
        prev = curr
        curr = tmp
    }
    return prev[n]
}

private fun matchesNameToken(t: String, nt: String, maxErr: Int): Boolean {
    if (nt == t) return true
    if (maxErr <= 0) return false
    if (nt.isEmpty() || t.isEmpty() || nt[0] != t[0]) return false
    return levenshteinBounded(t, nt, maxErr) <= maxErr
}

private fun prefixCandidates(prefix: String): List<String> = listOf(prefix) + SYNONYMS[prefix].orEmpty()

private fun startsWithPrefix(nt: String, prefix: String): Boolean {
    for (c in prefixCandidates(prefix)) if (nt.startsWith(c)) return true
    return false
}

private fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371000.0
    val dLat = (lat2 - lat1) * PI / 180.0
    val dLon = (lon2 - lon1) * PI / 180.0
    val la1 = lat1 * PI / 180.0
    val la2 = lat2 * PI / 180.0
    val a = sin(dLat / 2) * sin(dLat / 2) + cos(la1) * cos(la2) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * r * asin(sqrt(a))
}

/**
 * The locality synthetic term is `0x02 + localityId (4 bytes big-endian)`. The
 * index builder stores it as an ISO-8859-1-decoded String and writes it back
 * UTF-8, so high bytes are UTF-8 expanded on disk — mirror that round trip.
 */
private fun localityTermBytes(localityId: Int): ByteArray {
    val raw =
        byteArrayOf(
            0x02,
            (localityId ushr 24).toByte(),
            (localityId ushr 16).toByte(),
            (localityId ushr 8).toByte(),
            localityId.toByte(),
        )
    return String(raw, Charsets.ISO_8859_1).toByteArray(Charsets.UTF_8)
}

private val COORD_REGEX = Regex("""^\s*(-?\d+(?:\.\d+)?)\s*[,;]\s*(-?\d+(?:\.\d+)?)\s*$""")
private val COORD_SPACE_REGEX = Regex("""^\s*(-?\d+(?:\.\d+)?)\s+(-?\d+(?:\.\d+)?)\s*$""")

private fun parseCoordinates(s: String): Pair<Double, Double>? {
    val m = COORD_REGEX.matchEntire(s) ?: COORD_SPACE_REGEX.matchEntire(s) ?: return null
    val lat = m.groupValues[1].toDoubleOrNull() ?: return null
    val lon = m.groupValues[2].toDoubleOrNull() ?: return null
    if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) return null
    return lat to lon
}

private fun formatCoord(v: Double): String {
    val r = round(v * 1e6) / 1e6
    return if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()
}
