# AGENTS.md

Offline-first motorcycle routing app. Kotlin + Compose + MapLibre (map) + GraphHopper (routing).

## Build

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug
```

- `JAVA_HOME` is mandatory: the keg-only JDK 17 at `/opt/homebrew/opt/openjdk@17` is the only compatible JDK. Plain `gradle` is Gradle 9.7 on JDK 26 and breaks AGP 8.7.3; always use `./gradlew` (wrapper = Gradle 8.13).
- Output: `app/build/outputs/apk/debug/app-debug.apk`; size depends on generated graph and tile assets (~400 MB with the current Queensland archive).
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

#### Route blend slider (`moto_blend`)

`GraphHopperRouter.route(from, to, blend)` takes a per-request blend `t` in [0,1] and ships it to the factory via the `moto_blend` request hint (default 1.0, shared constant in `BlendedWeighting.kt`):

- `t = 1.0` — pure `CustomWeighting`, routed via CH (the weighting baked into the CH graph at import time). No extra hints are sent; behavior is byte-for-byte the pre-blend app.
- `t < 1.0` — the request MUST set `ch.disable=true` (`Parameters.CH.DISABLE`): the CH solver ignores request hints and always uses the import-time weighting, so without it the slider silently does nothing. The flexible path lets the factory build a `BlendedWeighting` over `CustomWeighting` + `FastestWeighting`.
- `t = 0.0` — pure fastest routing over the motorcycle model's effective speeds, without the model's scenic priorities or distance influence. The CustomWeighting branch is skipped entirely for edge weights (avoids `0 * Infinity = NaN` on model-blocked edges).
- `FastestWeighting` is deliberately app-owned rather than GraphHopper's `SpeedWeighting`: `car_average_speed` and the model helper return km/h, so edge travel seconds are `distanceMetres * 3.6 / speedKmh`. `SpeedWeighting` uses `distance / speed` directly and produced ETAs 3.6× too short. Fastest and Custom share `MotorcycleWeightingHelper.getSpeed`, so route style changes road preference, not the physical meaning of ETA.
- Access is a hard directional constraint at every slider position: `car_access == false` → `Double.POSITIVE_INFINITY` from the blending weighting itself, because the flexible solver applies NO access filter (its directed edge filter is `!inSubnetwork && Double.isFinite(calcEdgeWeight(...))`). Reverse traversal must read `getReverse(car_access)`, not the forward value.
- `calcMinWeightPerDistance()` is the same convex combination of the two components' minima — each is a per-meter lower bound of its own weights, so the blend is an admissible A* heuristic.
- `calcEdgeMillis()` reports travel time using the shared motorcycle-model speed mapping regardless of `t`; both flexible components therefore agree on millis for a given edge.
- The blend lives entirely in the weighting factory — no graph re-import, no profile hash change, no `MotorcycleWeightingHelper`/`tools/` changes. The stored `profiles` hash is untouched because `createWeighting` at load time sees an empty hints PMap and takes the pure-CustomWeighting path.
- The UI is a `Route style` Slider in `RoutePlanPanel` (`RouteScreen.kt`), default Moto (1.0); START routes with the current value; releasing the slider after a successful route re-routes with the last resolved points; a slider re-route cancels any in-flight route job.

- Generated artifacts are gitignored: `data/`, `app/src/main/assets/graph-cache/`, `tools/gh/*.jar`, `*.osm.pbf`. Fresh clones have no graph until the pipeline is re-run.

## Map tile data pipeline (offline basemap)

The basemap is prebuilt on the desktop from the same Queensland OSM extract. The app ships one OpenMapTiles vector tile archive in `app/src/main/assets/tiles/`. The app copies the archive to `{filesDir}/tiles/` on first map load. MapLibre Native reads it with `pmtiles://file://` byte-range reads.

To rebuild the basemap:

1. Download `data/queensland.osm.pbf` from Geofabrik.
2. Run:
   ```
   tools/tiles/build-tiles.sh
   ```
3. Build the APK after the script copies `data/tiles/queensland.pmtiles` and `data/tiles/queensland.pmtiles.sha256` to `app/src/main/assets/tiles/`.

- `tools/tiles/build-tiles.sh` pins Planetiler `0.10.2`. The standalone jar uses the OpenMapTiles profile and writes PMTiles with `--output`.
- Planetiler `0.10.2` needs JDK 21. The script finds JDK 21 with `java_home -v 21`; set `TILE_JAVA_HOME` to override it. The Android build still needs JDK 17.
- `--download` fetches the Natural Earth and water-polygon sources into `data/sources/` on the desktop. The phone does not download map data.
- `app/src/main/assets/style.json` contains the local style. It has no remote style, glyph, or sprite URL. Its only runtime-replaced token is `{tiles_path}` (placeholder lives in `app/src/main/assets/style.json`, replacement in RouteScreen's `loadOfflineStyle`); glyphs and sprites use `asset://` URLs baked into the style (see the glyphs/sprites section).
- The SHA-256 sidecar lets the app replace a cached archive after an APK update without hashing the full archive on every launch.
- The map must show `© OpenMapTiles.org © OpenStreetMap contributors` attribution. OSM data and the OpenMapTiles style require it.
- Generated tile output and the Planetiler jar are gitignored. A fresh clone must run this pipeline before `assembleDebug`.

## Glyphs and sprites (offline labels & icons)

The style renders text labels and POI icons with zero network: Noto glyph PBFs (3 fontstacks) and the OSM Bright sprite set ship in APK assets and are read directly from them at runtime via `asset://` URLs baked into `style.json` — `asset://glyphs/{fontstack}/{range}.pbf` and `asset://sprites/sprite`. No filesDir copy, no manifest marker, and no runtime token replacement for these two; `{tiles_path}` is the only runtime-replaced token left in the style.

To fetch/refresh/verify:

```
tools/style/fetch-style-assets.sh            # fetch what's missing + install into app assets
tools/style/fetch-style-assets.sh --force    # re-download everything
tools/style/fetch-style-assets.sh --verify   # drift-check data/style/ vs the stored manifest
```

- Sources + licenses: glyph PBFs are the Noto Sans family (`Noto Sans Regular`, `Noto Sans Bold`, `Noto Sans Italic`; one PBF per 256-glyph range, `0-255`..`65280-65535`), served from the font-glyphs GitHub Pages mirror (`https://orangemug.github.io/font-glyphs/glyphs/{fontstack}/{range}.pbf`) of the same `googlei18n/noto-fonts` family — the historical OpenMapTiles glyph host `fonts.openmaptiles.org` has been serving an HTML landing page for every path since 2025 (see `openmaptiles/fonts#26`), so the mirror with the identical URL scheme is pinned instead. Noto fonts: SIL OFL 1.1. Sprites: the OSM Bright sprite set (`sprite.png/.json`, `sprite@2x.png/.json`) from the `osm-bright-gl-style` gh-pages branch, downloaded from the resolved commit SHA for reproducibility; design CC BY 4.0 (OpenMapTiles/MapTiler/Mapbox derivation), covered by the existing map-corner "© OpenMapTiles.org © OpenStreetMap contributors" attribution — no new UI burden.
- Glyph content is not version-tagged, so `data/style/style-assets.sha256` (a sorted `sha256  <repo-root-relative-path>` manifest of every file under `data/style/glyphs/` and `data/style/sprites/`) is the drift detector for the pipeline: run `--verify` when updating. It lives only in `data/style/`; nothing is installed into app assets.
- Hard constraints: fontstack directory names under `app/src/main/assets/glyphs/` must match the `text-font` values in style.json exactly (`Noto Sans Regular`, `Noto Sans Bold`, `Noto Sans Italic` — literal spaces; the AssetManager file source percent-decodes `%20`, and raw spaces work too); every icon name referenced in style.json must exist in sprite.json (see `data/style/icon-names.txt`; the script fails the install if any of the required keys are missing); `0-255` and `256-511` for every fontstack are required and a missing one fails the script, while a 404 on a higher range is tolerated with a warning.
- Hard constraint (learned the expensive way): `file://` URLs do NOT work for glyph/sprite requests on maplibre-android — the default local file source returns NotFound even when the file exists on disk; use `asset://` for anything read from APK assets. PMTiles still uses `pmtiles://file://` because that goes through the dedicated PMTiles file source, not the generic local file source.
- The generated dirs `data/style/`, `app/src/main/assets/glyphs/`, and `app/src/main/assets/sprites/` are gitignored — a fresh clone must run the script before `assembleDebug`.

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
- The app has no runtime network call. The style, vector tiles, geocoder, and routing graph are local assets; routing is fully offline.
- MapLibre 13.5.0: GeoJSON classes are `org.maplibre.geojson.*` (NOT `com.mapbox.*`); the `android-sdk-geojson` artifact arrives transitively. Route drawing uses `MapLibreMap.getStyle {}` (style-race-safe), not `MapView.style`.
- slf4j-android must stay 1.7.36 — the 2.x line has no Android binding.
- Router init is lazy (first Route press): graph load happens on `Dispatchers.IO`, never the UI thread.
