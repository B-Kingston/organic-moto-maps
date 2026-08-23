#!/usr/bin/env bash
# Fetches the offline glyph (Noto Sans) and sprite (OSM Bright) assets that
# app/src/main/assets/style.json needs to render text labels and POI icons with
# zero runtime network, then installs them into the app assets. Run from the
# repo root:
#
#   tools/style/fetch-style-assets.sh            # fetch what's missing + install
#   tools/style/fetch-style-assets.sh --force    # re-download everything
#   tools/style/fetch-style-assets.sh --verify   # drift-check data/style/ vs manifest
#
# Glyph PBFs: Noto Sans Regular/Bold/Italic, one PBF per 256-glyph range
# (0-255 .. 65280-65535), from the font-glyphs GitHub Pages mirror of the same
# Noto family (googlei18n/noto-fonts, built with fontnik). The historical
# OpenMapTiles glyph host fonts.openmaptiles.org has been serving an HTML
# landing page for every path since 2025 (see openmaptiles/fonts#26), so this
# mirror — identical {fontstack}/{range}.pbf scheme and fontstack names — is
# pinned instead. Noto fonts: SIL OFL 1.1.
#
# Sprites: OSM Bright sprite set (sprite.png/.json, sprite@2x.png/.json) from
# the osm-bright-gl-style gh-pages branch, downloaded from the resolved commit
# SHA for reproducibility. Design: CC BY 4.0 (OpenMapTiles/MapTiler/Mapbox
# derivation) — covered by the existing map-corner "© OpenMapTiles.org ©
# OpenStreetMap contributors" attribution.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO_ROOT"

MODE="download"
if [ "${1:-}" = "--force" ]; then
    MODE="force"
elif [ "${1:-}" = "--verify" ]; then
    MODE="verify"
elif [ $# -gt 0 ]; then
    echo "Usage: $0 [--force|--verify]" >&2
    exit 1
fi

GLYPHS_BASE_URL="https://orangemug.github.io/font-glyphs/glyphs"
FONT_STACKS=("Noto Sans Regular" "Noto Sans Bold" "Noto Sans Italic")
# 256 ranges per stack: 0-255, 256-511, ..., 65280-65535
RANGE_LAST_START=65280

SPRITE_REPO_API="https://api.github.com/repos/openmaptiles/osm-bright-gl-style/commits/gh-pages"
SPRITE_RAW_BASE="https://raw.githubusercontent.com/openmaptiles/osm-bright-gl-style"
SPRITE_FILES=("sprite.png" "sprite.json" "sprite@2x.png" "sprite@2x.json")

STYLE_DIR="data/style"
GLYPHS_DIR="$STYLE_DIR/glyphs"
SPRITES_DIR="$STYLE_DIR/sprites"
MANIFEST="$STYLE_DIR/style-assets.sha256"
ICON_NAMES="$STYLE_DIR/icon-names.txt"

ASSET_GLYPHS="app/src/main/assets/glyphs"
ASSET_SPRITES="app/src/main/assets/sprites"

# Icon keys that style.json references; the sprite set must contain at least
# these (see $ICON_NAMES for the full sorted list).
REQUIRED_ICONS=(
    fuel_11 cafe_11 restaurant_11 fast_food_11 bar_11 beer_11 grocery_11 shop_11
    hospital_11 pharmacy_11 police_11 school_11 bank_11 campsite_11 picnic_site_11
    attraction_11 lodging_11 toilet_11 information_11 park_11
)

# Portable file size in bytes (BSD stat on macOS, GNU stat elsewhere).
file_size() {
    if stat -f%z "$1" >/dev/null 2>&1; then
        stat -f%z "$1"
    else
        stat -c%s "$1"
    fi
}

# Remote Content-Length for a URL (empty string if HEAD is unavailable).
remote_size() {
    curl -fsIL --retry 3 "$1" 2>/dev/null \
        | awk 'tolower($1)=="content-length:" { gsub("\r",""); size=$2 } END { print size }'
}

# Every file under data/style/glyphs + data/style/sprites as
# "<sha256>  <repo-root-relative-path>" lines, sorted by path.
compute_manifest() {
    find "$GLYPHS_DIR" "$SPRITES_DIR" -type f -print0 \
        | sort -z \
        | xargs -0 shasum -a 256 \
        | sed "s|$REPO_ROOT/||"
}

if [ "$MODE" = "verify" ]; then
    if [ ! -f "$MANIFEST" ]; then
        echo "ERROR: no manifest at $MANIFEST — run the script without --verify first." >&2
        exit 1
    fi
    if [ ! -d "$GLYPHS_DIR" ] || [ ! -d "$SPRITES_DIR" ]; then
        echo "ERROR: $GLYPHS_DIR or $SPRITES_DIR missing — nothing to verify." >&2
        exit 1
    fi
    echo "== Verifying $GLYPHS_DIR + $SPRITES_DIR against $MANIFEST =="
    current="$(mktemp)"
    trap 'rm -f "$current"' EXIT
    compute_manifest > "$current"
    if diff -u "$MANIFEST" "$current"; then
        echo "OK: style assets match the manifest ($(wc -l < "$MANIFEST" | tr -d ' ') files)."
        exit 0
    else
        echo "DRIFT DETECTED: files under data/style/ differ from $MANIFEST" >&2
        exit 1
    fi
fi

echo "== Downloading glyph PBFs (Noto Sans, 256 ranges x 3 fontstacks) =="
mkdir -p "$GLYPHS_DIR"
for stack in "${FONT_STACKS[@]}"; do
    encoded="${stack// /%20}"
    dir="$GLYPHS_DIR/$stack"
    mkdir -p "$dir"
    downloaded=0
    skipped=0
    warned=0
    for (( start = 0; start <= RANGE_LAST_START; start += 256 )); do
        end=$((start + 255))
        range="${start}-${end}"
        dest="$dir/$range.pbf"
        url="$GLYPHS_BASE_URL/$encoded/$range.pbf"
        if [ "$MODE" != "force" ] && [ -f "$dest" ] \
           && [ "$(remote_size "$url")" = "$(file_size "$dest")" ]; then
            skipped=$((skipped + 1))
            continue
        fi
        if curl -fL --retry 3 -o "$dest" "$url" 2>/dev/null; then
            downloaded=$((downloaded + 1))
        else
            rm -f "$dest"
            if [ "$range" = "0-255" ] || [ "$range" = "256-511" ]; then
                echo "ERROR: required glyph range $range is missing for '$stack' ($url)" >&2
                exit 1
            fi
            warned=$((warned + 1))
            echo "  warning: $stack/$range.pbf not available (404) — skipping" >&2
        fi
    done
    echo "  $stack: $downloaded downloaded, $skipped unchanged, $warned missing (high range, skipped)"
done

echo "== Resolving pinned sprite commit (osm-bright-gl-style gh-pages) =="
SPRITE_SHA=""
if SPRITE_SHA="$(curl -fsSL --retry 3 "$SPRITE_REPO_API" 2>/dev/null \
    | python3 -c "import json,sys; print(json.load(sys.stdin)['sha'])" 2>/dev/null)"; then
    echo "  Pinned sprite commit: $SPRITE_SHA"
    SPRITE_BASE="$SPRITE_RAW_BASE/$SPRITE_SHA"
else
    SPRITE_SHA=""
    echo "  warning: could not resolve gh-pages commit SHA; falling back to the branch URL" >&2
    SPRITE_BASE="$SPRITE_RAW_BASE/gh-pages"
fi

echo "== Downloading sprites (OSM Bright) =="
mkdir -p "$SPRITES_DIR"
for f in "${SPRITE_FILES[@]}"; do
    dest="$SPRITES_DIR/$f"
    if [ "$MODE" != "force" ] && [ -f "$dest" ] \
       && [ "$(remote_size "$SPRITE_BASE/$f")" = "$(file_size "$dest")" ]; then
        echo "  skip (unchanged): $f"
        continue
    fi
    echo "  downloading $f"
    curl -fL --retry 3 -o "$dest" "$SPRITE_BASE/$f"
done

echo "== Generating icon-names.txt from sprite.json =="
python3 - "$SPRITES_DIR/sprite.json" "$ICON_NAMES" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as fh:
    sprite = json.load(fh)
with open(sys.argv[2], "w", encoding="utf-8") as out:
    for name in sorted(sprite.keys()):
        out.write(name + "\n")
PY
echo "  $(wc -l < "$ICON_NAMES" | tr -d ' ') icon names -> $ICON_NAMES"

missing_icon=0
for icon in "${REQUIRED_ICONS[@]}"; do
    if ! grep -qxF "$icon" "$ICON_NAMES"; then
        echo "ERROR: sprite set is missing required icon '$icon'" >&2
        missing_icon=1
    fi
done
if [ "$missing_icon" -ne 0 ]; then
    echo "ERROR: sprite.json lacks icon(s) referenced by style.json; aborting install." >&2
    exit 1
fi
echo "  all ${#REQUIRED_ICONS[@]} required icons present"

echo "== Writing manifest =="
compute_manifest > "$MANIFEST"
echo "  $(wc -l < "$MANIFEST" | tr -d ' ') entries -> $MANIFEST"

echo "== Installing into app assets =="
rm -rf "$ASSET_GLYPHS"
cp -R "$GLYPHS_DIR" "$ASSET_GLYPHS"
echo "  glyphs -> $ASSET_GLYPHS ($(find "$ASSET_GLYPHS" -type f | wc -l | tr -d ' ') files)"
rm -rf "$ASSET_SPRITES"
mkdir -p "$ASSET_SPRITES"
cp "$SPRITES_DIR"/sprite.png "$SPRITES_DIR"/sprite.json \
   "$SPRITES_DIR"/sprite@2x.png "$SPRITES_DIR"/sprite@2x.json "$ASSET_SPRITES/"
echo "  sprites -> app/src/main/assets/sprites/"

echo
echo "== Summary =="
for stack in "${FONT_STACKS[@]}"; do
    count="$(find "$GLYPHS_DIR/$stack" -type f | wc -l | tr -d ' ')"
    echo "  $stack: $count glyph PBFs"
done
echo "  Total glyph files: $(find "$GLYPHS_DIR" -type f | wc -l | tr -d ' ')"
echo "  Glyphs size: $(du -sh "$GLYPHS_DIR" | cut -f1)"
echo "  Sprites size: $(du -sh "$SPRITES_DIR" | cut -f1)"
if [ -n "$SPRITE_SHA" ]; then
    echo "  Pinned sprite commit: $SPRITE_SHA"
else
    echo "  Pinned sprite commit: <unpinned — gh-pages branch URL used>"
fi
all_present=1
for f in "${SPRITE_FILES[@]}"; do
    if [ -f "$SPRITES_DIR/$f" ]; then
        echo "  OK $SPRITES_DIR/$f ($(du -h "$SPRITES_DIR/$f" | cut -f1))"
    else
        echo "  MISSING $SPRITES_DIR/$f" >&2
        all_present=0
    fi
done
if [ -f "$MANIFEST" ]; then
    echo "  OK $MANIFEST"
else
    echo "  MISSING $MANIFEST" >&2
    all_present=0
fi
if [ "$all_present" -ne 1 ]; then
    echo "ERROR: not all sprite files / manifest are in place." >&2
    exit 1
fi
echo "Done."
