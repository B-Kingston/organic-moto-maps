# AGENTS.md

Offline-first motorcycle routing app. Kotlin + Compose + MapLibre (map) + GraphHopper (routing).

## Build

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug
```

- `JAVA_HOME` is mandatory. Use `/opt/homebrew/opt/openjdk@17`. Plain `gradle` uses Gradle 9.7 on JDK 26 and breaks this build. Always use `./gradlew` (wrapper = Gradle 9.5.0, AGP = 9.3.1, Kotlin = 2.2.10).
- Output: `app/build/outputs/apk/debug/app-debug.apk`; the separately distributed PMTiles basemap is not included in its size.
- `local.properties` (gitignored) points `sdk.dir` at `~/Library/Android/sdk`.
- `minSdk = 26` is a hard floor: GraphHopper's jar fails dexing below it. Do not lower.
- `tools/test/ci.sh` is the light CI tier. It runs `tools/test/preflight.sh`, every JVM suite, and `assembleDebug`. It does not need an emulator.
- The light tier runs automatically in GitHub Actions (`.github/workflows/ci.yml`): it regenerates the gitignored assets from the pinned pipeline (OSM extract download with PBF magic verification → tiles → graph import → geocoder index → style fetch), then invokes `tools/test/ci.sh`. Bump `CACHE_SEED` in the workflow to force a data rebuild. Locally, `git config core.hooksPath githooks` enables a pre-push hook that runs the same tier.
- `tools/test/run-all.sh` is the manual full trigger. It runs the light tier, pre-warms the graph, tiles, and geocoder, proves routing and map rendering in airplane mode, then runs every instrumented suite with seeded fuzzing. It needs a booted AVD.
- Direct JVM command: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:testDebugUnitTest :geocoder-tool:test`.
- Direct Android command: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:connectedDebugAndroidTest`. Run one class with `-Pandroid.testInstrumentationRunnerArguments.class=...`. Run fuzz with `-Pandroid.testInstrumentationRunnerArguments.fuzzSeeds=42,1337 -Pandroid.testInstrumentationRunnerArguments.fuzzSteps=120`.
- Core Android suites: `RouteCorpusTest`, `GraphCopyTest`, `OfflineMapSmokeTest`, `CarouselMapConsistencyTest`, `SqlSavedRouteStoreTest`, and `GeocoderOnDeviceTest`.
- Compose UI suites: `PlannerPanelTest`, `ComplexityDialTest`, `SaveBubbleTest`, `SavedRoutesSheetTest`, `SearchDebounceTest`, `LifecycleRecreationTest`, `AccessibilityTest`, `FontScaleTest`, `SavedRouteFlowTest`, and `RoadShareApplyTest` (road-share Apply → persistence + re-route). Dial gestures go through the shared `KnobRobot` (`ui/KnobRobot.kt`) — never synthesize knob arcs inline; retune gesture physics there only.
- Fuzz suites: `FuzzCampaignTest`, `FuzzReplayTest`, `FuzzMinimizerTest`, `MemoryStressTest`, and `PerfSmokeTest`. The full trigger uses two seeds and 120 steps. `PerfSmokeTest` keeps absolute ceilings AND runs each warm measurement twice — the repeat must stay within 3× of the first (intra-run self-consistency; a package wipe after every connected task makes on-device cross-run history impossible). The durable cross-run comparison lives in `tools/test/run-all.sh`: it appends `perf.json` metrics to `build/perf.history.jsonl` and fails when any metric exceeds its factor over the previous entry (cold 2.5x, warm and curve-weighted routing 1.75x, geocoder load/query 2.0x) — delete that history file after a deliberate slowdown (e.g. graph rebuild) to re-baseline. `FuzzCampaignTest` escalates a soft warning to a hard failure when it fires on more than `fuzzWarnFraction` (default 0.25) of a seed's steps.
- Route corpus gold baselines: `RouteCorpusEntry.goldDistanceMeters`/`goldDurationMillis` pin measured fastest-route values and alert at ±15% (`RouteCorpusTest.fastestRoutesMatchCommittedGoldBaselines`). Recalibrate via the `@Ignore calibrationProbe` and fill the golds from its GOLD_PROBE lines whenever OSM data or the weighting model changes deliberately.
- Airplane-mode proof: `adb shell cmd connectivity airplane-mode enable`; run `OfflineMapSmokeTest` and `RouteCorpusTest`; then run `adb shell cmd connectivity airplane-mode disable`. `run-all.sh` restores airplane mode on failure.
- `tools/test/process-death.sh` is a manual pre-release check. It kills and relaunches the app, then prints the restored fields and dial level.
- Instrumented test methods must use plain camelCase names. Backtick-spaced names fail D8 at minSdk 26. No lint task exists.
- `RouteSearchCoordinator`, `GeocodeController`, and the keys in `UiSemantics.kt` are deliberate test seams. Keep their state transitions and observable values stable when the related feature changes.

The test suite is permanent repository infrastructure. It is not a one-off deliverable. Every new feature, UI element, routing, geocoder, storage, or generated-data pipeline change must add or update tests at the same rigor. Pure logic needs JVM invariant or property tests in the matching package. Android-bound behavior needs instrumented tests. Route-visible changes need a corpus entry and recalibration. New composables and controls need content descriptions, 48 dp targets, planner coverage, accessibility coverage, and fuzz-oracle coverage. Weighting or custom-model changes need MotorcycleProfileTest and ComplexityWeightingTest updates together. Storage schema changes need both store-contract suites and repository truthfulness tests.

## Route storage (saved rides)

The app persists user-saved routes in device SQLite (`saved_routes.db`, schema v1: `saved_routes` + `saved_route_comments` with an `ON DELETE CASCADE` foreign key). Long-pressing a coloured carousel card opens a save bubble (`SaveRouteBubble.kt`); the bookmark icon stores the exact path (encoded polyline, precision 1e5), geocoded From/To names, distance, duration, and the live routing controls (complexity, road share, block-unpaved). The storage menu is the bookmark button left of the ride-complexity knob; it opens `SavedRoutesSheet` (mini-map banner drawn by `RouteMiniMap.kt` from the decoded polyline — no tiles, no network), per-route comments, and delete-with-confirm.

- Storage decision: raw `SQLiteOpenHelper` behind the narrow `SavedRouteStore` interface instead of Room — zero new toolchain deps (no KSP/kapt) for two tables, and every implementation must satisfy one documented contract (`SavedRouteStore.kt` KDoc), which both the JVM in-memory fake and the instrumented SQL suite assert. Revisit Room if the schema grows.
- Key files: `app/src/main/java/com/organicmoto/maps/storage/` (models, `PolylineCodec`, `SqlSavedRouteStore`, `SavedRouteRepository`, `RouteSimilarity`) and root-package UI files (`SaveRouteBubble.kt`, `SavedRoutesSheet.kt`, `RouteMiniMap.kt`, `SavedRouteIcons.kt` — icons are hand-drawn Canvas like the rest of the app).
- Loading a saved route restores the editable planning state (fields, points, complexity dial, road share, block-unpaved) and re-routes; `submitRoute(preferredGeometry=…)` then re-selects the fresh candidate closest to the stored shape via `RouteSimilarity` (mean haversine to strided candidate vertices). It does NOT rewrite the persisted road-share/block-unpaved preferences — those still change only through Route settings Apply.
- Geofabrik now serves region extracts three levels deep (e.g. `/australia-oceania/australia/queensland-latest.osm.pbf`); shallower paths return their HTML index with HTTP 200, so verify the downloaded bytes are a PBF before importing.

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

#### Ride complexity dial (`moto_complexity`)

`GraphHopperRouter.route(from, to, complexity)` takes an unbounded per-request value >= 0 via the `moto_complexity` hint (shared constant in `ComplexityWeighting.kt`):

- `complexity = 0.0` — pure fastest routing over the motorcycle model's effective speeds, without scenic priorities or distance influence. This is the UI default.
- `complexity = 1.0` — one full step of motorcycle-model and geometry-derived curve preference; values above 1 keep strengthening it without an upper bound.
- Every user route sets `ch.disable=true`: curve preference is computed from each edge's OSM geometry at request time and is not baked into CH. `ComplexityWeighting` computes `w_fastest + complexity * (max(0, w_custom - w_fastest) + w_curvePenalty)`. Consecutive point triples define circumcircle radii. Each segment uses the tighter adjacent radius and a length-weighted score for the 30 m, 60 m, 100 m, and 175 m radius bands. The weighting uses the larger of this radius exposure and the existing accumulated-heading exposure. This keeps broad sustained bends competitive while the radius score identifies tight corners. The curve penalty is largest for straight geometry and every added term stays non-negative.
- `FastestWeighting` is app-owned rather than GraphHopper's `SpeedWeighting`: model speeds are km/h, so edge seconds are `distanceMetres * 3.6 / speedKmh`. Fastest and Custom share `MotorcycleWeightingHelper.getSpeed`, so the dial changes route preference, not ETA semantics.
- Access is a hard directional constraint at every dial position: `car_access == false` returns infinity from `ComplexityWeighting`, including reverse-direction reads via `getReverse(car_access)`.
- `calcMinWeightPerDistance()` uses Fastest's lower bound; all complexity costs are non-negative, so it remains admissible. `calcEdgeMillis()` always uses the shared physical travel-time mapping.
- This feature needs no graph re-import or profile/helper change. Empty hints during graph load return pure CustomWeighting, preserving the stored profile and CH preparation; `GraphHopperRouter` always supplies the dial hint for actual routes.
- The UI is an endless click-detented rotary knob in `RoutePlanPanel`: 8 detent clicks per revolution, one click = one level (+1.0 `moto_complexity`); winding past level 8 keeps counting (9, 10, ...) with the indicator wrapping visually around the ring; counter-clockwise rotation stops hard at Fastest (0). Every crossed click fires a haptic tick (`CLOCK_TICK`, firmer `VIRTUAL_KEY` at the zero stop), and release snaps to the nearest level with a short spring settle. The knob exposes `contentDescription = "Ride complexity level N"` for accessibility/UI tests. A small cog at the knob's top-right opens the Road alternatives dialog; its second dial sets a target maximum road share from 10–90% in 5% steps (default 70%), persists in `route_preferences`, and re-routes on Apply. `GraphHopperRouter` stores the percentage with each cached detent (changing it regenerates the current detent while retaining lower routes for comparison) and requests `alternative_route` candidates with progressively wider exploration. Positive detents softly penalize edge IDs already used by lower detents, with stronger penalties on later retries and stricter shared-road targets; the penalty is non-negative and never hard-blocks a road, so connectivity is preserved. Normal alternatives are capped relative to the detent's own first candidate: detent 1 at 1.50x travel time and 1.65x distance, loosening gradually to hard caps of 2.00x/2.25x. Pairs of real directed shared-edge anchors reject pronounced local divergence/rejoin bubbles over 1.5 km extra and 2.25x the corresponding primary section, and a candidate must contain one continuous distinct stretch of at least `clamp(5% of route distance, 750 m, 3 km)` before it counts as meaningfully new. Do NOT judge alternatives against the cached Fastest route: a curvy ride is legitimately slow. The configured overlap is a strong target, not a failure condition: if no normally bounded candidate meets it, the router prefers a meaningful target-meeting candidate even outside the normal detour budget, then the most distinct sensible candidate, and finally the detent's honest primary. Routing must never reject an otherwise routable journey merely because the overlap target is impossible; it must also never promote a tiny side-street-only variation just to claim novelty.

- Generated artifacts are gitignored: `data/`, `app/src/main/assets/graph-cache/`, `tools/gh/*.jar`, `*.osm.pbf`. Fresh clones have no graph until the pipeline is re-run.

## Map tile data pipeline (offline basemap)

The basemap is prebuilt on the desktop from the same Queensland OSM extract and distributed separately from the APK. On the phone, **Load map file** opens Android's document picker; the app validates the selected PMTiles v3 archive, atomically copies it to `{filesDir}/tiles/basemap.pmtiles`, and reuses it across launches. MapLibre Native reads it with `pmtiles://file://` byte-range reads. A failed replacement never destroys the installed map.

To rebuild the basemap:

1. Download `data/queensland.osm.pbf` from Geofabrik.
2. Run:
   ```
   tools/tiles/build-tiles.sh
   ```
3. Distribute `data/tiles/queensland.pmtiles` as a separate download. Copy or download it to the phone, then select it with **Load map file**. The `.sha256` sidecar is for distribution verification and is not selected in the app.

- `tools/tiles/build-tiles.sh` pins Planetiler `0.10.2`. The standalone jar uses the OpenMapTiles profile and writes PMTiles with `--output`.
- Planetiler `0.10.2` needs JDK 21. The script finds JDK 21 with `java_home -v 21`; set `TILE_JAVA_HOME` to override it. The Android build still needs JDK 17.
- `--download` fetches the Natural Earth and water-polygon sources into `data/sources/` on the desktop. The phone does not download map data.
- `app/src/main/assets/style.json` contains the local style. It has no remote style, glyph, or sprite URL. Its only runtime-replaced token is `{tiles_path}` (placeholder lives in `app/src/main/assets/style.json`, replacement in RouteScreen's `loadOfflineStyle`); glyphs and sprites use `asset://` URLs baked into the style (see the glyphs/sprites section).
- The APK must not contain the generated PMTiles archive. Importing uses Android's Storage Access Framework, so no broad storage permission is needed.
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
- Hard constraints: fontstack directory names under `app/src/main/assets/glyphs/` must match the `text-font` values in style.json exactly (`Noto Sans Regular`, `Noto Sans Bold`, `Noto Sans Italic` — literal spaces; the AssetManager file source percent-decodes `%20`, and raw spaces work too); every icon name referenced in style.json must exist in sprite.json (see `data/style/icon-names.txt`; the script fails the install if any of the required keys are missing); `0-255` and `256-511` for every fontstack are required and a missing one fails the script, while a 404 on a higher range is tolerated with a warning. The icon-name ⊆ sprite.json invariant is additionally asserted on the JVM by `StyleAndSpritesIntegrityTest.referencedIconNamesAllExistInTheSpriteSheet`, so editing style.json or the sprite sheet cannot silently regress POI icons.
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
