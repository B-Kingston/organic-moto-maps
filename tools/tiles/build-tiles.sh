#!/usr/bin/env bash
# Builds the separately distributed offline vector basemap (PMTiles,
# OpenMapTiles schema) from an OSM extract. Run from the repo root:
#
#   tools/tiles/build-tiles.sh [path/to/extract.osm.pbf]
#
# Planetiler's standalone jar builds the OpenMapTiles profile by default.
# The version here is pinned: the built-in profile's layer set evolves with
# planetiler releases, and app/src/main/assets/style.json references the
# resulting layer names — bump both together deliberately.
#
# Environment overrides (all optional; defaults preserve the documented
# pipeline used by CI and preflight):
#   TILE_PBF             OSM extract path (overrides the positional argument)
#   TILE_OUT             output PMTiles path
#   TILE_MAX_HEAP        JVM heap for Planetiler (default 12g)
#   TILE_MAXZOOM         max zoom (default 14)
#   TILE_JAVA_HOME       JDK 21 home (default: java_home -v 21, then JAVA_HOME)
#   TILE_PLANETILER_JAR  pinned Planetiler jar path
#   TILE_SOURCES_DIR     Planetiler download directory (--download_dir)
#   TILE_TMP_DIR         Planetiler temporary directory (--tmpdir)
#   TILE_TILE_WEIGHTS    tile-weights tsv.gz (--tile_weights)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO_ROOT"

PLANETILER_VERSION="0.10.2"
PLANETILER_URL="https://github.com/onthegomap/planetiler/releases/download/v${PLANETILER_VERSION}/planetiler.jar"
# Supply-chain pin, same policy as the GraphHopper import jar: a compromised
# release or MITM on the retry path must not inject code into the machine
# that builds the shipped graph/tiles/geocoder. Bump together with the version.
PLANETILER_JAR_SHA256="f310bd0413e2e4512b27f4046d418664e8e1d3bf31603c2a70e23de06c167e4d"
PLANETILER_JAR="${TILE_PLANETILER_JAR:-tools/tiles/planetiler.jar}"

# Tile generator v0.10.2 is built for JDK 21. The Android/Gradle build still uses JDK 17.
TILE_JAVA_HOME="${TILE_JAVA_HOME:-}"
if [ -z "$TILE_JAVA_HOME" ]; then
    TILE_JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
fi
if [ -z "$TILE_JAVA_HOME" ]; then
    TILE_JAVA_HOME="${JAVA_HOME:-}"
fi
JAVA="$TILE_JAVA_HOME/bin/java"
PBF="${TILE_PBF:-${1:-data/queensland.osm.pbf}}"
OUT="${TILE_OUT:-data/tiles/queensland.pmtiles}"
MAX_HEAP="${TILE_MAX_HEAP:-12g}"
MAXZOOM="${TILE_MAXZOOM:-14}"
SOURCES_DIR="${TILE_SOURCES_DIR:-data/sources}"
TMP_DIR="${TILE_TMP_DIR:-data/tmp}"
TILE_WEIGHTS="${TILE_TILE_WEIGHTS:-data/tile_weights.tsv.gz}"

if [ ! -f "$PBF" ]; then
    echo "OSM extract not found: $PBF (download it, e.g. Geofabrik queensland-latest.osm.pbf)" >&2
    exit 1
fi
if [ ! -x "$JAVA" ]; then
    echo "JDK not found at $TILE_JAVA_HOME (planetiler needs JDK 21+)" >&2
    exit 1
fi
JAVA_MAJOR="$("$JAVA" -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | sed -n '1p')"
if [ -z "$JAVA_MAJOR" ] || [ "$JAVA_MAJOR" -lt 21 ]; then
    echo "Planetiler $PLANETILER_VERSION needs JDK 21; found $TILE_JAVA_HOME" >&2
    exit 1
fi

if [ ! -f "$PLANETILER_JAR" ]; then
    echo "Downloading planetiler $PLANETILER_VERSION..."
    curl -fL --retry 3 -o "$PLANETILER_JAR" "$PLANETILER_URL"
fi

# Portable SHA-256 (Linux sha256sum, macOS shasum). No `-c` file format so the
# same code path works on both.
hash_file() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | cut -d ' ' -f1
    else
        shasum -a 256 "$1" | cut -d ' ' -f1
    fi
}
ACTUAL_JAR_HASH="$(hash_file "$PLANETILER_JAR")"
if [ "$ACTUAL_JAR_HASH" != "$PLANETILER_JAR_SHA256" ]; then
    echo "Planetiler jar checksum mismatch: delete $PLANETILER_JAR and re-run" >&2
    exit 1
fi

mkdir -p "$(dirname "$OUT")" "$SOURCES_DIR" "$TMP_DIR"

# --download fetches the OpenMapTiles profile's external sources
# (Natural Earth, water polygons) into $SOURCES_DIR on first run.
echo "Generating vector tiles from $PBF (zoom 0-$MAXZOOM)..."
"$JAVA" -Xmx"$MAX_HEAP" -jar "$PLANETILER_JAR" \
    --osm-path="$PBF" \
    --output="$OUT" \
    --maxzoom="$MAXZOOM" \
    --download \
    --download_dir="$SOURCES_DIR" \
    --tmpdir="$TMP_DIR" \
    --tile_weights="$TILE_WEIGHTS" \
    --http-timeout=PT120S \
    --http-retries=10 \
    --force

HASH_OUT="$OUT.sha256"
hash_file "$OUT" > "$HASH_OUT"
echo "Done: $OUT ($(du -h "$OUT" | cut -f1))"
echo "Copy or download this .pmtiles file to the phone, then choose Load map file in the app."
