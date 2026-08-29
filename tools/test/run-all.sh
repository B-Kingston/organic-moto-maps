#!/usr/bin/env bash
# Run the complete manual verification sequence, including the light tier.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO_ROOT"

JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export JAVA_HOME
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
EMULATOR="${EMULATOR:-$HOME/Library/Android/sdk/emulator/emulator}"
PACKAGE="com.organicmoto.maps"
REPORT_DIR="build/test-report"
# Cross-run perf baseline lives outside REPORT_DIR: the script wipes
# build/test-report at startup, which would erase the history every run.
PERF_HISTORY="build/perf.history.jsonl"

fail() {
    printf 'ERROR: %s\n' "$1" >&2
    exit 1
}

[[ -x "$ADB" ]] || fail "adb was not found at $ADB"
[[ -x "$EMULATOR" ]] || fail "emulator was not found at $EMULATOR"

tools/test/ci.sh

if ! "$ADB" get-state >/dev/null 2>&1; then
    AVD="$($EMULATOR -list-avds | sed -n '1p')"
    [[ -n "$AVD" ]] || fail "No booted device and no AVD is configured. Boot an AVD, or run: $EMULATOR @<avd>"
    printf 'No booted device. Starting %s with: %s @%s\n' "$AVD" "$EMULATOR" "$AVD"
    "$EMULATOR" "@$AVD" >/tmp/organic-moto-emulator.log 2>&1 &
fi

"$ADB" wait-for-device
booted=0
for attempt in $(seq 1 180); do
    boot_state="$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
    if [[ "$boot_state" = "1" ]]; then
        booted=1
        break
    fi
    sleep 2
done
[[ "$booted" = "1" ]] || fail "The Android emulator did not finish booting"

APK="app/build/outputs/apk/debug/app-debug.apk"
[[ -s "$APK" ]] || fail "Missing $APK after the light tier"
"$ADB" install -r "$APK"
"$ADB" shell am start -n "$PACKAGE/.MainActivity"
printf 'Pre-warming graph, tiles, and geocoder copies for 180 seconds.\n'
sleep 180
"$ADB" shell am force-stop "$PACKAGE"
rm -rf "$REPORT_DIR"
mkdir -p "$REPORT_DIR/fuzz" "$REPORT_DIR/perf"

airplane=0
restore_airplane() {
    if [[ "$airplane" = "1" ]]; then
        "$ADB" shell cmd connectivity airplane-mode disable >/dev/null 2>&1 || true
        airplane=0
    fi
}
trap restore_airplane EXIT

"$ADB" shell cmd connectivity airplane-mode enable
airplane=1
JAVA_HOME="$JAVA_HOME" ./gradlew :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.organicmoto.maps.map.OfflineMapSmokeTest,com.organicmoto.maps.routing.RouteCorpusTest \
    2>&1 | tee "$REPORT_DIR/offline.log"
echo
"$ADB" shell cmd connectivity airplane-mode disable
airplane=0
"$ADB" logcat -c || true

copy_reports_live() {
    local source="$1"
    local destination="$2"
    local listing
    if ! listing="$("$ADB" shell run-as "$PACKAGE" find "files/$source" -type f 2>/dev/null | tr -d '\r')"; then
        return 0
    fi
    while IFS= read -r path; do
        [[ -n "$path" ]] || continue
        local relative
        if [[ "$source" = *.json ]]; then
            relative="$(basename "$path")"
        else
            relative="${path#files/$source/}"
        fi
        local target="$destination/$relative"
        local temporary="$target.tmp"
        mkdir -p "$(dirname "$target")"
        if "$ADB" exec-out run-as "$PACKAGE" cat "$path" > "$temporary"; then
            mv "$temporary" "$target"
        else
            rm -f "$temporary"
        fi
    done <<< "$listing"
}

watch_reports() {
    local gradle_pid="$1"
    while kill -0 "$gradle_pid" 2>/dev/null; do
        copy_reports_live fuzz "$REPORT_DIR/fuzz"
        copy_reports_live perf.json "$REPORT_DIR"
        sleep 1
    done
    copy_reports_live fuzz "$REPORT_DIR/fuzz"
    copy_reports_live perf.json "$REPORT_DIR"
}

FULL_PID=
WATCH_PID=
JAVA_HOME="$JAVA_HOME" ./gradlew :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.fuzzSeeds=42,1337 \
    -Pandroid.testInstrumentationRunnerArguments.fuzzSteps=120 \
    2>&1 | tee "$REPORT_DIR/full.log" &
FULL_PID=$!
watch_reports "$FULL_PID" &
WATCH_PID=$!
set +e
wait "$FULL_PID"
FULL_STATUS=$?
set -e
kill "$WATCH_PID" >/dev/null 2>&1 || true
wait "$WATCH_PID" >/dev/null 2>&1 || true
(( FULL_STATUS == 0 )) || exit "$FULL_STATUS"
"$ADB" logcat -d -s OrganicMoto.Fuzz:I '*:S' | tr -d '\r' > "$REPORT_DIR/fuzz.log"



pull_internal_reports() {
    local source="$1"
    local destination="$2"
    local listing
    if ! listing="$("$ADB" shell run-as "$PACKAGE" find "files/$source" -type f 2>/dev/null | tr -d '\r')"; then
        local existing
        if [[ "$source" = *.json ]]; then
            existing="$destination/$(basename "$source")"
        else
            existing="$(find "$destination" -type f -print -quit)"
        fi
        if [[ -n "$existing" ]]; then
            return 0
        fi
        printf 'WARN: run-as could not read files/%s. The Android test task may have removed the app package.\n' "$source" >&2
        return 0
    fi
    while IFS= read -r path; do
        [[ -n "$path" ]] || continue
        local relative
        if [[ "$source" = *.json ]]; then
            relative="$(basename "$path")"
        else
            relative="${path#files/$source/}"
        fi
        local target="$destination/$relative"
        local temporary="$target.tmp"
        mkdir -p "$(dirname "$target")"
        if "$ADB" exec-out run-as "$PACKAGE" cat "$path" > "$temporary"; then
            mv "$temporary" "$target"
        else
            rm -f "$temporary"
        fi
    done <<< "$listing"
}

pull_internal_reports fuzz "$REPORT_DIR/fuzz"
pull_internal_reports perf.json "$REPORT_DIR"

# Cross-run performance drift gate. The app package is wiped after every
# connected task, so on-device history cannot survive a run; the durable
# baseline therefore lives here, host-side. Each full trigger appends its
# measured metrics to perf.history.jsonl and compares against the most
# recent previous entry. Delete that file to re-baseline after a deliberate
# slowdown (e.g. graph rebuild).
if [[ -s "$REPORT_DIR/perf.json" ]]; then
    DRIFT_FACTORS='{"coldRouteMs":2.5,"warmRouteMs":1.75,"geocoderLoadMs":2.0,"queryMs":2.0}'
    python3 - "$REPORT_DIR/perf.json" "$PERF_HISTORY" "$DRIFT_FACTORS" <<'PY'
import json
import sys
from pathlib import Path

perf_path = Path(sys.argv[1])
history_path = Path(sys.argv[2])
factors = json.loads(sys.argv[3])

current = json.loads(perf_path.read_text())
history = [
    json.loads(line)
    for line in history_path.read_text().splitlines()
    if line.strip()
] if history_path.is_file() else []

for key, factor in factors.items():
    if not history or key not in history[-1] or key not in current:
        continue
    before = float(history[-1][key])
    now = float(current[key])
    budget = max(before * factor, before + 250.0)
    if now > budget:
        sys.exit(
            f"PERF DRIFT: {key} regressed from {before:.0f}ms to {now:.0f}ms "
            f"(budget {budget:.0f}ms). If intended (e.g. graph rebuild), "
            f"delete {history_path} to re-baseline."
        )

entry = {
    "timestampMs": __import__("time").time_ns() // 1_000_000,
    **{key: current[key] for key in factors if key in current},
}
with history_path.open("a") as handle:
    handle.write(json.dumps(entry) + "\n")
print(f"OK: perf drift check against {len(history)} prior entr(y/ies); baseline appended.")
PY
fi

if command -v jq >/dev/null 2>&1; then
    while IFS= read -r report; do
        [[ -n "$report" ]] || continue
        jq empty "$report"
    done < <(find "$REPORT_DIR/fuzz" -type f -name failure.json -print)
fi

python3 - "$REPORT_DIR/summary.txt" <<'PY'
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET
root = Path.cwd()
summary_path = Path(sys.argv[1])
xml_paths = sorted(set(root.glob("app/build/test-results/**/*.xml")) | set(root.glob("app/build/outputs/androidTest-results/**/*.xml")))
tests = failures = skipped = 0
for path in xml_paths:
    try:
        tree = ET.parse(path)
    except (ET.ParseError, OSError):
        continue
    for suite in tree.iter("testsuite"):
        tests += int(suite.attrib.get("tests", 0))
        failures += int(suite.attrib.get("failures", 0)) + int(suite.attrib.get("errors", 0))
        skipped += int(suite.attrib.get("skipped", 0))

state_count = 0
for path in (root / "build/test-report/fuzz").glob("**/failure.json"):
    try:
        import json
        data = json.loads(path.read_text())
        state_count += len(set(data.get("stateSequence", [])))
    except (OSError, ValueError, TypeError):
        pass
log_state_count = 0
for path in (root / "build/test-report").glob("*.log"):
    try:
        log_state_count += sum(
            int(match.group(1))
            for match in re.finditer(r"FUZZ_REPORT .*? states=(\d+)", path.read_text(errors="ignore"))
        )
    except OSError:
        pass
state_count = max(state_count, log_state_count)

lines = [
    "Organic Moto Maps test summary",
    f"JUnit XML files: {len(xml_paths)}",
    f"tests: {tests}",
    f"failures: {failures}",
    f"skipped: {skipped}",
    f"fuzz states discovered: {state_count}",
]
summary_path.write_text("\n".join(lines) + "\n")
print("\n".join(lines))
PY

printf 'Full test tier passed. Reports are under %s.\n' "$REPORT_DIR"
