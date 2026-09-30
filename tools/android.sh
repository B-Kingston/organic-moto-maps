#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP_ID="com.organicmoto.maps"
ACTIVITY="$APP_ID/.MainActivity"
AVD="${ANDROID_AVD:-Pixel_10_Pro}"

if [ -z "${JAVA_HOME:-}" ] && [ -x /opt/homebrew/opt/openjdk@17/bin/java ]; then
    JAVA_HOME=/opt/homebrew/opt/openjdk@17
    export JAVA_HOME
fi

SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [ -z "$SDK_DIR" ] && [ -f "$ROOT/local.properties" ]; then
    SDK_DIR="$(sed -n 's/^sdk\.dir=//p' "$ROOT/local.properties" | sed -n '1p')"
fi
if [ -z "$SDK_DIR" ]; then
    SDK_DIR="$HOME/Library/Android/sdk"
fi

ADB="${ADB:-$SDK_DIR/platform-tools/adb}"
EMULATOR="${EMULATOR:-$SDK_DIR/emulator/emulator}"
DEVICE_SERIAL=""

fail() {
    printf 'Error: %s\n' "$*" >&2
    exit 1
}

require_adb() {
    [ -x "$ADB" ] || fail "adb not found at $ADB. Set ANDROID_SDK_ROOT or ANDROID_HOME."
    "$ADB" start-server >/dev/null
}

online_devices() {
    "$ADB" devices | awk 'NR > 1 && $2 == "device" { print $1 }'
}

select_device() {
    require_adb

    if [ -n "${ANDROID_SERIAL:-}" ]; then
        DEVICE_SERIAL="$ANDROID_SERIAL"
        [ "$("$ADB" -s "$DEVICE_SERIAL" get-state 2>/dev/null || true)" = device ] \
            || fail "Android device '$DEVICE_SERIAL' is not online. Check adb devices."
        return
    fi

    devices="$(online_devices)"
    count="$(printf '%s\n' "$devices" | awk 'NF { count++ } END { print count + 0 }')"
    if [ "$count" -eq 1 ]; then
        DEVICE_SERIAL="$(printf '%s\n' "$devices" | sed -n '1p')"
        return
    fi
    if [ "$count" -gt 1 ]; then
        fail "More than one device is online. Set ANDROID_SERIAL to choose one."
    fi

    [ -x "$EMULATOR" ] || fail "No Android device is online and emulator was not found at $EMULATOR."
    avds="$("$EMULATOR" -list-avds)"
    if ! printf '%s\n' "$avds" | awk -v wanted="$AVD" '$0 == wanted { found = 1 } END { exit !found }'; then
        fail "AVD '$AVD' was not found. Set ANDROID_AVD or create this AVD once."
    fi

    printf 'Starting emulator %s...\n' "$AVD"
    nohup "$EMULATOR" -avd "$AVD" -no-snapshot-load >/dev/null 2>&1 &

    attempt=0
    while [ "$attempt" -lt 90 ]; do
        devices="$(online_devices)"
        count="$(printf '%s\n' "$devices" | awk 'NF { count++ } END { print count + 0 }')"
        if [ "$count" -eq 1 ]; then
            DEVICE_SERIAL="$(printf '%s\n' "$devices" | sed -n '1p')"
            booted="$("$ADB" -s "$DEVICE_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
            [ "$booted" = 1 ] && return
        elif [ "$count" -gt 1 ]; then
            fail "More than one device is online. Set ANDROID_SERIAL to choose one."
        fi
        attempt=$((attempt + 1))
        sleep 2
    done
    fail "The emulator did not finish booting within 180 seconds. Check emulator output."
}

build_app() {
    cd "$ROOT"
    ./gradlew :app:assembleDebug
}

install_and_launch() {
    apk="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
    map_archive="$ROOT/data/tiles/queensland.pmtiles"
    [ -f "$apk" ] || fail "Debug APK not found at $apk. Run the build command first."
    [ -s "$map_archive" ] || fail "Queensland PMTiles archive not found at $map_archive. Run tools/tiles/build-tiles.sh first."
    "$ADB" -s "$DEVICE_SERIAL" install -r "$apk"
    # Match the app's final imported-map location so emulator launches are
    # ready to render the offline basemap without a manual document-picker step.
    "$ADB" -s "$DEVICE_SERIAL" shell "run-as $APP_ID mkdir -p files/tiles"
    "$ADB" -s "$DEVICE_SERIAL" shell "run-as $APP_ID sh -c 'cat > files/tiles/basemap.pmtiles'" < "$map_archive"
    "$ADB" -s "$DEVICE_SERIAL" shell am force-stop "$APP_ID"
    "$ADB" -s "$DEVICE_SERIAL" shell am start -n "$ACTIVITY"
}

stream_logs() {
    pid="$("$ADB" -s "$DEVICE_SERIAL" shell pidof -s "$APP_ID" 2>/dev/null | tr -d '\r')"
    [ -n "$pid" ] || fail "The app is not running. Run tools/android.sh run first."
    printf 'Streaming logs for %s on %s. Press Ctrl-C to stop.\n' "$APP_ID" "$DEVICE_SERIAL"
    exec "$ADB" -s "$DEVICE_SERIAL" logcat -v color --pid="$pid"
}

usage() {
    cat <<'USAGE'
Usage: tools/android.sh <command>

Commands:
  build    Build the debug APK.
  run      Build, install, and launch the app.
  debug    Build, launch, and stream the app process logs.
  logs     Stream logs from the running app process.
  stop     Stop the app on the selected device.
  devices  List Android devices visible to adb.

Set ANDROID_SERIAL to choose a device. If no device is online, run and debug
start the Pixel_10_Pro emulator. Set ANDROID_AVD to choose another AVD.
USAGE
}

command="${1:-help}"
case "$command" in
    build)
        build_app
        ;;
    run|debug)
        build_app
        select_device
        install_and_launch
        if [ "$command" = debug ]; then
            stream_logs
        fi
        ;;
    logs)
        select_device
        stream_logs
        ;;
    stop)
        select_device
        "$ADB" -s "$DEVICE_SERIAL" shell am force-stop "$APP_ID"
        ;;
    devices)
        require_adb
        "$ADB" devices -l
        ;;
    help|-h|--help)
        usage
        ;;
    *)
        usage >&2
        fail "Unknown command '$command'."
        ;;
esac
