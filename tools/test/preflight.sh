#!/usr/bin/env bash
# Verify the generated assets and toolchain before a test run.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO_ROOT"

JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export JAVA_HOME

GRAPH_IMPORT='JAVA_HOME=/opt/homebrew/opt/openjdk@17 $JAVA_HOME/bin/java -Xmx12g -cp tools/gh/graphhopper-web-11.0.jar tools/gh/MotoGraphImport.java import tools/gh/config.yml; cp -R data/graph-cache app/src/main/assets/graph-cache'
TILE_BUILD='tools/tiles/build-tiles.sh'
GEOCODER_BUILD='JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :geocoder-tool:run --args="data/queensland.osm.pbf app/src/main/assets/geocoder"'
STYLE_BUILD='tools/style/fetch-style-assets.sh'

fail() {
    printf 'ERROR: %s\n' "$1" >&2
    printf 'Regenerate or repair with: %s\n' "$2" >&2
    exit 1
}

if [[ ! -x "$JAVA_HOME/bin/java" ]]; then
    fail "JDK 17 was not found at $JAVA_HOME" 'Set JAVA_HOME to a JDK 17 installation, for example JAVA_HOME=/opt/homebrew/opt/openjdk@17'
fi
JAVA_VERSION="$($JAVA_HOME/bin/java -version 2>&1 | awk -F'"' '/version/ { print $2; exit }')"
case "$JAVA_VERSION" in
    17.*) ;;
    *) fail "Java 17 is required; found ${JAVA_VERSION:-unknown}" 'Set JAVA_HOME=/opt/homebrew/opt/openjdk@17' ;;
esac
printf 'OK: JDK %s (%s)\n' "$JAVA_VERSION" "$JAVA_HOME"

GRAPH_DIR="app/src/main/assets/graph-cache"
for file in nodes edges properties; do
    [[ -s "$GRAPH_DIR/$file" ]] || fail "Missing or empty $GRAPH_DIR/$file" "$GRAPH_IMPORT"
done
GRAPH_PROPERTIES="$(strings "$GRAPH_DIR/properties")"
case "$GRAPH_PROPERTIES" in
    *'profiles=motorcycle|198752012'*) ;;
    *) fail "Graph profile marker is not motorcycle|198752012" "$GRAPH_IMPORT" ;;
esac
# Lane guidance lives in edge KV storage, written only by tools/gh/MotoGraphImport.java.
grep -q 'moto_lanes' "$GRAPH_DIR/edgekv_keys" 2>/dev/null ||
    fail "Graph has no moto_lanes lane guidance (imported with the stock GraphHopper command?)" "$GRAPH_IMPORT"
printf 'OK: GraphHopper cache files, stored profile marker, and lane guidance\n'

TILE="data/tiles/queensland.pmtiles"
TILE_HASH_FILE="data/tiles/queensland.pmtiles.sha256"
[[ -s "$TILE" && -s "$TILE_HASH_FILE" ]] || fail "Missing PMTiles archive or SHA-256 sidecar" "$TILE_BUILD"
EXPECTED_HASH="$(tr -d '[:space:]' < "$TILE_HASH_FILE")"
ACTUAL_HASH="$(shasum -a 256 "$TILE" | cut -d ' ' -f1)"
[[ "$EXPECTED_HASH" = "$ACTUAL_HASH" ]] || fail "PMTiles SHA-256 sidecar does not match the archive" "$TILE_BUILD"
printf 'OK: PMTiles archive and SHA-256 sidecar\n'

GEOCODER="app/src/main/assets/geocoder/geocoder.dat"
[[ -s "$GEOCODER" ]] || fail "Missing or empty $GEOCODER" "$GEOCODER_BUILD"
if ! JAVA_HOME="$JAVA_HOME" ./gradlew :geocoder-tool:run --args="--validate $GEOCODER"; then
    fail "Geocoder index validation failed" "$GEOCODER_BUILD"
fi
if ! JAVA_HOME="$JAVA_HOME" ./gradlew :geocoder-tool:run --args="--check-sync"; then
    fail "Geocoder keyword sources are out of sync" "$GEOCODER_BUILD"
fi
printf 'OK: Geocoder index and keyword sources\n'

[[ -d app/src/main/assets/glyphs ]] || fail "Missing app glyph assets" "$STYLE_BUILD"
[[ -d app/src/main/assets/sprites ]] || fail "Missing app sprite assets" "$STYLE_BUILD"
GLYPH_FILE="$(find app/src/main/assets/glyphs -type f -name '*.pbf' -print -quit)"
[[ -n "$GLYPH_FILE" ]] || fail "No glyph PBF files were found" "$STYLE_BUILD"
SPRITE_FILE="$(find app/src/main/assets/sprites -type f -name 'sprite.json' -print -quit)"
[[ -n "$SPRITE_FILE" ]] || fail "No sprite JSON file was found" "$STYLE_BUILD"
if ! tools/style/fetch-style-assets.sh --verify; then
    fail "Glyph or sprite data drifted from data/style/style-assets.sha256" "$STYLE_BUILD --verify"
fi
printf 'OK: Glyph and sprite assets\n'

printf 'Preflight passed.\n'
