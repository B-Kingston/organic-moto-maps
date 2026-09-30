#!/usr/bin/env bash
# Manual pre-release check for saved planner state after process death.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO_ROOT"
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
PACKAGE="com.organicmoto.maps"

[[ -x "$ADB" ]] || {
    printf 'ERROR: adb was not found at %s\n' "$ADB" >&2
    exit 1
}

"$ADB" wait-for-device
"$ADB" shell am kill "$PACKAGE"
"$ADB" shell am start -n "$PACKAGE/.MainActivity"
sleep 5
"$ADB" shell uiautomator dump /sdcard/curvemaps-window.xml >/dev/null
XML="$("$ADB" shell cat /sdcard/curvemaps-window.xml | tr -d '\r')"

TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT
printf '%s\n' "$XML" > "$TMP"
python3 - "$TMP" <<'PY'
import sys
from pathlib import Path
import xml.etree.ElementTree as ET

root = ET.parse(Path(sys.argv[1])).getroot()
labels = {"From": None, "To": None}
for edit in root.iter("node"):
    if edit.attrib.get("class") != "android.widget.EditText":
        continue
    descendants = list(edit.iter("node"))
    for label in labels:
        if any(node.attrib.get("content-desc") == label for node in descendants):
            labels[label] = edit.attrib.get("text", "")
for label, value in labels.items():
    print(f"{label}: {value}")
levels = [
    node.attrib.get("content-desc", "")
    for node in root.iter("node")
    if node.attrib.get("content-desc", "").startswith("Ride complexity level ")
]
print("Knob: " + (levels[0] if levels else "not found"))
PY

printf 'Manual process-death check complete. Review the restored values above.\n'
