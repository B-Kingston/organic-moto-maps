#!/usr/bin/env bash
set -euo pipefail

usage() {
    printf 'Usage: tools/publish-apk.sh INSTANCE APK [SSH_TARGET]\n'
    printf 'Publish an existing APK, update the selected fdka endpoint, and show its URL/status.\n'
}

if [[ "${1:-}" == --help || "${1:-}" == -h ]]; then
    usage
    exit 0
fi
if [[ $# -lt 2 || $# -gt 3 || -z "$1" || "$1" == -* ]]; then
    usage >&2
    exit 2
fi
instance="$1"
apk="$2"
if [[ ! -s "$apk" ]]; then
    printf 'Error: APK is missing or empty: %s\n' "$apk" >&2
    exit 1
fi
# Resolve the path before fdka changes its working directory.
apk="$(cd "$(dirname "$apk")" && pwd)/$(basename "$apk")"
if command -v fdka >/dev/null 2>&1; then
    publisher=fdka
elif command -v f-droideka >/dev/null 2>&1; then
    publisher=f-droideka
else
    printf 'Error: Install fdka (f-droideka) first.\n' >&2
    exit 1
fi
target=()
if [[ $# -eq 3 ]]; then
    if [[ -z "$3" || "$3" == -* ]]; then
        printf 'Error: SSH_TARGET must be a non-empty target, not an option.\n' >&2
        exit 2
    fi
    target=("$3")
fi
"$publisher" -n "$instance" publish "$apk"
"$publisher" -n "$instance" up ${target[@]+"${target[@]}"}
"$publisher" -n "$instance" urls ${target[@]+"${target[@]}"}
"$publisher" -n "$instance" status
