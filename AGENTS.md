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

1. Download an OSM `.osm.pbf` extract (e.g. Geofabrik) and place it at `data/queensland.osm.pbf` (or set `datareader.file` in `tools/gh/config.yml` to your local file — relative paths in `config.yml` resolve against the repo root, so run the import from the repo root).
2. Import (needs JDK 17; a state extract takes ~1 min):
   ```
   JAVA_HOME=/opt/homebrew/opt/openjdk@17
   $JAVA_HOME/bin/java -Xmx12g -jar tools/gh/graphhopper-web-11.0.jar import tools/gh/config.yml
   ```
3. `cp -R data/graph-cache app/src/main/assets/graph-cache` then rebuild.

Hard constraints learned the expensive way:

- The `graphhopper-core` version in `gradle/libs.versions.toml` MUST equal the import jar version in `tools/gh/` (stored graph format is version-bound). Bump both together; verify the new version exists on Maven Central first (10.3 does not exist; 11.0 is current).
- Profile name `motorcycle` must match in all three places: `config.yml` profile name, `Profile("motorcycle")` in `GraphHopperRouter`, and `GHRequest.setProfile("motorcycle")`.
- `config.yml` must keep `custom_model_files: [motorcycle.json]`, `import.osm.ignored_highways`, and the `graph.encoded_values` line — import fails without each. The app-side `GraphHopperConfig` in `GraphHopperRouter.kt` must also carry `import.osm.ignored_highways` with the same value — GraphHopper 11's `init()` throws `Missing 'import.osm.ignored_highways'` at load time if the key is absent.
- The app-side profile construction in `GraphHopperRouter.motorcycleProfile()` must mirror the import-side YAML profile exactly, including PMap hint insertion order (`custom_model_files` then `custom_model`, with the `Profile(String)` default `custom_model` hint removed first) and the JAR's built-in motorcycle custom model statements — GraphHopper's load-time profile-version check compares the stored `profiles` property against a string rendered from the current profile that hashes the whole hints PMap. Verified fact: the current graph stores `profiles=motorcycle|198752012`, which is reproduced only when `custom_model_files` is a `List.of("motorcycle.json")` (a bare String or any other file name yields a different hash and the load fails).
- `custom_model_files: [motorcycle.json]` resolves to the JAR's built-in classpath resource `/com/graphhopper/custom_models/motorcycle.json` (`GraphHopper.resolveCustomModelFiles`); it does NOT read `tools/gh/motorcycle.json` from disk, and a filesystem file with that name would abort the import with `Custom model file name 'motorcycle.json' is already used for built-in profiles` (this is why `custom_models.directory` must NOT point at a folder containing `motorcycle.json`). `tools/gh/motorcycle.json` is the checked-in canonical reference copy of the built-in model — it must stay identical to it, and `./tools/gh/generate-weighting.sh` verifies that before dumping the helper.
- Sanity-check a rebuilt graph: `... java -jar tools/gh/graphhopper-web-11.0.jar server tools/gh/config.yml` binds port **8080** (not 8989), then `curl "http://localhost:8080/route?point=-27.4679,153.0281&point=-16.9203,145.7710&profile=motorcycle"`.

### Custom-model weighting on Android (Janino does not work on ART)

GraphHopper compiles `weighting=custom` expressions with Janino at load time; Janino emits JVM bytecode, which ART cannot load — routing fails with `Could not create weighting for profile: 'motorcycle'` / `Cannot compile expression: can't load this type of class file`. The fix ships the compiled weighting as plain Java source:

- `app/src/main/java/com/graphhopper/routing/weighting/custom/MotorcycleWeightingHelper.java` is the Janino-generated `CustomWeightingHelper` subclass (generated on the desktop JVM, verbatim except the class name).
- `app/src/main/java/com/organicmoto/maps/routing/MotorcycleWeightingFactory.kt` implements `WeightingFactory`, mirroring `DefaultWeightingFactory`'s `custom` branch but building `CustomWeighting.Parameters` from the pre-compiled helper. `GraphHopperRouter` installs it via an anonymous `GraphHopper` subclass overriding `createWeightingFactory()`.
- Regenerate after ANY change to the custom model statements: `./tools/gh/generate-weighting.sh`, then adapt the dumped `JaninoCustomWeightingHelperSubclassN.java` into the app file above (rename class only, keep package `com.graphhopper.routing.weighting.custom`).
- The motorcycle custom model has ONE canonical source — the JAR built-in model, with `tools/gh/motorcycle.json` as its checked-in reference copy — and the following must all change together, followed by a graph re-import (the profile hash changes): `tools/gh/motorcycle.json`, `GraphHopperRouter.motorcycleProfile()`, `tools/gh/GenerateWeighting.java`'s `motorcycleProfile()`, the generated `MotorcycleWeightingHelper.java`, and the max-speed math in `MotorcycleWeightingFactory.createParameters()`. `generate-weighting.sh` verifies the first three agree (built-in vs `tools/gh/motorcycle.json` vs the `GenerateWeighting` profile) before dumping the helper, so a drifted reference copy or profile mirror fails the script instead of shipping a broken weighting.
- `MotorcycleWeightingFactory` is restricted to the `motorcycle` profile: non-custom weightings are delegated to `DefaultWeightingFactory`, and custom weightings on any other profile are rejected with a clear error (the pre-compiled helper implements exactly one model).

- Generated artifacts are gitignored: `data/`, `app/src/main/assets/graph-cache/`, `tools/gh/*.jar`, `*.osm.pbf`. Fresh clones have no graph until the pipeline is re-run.

## Geocoding data pipeline (offline place search)

The offline search index (`geocoder.dat`) is prebuilt on the desktop and shipped in APK assets, mirroring the graph pipeline. The Android-side reader + search engine live in `app/src/main/java/com/organicmoto/maps/geocoding/` (`GeocoderIndex` mmap reader, `SearchEngine`); the binary format is fixed and documented in `tools/geocoder/README.md` — do not change it without updating that doc and the reader.

To (re)build the index for the current extract:

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :geocoder-tool:run --args="data/queensland.osm.pbf app/src/main/assets/geocoder"
```

(replace `data/queensland.osm.pbf` with your extract path — the tool reads the PBF from the repo root working directory)

- `:geocoder-tool` is a plain JVM module (no Android). It reads the PBF via osmosis (`osmosis-pbf`, the `crosby.binary.osmosis.OsmosisReader` Sink API — note: `osmosis-osm-binary` has no 0.49.x, the reader moved to `osmosis-pbf`), classifies POI/STREET/LOCALITY features, normalizes names, and writes `app/src/main/assets/geocoder/geocoder.dat` (a little-endian inverted index: header → doc offsets → doc records → sorted term dict → varint-delta posting lists).
- Verify: `... run --args="--inspect <pbf> app/src/main/assets/geocoder/geocoder.dat brisbane ann cafe fuel qld"` prints per-term posting counts and sample doc names.
- `geocoder.dat` is a generated artifact (`app/src/main/assets/geocoder/` is gitignored).
- Category keywords live in `geocoder-tool/src/main/resources/poi_keywords.tsv` (embedded fallback copy in `PoiKeywords.kt`; keep both in sync).
- `geocoder-tool` and the app share the exact same normalization/tokenization code: `geocoder-tool/src/main/kotlin/com/organicmoto/geocoder/tool/Text.kt` (pure Kotlin, no Android imports). STEP 2 copies it verbatim into the app sources — **keep the two copies in sync**; any change must be applied to both.
- JDK note: `:geocoder-tool` deliberately has no `jvmToolchain(17)` — Gradle's Homebrew auto-detection resolves `JAVA_HOME=/opt/homebrew/opt/openjdk@17` to the keg root (missing `lib/jmods`), which breaks Kotlin ("No class roots"). It uses `jvmTarget = 17` on the daemon JVM, like `:app`.

## Android emulator / device

- SDK binaries are not on PATH; use full paths: `~/Library/Android/sdk/platform-tools/adb`, `~/Library/Android/sdk/emulator/emulator`.
- Orca's `orca emulator` CLI drives Android too (not iOS-only): backend is adb/emulator/avdmanager. Verified working: `devices` (lists AVDs + adb devices, booted vs shutdown), `attach <serial>`, `tap x y` (normalized 0..1), `type "text"` (US ASCII), `gesture '[{...}]'` (swipe), `button home|back|...`, `rotate landscape_left|portrait`, `ax --json` (uiautomator tree, bounds in physical px — this AVD is 1280x2856), `install ./app-debug.apk` (121 MB APK ok), `launch com.organicmoto.maps` (default activity), `logcat --lines N`, `kill`.
- Gotchas: `exec` is broken in current Orca (`Cannot use 'in' operator to search for 'screenshotStatus'...` parse bug) — use raw adb instead. `attach` is required once per worktree before unqualified commands. A shutdown AVD lists but must be booted first (`emulator @<avd>`). Tap accuracy can be off when the IME resizes the layout mid-session; re-dump `ax` for fresh bounds.
- Emulator touch/`tap` attempts are flaky and often fail for reasons unrelated to app code (IME layout shifts, uiautomator bounds drift, adb races). Do not loop retrying taps beyond a handful of attempts, and do not infer a bug in our app code just because a tap "did nothing". After ~3 failed attempts, stop, re-dump `ax` for fresh bounds, or switch to `type`/`launch`/logcat to verify behavior instead of retrying the tap.
- Debugging logs: `orca emulator logcat --lines 500 --json` for a one-shot parsed dump (fields: timestamp/level/tag/message) — pipe through jq/grep for tags; use raw `adb logcat` for live streaming. App logs use `android.util.Log` under `OrganicMoto.*` tags: `OrganicMoto.RouteScreen` (route submit/success/errors, point resolution), `OrganicMoto.SearchField` (per-keystroke text, debounced searches, picked results), `OrganicMoto.GeocodeCtrl` (first index load), `OrganicMoto.SearchEngine` (per-query timing), `OrganicMoto.GeocoderIndex` (asset copy/mmap), `OrganicMoto.Router` (GraphHopper init + route request), `OrganicMoto.PointParser` (lat,lon fallback). Levels: `I` = user actions/successes, `D` = lifecycle/timing, `V` = per-keystroke, `W` = degradations (geocoder unavailable → fallback), `E` = failures. One-shot filter: `adb logcat OrganicMoto:V com.graphhopper:W *:S`.
- Emulator workflow: `orca emulator install app/build/outputs/apk/debug/app-debug.apk && orca emulator launch com.organicmoto.maps`, then drive with `tap`/`type`/`ax`.

## Code facts that differ from defaults

- From/To are geocode-search fields (`GeocodeSearchField` in `SearchField.kt`: 300 ms debounce, results dropdown) backed by the offline index (`assets/geocoder/geocoder.dat` → `{filesDir}/geocoder/`, mmap via `GeocoderIndex`, `GeocodeSearchController` lazy-loads the index on the first search). Coordinates still work: type `lat,lon` (or `lat lon`) and either pick the geocoder's COORDINATE result or hit Route and let the `PointParser` fallback resolve it. `SearchText` normalization/tokenization is shared with `:geocoder-tool` — the `Text.kt` copies in `geocoder-tool/src/main/kotlin/com/organicmoto/geocoder/tool/Text.kt` and `app/src/main/java/com/organicmoto/maps/geocoding/Text.kt` must stay in sync. The ranking model constants are ported from Organic Maps' `ranking_info.cpp`.
- The only runtime network call is the MapLibre demo style URL in `RouteScreen.kt`; routing is fully offline.
- MapLibre 13.5.0: GeoJSON classes are `org.maplibre.geojson.*` (NOT `com.mapbox.*`); the `android-sdk-geojson` artifact arrives transitively. Route drawing uses `MapLibreMap.getStyle {}` (style-race-safe), not `MapView.style`.
- slf4j-android must stay 1.7.36 — the 2.x line has no Android binding.
- Router init is lazy (first Route press): graph load happens on `Dispatchers.IO`, never the UI thread.
