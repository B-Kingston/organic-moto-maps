package com.organicmoto.geocoder.tool

import crosby.binary.osmosis.OsmosisReader
import org.openstreetmap.osmosis.core.container.v0_6.EntityContainer
import org.openstreetmap.osmosis.core.domain.v0_6.Node
import org.openstreetmap.osmosis.core.domain.v0_6.Relation
import org.openstreetmap.osmosis.core.domain.v0_6.Tag
import org.openstreetmap.osmosis.core.domain.v0_6.Way
import org.openstreetmap.osmosis.core.task.v0_6.Sink
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.system.exitProcess

private const val TYPE_POI = 0
private const val TYPE_STREET = 1
private const val TYPE_LOCALITY = 2

private const val NO_LOCALITY = -1 // written as u32 0xFFFFFFFF

private val UTF_8 = StandardCharsets.UTF_8
private val US_ASCII = StandardCharsets.US_ASCII
private val ISO_8859_1 = StandardCharsets.ISO_8859_1

/**
 * STEP 1 of the OM-style offline search: a desktop tool that reads an OSM PBF
 * and writes the compact binary search index (geocoder.dat) that the Android
 * app (STEP 2) loads as an asset.
 *
 * The on-disk format (all integers little-endian):
 * ```
 * [0..8)     magic "OMGEO01\n"
 * [8..12)    u32 docCount
 * [12..16)   u32 termCount
 * [16..20)   u32 localityCount
 * [20..24)   u32 docsOffset      == 32 + 4*docCount (end of the doc offset table)
 * [24..28)   u32 termsOffset
 * [28..32)   u32 postingsOffset
 * [32..32+4*docCount)            u32 docOffset[i] absolute file offset of doc i
 * [docsOffset..termsOffset)      doc records (see writeDoc)
 * [termsOffset..postingsOffset)  term dict entries, sorted by unsigned bytes:
 *                                u16 len | term bytes | u32 postingOffset
 *                                (relative to postingsOffset) | u32 postingCount
 * [postingsOffset..EOF)          per term, postingCount varint deltas (LEB128,
 *                                unsigned) of sorted ascending docIds; first
 *                                delta = first docId, then docId - previous.
 * ```
 */
fun main(args: Array<String>) {
    when {
        args.isNotEmpty() && args[0] == "--inspect" -> inspect(args)
        args.isNotEmpty() && args[0] == "--validate" -> validate(args)
        args.isNotEmpty() && args[0] == "--check-sync" -> checkSync()
        args.size < 2 -> {
            System.err.println("Usage: GeocoderIndexBuilder <pbf.osm.pbf> <outputDir>")
            System.err.println("       GeocoderIndexBuilder --inspect <pbf(ignored)> <geocoder.dat> [terms...]")
            System.err.println("       GeocoderIndexBuilder --validate <geocoder.dat>")
            System.err.println("       GeocoderIndexBuilder --check-sync")
            exitProcess(1)
        }
        else -> {
            val t0 = System.nanoTime()
            val outFile = IndexBuilder().build(File(args[0]), File(args[1]))
            println("Total time: ${(System.nanoTime() - t0) / 1_000_000_000}s")
            println("Index written to: $outFile")
        }
    }
}

// ---------------------------------------------------------------------------
// Classification tables
// ---------------------------------------------------------------------------

private val POI_KEYS = setOf(
    "amenity", "tourism", "shop", "leisure", "historic", "office", "aeroway",
    "railway", "natural", "man_made", "healthcare", "public_transport", "emergency",
)

/** Extra keys the classifier looks at (name/ref/brand/etc. + POI subType hints). */
private val INTERESTING_KEYS = POI_KEYS + setOf(
    "name", "name:en", "alt_name", "short_name", "old_name", "ref", "brand",
    "operator", "place", "highway", "population", "power", "barrier",
)

// --- LOCALITY ---------------------------------------------------------------

/** place value -> subType (0=city,1=town,2=village,3=suburb/neighbourhood,4=state,5=other). */
private val LOCALITY_SUBTYPE = mapOf(
    "city" to 0, "town" to 1, "village" to 2, "suburb" to 3, "neighbourhood" to 3,
    "state" to 4, "hamlet" to 5,
)

private val LOCALITY_RANK = mapOf(
    "state" to 250, "city" to 235, "town" to 220, "village" to 195,
    "suburb" to 185, "hamlet" to 165, "neighbourhood" to 150,
)

/** Additional place values that become LOCALITY-other docs if named. */
private val OTHER_PLACES = setOf("locality", "isolated_dwelling", "farm")

/** Max assignment radius (metres) per locality subType, as in the spec. */
private val LOCALITY_RADIUS_M = doubleArrayOf(
    20000.0, // city
    12000.0, // town
    8000.0,  // village
    5000.0,  // suburb/neighbourhood
    300000.0, // state
    8000.0,  // other (hamlet, locality, isolated_dwelling, farm)
)

// --- STREET ------------------------------------------------------------------

private val STREET_RANK = mapOf(
    "motorway" to 210, "trunk" to 210, "primary" to 190, "secondary" to 175,
    "tertiary" to 160, "unclassified" to 150, "residential" to 145,
    "living_street" to 140, "service" to 135, "track" to 125, "cycleway" to 120,
)
private const val STREET_RANK_FALLBACK = 115

/** Highway values never indexed as streets (matches the app's ignored_highways spirit). */
private val SKIPPED_HIGHWAYS = setOf(
    "footway", "steps", "corridor", "path", "bridleway", "pedestrian",
    "construction", "proposed",
)

/**
 * Highway values that are POIs, never streets, even when tagged on a way.
 * These features carry a name but no POI_KEYS tag (a bare `highway=bus_stop`
 * node, a `highway=rest_area`/`services` area way, ...), so without this they
 * would be dropped (node) or misclassified as STREET (way). `poiSubType` and
 * `poi_keywords.tsv` already know these values; classification just never
 * reached them.
 */
private val POI_HIGHWAYS = setOf(
    "bus_stop", "bus_bay", "platform", "rest_area", "services", "elevator",
)

/** 0=motorway/trunk, 1=primary/secondary/tertiary, 2=residential/living_street, 3=other, 4=default(unused). */
private fun streetSubType(highway: String): Int = when (highway) {
    "motorway", "trunk" -> 0
    "primary", "secondary", "tertiary" -> 1
    "residential", "living_street" -> 2
    else -> 3 // service, track, cycleway, unclassified, road, ...
}

// --- POI ---------------------------------------------------------------------

private fun poiRank(tags: Map<String, String>): Int = when {
    tags["aeroway"] == "aerodrome" -> 240
    tags["amenity"] == "hospital" -> 210
    tags["amenity"] == "university" || tags["amenity"] == "college" -> 205
    tags["railway"] == "station" -> 195
    tags["amenity"] == "fuel" -> 185
    tags["tourism"] in setOf("museum", "zoo", "theme_park", "attraction") -> 175
    tags["tourism"] in setOf("hotel", "motel", "hostel", "caravan_site", "camp_site") -> 165
    tags["amenity"] == "police" -> 170
    tags["amenity"] == "post_office" -> 165
    tags["amenity"] == "charging_station" -> 160
    tags["amenity"] == "school" -> 160
    tags["amenity"] in setOf("restaurant", "fast_food", "cafe", "pub", "bar") -> 150
    tags.containsKey("shop") -> 140
    tags["leisure"] in setOf("park", "garden", "beach_resort") ||
        tags["natural"] in setOf("beach", "peak", "water") -> 135
    tags["amenity"] in setOf("bank", "pharmacy", "clinic") -> 155
    tags["highway"] in setOf("rest_area", "services") -> 165
    tags["highway"] in setOf("bus_stop", "bus_bay", "platform") ||
        tags["railway"] in setOf("tram_stop", "halt") -> 155
    else -> 120
}

/** OM PoiType order; 8 (SERVICE) is checked before the 7 (GENERAL) fallback. */
private fun poiSubType(tags: Map<String, String>): Int = when {
    tags["aeroway"] == "aerodrome" || tags["railway"] == "station" -> 0 // TRANSPORT_MAJOR
    tags["highway"] in setOf("bus_stop", "bus_bay", "platform") || tags["amenity"] == "taxi" ||
        tags["railway"] in setOf("tram_stop", "halt") -> 1 // TRANSPORT_LOCAL
    tags["amenity"] in setOf("restaurant", "fast_food", "cafe", "pub", "bar", "ice_cream") -> 2 // EAT
    tags["tourism"] in setOf("hotel", "motel", "hostel", "camp_site", "caravan_site", "guest_house") -> 3 // HOTEL
    tags.containsKey("shop") ||
        tags["amenity"] in setOf("bank", "pharmacy", "post_office", "marketplace", "cinema", "clinic") -> 4 // SHOP_AMENITY
    tags["tourism"] in setOf("museum", "zoo", "attraction", "viewpoint", "gallery") ||
        tags.containsKey("historic") ||
        tags["leisure"] in setOf("park", "garden", "beach_resort") ||
        tags["natural"] in setOf("beach", "peak", "water", "volcano") -> 5 // ATTRACTION
    tags["amenity"] in setOf("fuel", "charging_station", "car_wash", "car_repair", "motorcycle_parking", "parking") ||
        tags["highway"] in setOf("rest_area", "services") ||
        tags["shop"] in setOf("motorcycle", "car_repair", "tyres") -> 6 // CAR_INFRA
    tags["amenity"] in setOf("toilets", "bench", "fountain", "drinking_water", "waste_basket", "shelter") ||
        tags.containsKey("power") || tags.containsKey("barrier") -> 8 // SERVICE
    else -> 7 // GENERAL
}

// ---------------------------------------------------------------------------
// Doc model
// ---------------------------------------------------------------------------

private class Doc(
    val docType: Int,
    val subType: Int,
    val rank: Int,
    val latE7: Int,
    val lonE7: Int,
    val name: String,
    /** Normalized tokens of the display name (stored in the doc record). */
    val nameTokens: List<String>,
) {
    var localityId: Int = NO_LOCALITY
    var cityName: String = ""
}

// ---------------------------------------------------------------------------
// Index builder
// ---------------------------------------------------------------------------

private class EntityCounts {
    var nodes = 0L
    var ways = 0L
    var relations = 0L
    var total = 0L
}

class IndexBuilder {

    private val terms = HashMap<String, IntList>()
    private val docs = ArrayList<Doc>(1_000_000)
    private val counts = EntityCounts()
    private val keywords = PoiKeywords.load()

    fun build(pbf: File, outDir: File): File {
        println("Reading $pbf ...")
        parse(pbf)
        println("Assigning localities ...")
        assignLocalities()
        addLocalityPostings()
        check(docs.isNotEmpty()) {
            "no indexable features found in $pbf — the Android reader requires docCount > 0; refusing to write an empty index"
        }
        check(terms.isNotEmpty()) {
            "no terms were indexed — the Android reader requires termCount > 0; refusing to write an empty index"
        }
        outDir.mkdirs()
        val outFile = File(outDir, "geocoder.dat")
        val entries = write(outFile)
        printStats(outFile, entries)
        return outFile
    }

    // --- PBF pass -----------------------------------------------------------

    private fun parse(pbf: File) {
        val nodeMap = LongLongMap()
        val reader = OsmosisReader(pbf)
        reader.setSink(object : Sink {
            override fun initialize(metaData: MutableMap<String, Any>) {}

            override fun process(entityContainer: EntityContainer) {
                when (val e = entityContainer.entity) {
                    is Node -> {
                        nodeMap[e.id] = pack(nodeLatE7(e), nodeLonE7(e))
                        processNode(e)
                        counts.nodes++
                    }
                    is Way -> {
                        processWay(e, nodeMap)
                        counts.ways++
                    }
                    is Relation -> counts.relations++
                    else -> {}
                }
                counts.total++
                if (counts.total % 100_000L == 0L) {
                    println("Processed ${counts.total} entities (docs so far: ${docs.size})")
                }
            }

            override fun complete() {}

            override fun close() {}
        })
        reader.run()
        println("Entities: nodes=${counts.nodes} ways=${counts.ways} relations=${counts.relations}")
        // The node coordinate table is the single largest allocation (hundreds of
        // MB for a state extract). It is only needed while ways are being
        // processed, so release it before locality assignment / writing.
        nodeMap.clear()
    }

    private fun processNode(node: Node) {
        val tags = interestingTags(node.tags) ?: return
        classify(nodeLatE7(node), nodeLonE7(node), tags, isWay = false)
    }

    private fun processWay(way: Way, nodeMap: LongLongMap) {
        val tags = interestingTags(way.tags) ?: return
        // Centroid over resolvable node refs (extracts can reference missing nodes).
        var latSum = 0L
        var lonSum = 0L
        var n = 0
        for (wn in way.wayNodes) {
            val packed = nodeMap[wn.nodeId]
            if (packed != 0L) {
                latSum += (packed ushr 32).toInt()
                lonSum += (packed and 0xFFFFFFFFL).toInt()
                n++
            }
        }
        if (n == 0) return // unresolvable way
        val latE7 = (latSum.toDouble() / n).roundToInt()
        val lonE7 = (lonSum.toDouble() / n).roundToInt()
        classify(latE7, lonE7, tags, isWay = true)
    }

    /** Classification priority: LOCALITY > highway-only POI > STREET (ways only) > POI. */
    private fun classify(latE7: Int, lonE7: Int, tags: Map<String, String>, isWay: Boolean) {
        val displayName = displayName(tags) ?: return
        val place = tags["place"]
        if (place != null) {
            val subType = LOCALITY_SUBTYPE[place]
            if (subType != null) {
                addDoc(
                    TYPE_LOCALITY, subType, localityRank(tags, LOCALITY_RANK[place] ?: 130),
                    latE7, lonE7, displayName, localityTerms(tags),
                )
                return
            }
            if (place in OTHER_PLACES) {
                addDoc(TYPE_LOCALITY, 5, 130, latE7, lonE7, displayName, localityTerms(tags))
                return
            }
        }
        val highway = tags["highway"]
        if (highway in POI_HIGHWAYS) {
            // Named highway-only POIs (bus stops, rest areas, services, ...):
            // POI even when the feature is a way — never a street.
            addDoc(TYPE_POI, poiSubType(tags), poiRank(tags), latE7, lonE7, displayName, poiTerms(tags))
            return
        }
        if (isWay && highway != null && highway !in SKIPPED_HIGHWAYS) {
            addDoc(
                TYPE_STREET, streetSubType(highway),
                STREET_RANK[highway] ?: STREET_RANK_FALLBACK,
                latE7, lonE7, displayName, streetTerms(tags),
            )
            return
        }
        if (tags.keys.any { it in POI_KEYS }) {
            addDoc(TYPE_POI, poiSubType(tags), poiRank(tags), latE7, lonE7, displayName, poiTerms(tags))
        }
    }

    private fun addDoc(
        docType: Int, subType: Int, rank: Int, latE7: Int, lonE7: Int,
        name: String, terms: List<String>,
    ) {
        val docId = docs.size
        val nameTokens = SearchText.tokenize(SearchText.normalize(name)).let(SearchText::capTokens)
        val doc = Doc(docType, subType, rank, latE7, lonE7, name, nameTokens)
        if (docType == TYPE_LOCALITY) {
            doc.localityId = docId
            doc.cityName = name
        }
        docs.add(doc)
        for (t in terms) addTerm(t, docId)
    }

    // --- per-doc term lists ---------------------------------------------------

    private fun displayName(tags: Map<String, String>): String? {
        val name = tags["name"] ?: tags["name:en"] ?: return null
        return if (SearchText.tokenize(SearchText.normalize(name)).isEmpty()) null else name
    }

    private fun localityTerms(tags: Map<String, String>): List<String> {
        val out = ArrayList<String>()
        addFieldTokens(out, tags["name"])
        val nameEn = tags["name:en"]
        if (nameEn != null && nameEn != tags["name"]) addFieldTokens(out, nameEn)
        addFieldTokens(out, tags["alt_name"])
        addFieldTokens(out, tags["short_name"])
        return out
    }

    private fun streetTerms(tags: Map<String, String>): List<String> {
        val out = ArrayList<String>()
        addStreetField(out, tags["name"])
        val nameEn = tags["name:en"]
        if (nameEn != null && nameEn != tags["name"]) addStreetField(out, nameEn)
        addStreetField(out, tags["old_name"])
        addFieldTokens(out, tags["ref"]) // road number; no synonym filtering
        return out
    }

    private fun poiTerms(tags: Map<String, String>): List<String> {
        val out = ArrayList<String>()
        addFieldTokens(out, tags["name"])
        val nameEn = tags["name:en"]
        if (nameEn != null && nameEn != tags["name"]) addFieldTokens(out, nameEn)
        addFieldTokens(out, tags["brand"])
        addFieldTokens(out, tags["operator"])
        for (kw in keywords.keywordsFor(tags)) addFieldTokens(out, kw)
        return out
    }

    private fun addFieldTokens(out: MutableList<String>, field: String?) {
        if (field.isNullOrEmpty()) return
        for (tok in SearchText.tokenize(SearchText.normalize(field)).let(SearchText::capTokens)) {
            out.add(tok)
        }
    }

    /** Name fields with street-synonym dropping (per field, like OM). */
    private fun addStreetField(out: MutableList<String>, field: String?) {
        if (field.isNullOrEmpty()) return
        val tokens = SearchText.tokenize(SearchText.normalize(field)).let(SearchText::capTokens)
        out.addAll(SearchText.streetTokens(tokens))
    }

    // --- terms map -------------------------------------------------------------

    private fun addTerm(term: String, docId: Int) {
        val list = terms.getOrPut(term) { IntList() }
        // Doc ids are appended in ascending doc order; within one doc a term can
        // repeat (e.g. keyword == name token), and those repeats are consecutive
        // in this list, so checking the last element dedupes correctly.
        if (list.size == 0 || list.last != docId) list.add(docId)
    }

    private fun addLocalityPostings() {
        for ((docId, d) in docs.withIndex()) {
            if (d.localityId != NO_LOCALITY) addTerm(localityTerm(d.localityId), docId)
        }
    }

    /** Synthetic term 0x02 + BE localityId: one posting per doc in that locality. */
    private fun localityTerm(localityId: Int): String {
        val bytes = ByteArray(5)
        bytes[0] = 0x02
        bytes[1] = (localityId ushr 24).toByte()
        bytes[2] = (localityId ushr 16).toByte()
        bytes[3] = (localityId ushr 8).toByte()
        bytes[4] = localityId.toByte()
        return String(bytes, ISO_8859_1)
    }

    // --- locality assignment ------------------------------------------------------

    private fun assignLocalities() {
        val localities = ArrayList<Int>()
        for ((i, d) in docs.withIndex()) if (d.docType == TYPE_LOCALITY) localities.add(i)
        if (localities.isEmpty()) return

        var minLat = Int.MAX_VALUE
        var maxLat = Int.MIN_VALUE
        var minLon = Int.MAX_VALUE
        var maxLon = Int.MIN_VALUE
        for (id in localities) {
            val d = docs[id]
            if (d.latE7 < minLat) minLat = d.latE7
            if (d.latE7 > maxLat) maxLat = d.latE7
            if (d.lonE7 < minLon) minLon = d.lonE7
            if (d.lonE7 > maxLon) maxLon = d.lonE7
        }

        // 10km grid over the locality-center bbox.
        val cellLatDeg = 10000.0 / 111000.0
        val midLatRad = Math.toRadians((minLat + maxLat) / 2.0 / 1e7)
        val cellLonDeg = 10000.0 / (111000.0 * cos(midLatRad))

        fun cellOf(latE7: Int, lonE7: Int): Pair<Int, Int> {
            val cx = floor((lonE7 / 1e7 - minLon / 1e7) / cellLonDeg).toInt()
            val cy = floor((latE7 / 1e7 - minLat / 1e7) / cellLatDeg).toInt()
            return cx to cy
        }

        fun packCell(cx: Int, cy: Int): Long = (cx.toLong() shl 32) or (cy.toLong() and 0xFFFFFFFFL)

        val grid = HashMap<Long, MutableList<Int>>()
        for (id in localities) {
            val (cx, cy) = cellOf(docs[id].latE7, docs[id].lonE7)
            grid.getOrPut(packCell(cx, cy)) { ArrayList() }.add(id)
        }

        for ((docId, d) in docs.withIndex()) {
            if (d.docType == TYPE_LOCALITY) continue
            val (cx, cy) = cellOf(d.latE7, d.lonE7)
            var best = -1
            var bestDist = Double.MAX_VALUE
            for (dx in -1..1) for (dy in -1..1) {
                grid[packCell(cx + dx, cy + dy)]?.let { cell ->
                    for (lid in cell) {
                        val dist = distanceM(d, docs[lid])
                        if (dist < bestDist) {
                            bestDist = dist
                            best = lid
                        }
                    }
                }
            }
            if (best < 0) {
                // Empty cell neighbourhood: linear scan over all localities.
                for (lid in localities) {
                    val dist = distanceM(d, docs[lid])
                    if (dist < bestDist) {
                        bestDist = dist
                        best = lid
                    }
                }
            }
            if (best >= 0 && bestDist <= LOCALITY_RADIUS_M[docs[best].subType]) {
                d.localityId = best
                d.cityName = docs[best].name
            }
        }
    }

    private fun distanceM(a: Doc, b: Doc): Double {
        val dLat = (b.latE7 - a.latE7) / 1e7 * 111000.0
        val midLatRad = Math.toRadians((a.latE7 + b.latE7) / 2.0 / 1e7)
        val dLon = (b.lonE7 - a.lonE7) / 1e7 * 111000.0 * cos(midLatRad)
        return sqrt(dLat * dLat + dLon * dLon)
    }

    // --- writing -----------------------------------------------------------------

    /**
     * Writes the index atomically: the full file is written to a temp file in
     * the destination directory, fsynced, then renamed over the target, so a
     * crash mid-write can never leave a truncated/corrupt `geocoder.dat` at the
     * final path. Every offset/length is validated against the reader's
     * contract (u32 file offsets read as signed Ints, u16 string lengths)
     * before anything hits disk.
     */
    private fun write(outFile: File): List<Pair<String, IntArray>> {
        val entries = terms
            .map { (term, postings) -> term to postings.toSortedUniqueArray() }
            .sortedWith { a, b -> compareBytesUnsigned(a.first.toByteArray(UTF_8), b.first.toByteArray(UTF_8)) }
        // The raw IntList posting lists are no longer needed once the sorted
        // arrays exist; drop them before the potentially large write pass.
        terms.clear()

        val docCount = docs.size
        val localityCount = docs.count { it.docType == TYPE_LOCALITY }
        val termCount = entries.size
        val headerSize = 32
        val docsOffset = headerSize.toLong() + 4L * docCount

        check(docCount <= (0xFFFF_FFFFL - headerSize) / 4) {
            "too many docs ($docCount): doc offset table alone exceeds u32 file offsets"
        }
        checkU32Offset("docsOffset", docsOffset)

        val docSizes = IntArray(docCount)
        var docBytes = 0L
        for ((i, d) in docs.withIndex()) {
            val nameBytes = d.name.toByteArray(UTF_8)
            val cityBytes = d.cityName.toByteArray(UTF_8)
            check(nameBytes.size <= 0xFFFF) {
                "doc name too long (${nameBytes.size} bytes) for u16 length field: \"${d.name.take(40)}\""
            }
            check(cityBytes.size <= 0xFFFF) {
                "city name too long (${cityBytes.size} bytes) for u16 length field: \"${d.cityName.take(40)}\""
            }
            // Must stay in lockstep with writeDoc (incl. the 255-token cap).
            var size = 1 + 1 + 1 + 4 + 4 + 4 + 2 + nameBytes.size + 1 + 2 + cityBytes.size
            for (t in d.nameTokens.take(255)) {
                val tb = t.toByteArray(UTF_8)
                check(tb.size <= 0xFFFF) { "name token too long (${tb.size} bytes) for u16 length field" }
                size += 2 + tb.size
            }
            docSizes[i] = size
            docBytes += size
        }
        val termsOffset = docsOffset + docBytes
        checkU32Offset("termsOffset", termsOffset)

        var termBytes = 0L
        for ((term, _) in entries) {
            val tb = term.toByteArray(UTF_8)
            check(tb.size <= 0xFFFF) { "term too long (${tb.size} bytes) for u16 length field" }
            termBytes += 2 + tb.size + 4 + 4
        }
        val postingsOffset = termsOffset + termBytes
        checkU32Offset("postingsOffset", postingsOffset)

        val blobSizes = IntArray(termCount)
        var postingsBytes = 0L
        for ((i, pair) in entries.withIndex()) {
            var size = 0
            var prev = 0
            for (id in pair.second) {
                size += varIntSize(id - prev)
                prev = id
            }
            blobSizes[i] = size
            postingsBytes += size
        }
        val expected = postingsOffset + postingsBytes
        checkU32Offset("file size", expected)

        val tmp = File(
            outFile.absoluteFile.parentFile,
            ".${outFile.name}.tmp-${ProcessHandle.current().pid()}",
        )
        try {
            BufferedOutputStream(FileOutputStream(tmp), 1 shl 20).use { out ->
                out.write("OMGEO01\n".toByteArray(US_ASCII))
                writeU32(out, docCount)
                writeU32(out, termCount)
                writeU32(out, localityCount)
                writeU32(out, docsOffset.toInt())
                writeU32(out, termsOffset.toInt())
                writeU32(out, postingsOffset.toInt())

                var off = docsOffset
                for (i in docs.indices) {
                    writeU32(out, off.toInt())
                    off += docSizes[i]
                }

                for (d in docs) writeDoc(out, d)

                var rel = 0
                for ((i, pair) in entries.withIndex()) {
                    val tb = pair.first.toByteArray(UTF_8)
                    writeU16(out, tb.size)
                    out.write(tb)
                    writeU32(out, rel)
                    writeU32(out, pair.second.size)
                    rel += blobSizes[i]
                }

                for ((_, postings) in entries) {
                    var prev = 0
                    for (id in postings) {
                        writeVarUInt(out, id - prev)
                        prev = id
                    }
                }
            }

            // fsync before the rename so a power loss cannot leave a
            // zero-length/truncated file at the final path either.
            FileChannel.open(tmp.toPath(), StandardOpenOption.WRITE).use { it.force(true) }

            // Consistency checks against the temp file, before it is visible
            // at the final path.
            check(tmp.length() == expected) {
                "size mismatch: file=${tmp.length()} expected=$expected"
            }
            check(entries.size == termCount)

            try {
                Files.move(
                    tmp.toPath(), outFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), outFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            tmp.delete() // no-op after a successful rename
        }

        println("Wrote ${outFile.name}: docs=$docCount terms=$termCount localities=$localityCount " +
            "fileSize=${outFile.length()} bytes")
        return entries
    }

    /** The reader stores file offsets as u32 and reads them as signed Ints. */
    private fun checkU32Offset(label: String, value: Long) {
        check(value >= 0 && value <= 0xFFFF_FFFFL) { "$label=$value exceeds u32 range" }
        check(value <= Int.MAX_VALUE) { "$label=$value exceeds the Android reader's Int range" }
    }

    private fun writeDoc(out: OutputStream, d: Doc) {
        out.write(d.docType)
        out.write(d.subType)
        out.write(d.rank)
        writeU32(out, d.latE7)
        writeU32(out, d.lonE7)
        writeU32(out, d.localityId) // NO_LOCALITY == -1 == 0xFFFFFFFF
        val nameBytes = d.name.toByteArray(UTF_8)
        check(nameBytes.size <= 0xFFFF) {
            "doc name too long (${nameBytes.size} bytes) for u16 length field: \"${d.name.take(40)}\""
        }
        writeU16(out, nameBytes.size)
        out.write(nameBytes)
        val tokens = d.nameTokens
        out.write(min(tokens.size, 255))
        for (t in tokens.take(255)) {
            val tb = t.toByteArray(UTF_8)
            check(tb.size <= 0xFFFF) { "name token too long (${tb.size} bytes) for u16 length field" }
            writeU16(out, tb.size)
            out.write(tb)
        }
        val cityBytes = d.cityName.toByteArray(UTF_8)
        check(cityBytes.size <= 0xFFFF) { "city name too long (${cityBytes.size} bytes) for u16 length field" }
        writeU16(out, cityBytes.size)
        out.write(cityBytes)
    }

    // --- stats --------------------------------------------------------------------

    private fun printStats(outFile: File, entries: List<Pair<String, IntArray>>) {
        val poi = docs.count { it.docType == TYPE_POI }
        val street = docs.count { it.docType == TYPE_STREET }
        val locality = docs.count { it.docType == TYPE_LOCALITY }
        val localityByClass = IntArray(6)
        for (d in docs) if (d.docType == TYPE_LOCALITY) localityByClass[d.subType]++
        val postingsSum = entries.sumOf { it.second.size.toLong() }
        println("=".repeat(40))
        println("GEOCODER INDEX STATS")
        println("Entities: nodes=${counts.nodes} ways=${counts.ways} relations=${counts.relations} total=${counts.total}")
        println("Docs: POI=$poi STREET=$street LOCALITY=$locality (total=${docs.size})")
        println("Locality classes: city=${localityByClass[0]} town=${localityByClass[1]} " +
            "village=${localityByClass[2]} suburb=${localityByClass[3]} state=${localityByClass[4]} other=${localityByClass[5]}")
        println("Terms: ${entries.size}")
        println("Postings (sum of posting list lengths): $postingsSum")
        println("File size: ${outFile.length()} bytes")
    }

    // --- helpers --------------------------------------------------------------------

    private fun localityRank(tags: Map<String, String>, base: Int): Int {
        var rank = base
        val pop = tags["population"]?.trim()?.toLongOrNull()
        if (pop != null && pop > 0) {
            rank = min(255, rank + min(15, log10(pop.toDouble()).toInt() * 4))
        }
        return rank
    }
}

// ---------------------------------------------------------------------------
// PBF helpers
// ---------------------------------------------------------------------------

private fun nodeLatE7(node: Node): Int = (node.latitude * 1e7).roundToInt()
private fun nodeLonE7(node: Node): Int = (node.longitude * 1e7).roundToInt()

private fun pack(latE7: Int, lonE7: Int): Long =
    (latE7.toLong() shl 32) xor (lonE7.toLong() and 0xFFFFFFFFL)

/** Builds the tag map only when the entity carries at least one interesting key. */
private fun interestingTags(tags: Collection<Tag>): Map<String, String>? {
    var map: HashMap<String, String>? = null
    for (t in tags) {
        if (t.key in INTERESTING_KEYS) {
            if (map == null) map = HashMap()
            map[t.key] = t.value
        }
    }
    return map
}

// ---------------------------------------------------------------------------
// Little-endian / varint writers
// ---------------------------------------------------------------------------

private fun writeU16(out: OutputStream, v: Int) {
    out.write(v and 0xFF)
    out.write((v ushr 8) and 0xFF)
}

private fun writeU32(out: OutputStream, v: Int) {
    out.write(v and 0xFF)
    out.write((v ushr 8) and 0xFF)
    out.write((v ushr 16) and 0xFF)
    out.write((v ushr 24) and 0xFF)
}

/** Unsigned LEB128 varint (deltas are non-negative). */
private fun writeVarUInt(out: OutputStream, v: Int) {
    var x = v
    while (x >= 0x80) {
        out.write((x and 0x7F) or 0x80)
        x = x ushr 7
    }
    out.write(x)
}

private fun varIntSize(v: Int): Int {
    var x = v
    var n = 1
    while (x >= 0x80) {
        x = x ushr 7
        n++
    }
    return n
}

/** Unsigned lexicographic byte comparison (term dict is sorted this way). */
private fun compareBytesUnsigned(a: ByteArray, b: ByteArray): Int {
    val n = min(a.size, b.size)
    for (i in 0 until n) {
        val x = a[i].toInt() and 0xFF
        val y = b[i].toInt() and 0xFF
        if (x != y) return x - y
    }
    return a.size - b.size
}

// ---------------------------------------------------------------------------
// --inspect / --validate / --check-sync modes
// ---------------------------------------------------------------------------

private class TermEntry(val bytes: ByteArray, val postingOffset: Int, val postingCount: Int)

private class IndexHeader(
    val docCount: Int,
    val termCount: Int,
    val localityCount: Int,
    val docsOffset: Int,
    val termsOffset: Int,
    val postingsOffset: Int,
)

/**
 * Validates the magic + header the same way the Android reader does
 * (GeocoderIndex.parse): a file the reader would reject at load time is
 * reported as corrupt here, so bad indexes are caught on the desktop instead
 * of on-device.
 */
private fun readHeader(buf: ByteBuffer, file: File): IndexHeader {
    if (buf.capacity() < 32) {
        throw IllegalStateException(
            "not a geocoder index (${buf.capacity()} bytes < 32-byte header): $file"
        )
    }
    val magic = String(ByteArray(8) { buf.get(it) }, US_ASCII)
    check(magic == "OMGEO01\n") { "not a geocoder index (bad magic): $file" }
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
    if (docsOffset != (32L + 4L * docCount).toInt()) {
        throw IllegalStateException(
            "corrupt geocoder index header: docsOffset=$docsOffset expected=${32L + 4L * docCount}"
        )
    }
    if (termsOffset < docsOffset || postingsOffset < termsOffset || postingsOffset > buf.capacity()) {
        throw IllegalStateException(
            "corrupt geocoder index header: docs=$docsOffset terms=$termsOffset postings=$postingsOffset file=${buf.capacity()}"
        )
    }
    return IndexHeader(docCount, termCount, localityCount, docsOffset, termsOffset, postingsOffset)
}

private fun inspect(args: Array<String>) {
    if (args.size < 3) {
        System.err.println("Usage: GeocoderIndexBuilder --inspect <pbf(ignored)> <geocoder.dat> [terms...]")
        exitProcess(1)
    }
    val file = File(args[2])
    check(file.isFile) { "not a file: $file" }
    FileChannel.open(file.toPath(), StandardOpenOption.READ).use { ch ->
        val buf = ch.map(FileChannel.MapMode.READ_ONLY, 0, file.length()).order(ByteOrder.LITTLE_ENDIAN)
        val h = readHeader(buf, file)

        println("== ${file.name} (${file.length()} bytes) ==")
        println("docs: ${h.docCount}  terms: ${h.termCount}  localities: ${h.localityCount}")

        // One sequential pass over doc records for docType / locality-class counts.
        val docTypeCounts = IntArray(3)
        val locClasses = IntArray(6)
        var off = h.docsOffset
        for (i in 0 until h.docCount) {
            if (off < h.docsOffset || off >= h.termsOffset) {
                throw IllegalStateException(
                    "corrupt geocoder index: doc record $i at $off outside [docsOffset, termsOffset)"
                )
            }
            val type = buf.get(off).toInt() and 0xFF
            val subType = buf.get(off + 1).toInt() and 0xFF
            if (type !in 0..2) throw IllegalStateException("corrupt geocoder index: doc $i has unknown type $type")
            docTypeCounts[type]++
            if (type == TYPE_LOCALITY) {
                if (subType !in 0..5) {
                    throw IllegalStateException("corrupt geocoder index: locality doc $i has unknown subType $subType")
                }
                locClasses[subType]++
            }
            off = docRecordEnd(buf, off)
        }
        if (off != h.termsOffset) {
            throw IllegalStateException("corrupt geocoder index: doc records end at $off but termsOffset=${h.termsOffset}")
        }
        println("docs by type: POI=${docTypeCounts[0]} STREET=${docTypeCounts[1]} LOCALITY=${docTypeCounts[2]}")
        println("locality classes: city=${locClasses[0]} town=${locClasses[1]} village=${locClasses[2]} " +
            "suburb=${locClasses[3]} state=${locClasses[4]} other=${locClasses[5]}")

        // Load the term dict for lookups.
        val entries = ArrayList<TermEntry>(h.termCount)
        off = h.termsOffset
        for (i in 0 until h.termCount) {
            if (off + 2 > h.postingsOffset) {
                throw IllegalStateException("corrupt geocoder index: term dict entry $i overruns the dictionary")
            }
            val len = buf.getShort(off).toInt() and 0xFFFF
            off += 2
            if (off + len + 8 > h.postingsOffset) {
                throw IllegalStateException("corrupt geocoder index: term dict entry $i overruns the dictionary")
            }
            val bytes = ByteArray(len)
            buf.get(off, bytes)
            off += len
            val postingOffset = buf.getInt(off)
            val postingCount = buf.getInt(off + 4)
            off += 8
            if (postingOffset < 0 || postingCount < 0) {
                throw IllegalStateException(
                    "corrupt geocoder index: term dict entry $i has postingOffset=$postingOffset postingCount=$postingCount"
                )
            }
            entries.add(TermEntry(bytes, postingOffset, postingCount))
        }
        if (off != h.postingsOffset) {
            throw IllegalStateException("corrupt geocoder index: term dict ends at $off but postingsOffset=${h.postingsOffset}")
        }

        // doc offsets table
        val docOffsets = IntArray(h.docCount)
        for (i in 0 until h.docCount) {
            val o = buf.getInt(32 + 4 * i)
            if (o < h.docsOffset || o >= h.termsOffset) {
                throw IllegalStateException(
                    "corrupt geocoder index: doc offset table entry $i = $o outside [docsOffset, termsOffset)"
                )
            }
            docOffsets[i] = o
        }

        for (arg in args.drop(3)) {
            val tokens = SearchText.tokenize(SearchText.normalize(arg))
            if (tokens.isEmpty()) {
                println("term \"$arg\": normalizes to nothing")
                continue
            }
            for (tok in tokens) {
                val idx = binarySearch(entries, tok.toByteArray(UTF_8))
                if (idx < 0) {
                    println("term \"$tok\" (from \"$arg\"): 0 postings")
                    continue
                }
                val e = entries[idx]
                val docIds = decodePostings(buf, h.postingsOffset + e.postingOffset, e.postingCount)
                println("term \"$tok\" (from \"$arg\"): ${e.postingCount} postings")
                for (id in docIds.take(10)) {
                    val (label, name, city) = docInfo(buf, docOffsets[id])
                    if (city.isNotEmpty()) println("    [$label] $name ($city)")
                    else println("    [$label] $name")
                }
            }
        }
    }
}

/**
 * `--validate <geocoder.dat>`: full structural validation of an existing index.
 * Goes beyond the reader's load-time header checks: it verifies the doc offset
 * table and every doc record, term-dict ordering, posting blob layout and every
 * decoded posting id. Use it to certify a file is compatible with the Android
 * reader before shipping it as an asset.
 */
private fun validate(args: Array<String>) {
    if (args.size < 2) {
        System.err.println("Usage: GeocoderIndexBuilder --validate <geocoder.dat>")
        exitProcess(1)
    }
    val file = File(args[1])
    check(file.isFile) { "not a file: $file" }
    FileChannel.open(file.toPath(), StandardOpenOption.READ).use { ch ->
        val buf = ch.map(FileChannel.MapMode.READ_ONLY, 0, file.length()).order(ByteOrder.LITTLE_ENDIAN)
        val h = readHeader(buf, file)

        // --- doc offset table: strictly increasing, inside the record region ---
        val docOffsets = IntArray(h.docCount)
        for (i in 0 until h.docCount) {
            val o = buf.getInt(32 + 4 * i)
            if (o < h.docsOffset || o >= h.termsOffset) {
                throw IllegalStateException(
                    "corrupt: doc offset table entry $i = $o outside [docsOffset=${h.docsOffset}, termsOffset=${h.termsOffset})"
                )
            }
            if (i > 0 && o <= docOffsets[i - 1]) {
                throw IllegalStateException(
                    "corrupt: doc offset table entry $i = $o not strictly increasing (prev=${docOffsets[i - 1]})"
                )
            }
            docOffsets[i] = o
        }
        if (docOffsets[0] != h.docsOffset) {
            throw IllegalStateException(
                "corrupt: first doc record at ${docOffsets[0]} but docsOffset=${h.docsOffset}"
            )
        }

        // --- doc records: each ends exactly where the next begins; the last
        //     ends at termsOffset; type/subType/localityId are in range ---
        for (i in 0 until h.docCount) {
            val recordEnd = docRecordEnd(buf, docOffsets[i])
            val expectedEnd = if (i + 1 < h.docCount) docOffsets[i + 1] else h.termsOffset
            if (recordEnd != expectedEnd) {
                throw IllegalStateException(
                    "corrupt: doc record $i at ${docOffsets[i]} ends at $recordEnd but expected $expectedEnd"
                )
            }
            val type = buf.get(docOffsets[i]).toInt() and 0xFF
            if (type !in 0..2) throw IllegalStateException("corrupt: doc $i has unknown type $type")
            if (type == TYPE_LOCALITY) {
                val subType = buf.get(docOffsets[i] + 1).toInt() and 0xFF
                if (subType !in 0..5) {
                    throw IllegalStateException("corrupt: locality doc $i has unknown subType $subType")
                }
            }
            // localityId: offset 11 = 1 docType + 1 subType + 1 rank + 4 lat + 4 lon
            val localityU32 = buf.getInt(docOffsets[i] + 11).toLong() and 0xFFFFFFFFL
            if (localityU32 != 0xFFFFFFFFL && localityU32 >= h.docCount.toLong()) {
                throw IllegalStateException(
                    "corrupt: doc $i has localityId $localityU32 out of range (docCount=${h.docCount})"
                )
            }
        }

        // --- term dict: strictly ascending unsigned sort; blobs contiguous;
        //     every posting decodes to a strictly ascending doc id < docCount ---
        var o = h.termsOffset
        var prevTerm: ByteArray? = null
        var cumulativeBlob = 0
        for (i in 0 until h.termCount) {
            if (o + 2 > h.postingsOffset) {
                throw IllegalStateException("corrupt: term dict entry $i overruns the dictionary")
            }
            val len = buf.getShort(o).toInt() and 0xFFFF
            o += 2
            if (o + len + 8 > h.postingsOffset) {
                throw IllegalStateException("corrupt: term dict entry $i overruns the dictionary")
            }
            val bytes = ByteArray(len)
            buf.get(o, bytes)
            o += len
            val relOffset = buf.getInt(o)
            val postingCount = buf.getInt(o + 4)
            o += 8
            if (relOffset < 0 || postingCount <= 0) {
                throw IllegalStateException(
                    "corrupt: term dict entry $i has postingOffset=$relOffset postingCount=$postingCount"
                )
            }
            if (relOffset != cumulativeBlob) {
                throw IllegalStateException(
                    "corrupt: term dict entry $i postingOffset=$relOffset but posting blobs are contiguous (expected $cumulativeBlob)"
                )
            }
            if (prevTerm != null && compareBytesUnsigned(prevTerm, bytes) >= 0) {
                throw IllegalStateException("corrupt: term dict not strictly sorted at entry $i")
            }
            prevTerm = bytes

            var p = h.postingsOffset + relOffset
            var prevId = 0
            for (k in 0 until postingCount) {
                if (p >= buf.capacity()) {
                    throw IllegalStateException("corrupt: posting blob of term dict entry $i overruns the file")
                }
                var v = 0
                var shift = 0
                while (true) {
                    if (p >= buf.capacity()) {
                        throw IllegalStateException("corrupt: posting blob of term dict entry $i overruns the file")
                    }
                    val b = buf.get(p).toInt() and 0xFF
                    p++
                    v = v or ((b and 0x7F) shl shift)
                    if (b and 0x80 == 0) break
                    shift += 7
                    if (shift > 35) {
                        throw IllegalStateException("corrupt: overlong varint in posting blob of term dict entry $i")
                    }
                }
                val id = prevId + v // first delta == first docId, then docId - previous
                if (id >= h.docCount) {
                    throw IllegalStateException("corrupt: posting id $id >= docCount=${h.docCount} in term dict entry $i")
                }
                if (k > 0 && id <= prevId) {
                    throw IllegalStateException("corrupt: posting ids not strictly ascending in term dict entry $i")
                }
                prevId = id
            }
            cumulativeBlob = p - h.postingsOffset
        }
        if (o != h.postingsOffset) {
            throw IllegalStateException("corrupt: term dict ends at $o but postingsOffset=${h.postingsOffset}")
        }
        if (cumulativeBlob != buf.capacity() - h.postingsOffset) {
            throw IllegalStateException(
                "corrupt: posting blobs end at ${h.postingsOffset + cumulativeBlob} but file is ${buf.capacity()} bytes"
            )
        }
    }
    println("OK: $file is a valid geocoder index")
}

/**
 * `--check-sync`: verifies the duplicated sources that must stay in sync:
 *  1. `poi_keywords.tsv` (resource) vs the embedded copy in `PoiKeywords.kt`;
 *  2. this module's `Text.kt` vs the Android app's `Text.kt` copy (only the
 *     `package` line is allowed to differ);
 *  3. `poi_keywords.tsv` rule sanity (malformed/conflicting rules).
 * Run from the repo root (as the documented pipeline does).
 */
private fun checkSync() {
    val errors = ArrayList<String>()

    // 1. poi_keywords.tsv vs the embedded PoiKeywords.kt copy.
    try {
        PoiKeywords.checkEmbeddedSync()
        println("OK: poi_keywords.tsv matches the embedded PoiKeywords.kt copy")
    } catch (e: IllegalStateException) {
        errors.add(e.message ?: "keyword sync check failed")
    }

    // 2. Text.kt: tool copy vs app copy (package line differs by design).
    val toolText = File("geocoder-tool/src/main/kotlin/com/organicmoto/geocoder/tool/Text.kt")
    val appText = File("app/src/main/java/com/organicmoto/maps/geocoding/Text.kt")
    for (f in listOf(toolText, appText)) {
        if (!f.isFile) errors.add("missing file: $f (run from the repo root)")
    }
    if (toolText.isFile && appText.isFile) {
        val toolLines = toolText.readLines().drop(1) // strip `package ...`
        val appLines = appText.readLines().drop(1)
        if (toolLines == appLines) {
            println("OK: geocoder-tool Text.kt matches the app's Text.kt copy")
        } else {
            errors.add("Text.kt copies differ: $toolText vs $appText — apply the change to BOTH (see AGENTS.md)")
        }
    }

    if (errors.isEmpty()) {
        println("All sync checks passed.")
    } else {
        for (e in errors) System.err.println("FAIL: $e")
        exitProcess(1)
    }
}

/**
 * Byte length of one doc record starting at [off]. Throws a descriptive error
 * if the record would overrun the mapped file.
 */
private fun docRecordEnd(buf: ByteBuffer, off: Int): Int {
    fun need(pos: Int, len: Int) {
        if (pos < 0 || len < 0 || pos.toLong() + len > buf.capacity()) {
            throw IllegalStateException(
                "corrupt geocoder index: doc record at $off overruns the file " +
                    "(read of $len bytes at $pos, file=${buf.capacity()} bytes)"
            )
        }
    }
    var o = off + 1 + 1 + 1 + 4 + 4 + 4
    need(o, 2)
    val nameLen = buf.getShort(o).toInt() and 0xFFFF
    o += 2
    need(o, nameLen)
    o += nameLen
    need(o, 1)
    val tokenCount = buf.get(o).toInt() and 0xFF
    o += 1
    for (i in 0 until tokenCount) {
        need(o, 2)
        val len = buf.getShort(o).toInt() and 0xFFFF
        o += 2
        need(o, len)
        o += len
    }
    need(o, 2)
    val cityLen = buf.getShort(o).toInt() and 0xFFFF
    return o + 2 + cityLen
}

private fun docInfo(buf: ByteBuffer, off: Int): Triple<String, String, String> {
    val type = buf.get(off).toInt() and 0xFF
    val label = when (type) {
        TYPE_POI -> "POI"
        TYPE_STREET -> "STREET"
        TYPE_LOCALITY -> "LOCALITY"
        else -> "?"
    }
    var o = off + 1 + 1 + 1 + 4 + 4 + 4
    val len = buf.getShort(o).toInt() and 0xFFFF
    o += 2
    val nameBytes = ByteArray(len)
    buf.get(o, nameBytes)
    o += len
    val tokenCount = buf.get(o).toInt() and 0xFF
    o += 1
    for (i in 0 until tokenCount) {
        val tl = buf.getShort(o).toInt() and 0xFFFF
        o += 2 + tl
    }
    val cityLen = buf.getShort(o).toInt() and 0xFFFF
    o += 2
    val cityBytes = ByteArray(cityLen)
    buf.get(o, cityBytes)
    return Triple(label, String(nameBytes, UTF_8), String(cityBytes, UTF_8))
}

/** Decodes [count] varint deltas starting at [start]; bounds-checked. */
private fun decodePostings(buf: ByteBuffer, start: Int, count: Int): IntArray {
    val out = IntArray(count)
    var o = start
    var prev = 0
    for (i in 0 until count) {
        var v = 0
        var shift = 0
        while (true) {
            if (o >= buf.capacity()) {
                throw IllegalStateException("corrupt geocoder index: posting blob overruns the file at $o")
            }
            val b = buf.get(o).toInt() and 0xFF
            o++
            v = v or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        prev += v
        out[i] = prev
    }
    return out
}

private fun binarySearch(entries: List<TermEntry>, key: ByteArray): Int {
    var lo = 0
    var hi = entries.size - 1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        val c = compareBytesUnsigned(entries[mid].bytes, key)
        when {
            c < 0 -> lo = mid + 1
            c > 0 -> hi = mid - 1
            else -> return mid
        }
    }
    return -1
}
