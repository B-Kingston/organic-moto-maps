#!/usr/bin/env bash
# Run the cheap, emulator-free verification tier.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO_ROOT"

JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export JAVA_HOME

tools/test/preflight.sh
JAVA_HOME="$JAVA_HOME" ./gradlew :geocoder-tool:test :app:testDebugUnitTest :app:assembleDebug
printf 'Light CI tier passed.\n'
