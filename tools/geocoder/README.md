# Geocoder index builder (`:geocoder-tool`)

Desktop tool (Kotlin/JVM) that reads an OSM `.osm.pbf` extract and emits
`geocoder.dat`, a compact binary inverted search index shipped as an Android
asset (`app/src/main/assets/geocoder/geocoder.dat`). It is STEP 1 of adding
Organic-Maps-style offline place search to the app: the Android-side search
engine (STEP 2) reads this exact binary format.

## How it works

Mirrors Organic Maps' map-generation pipeline. Every OSM feature is classified
as POI / STREET / LOCALITY (in that priority order) with a rank and subType;
names are normalized (`Text.kt`: NFKD, lowercase, accent stripping, OM's exact
delimiter set and the `"xyz's" -> "xyzs"` rule), tokenized, and each token is
indexed as a term pointing at a sorted posting list of doc ids. Streets drop
street-type synonyms ("street", "road", …) with OM's `StreetTokensFilter`
semantics and additionally index their `ref` (road number); POIs additionally
index category keywords from `poi_keywords.tsv` plus `brand`/`operator`.
Every non-locality doc is assigned to its nearest locality within a per-class
radius (10 km spatial grid over locality centers); each doc with an assigned
locality gets one extra posting on the synthetic term `0x02 + localityId(4B BE)`
so queries can constrain by locality ("cafe brisbane"). The result is a
little-endian binary file: header → doc offset table → doc records → sorted
term dictionary → varint-delta posting blobs.

**Highway-only POIs.** Some named features carry only a `highway` tag and no
POI key: `highway=bus_stop` nodes, `highway=rest_area` / `highway=services`
areas, `highway=platform`, `highway=bus_bay`, `highway=elevator`. These are
classified as POIs (never streets — even when tagged on a way); without this
they would be dropped entirely (node) or misclassified as STREET (way). Their
subType/rank come from `poiSubType`/`poiRank` (`TRANSPORT_LOCAL`/`CAR_INFRA`)
and keywords from `poi_keywords.tsv` (`highway=bus_stop` → "bus stop,bus",
`highway=rest_area` → "rest area,rest stop", …). Bare unnamed highway features
stay unindexed, as before.

### Pipeline

1. Download an OSM `.osm.pbf` extract (e.g. Geofabrik).
2. Build & run (working dir = repo root; output dir is created if missing):

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :geocoder-tool:run --args="/Users/bailee/Downloads/queensland-260812.osm.pbf app/src/main/assets/geocoder"
```

The tool writes `app/src/main/assets/geocoder/geocoder.dat` **atomically** (a
temp file in the destination directory is fsynced and renamed over the target,
so a crash mid-write never leaves a truncated index at the final path), prints
progress every 100k entities and final stats (docs per type, terms, postings,
file size, locality classes).

The build refuses to write an **empty** index (no docs / no terms) with a clear
error: the Android reader requires `docCount > 0` and `termCount > 0`, so an
empty file would only fail at load time on-device. All file offsets/lengths
are bounds-checked against the reader's contract (u32 offsets read as signed
Ints, u16 string lengths) before anything is written; an oversized name, term
or >2 GiB index fails the build instead of producing a corrupt file.

3. Verify with `--inspect` (first arg is the PBF path, currently unused):

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :geocoder-tool:run --args="--inspect /Users/bailee/Downloads/queensland-260812.osm.pbf app/src/main/assets/geocoder/geocoder.dat brisbane ann cafe fuel qld"
```

4. **Certify the binary format** with `--validate` (full structural check —
   header, doc offset table, every doc record, term-dict ordering, posting
   blob layout and every decoded posting id — mirroring what the reader checks
   at load time, plus more):

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :geocoder-tool:run --args="--validate app/src/main/assets/geocoder/geocoder.dat"
```

`geocoder.dat` is a generated artifact (`app/src/main/assets/geocoder/` is
gitignored), rebuilt by re-running the command above.

## Binary format (`geocoder.dat`, all integers little-endian)

| Offset | Size | Field |
|---|---|---|
| 0 | 8 | magic `"OMGEO01\n"` |
| 8 | 4 | `u32 docCount` |
| 12 | 4 | `u32 termCount` |
| 16 | 4 | `u32 localityCount` |
| 20 | 4 | `u32 docsOffset` (`== 32 + 4*docCount`) |
| 24 | 4 | `u32 termsOffset` |
| 28 | 4 | `u32 postingsOffset` |
| 32 | 4*docCount | `u32 docOffset[i]` — absolute file offset of doc record i |
| docsOffset | … | doc records (below) |
| termsOffset | … | term dict entries (below) |
| postingsOffset | … | posting blobs (below) |

The magic's `"01"` is the format version; the reader requires the exact magic.
All offsets are u32 and the Android reader reads them as signed Ints, so the
builder validates that every offset fits both ranges (practical limit: index
files < 2 GiB). String lengths are u16 (builder rejects anything longer) and
per-doc token counts are capped at 255.

Doc record:

| Size | Field |
|---|---|
| 1 | `u8 docType` (0=POI, 1=STREET, 2=LOCALITY) |
| 1 | `u8 subType` (meaning depends on docType) |
| 1 | `u8 rank` (0..255) |
| 4 | `i32 latE7` |
| 4 | `i32 lonE7` |
| 4 | `u32 localityId` (`0xFFFFFFFF` = none) |
| 2 + n | `u16 nameLen` + name UTF-8 (display name) |
| 1 + Σ | `u8 tokenCount` + per token `u16 len` + token UTF-8 (normalized name tokens for name-score ranking) |
| 2 + m | `u16 cityLen` + city UTF-8 (locality display name, `""` if none) |

Term dict entry (entries sorted lexicographically by term bytes, unsigned):

| Size | Field |
|---|---|
| 2 + n | `u16 len` + term bytes |
| 4 | `u32 postingOffset` (relative to `postingsOffset`) |
| 4 | `u32 postingCount` |

Posting blob (one per term, in dict order): `postingCount` unsigned LEB128
varint deltas of sorted ascending doc ids — first delta = first docId, then
`docId - previous`.

The synthetic locality term is the 5 bytes `0x02` + localityId big-endian
(`0x02` can never appear inside a normalized token, so it cannot collide).

## Format & source-sync compatibility checks

Two sources are duplicated by design and must be kept in sync; both are now
**enforced** so a build never silently uses a stale copy:

- `poi_keywords.tsv` (resource) and the embedded copy in `PoiKeywords.kt` —
  `PoiKeywords.load()` compares them on **every** index build and fails with a
  pointer to both files on mismatch; malformed or conflicting rules (duplicate
  `key[=value]` with different keywords, empty keyword lists) are also errors.
- `geocoder-tool/.../Text.kt` and the Android app's `geocoding/Text.kt` —
  byte-identical except the `package` line.

Run both checks explicitly (from the repo root) with:

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :geocoder-tool:run --args="--check-sync"
```

`--validate <geocoder.dat>` is the binary-format counterpart: it verifies the
header exactly as the Android reader does (magic, counts, `docsOffset`,
offset ordering/bounds) and then walks the whole file — doc offset table
strictly increasing and inside the record region, every doc record ending
exactly where the next begins with the last ending at `termsOffset`,
type/subType/localityId in range, term dict strictly ascending, posting blobs
contiguous, and every decoded posting id strictly ascending and `< docCount`.
A file that passes `--validate` will load in the Android reader.

## Category keywords

`geocoder-tool/src/main/resources/poi_keywords.tsv` — one rule per line,
`osmKey[=value] TAB keyword1,keyword2,…`. A bare-key rule is a fallback that
applies in addition to any specific `key=value` rule that matched (e.g.
`shop=supermarket` gets `supermarket,groceries` plus the bare `shop` fallback
`shop`). Loaded from the tool's classpath; an embedded copy in
`PoiKeywords.kt` is used if the resource is missing. **Keep the two in sync —
the build now fails if they drift** (see above).

## Memory behaviour

The builder streams the PBF once (osmosis `Sink` API). The two large
allocations are the node-id → coordinate map (`LongLongMap`: primitive
open-addressing, starts at 2^20 slots and doubles only as needed, so a small
extract does not pre-allocate hundreds of MB) and the `HashMap<String, IntList>`
term table. After the PBF pass the node map is released (`clear()`) before
locality assignment, and the raw posting lists are dropped once the sorted
arrays are built for writing — peak memory stays close to the term table plus
one sorted copy of the postings.

## How this mirrors Organic Maps

- **Inverted index**: normalized tokens → sorted posting lists (OM's trie with
  posting lists).
- **Normalization/tokenization**: `Text.kt` is a faithful port of OM's
  `search_string_utils.cpp` + `search_delimiters.cpp` — NFKD, lowercase,
  combining-mark strip, special-char fixes, the exact delimiter set, and the
  possessive merge. It has zero Android imports so STEP 2 copies it verbatim
  into the app; **keep the two copies in sync** (`--check-sync` verifies).
- **Street synonym dropping**: OM's `StreetTokensFilter` semantics (first
  synonym delayed, second flushes both, lone trailing synonym dropped).
- **Locality postings**: per-locality posting lists (synthetic term per doc)
  so queries can be constrained by locality, exactly like OM's per-locality
  lists.
- **Ranking inputs**: per-feature rank (0..255), type (subType), and name-token
  statistics are all stored per doc for STEP 2's ranking.
