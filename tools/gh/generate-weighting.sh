#!/usr/bin/env bash
# Regenerates the Janino-compiled custom weighting helper source for the app.
# Why: ART cannot load Janino-generated JVM bytecode at runtime, so the helper
# is generated as Java source on the desktop and shipped as a normal app class.
#
# The script first verifies that the canonical motorcycle model is in sync
# (JAR built-in == tools/gh/motorcycle.json == GenerateWeighting.motorcycleProfile())
# and aborts on any drift — see tools/gh/GenerateWeighting.java and
# tools/gh/motorcycle.json.
#
# Usage: ./tools/gh/generate-weighting.sh [graph-cache-dir] [out-dir]
# Defaults: data/graph-cache, build/generated/weighting (repo-relative, gitignored).
# JDK: uses $JAVA_HOME if set (e.g. JAVA_HOME=/opt/homebrew/opt/openjdk@17),
#      otherwise a `java` on PATH, otherwise the Homebrew JDK 17 keg.
set -euo pipefail
cd "$(dirname "$0")/../.."   # always run from the repo root

GRAPH="${1:-data/graph-cache}"
OUT="${2:-build/generated/weighting}"
JAR="tools/gh/graphhopper-web-11.0.jar"
CLASSES="build/generated/weighting-classes"

if [ -n "${JAVA_HOME:-}" ]; then
  JAVA_BIN="${JAVA_HOME}/bin"
elif command -v java >/dev/null 2>&1; then
  JAVA_BIN="$(dirname "$(command -v java)")"
else
  JAVA_BIN="/opt/homebrew/opt/openjdk@17/bin"
fi
[ -x "$JAVA_BIN/java" ] || { echo "no usable JDK: set JAVA_HOME to a JDK 17 installation (e.g. JAVA_HOME=/opt/homebrew/opt/openjdk@17)"; exit 1; }

[ -f "$JAR" ] || { echo "missing $JAR (download the web jar for the pinned graphhopper version)"; exit 1; }
[ -d "$GRAPH" ] || { echo "missing graph dir: $GRAPH (build it first, see README.md)"; exit 1; }

mkdir -p "$OUT" "$CLASSES"

"$JAVA_BIN/javac" -cp "$JAR" -d "$CLASSES" tools/gh/GenerateWeighting.java

# GenerateWeighting verifies the canonical model sync before compiling the
# weighting, then triggers the Janino dump (source_debugging.dir must exist).
"$JAVA_BIN/java" \
  -cp "$CLASSES:$JAR" \
  -Dorg.codehaus.janino.source_debugging.enable=true \
  -Dorg.codehaus.janino.source_debugging.dir="$OUT" \
  GenerateWeighting "$GRAPH"

echo "Generated: $(ls "$OUT")"
echo "Adapt the generated class into app/src/main/java/com/graphhopper/routing/weighting/custom/ (see AGENTS.md)."
