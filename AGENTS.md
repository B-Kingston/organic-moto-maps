# AGENTS.md

Offline-first motorcycle routing app. Kotlin + Compose + MapLibre (map) + GraphHopper (routing).

## Build

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug
```

- `JAVA_HOME` is mandatory: the keg-only JDK 17 at `/opt/homebrew/opt/openjdk@17` is the only compatible JDK. Plain `gradle` is Gradle 9.7 on JDK 26 and breaks AGP 8.7.3; always use `./gradlew` (wrapper = Gradle 8.13).
- Output: `app/build/outputs/apk/debug/app-debug.apk` (~120 MB, graph embedded).
- `local.properties` (gitignored) points `sdk.dir` at `~/Library/Android/sdk`.
- `minSdk = 26` is a hard floor: GraphHopper's jar fails dexing below it. Do not lower.
- No tests, no lint, no CI. A successful build is the only verification.

## Graph data pipeline (do not route around it)

The routing graph is prebuilt on the desktop and shipped in APK assets. The app NEVER imports a PBF on-device; `GraphHopperRouter` copies `assets/graph-cache` to `{filesDir}/gh-cache` on first use and loads via MMAP.

To rebuild the graph for a new/different extract:

1. Download an OSM `.osm.pbf` extract (e.g. Geofabrik).
2. Set `datareader.file` in `tools/gh/config.yml`.
3. Import: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 /opt/homebrew/opt/openjdk@17/bin/java -Xmx12g -jar tools/gh/graphhopper-web-11.0.jar import tools/gh/config.yml` (a state extract takes ~1 min).
4. `cp -R data/graph-cache app/src/main/assets/graph-cache` then rebuild.

Hard constraints learned the expensive way:

- The `graphhopper-core` version in `gradle/libs.versions.toml` MUST equal the import jar version in `tools/gh/` (stored graph format is version-bound). Bump both together; verify the new version exists on Maven Central first (10.3 does not exist; 11.0 is current).
- Profile name `motorcycle` must match in all three places: `config.yml` profile name, `Profile("motorcycle")` in `GraphHopperRouter`, and `GHRequest.setProfile("motorcycle")`.
- `config.yml` must keep `custom_model_files: [motorcycle.json]`, `import.osm.ignored_highways`, and the `graph.encoded_values` line — import fails without each.
- Sanity-check a rebuilt graph: `... java -jar tools/gh/graphhopper-web-11.0.jar server tools/gh/config.yml` binds port **8080** (not 8989), then `curl "http://localhost:8080/route?point=-27.4679,153.0281&point=-16.9203,145.7710&profile=motorcycle"`.
- Generated artifacts are gitignored: `data/`, `app/src/main/assets/graph-cache/`, `tools/gh/*.jar`, `*.osm.pbf`. Fresh clones have no graph until the pipeline is re-run.

## Android emulator / device

- SDK binaries are not on PATH; use full paths: `~/Library/Android/sdk/platform-tools/adb`, `~/Library/Android/sdk/emulator/emulator`.
- Orca's `orca emulator` CLI drives Android too (not iOS-only): backend is adb/emulator/avdmanager. Verified working: `devices` (lists AVDs + adb devices, booted vs shutdown), `attach <serial>`, `tap x y` (normalized 0..1), `type "text"` (US ASCII), `gesture '[{...}]'` (swipe), `button home|back|...`, `rotate landscape_left|portrait`, `ax --json` (uiautomator tree, bounds in physical px — this AVD is 1280x2856), `install ./app-debug.apk` (121 MB APK ok), `launch com.organicmoto.maps` (default activity), `logcat --lines N`, `kill`.
- Gotchas: `exec` is broken in current Orca (`Cannot use 'in' operator to search for 'screenshotStatus'...` parse bug) — use raw adb instead. `attach` is required once per worktree before unqualified commands. A shutdown AVD lists but must be booted first (`emulator @<avd>`). Tap accuracy can be off when the IME resizes the layout mid-session; re-dump `ax` for fresh bounds.
- Debugging logs: `orca emulator logcat --lines 500 --json` for a one-shot parsed dump (fields: timestamp/level/tag/message) — pipe through jq/grep for tags; use raw `adb logcat` for live streaming. Note: the app code itself has no Log/Logger statements; slf4j-android is a dependency only so GraphHopper's internal logs (tags like `com.graphhopper.*` logger names) reach logcat.
- Emulator workflow: `orca emulator install app/build/outputs/apk/debug/app-debug.apk && orca emulator launch com.organicmoto.maps`, then drive with `tap`/`type`/`ax`.

## Code facts that differ from defaults

- From/To fields parse `lat,lon` strings only (`PointParser`); geocoding is not implemented.
- The only runtime network call is the MapLibre demo style URL in `RouteScreen.kt`; routing is fully offline.
- MapLibre 13.5.0: GeoJSON classes are `org.maplibre.geojson.*` (NOT `com.mapbox.*`); the `android-sdk-geojson` artifact arrives transitively. Route drawing uses `MapLibreMap.getStyle {}` (style-race-safe), not `MapView.style`.
- slf4j-android must stay 1.7.36 — the 2.x line has no Android binding.
- Router init is lazy (first Route press): graph load happens on `Dispatchers.IO`, never the UI thread.
