#!/usr/bin/env bash
# Builds the offline vector basemap (PMTiles, OpenMapTiles schema) from the
# QLD OSM extract and installs it into the app assets, mirroring the
# tools/gh graph pipeline. Run from the repo root:
#
#   tools/tiles/build-tiles.sh [path/to/extract.osm.pbf]
#
# Planetiler's standalone jar builds the OpenMapTiles profile by default.
# The version here is pinned: the built-in profile's layer set evolves with
# planetiler releases, and app/src/main/assets/style.json references the
# resulting layer names — bump both together deliberately.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO_ROOT"

PLANETILER_VERSION="0.10.2"
PLANETILER_URL="https://github.com/onthegomap/planetiler/releases/download/v${PLANETILER_VERSION}/planetiler.jar"
PLANETILER_JAR="tools/tiles/planetiler.jar"

# Tile generator v0.10.2 is built for JDK 21. The Android/Gradle build still uses JDK 17.
TILE_JAVA_HOME="${TILE_JAVA_HOME:-}"
if [ -z "$TILE_JAVA_HOME" ]; then
    TILE_JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
fi
if [ -z "$TILE_JAVA_HOME" ]; then
    TILE_JAVA_HOME="${JAVA_HOME:-}"
fi
JAVA="$TILE_JAVA_HOME/bin/java"
PBF="${1:-data/queensland.osm.pbf}"
OUT="data/tiles/queensland.pmtiles"
ASSET_DIR="app/src/main/assets/tiles"

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

mkdir -p data/tiles "$ASSET_DIR"

# --download fetches the OpenMapTiles profile's external sources
# (Natural Earth, water polygons) into data/sources/ on first run.
echo "Generating vector tiles from $PBF (zoom 0-14)..."
"$JAVA" -Xmx12g -jar "$PLANETILER_JAR" \
    --osm-path="$PBF" \
    --output="$OUT" \
    --maxzoom=14 \
    --download \
    --http-timeout=PT120S \
    --http-retries=10 \
    --force

echo "Installing $OUT -> $ASSET_DIR/queensland.pmtiles"
cp "$OUT" "$ASSET_DIR/queensland.pmtiles"
HASH_OUT="data/tiles/queensland.pmtiles.sha256"
shasum -a 256 "$OUT" | cut -d ' ' -f1 > "$HASH_OUT"
cp "$HASH_OUT" "$ASSET_DIR/queensland.pmtiles.sha256"
echo "Done: $(du -h "$ASSET_DIR/queensland.pmtiles" | cut -f1)"
