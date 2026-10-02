#!/usr/bin/env bash
# Run the cheap, emulator-free verification tier.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO_ROOT"

JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export JAVA_HOME

tools/test/preflight.sh
GH_JAR="tools/gh/graphhopper-web-11.0.jar"
[[ -f "$GH_JAR" ]] || { printf 'ERROR: missing %s (download the pinned GraphHopper web jar)\n' "$GH_JAR" >&2; exit 1; }
"$JAVA_HOME/bin/java" -cp "$GH_JAR" tools/gh/MotoGraphImport.java --self-test
JAVA_HOME="$JAVA_HOME" ./gradlew :geocoder-tool:test :app:testDebugUnitTest :app:assembleDebug

# The map server is a separate Go module; run its suites when Go is available
# (the full -race run lives in .github/workflows/ci.yml).
if command -v go >/dev/null 2>&1; then
    (cd map-server && go vet ./... && go test ./...)
else
    printf 'NOTE: Go not found; skipping map-server tests.\n'
fi

printf 'Light CI tier passed.\n'
