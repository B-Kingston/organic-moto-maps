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
- The light tier runs automatically in GitHub Actions (`.github/workflows/ci.yml`): it regenerates the gitignored assets from the pinned pipeline (dated OSM extract download with PBF magic verification → tiles → graph import → geocoder index → style fetch), then invokes `tools/test/ci.sh`. Ordinary pushes restore the generated-data cache when the cache key is unchanged; they do not necessarily download a newer OSM extract.
- **Generated map-data freshness:** whenever the source map data or any pipeline input changes—new/different OSM extract, GraphHopper or Planetiler version, `tools/gh/config.yml`, `tools/gh/motorcycle.json`, `tools/tiles/build-tiles.sh`, geocoder source/format, or an intentional data refresh—update `OSM_EXTRACT_DATE` (when changing the extract) and bump `CACHE_SEED` in `.github/workflows/ci.yml` in the same commit (for example `v1` → `v2`). That cache miss forces CI to download and verify the PBF, rebuild the graph, PMTiles basemap, and geocoder, refetch style assets, and build the APK from the fresh dataset. Do not rely on changing only ignored `data/` files: CI cannot see those changes. A `v*` tag runs the same fresh/cached pipeline and uploads the APK and matching PMTiles archive to the GitHub Release.
- Locally, `git config core.hooksPath githooks` enables a pre-push hook that runs the same tier.
- `tools/test/run-all.sh` is the manual full trigger. It runs the light tier, pre-warms the graph, tiles, and geocoder, proves routing and map rendering in airplane mode, then runs every instrumented suite with seeded fuzzing. It needs a booted AVD.
- Direct JVM command: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:testDebugUnitTest :geocoder-tool:test`.
- Direct Android command: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:connectedDebugAndroidTest`. Run one class with `-Pandroid.testInstrumentationRunnerArguments.class=...` — one Compose-rule class per run, because several in one invocation collide on the single UiAutomation service. Run fuzz with `-Pandroid.testInstrumentationRunnerArguments.fuzzSeeds=42,1337 -Pandroid.testInstrumentationRunnerArguments.fuzzSteps=120`. Replay a specific seed with `-Pandroid.testInstrumentationRunnerArguments.fuzzSeedFile=media-ride.json`; the checked-in seeds are `initial.json` and `media-ride.json`.
- Core Android suites: `RouteCorpusTest`, `GraphCopyTest`, `OfflineMapSmokeTest`, `CarouselMapConsistencyTest`, `SqlSavedRouteStoreTest`, `GeocoderOnDeviceTest`, and `DarkRideStyleTest` (black-and-white ride-map native rendering).
- Compose UI suites: `PlannerPanelTest`, `ComplexityDialTest`, `SaveBubbleTest`, `SavedRoutesSheetTest`, `SearchDebounceTest`, `LifecycleRecreationTest`, `AccessibilityTest`, `FontScaleTest`, `SavedRouteFlowTest`, and `RoadShareApplyTest` (road-share Apply → persistence + re-route). Dial gestures go through the shared `KnobRobot` (`ui/KnobRobot.kt`) — never synthesize knob arcs inline; retune gesture physics there only.
- Fuzz suites: `FuzzCampaignTest`, `FuzzReplayTest`, `FuzzMinimizerTest`, `MemoryStressTest`, and `PerfSmokeTest`. The full trigger uses two seeds and 120 steps. `PerfSmokeTest` keeps absolute ceilings AND runs each warm measurement twice — the repeat must stay within 3× of the first (intra-run self-consistency; a package wipe after every connected task makes on-device cross-run history impossible). The durable cross-run comparison lives in `tools/test/run-all.sh`: it appends `perf.json` metrics to `build/perf.history.jsonl` and fails when any metric exceeds its factor over the previous entry (cold 2.5x, warm and curve-weighted routing 1.75x, geocoder load/query 2.0x) — delete that history file after a deliberate slowdown (e.g. graph rebuild) to re-baseline. `FuzzCampaignTest` escalates a soft warning to a hard failure when it fires on more than `fuzzWarnFraction` (default 0.25) of a seed's steps.
- Fuzz action vocabulary: one source of truth in `fuzz/FuzzActions.kt`. Every `FuzzAction` declares its own `wireName`, `target`, and `args()`, and `fuzz/FuzzActionCodec.kt` is the only place that maps action types to and from JSON. Seeding (`FuzzSeed`), minimisation, and `FailureRecorder` all go through it, so an action cannot be half-wired; `FuzzActionCodecTest` proves the round trip for every action, for the checked-in seeds, and for everything `FuzzRandom` can generate. **Add a new action by adding it to `FuzzActions` (plus its weight in `FuzzWeights`) — never by editing a private encoder.** Every action must stay inside the app: the notification-access button leaves for the platform's listener settings and finishes the activity, which a semantic campaign cannot revive, so that path belongs to `MediaAccessRecoveryOnDeviceTest`.
- Fuzz no-op accounting and coverage gates: `FuzzExecutor.execute` returns a `FuzzOutcome` (`PERFORMED` / `UNAVAILABLE` / `NO_SUCH_TARGET`) instead of silently doing nothing, and `CoverageTracker` only credits a target hit when the step actually reached the UI. Most random steps are no-ops by nature, so without this a campaign's coverage numbers are mostly fiction — the `FUZZ_REPORT` line reports `effective=N/M no-op`. The campaign fails on collapsed coverage: `fuzzMinEffectiveSteps` (default 10), `fuzzMinStates` (default 5), `fuzzMinMediaPanelStates` (default 0, off), and `fuzzRequireTargets` (comma-separated `UiTarget` names that must each be exercised). Raise them when adding actions; a gate nobody tightens catches nothing.
- Fuzz waiting: `fuzz/FuzzWait.kt` holds the gentle semantics polling shared by the campaign and the minimiser. Do not reintroduce `rule.waitUntil` in a fuzz suite — it forces compose-idle sync, which can trigger measure/layout inside a live map draw pass and crash with a framework-level IAE on the Pixel_10_Pro AVD. A poll timeout is still a hard failure.
- Route corpus gold baselines: `RouteCorpusEntry.goldDistanceMeters`/`goldDurationMillis` pin measured fastest-route values and alert at ±15% (`RouteCorpusTest.fastestRoutesMatchCommittedGoldBaselines`). Recalibrate via the `@Ignore calibrationProbe` and fill the golds from its GOLD_PROBE lines whenever OSM data or the weighting model changes deliberately.
- Airplane-mode proof: `adb shell cmd connectivity airplane-mode enable`; run `OfflineMapSmokeTest` and `RouteCorpusTest`; then run `adb shell cmd connectivity airplane-mode disable`. `run-all.sh` restores airplane mode on failure.
- `tools/test/process-death.sh` is a manual pre-release check. It kills and relaunches the app, then prints the restored fields and dial level.
- Instrumented test methods must use plain camelCase names. Backtick-spaced names fail D8 at minSdk 26. No lint task exists.
- `RouteSearchCoordinator`, `GeocodeController`, and the keys in `UiSemantics.kt` are deliberate test seams. Keep their state transitions and observable values stable when the related feature changes.

The test suite is permanent repository infrastructure. It is not a one-off deliverable. Every new feature, UI element, routing, geocoder, storage, or generated-data pipeline change must add or update tests at the same rigor. Pure logic needs JVM invariant or property tests in the matching package. Android-bound behavior needs instrumented tests. Route-visible changes need a corpus entry and recalibration. New composables and controls need content descriptions, 48 dp targets, planner coverage, accessibility coverage, and fuzz-oracle coverage. Weighting or custom-model changes need MotorcycleProfileTest and ComplexityWeightingTest updates together. Storage schema changes need both store-contract suites and repository truthfulness tests.

When a feature can be exercised or judged on the emulator, also add repeatable setup/control and screenshot coverage to `tools/test/visual.py`; keep its CLI and README examples current. Use accessible text/content descriptions instead of hard-coded coordinates when controlling app UI. Cover relevant intermediate states so agents can inspect the behavior as it changes.

## Bounded emulator-test debugging

- Prove test prerequisites before interpreting a failure as a production bug. Map/camera tests must install and validate their fixture before Activity launch, then assert installed-map and loaded-style semantics before checking guidance or gestures. Use `map/OfflineBasemapRule.kt` as the outermost `RuleChain` rule; its Monaco tiles are sufficient for camera behavior, not Queensland rendering/POI assertions. Do not seed files after launch and recreate the Activity merely to compensate for missing setup.
- Never relax production lifecycle, access, or readiness guards to make a broken fixture pass. Do not skip a required test when its fixture is missing.
- After two failures at the same stage, stop rerunning. Report the hypothesis, current-run evidence, and one discriminating next check. Change one diagnostic variable per run; a longer timeout is not a fix without evidence of slow progress.
- After each focused run, report what changed, the exact failure stage (or pass), and whether the failure moved. Delegated workers must provide these checkpoints rather than silently repeating research or runs.
- Tie native/logcat evidence to the current test's start time and app PID (prefer the per-test logcat under `app/build/outputs/androidTest-results/`). Old warnings are not evidence for the current failure.
- Only one instrumentation or visual driver may own an emulator serial at a time, including across agents/threads. Finish or explicitly stop the existing run before starting another; compilation-only checks can run without claiming the emulator.

## Route storage (saved rides)

The app persists user-saved routes in device SQLite (`saved_routes.db`, schema v1: `saved_routes` + `saved_route_comments` with an `ON DELETE CASCADE` foreign key). Long-pressing a coloured carousel card opens a save bubble (`SaveRouteBubble.kt`); the bookmark icon stores the exact path (encoded polyline, precision 1e5), geocoded From/To names, distance, duration, and the live routing controls (complexity, road share, block-unpaved). The storage menu is the bookmark button left of the ride-complexity knob; it opens `SavedRoutesSheet` (mini-map banner drawn by `RouteMiniMap.kt` from the decoded polyline — no tiles, no network), per-route comments, and delete-with-confirm.

- Storage decision: raw `SQLiteOpenHelper` behind the narrow `SavedRouteStore` interface instead of Room — zero new toolchain deps (no KSP/kapt) for two tables, and every implementation must satisfy one documented contract (`SavedRouteStore.kt` KDoc), which both the JVM in-memory fake and the instrumented SQL suite assert. Revisit Room if the schema grows.
- Key files: `app/src/main/java/com/organicmoto/maps/storage/` (models, `PolylineCodec`, `SqlSavedRouteStore`, `SavedRouteRepository`, `RouteSimilarity`) and root-package UI files (`SaveRouteBubble.kt`, `SavedRoutesSheet.kt`, `RouteMiniMap.kt`, `SavedRouteIcons.kt` — icons are hand-drawn Canvas like the rest of the app).
- Loading a saved route restores the editable planning state (fields, points, complexity dial, road share, block-unpaved) and re-routes; `submitRoute(preferredGeometry=…)` then re-selects the fresh candidate closest to the stored shape via `RouteSimilarity` (mean haversine to strided candidate vertices). It does NOT rewrite the persisted road-share/block-unpaved preferences — those still change only through Route settings Apply.
- Geofabrik now serves region extracts three levels deep (e.g. `/australia-oceania/australia/queensland-YYMMDD.osm.pbf`); shallower paths return their HTML index with HTTP 200, so verify the downloaded bytes are a PBF before importing. CI pins `OSM_EXTRACT_DATE` because the `latest` alias currently loops through a cached redirect.

## Graph data pipeline (do not route around it)

The routing graph is prebuilt on the desktop and shipped in APK assets. The app NEVER imports a PBF on-device; `GraphHopperRouter` copies `assets/graph-cache` to `{filesDir}/gh-cache` on first use and loads via MMAP.

To rebuild the graph for a new/different extract:

1. Download an OSM `.osm.pbf` extract (e.g. Geofabrik) and place it at `data/queensland.osm.pbf` (or set `datareader.file` in `tools/gh/config.yml` to your local file — relative paths in `config.yml` resolve against the repo root, so run the import from the repo root).
2. Import (needs JDK 17; a state extract takes ~1 min):
   ```
   JAVA_HOME=/opt/homebrew/opt/openjdk@17
   rm -rf data/graph-cache   # the importer refuses to run over an existing graph
   $JAVA_HOME/bin/java -Xmx12g -cp tools/gh/graphhopper-web-11.0.jar tools/gh/MotoGraphImport.java import tools/gh/config.yml
   ```
   Never use the stock `-jar … import` command: it produces a graph without lane guidance (preflight and `GraphAndGeocoderAssetIntegrityTest` reject it).
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
- The UI is an endless click-detented rotary knob in `RoutePlanPanel`: 8 detent clicks per revolution, one click = one level (+1.0 `moto_complexity`); winding past level 8 keeps counting (9, 10, ...) with the indicator wrapping visually around the ring; counter-clockwise rotation stops hard at Fastest (0). Every crossed click fires a haptic tick (`CLOCK_TICK`, firmer `VIRTUAL_KEY` at the zero stop), and release snaps to the nearest level with a short spring settle. The knob exposes `contentDescription = "Ride complexity level N"` for accessibility/UI tests. A small cog in the map-side action rail opens the Route settings screen; its road-share dial sets a target maximum road share from 10–90% in 5% steps (default 70%), persists in `route_preferences`, and re-routes on Apply. `GraphHopperRouter` stores the percentage with each cached detent (changing it regenerates the current detent while retaining lower routes for comparison) and requests `alternative_route` candidates with progressively wider exploration. Positive detents softly penalize edge IDs already used by lower detents, with stronger penalties on later retries and stricter shared-road targets; the penalty is non-negative and never hard-blocks a road, so connectivity is preserved. Normal alternatives are capped relative to the detent's own first candidate: detent 1 at 1.50x travel time and 1.65x distance, loosening gradually to hard caps of 2.00x/2.25x. Pairs of real directed shared-edge anchors reject pronounced local divergence/rejoin bubbles over 1.5 km extra and 2.25x the corresponding primary section, and a candidate must contain one continuous distinct stretch of at least `clamp(5% of route distance, 750 m, 3 km)` before it counts as meaningfully new. Do NOT judge alternatives against the cached Fastest route: a curvy ride is legitimately slow. The configured overlap is a strong target, not a failure condition: if no normally bounded candidate meets it, the router prefers a meaningful target-meeting candidate even outside the normal detour budget, then the most distinct sensible candidate, and finally the detent's honest primary. Routing must never reject an otherwise routable journey merely because the overlap target is impossible; it must also never promote a tiny side-street-only variation just to claim novelty.

- The map-side action rail (`SettingsMenu.kt`) keeps two 48 dp targets in the upper-right reach zone: the settings cog and the saved-routes bookmark. Tapping the cog expands the rail into a settings card — the pill becomes the card's tab, flush with the card's right edge and merged into the card's top edge by concave fillets (`SettingsCardShape`) — listing Route settings, Load map file, Import GPX route, and Sound settings. Both pill targets stay reachable while the card is open (the fuzz oracles and `SettingsMenuTest` pin this). The card dismisses on system Back (`BackHandler`) and on a tap anywhere outside it: `RouteScreen` overlays a full-screen scrim above both the map and the planner panel, so the planner cannot be edited behind an open card. Every screen the card opens carries the shared `SettingsScreenHeader` back button (`contentDescription = "Back"`, 48 dp) that returns to the planner — `RouteSettingsDialog` and `VoiceGuidanceSettingsDialog` both use it; Load map file and Import GPX route open Android's document picker, which brings its own system back/cancel affordance.

## Lane guidance (recommended lanes in the ride HUD)

When the road being left has two or more lanes in the direction of travel, the
top-left guidance card shows a lane strip under the street name with the lane(s)
to be in for the next maneuver highlighted.

- **Data**: `tools/gh/MotoGraphImport.java` is GraphHopper 11's `import` run through
  JDK 17's single-file source launcher with one extra way-tag parser. It writes a
  per-direction `moto_lanes` value into **edge KV storage** (format in its KDoc:
  `L|R:` driving side from GraphHopper's `Country`, then left-to-right lanes, each
  `?` count-only / empty unmarked / subset of `lsru`). KV storage was chosen
  because edge flags are 57/64 bits: arrows as encoded values would grow every
  edge, while KV costs ~1 MB for the 142k lane-tagged Queensland edges. The
  profile, encoded values and custom model are untouched, so
  `motorcycle|198752012` and the pre-compiled weighting stay valid. It works
  because `OSMReader.addEdge` runs way-tag parsers before writing the way's
  `key_values` map. Lane counts come from `lanes:forward/backward`, `lanes` on
  oneways (incl. implied motorway/roundabout), or an even two-way split;
  `turn:lanes*` arrows win when their count agrees; typos/unknown values are
  never guessed. `--self-test` pins the tag interpretation (run by `ci.sh`).
- **App**: `MotoPathDetailsBuilderFactory` adds `KVStringDetails("moto_lanes")`
  (reads the edge in travel direction); `buildGhRequest` requests it;
  `RouteTrackFactory.segmentLanes` expands it per segment; `LaneGuidance.forTurn`
  reads the segment entering the maneuver (looking back ≤150 m through unknown
  segments, never past the previous maneuver, stopping at a known single lane)
  and `LaneGuidance.recommend` picks lanes: marked lanes carrying the turn arrow;
  for keep/slight, through lanes when none carries it; else an unmarked outer
  lane; marked-but-contradicting data shows nothing. Count-only roads suggest the
  outermost lane on the turn side (U-turns: the oncoming side for the country).
  Continue, roundabouts and arrival show no lanes.
- **UI**: `LaneGuidanceRow` in `NavigationHud.kt` — white only (B&W-safe), not a
  touch target; `contentDescription = "Lane guidance: use … of N[, suggested]"`
  and `RecommendedLanesKey` (`"1,2/3"`, `"0/3 suggested"`).
- **Tests**: JVM `LaneGuidanceTest` (parsing, real Brisbane cases, driving side,
  look-back, factory, randomized invariants), `RouteRequestsTest`,
  `GraphAndGeocoderAssetIntegrityTest` (graph carries `moto_lanes`);
  instrumented `LaneGuidanceHudTest` and
  `RouteCorpusTest.cbdRouteCarriesLaneGuidanceFromTheGraph`; Go
  `TestGraphStepRunsTheLaneAwareImporter`. `tools/test/visual.py lanes --from
  '-27.4698,153.0251' --to '-27.5598,153.0811'` captures each lane maneuver.
- Changing `MotoGraphImport.java` changes graph content: bump `CACHE_SEED` (it is
  also in the CI cache key and map-server `ToolHashes`/Docker allowlist).

## Black-and-white ride map (B&W mode)

During an active ride with the moon toggle on (`darkRideMapEnabled`), `RouteScreen`
loads `app/src/main/assets/ride-dark-style.json` instead of `style.json` (same
`{tiles_path}` token replacement, same style-load path). The planner and the
post-END restore always use the colour style, so B&W screenshots must be taken
mid-ride.

- Roads are one solid layer per drivable class over `transportation`:
  `ride-service-roads` (service, track), `ride-local-roads` (tertiary, minor),
  `ride-major-roads` (motorway, trunk, primary, secondary). Pedestrian `path`
  features are deliberately **not drawn** — the archive ships them as dense
  parallel lines and areas around every street, which made the B&W frame read
  as an architectural sidewalk drawing instead of solid roads — and every road
  layer filters with `["==", ["geometry-type"], "LineString"]`: the z14
  archive really does ship `path`/`pier`/`bridge` **polygons** in that layer,
  and a line layer draws a polygon as its border. No casings, no
  `line-pattern`/`line-dasharray`/`line-gap-width`, no paint opacity.
- `line-cap`/`line-join` live in `layout`; MapLibre silently ignores them in
  `paint`. Widths are legacy `{base, stops}` zoom functions whose stops continue
  past the source's `maxzoom: 14` (local roads 9 dp at the z16 band, 18 dp at
  z18, major 13/26 dp) because guidance runs z14–z18 over overzoomed tiles.
  Colours stay strictly grayscale and dimmest-to-brightest in class order; labels
  are `text-pitch-alignment: viewport` at 14–16 sp so the 58° guidance tilt cannot
  flatten them. The B&W route overlay is runtime-added solid white without a
  casing, below the road labels. `road_class` path details split the route into
  service/local/major segments; `map/DarkRouteRoads.kt` mirrors the basemap's
  zoom-width stops with a 1 dp shoulder to cover antialiasing/tile simplification.
  Keep its width stops and the style in sync (the JVM test checks this). Other
  roads are approximately 5% dimmer than the original B&W palette.
- Tests: the dark-ride cases in `StyleAndSpritesIntegrityTest` (JVM) pin layer
  structure, per-class geometry filtering, width growth/thickness, layout caps and
  joins, and label readability; `DarkRideStyleTest` (instrumented) loads the
  shipped style into a real MapView with a synthetic GeoJSON source and proves
  native widths, round layout, and polygon rejection in pixels and
  `queryRenderedFeatures`, with no PMTiles installed.
  `tools/test/visual.py nav-camera --black-and-white both` captures every guidance
  frame (street-close, rider-lock release/relock, each speed band z14–z18, and the turn) in both styles.
  Changing this style needs no graph rebuild, no tile rebuild, and no CI cache-seed
  bump.

## Map rendering backend and native frame benchmark

MapLibre 13.5.0 publishes **one backend per artifact**: `org.maplibre.gl:android-sdk`
is Vulkan-only (`RenderingEngine.getCurrentType()` = VULKAN, and
`setCurrentType(OPENGL)` throws `UnsupportedOperationException`), while
`org.maplibre.gl:android-sdk-opengl` is the same release built for OpenGL ES.
A runtime `settings put global` switch cannot change the backend — the first
`MapLibre.getInstance()` fixes the type, and the single-backend artifacts reject
the other value. Backend choice therefore lives in `app/build.gradle.kts`:
debug builds depend on `android-sdk-opengl`, release keeps the published
Vulkan default, and `-PmaplibreBackend=vulkan|opengl` forces either for both
variants for A/B evidence. The emulator measures why: its Vulkan device is
software (`cmd gpu vkjson` → Goldfish GFXStream/llvmpipe) while its OpenGL ES
path translates onto the host GPU (`dumpsys SurfaceFlinger` → Apple M4 Pro).

**POI symbol batching (the dense-CBD fix).** MapLibre 13.5 turns every
distinct `symbol-sort-key` range into its own drawable, and the OpenMapTiles
POI layers carried a data-driven key (`["get","rank"]`): the dense Brisbane CBD
measured ~880 of 929 draw calls and ~1.2 s of render-thread encoding per frame
with it, versus ~50 draw calls without any key. The shipped style instead uses
a bounded rank band on `poi-icons`/`poi-labels`
(`["min", ["floor", ["/", ["coalesce", ["get","rank"], 30], 10]], 3]`): equal
keys collapse into adjacent ranges, so coarse priority order (ranks 0–9, 10–19,
20+, missing last) survives while drawables stay small. `SymbolBatchingStyleTest`
(JVM) rejects any unbounded key and pins the band shape/ordering;
`SymbolPriorityOnDeviceTest` runs the real PMTiles archive at the perf cameras
and fails if committed important winners (General Post Office, Queen Street
Stop 57, HSBC, Woolworths at z18; HSBC, Woolworths, Post Office Square, Anzac
Square at z16; Teneriffe Park at the houses camera) stop winning placement, or
if POI labels/icons/towers stop rendering. Never reintroduce a per-feature sort
key, and beware data-driven `icon-size`/`icon-image` `match` expressions on
`rank` — corrupting one to a data-driven form re-explodes drawables (measured:
15 FPS vs 60 FPS at CBD z18). Measured with the correct style, debug OpenGL:
CBD z14 31.9 fps, z16 55.5, z18 59.9 (p95 18.9 ms, 0 stalls >100 ms),
houses z18.5 60.1; the same style on the Vulkan artifact (debug and release
builds) measures z18 46.3 fps / p95 25.3 ms. Before the fix the same camera
matrix measured 0.8–5 fps.

**Wide-view (z14) status and the tile-LOD exploration.** The wide CBD view is
the one remaining acceptance gap: debug OpenGL measures ~31.9 fps / p95 ~48 ms
(cold ~33 fps) with zero warm frames over 100 ms, against ~60 fps / p95 ~19 ms
at z16, z18 and the houses camera. The per-frame counters say the cost is the
number of far tiles' geometry and labels (≈350 draw calls, ~240 KB/frame of
dynamic symbol upload at z14 versus 59 draws / ~1.1 MB at z18; the map's own
render-thread encoding is the whole frame). MapLibre's designed camera tile LOD
(`setTileLod*`, default pitch threshold 60° — which never activates at this
app's 58° camera) was explored as the legitimate wide-view lever and is **not**
enabled in production: an experimental override at 45°/radius 1 initially
measured ~57 fps / 140 draws, but a `zoomShift = -1` variant shifted the
evaluated layer zoom below `building-3d`'s minzoom and hid the wide-view
extrusions, and the radius-1/no-shift result did not reproduce across rebuilds
(same override later measured ~350 draws), so it is not defensible as a shipped
setting. The debug sweep runner keeps an optional `tileLod` override
(`MapPerfSweepRequest.tileLod`, wire-tested by `MapPerfSweepTest`) purely for
repeatable experiments. Do not reintroduce a production LOD or a zoom-shift
trick to chase the z14 number; if it is revisited it needs reproducible
before/after draws plus wide-view extrusion/label proof first.

On this emulator the release Vulkan artifact is software-rendered (llvmpipe),
so it is slower than the debug OpenGL artifact by design of the environment:
z18 46.3 fps / p95 25.3 ms, z16 31.7 / 44.9, houses 60.1 / 19.0, z14 17.5 /
120.7. The debug-OpenGL default exists for emulator tooling only; real hardware
Vulkan was not measurable (no physical device attached) and must not be
promised the OpenGL debug numbers.

The durable benchmark is `tools/test/visual.py perf`: it queues deterministic
`easeCamera` sweeps through the debug receiver (`MapPerfSweepQueue` /
`MapPerfSweepRunner`), records native `OnDidFinishRenderingFrame` timestamps
plus MapLibre `RenderingStats` counters, and writes
`build/brisbane-performance-report.md` (merge several runs with
`visual.py perf-merge --labels before-opengl,after-opengl ...` to get the
paired before/after table). Numbers are only acceptable with the semantics the
report carries:

- `fps` is the elapsed frame-timestamp average `(frames-1)*1000/durationMs`;
  `nativeFps.harmonic` recovers the same elapsed rate from the per-frame
  listener (`1e9/delta`, not smoothed) after discarding its first pre-sweep
  idle interval. Never average listener rates arithmetically.
- `encodingTime`/`renderingTime` are seconds in the native API; convert to ms.
  On Vulkan `encodingTime` includes `Context::beginFrame` fence waits, so it is
  encode+GPU-wait, not pure CPU encode work.
- `numDrawCalls` is per frame; `bufferUpdates`/`bufferUpdateBytes` and the
  texture counters are cumulative, so per-frame uploads are deltas between
  consecutive samples — never the mean of the running totals.
- `Window.FrameMetrics` durations describe the activity window, never the map
  SurfaceView presentation.
- A warm sweep with an interrupted/cancelled leg or a timed-out settle window
  is INVALID in the report; a canceled native camera leg must not be recorded
  as finished.
- Each report names the backend that actually initialized
  (`RenderingEngine.getCurrentType()` + the MapLibre artifact's `BuildConfig`)
  and the host GPU identity; a perf number without both is not evidence.

The probe (`map/MapPerformanceProbe.kt`) attaches only from the debug-only
sweep effect in `RouteScreen`, stays dormant (listeners early-return) outside
`begin`/`end`, suppresses the debug camera-snapshot writer through
`MapPerfProbeGate` while a sweep drives the camera, and the runner restores
camera, layer visibility and building paint in `finally` for completion,
cancellation and failure alike. `MapPerfSweepTest` (JVM) pins the plan and the
statistics; `MapPerformanceOnDeviceTest` proves the live listener, the
during-sweep diagnostic state, structural layer restoration, cancellation and
interruption cleanup, and that the report declares the real backend. Changing
the backend artifact needs no graph/tile rebuild and no CI cache-seed bump.

## Ride media control center

While riding, the data bar's far-left button opens a Compose media control
center anchored above the bar (`media/MediaControlCenter.kt`). It is fully
offline and unprivileged: no Spotify SDK, no network, no
`MEDIA_CONTENT_CONTROL`, and no accessibility service.

- **Data bar** (`NavigationHud.kt`): shares `RideSurfaceColor` (`#1C1C1E`) with
  the directions HUD, holds a four-metric grid (speed, compact time, distance,
  pace) with the media opener far-left and a distinct END right, and pads for
  the system navigation bar. `NavigationHudFormat.dataBarLayout` is the pure,
  JVM-tested decision that keeps a single compact row on a wide screen and
  switches to a two-row grid (two metrics per row, media/END on their own row)
  whenever four cells plus the glove-sized opener and END would drop a value
  below `MIN_METRIC_SP` (11.5 sp) at the active font scale — 320 dp at 2x font
  scale renders every value in full instead of a 10 sp ellipsis. Media and END
  are glove targets (64 dp); END keeps its own row in the grid.
  `NavigationHudFormat.etaCompactText` renders `1h 19m` / `45m` / `2h` so the
  row never wraps. The ride chrome uses one palette: white content on the dark
  `RideSurfaceColor` surface, `RideMutedColor` for labels, and the white-fill /
  `RideSurfaceColor`-ink pair of the map pills everywhere else. The pace delta
  and the roundabout exit badge carry no hue (the sign and the gained/lost label
  state the direction), and the centre-on-me button inverts that pill pair while
  the camera lock is on. The only remaining accent in the ride chrome is the red
  END button; `darkRideMapEnabled` (B&W ride mode) greys that one too, so B&W
  carries state by glyph and text only.
- **Layout**: `RouteScreen` measures the real data bar and the directions HUD
  with `onGloballyPositioned` and positions the media panel and the map controls
  (centre-on-me, zoom pill) from that height instead of hard-coded ride offsets.
  The panel is bounded to the space between the HUD and the bar and scrolls
  inside that bound, so landscape can never cover the next-turn guidance or END.
  System Back closes the open media panel (`BackHandler`); the settings card
  keeps priority (the media handler is disabled while it is open), so one Back
  always peels exactly the top-most surface.
  Opening starts a 15-second inactivity timer; panel touches, scrolling, and
  control activations restart it. Playback, volume, and player observations do
  not reset it. The 16 dp white pie beside Close shows accessible progress.
  Navigation-bar inset padding belongs inside the data bar's opaque surface,
  never outside it, so the map cannot peek out below the bar.
- **The panel/bar join** (`media/MediaPanelJoin.kt`): the open panel is fused to
  the bar as one continuous silhouette, the same trick the settings cog uses
  when its pill becomes the card's tab. The join is a geometry contract, not a
  taste call, and every value is derived from the bar's own geometry rather than
  restated: the panel is **full-bleed** (`leadingInset`/`trailingInset` = 0) so
  both surfaces share the screen edges and no step appears at the join; the bar
  has square top corners while open (rounded again when closed), so only the
  panel supplies the fused slab's top rounding; the panel
  reaches `fuseDepth` (exactly `DataBarVerticalPadding`, the bar's top content
  padding, now 6 dp) **down into**
  the bar and `RouteScreen` draws the panel **before** the bar, so the bar paints
  over the overlap and no antialiased seam can show; and the bar's
  `shadowElevation` drops to 0 while the panel is open, because the bar's own
  top-edge shadow would fall across the panel and redraw the very line the join
  removes (the panel supplies the elevation for the fused pair). Never re-add an
  inset, a second corner radius, or the bar's shadow while the panel is open.
  Tests: JVM `media/MediaPanelJoinTest` pins those relationships; instrumented
  `ui/MediaPanelJoinTest` proves the real laid-out edges are flush, that the
  overlap never reaches a metric or the opener/END, and that closing restores the
  bar's standalone rounded silhouette. `tools/test/visual.py media-controls`
  captures the fused portrait frame; `visual.py route ... ` plus a RIDE and a
  **Media controls** tap shows it in landscape, where the fused pair spans the
  full width.
- **Sessions** (`media/AndroidMediaSessionGateway.kt`): `MediaSessionManager`
  `getActiveSessions` + `MediaController` callbacks → `StateFlow`. A session's
  key is derived from its `MediaSession.Token` (binder-based `equals`/`hashCode`,
  stable across snapshots), so two sessions from one package or a reordered
  snapshot cannot swap identities; per-controller callbacks are guarded against
  stale sessions. Access is the user-granted notification listener
  (`MediaNotificationListenerService`, only `BIND_NOTIFICATION_LISTENER_SERVICE`);
  `MediaPermission` re-checks on resume. `MainActivity` shows an optional
  `MediaAccessPrompt` at fresh startup only when access is absent; **Not now**
  dismisses it, and Enable opens `ACTION_NOTIFICATION_LISTENER_SETTINGS`.
  The ride panel contains no permission prompt/button. `setOnSessionsChanged` reports
  whether the platform accepted the registration; the controller retries a
  refused registration on refresh and re-asserts it on resume and on a
  notification-listener reconnect (idempotent, never stacked), so a grant made
  after the panel attached starts live updates without reopening the panel.
  `onAudioInfoChanged` is part of the callback set: remote volume is applied
  asynchronously and only the observed value may be shown. The service overrides
  no notification callback, so notification content is never read.
- **Pure core** (`media/MediaModels.kt`, `MediaCenterState.kt`,
  `MediaCenterController.kt`): deterministic selection (user-pinned session
  retained; otherwise the playing session, ties broken by recency/package/key;
  a paused selected session is retained for Resume), capability mapping from the
  `PlaybackState` action mask, volume-target resolution, and single-command
  dispatch with an honest accepted/rejected result. It never retries or
  duplicates a command and never reports a false success; unsupported commands
  are disabled. `MediaVolumeControl` mirrors the `VolumeProvider` ABI values
  (`FIXED = 0`, `RELATIVE = 1`, `ABSOLUTE = 2`) and an on-device suite pins them
  against the real platform constants. `MediaCenterState.transportStateKnown`
  is true only when a selected session actually reported a `PlaybackState`; in
  the unknown state (no session, or a session without a state) the panel shows
  explicit **Pause and Play** controls instead of a toggle whose label would
  have to lie, and the footer says the state is unknown. When session discovery
  is unavailable it uses a clearly labelled limited
  `AudioManager.dispatchMediaKeyEvent` fallback with the explicit
  `KEYCODE_MEDIA_PLAY` / `KEYCODE_MEDIA_PAUSE` (never the ambiguous toggle),
  always as one paired DOWN/UP press.
- **Volume** (`media/AndroidVolumeController.kt`): local `STREAM_MUSIC` via
  `AudioManager.adjustStreamVolume` with real min/max/current readback and
  fixed-volume handling (`AudioManager.isVolumeFixed` plus a collapsed min/max
  range); remote sessions use `MediaController.adjustVolume` per
  `PlaybackInfo.volumeControl`, and their level is only ever shown from the
  session's observed `onAudioInfoChanged` value. Hardware volume presses are
  invisible to the app, so while the panel is open the controller observes the
  stream's system setting (immediate push) and polls the real readback as a
  backstop; the resolved level and its disabled limits follow the observation,
  never an optimistic guess. Volume works with no notification access. No audio
  focus is taken for controls, so `OfflineVoiceGuidance` ducking is untouched.
- **Glove ergonomics**: the media opener, close, previous, next, volume ±, END,
  and player-selection chips are all at least
  64 dp with visible 12 dp separation (no overlapping invisible targets); the
  primary play/pause control is 80 dp. Rows adapt (extra rows, scroll) instead
  of shrinking below the floor at 320 dp / 2x font scale, and nothing uses
  precision gestures or hold-to-repeat for skips or volume. This is an app-side
  target-size contract only; it does not change hardware touch sensitivity.
- Tests: JVM `media/MediaSessionSelectionTest`, `MediaCapabilitiesTest`,
  `MediaCenterControllerTest` (including listener retry/detach, observed volume
  updates, and the unknown-state contract); instrumented `MediaControlCenterTest`
  (glove targets, separation, unknown-state explicit pair, 320 dp / 2x and
  bounded-landscape scroll), `MediaPlatformAbiOnDeviceTest` (real
  `VolumeProvider`/`PlaybackState`/`KeyEvent` values, single paired DOWN/UP),
  `MediaVolumeOnDeviceTest`, `MediaSessionTransportOnDeviceTest` (native
  `MediaSession`), `MediaAccessRecoveryOnDeviceTest` (attach-before-grant →
  grant → live session updates without reopening), `MediaSessionVolumeOnDeviceTest`
  (remote readback through a real `VolumeProvider`), `MediaVolumePanelOnDeviceTest`
  (an external stream change reaches the open panel), `DataBarResponsiveTest`
  (320 dp / 2x readable grid and glove targets), `RideLandscapeLayoutTest`
  (panel between HUD and bar), `MediaPanelJoinTest` (fused panel/bar seam),
  and `NavigationHudTest` (opener, system Back,
  glove targets on the real screen); `AccessibilityTest`, `FontScaleTest`, and
  the fuzz oracles cover the panel (oracle floors are 64 dp targets and 80 dp
  play/pause; the campaign has dedicated `ToggleMediaPanel`/`StartRide` actions
  and reports `mediaPanelStates`). `tools/test/visual.py media-controls`
  captures the B&W playing/paused/no-player/permission-needed states, the
  Back-dismissed panel, using a **debug-source-set-only** synthetic session
  (`VisualMediaSessionReceiver`); release builds create no synthetic session.
  On-device test helpers edit only this app's entry in
  `enabled_notification_listeners` (never the whole list) and restore the exact
  starting access in `@After`; the visual runner restores it in a `finally`.

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

- For raw `adb` or emulator calls, SDK binaries are not on PATH; use full paths: `~/Library/Android/sdk/platform-tools/adb`, `~/Library/Android/sdk/emulator/emulator`.
- Orca's `orca emulator` CLI drives Android too (not iOS-only): backend is adb/emulator/avdmanager. Verified working: `devices` (lists AVDs + adb devices, booted vs shutdown), `attach <serial>`, `tap x y` (normalized 0..1), `type "text"` (US ASCII), `gesture '[{...}]'` (swipe), `button home|back|...`, `rotate landscape_left|portrait`, `ax --json` (uiautomator tree, bounds in physical px — this AVD is 1280x2856), `install ./app-debug.apk` (121 MB APK ok), `launch com.organicmoto.maps` (default activity), `logcat --lines N`, `kill`.
- Gotchas: `exec` is broken in current Orca (`Cannot use 'in' operator to search for 'screenshotStatus'...` parse bug) — use raw adb instead. `attach` is required once per worktree before unqualified commands. A shutdown AVD lists but must be booted first (`emulator @<avd>`). Tap accuracy can be off when the IME resizes the layout mid-session; re-dump `ax` for fresh bounds.
- Emulator touch/`tap` attempts are flaky and often fail for reasons unrelated to app code (IME layout shifts, uiautomator bounds drift, adb races). Do not loop retrying taps beyond a handful of attempts, and do not infer a bug in our app code just because a tap "did nothing". After ~3 failed attempts, stop, re-dump `ax` for fresh bounds, or switch to `type`/`launch`/logcat to verify behavior instead of retrying the tap.
- Debugging logs: `orca emulator logcat --lines 500 --json` for a one-shot parsed dump (fields: timestamp/level/tag/message) — pipe through jq/grep for tags; use raw `adb logcat` for live streaming. App logs use `android.util.Log` under `OrganicMoto.*` tags: `OrganicMoto.RouteScreen` (route submit/success/errors, point resolution), `OrganicMoto.SearchField` (per-keystroke text, debounced searches, picked results), `OrganicMoto.GeocodeCtrl` (first index load), `OrganicMoto.SearchEngine` (per-query timing), `OrganicMoto.GeocoderIndex` (asset copy/mmap), `OrganicMoto.Router` (GraphHopper init + route request), `OrganicMoto.Media` (media listener attach/re-register and observed local volume; debug builds only), `OrganicMoto.PointParser` (lat,lon fallback). Levels: `I` = user actions/successes, `D` = lifecycle/timing, `V` = per-keystroke, `W` = degradations (geocoder unavailable → fallback), `E` = failures. One-shot filter: `adb logcat OrganicMoto:V com.graphhopper:W *:S`.
- Emulator workflow: `orca emulator install app/build/outputs/apk/debug/app-debug.apk && orca emulator launch com.organicmoto.maps`, then drive with `tap`/`type`/`ax`.

### Project CLI loop

Use `tools/android.sh` for routine builds, launches, and log debugging. Do not open Android Studio for these tasks.

```sh
tools/android.sh run      # build, install, and launch
tools/android.sh debug    # build, launch, and stream app-process logs
tools/android.sh logs     # stream logs from an already-running app
tools/android.sh stop     # stop the app
```

- `run` and `debug` start the `Pixel_10_Pro` AVD when no device is online. Set `ANDROID_AVD` to choose another AVD. Set `ANDROID_SERIAL` to choose a connected device.
- The helper reads the SDK path from `ANDROID_SDK_ROOT`, `ANDROID_HOME`, `local.properties`, then `~/Library/Android/sdk`. If `JAVA_HOME` is unset, it uses Homebrew JDK 17 when installed. Direct Gradle commands must use `./gradlew`.
- `debug` streams the app process through `adb logcat --pid`. Ctrl-C stops the log stream. This helper does not attach a source-level debugger; use an IDE debugger for breakpoints.

## Regional packages and the map server (`map-server/`)

The app can install **complete regional data packages** (`.motomap`): vector
tiles + GraphHopper graph cache + offline geocoder index in one versioned ZIP64
archive. Packages are produced by `map-server/`, an independent Go module with
its own CLI (`serve` UI/API, `serve --headless`, `build` local export,
`catalog`, `admin`), an embedded vendored-HTMX UI, tests, and Docker.

- **Generation reuses the pinned pipeline** — it invokes `tools/tiles/build-tiles.sh`
  (Planetiler 0.10.2, JDK 21), `tools/gh/config.yml` + the GraphHopper 11.0 jar
  (JDK 17), and `:geocoder-tool` via the standalone JVM build
  (`geocoder-tool/standalone/settings.gradle.kts`, `installDist` — no Android
  SDK). `build-tiles.sh` accepts `TILE_PBF`, `TILE_OUT`, `TILE_MAX_HEAP`,
  `TILE_MAXZOOM`, `TILE_JAVA_HOME`, `TILE_SOURCES_DIR`, `TILE_TMP_DIR`,
  `TILE_TILE_WEIGHTS`, `TILE_PLANETILER_JAR`; defaults preserve the CI pipeline.
  The per-job GraphHopper config is the canonical file with only
  `datareader.file`/`graph.location` rewritten, so `motorcycle|198752012` and
  the pre-compiled weighting stay valid (`GenerateGraphConfig` + its test pin
  this). Any change to those inputs requires a `CACHE_SEED` bump.
- **Package contract**: `manifest.json` (schema 1) records region identity,
  source date/SHA-256, generator fingerprint, per-file lengths + SHA-256 for
  every component, the graph profile, geocoder magic, tiles format, and the
  attribution. `map-server/internal/manifest` and the Android
  `region/RegionPackage.kt` parse the same document; both sides reject unknown
  fields, unsafe paths, missing/extra components, and wrong compatibility
  markers. The server re-reads and verifies every package before publishing;
  the Android importer (`region/RegionArchiveImporter.kt`, pure JVM) re-verifies
  every file while extracting into a staging directory.
- **Queue and artifacts**: persistent `jobs.json`, restart recovery
  (`running` → requeued), one worker, bounded admission, dedup by pipeline
  fingerprint, immutable artifact cache, per-step progress, process-group
  cancellation, disk checks. Downloads use `http.ServeContent` (Range/If-Range/
  ETag), so the Android downloader resumes partial files.
- **Security model**: no authentication; per-IP token buckets (build/poll/
  download) plus per-client and global transfer caps; `X-Forwarded-For` honored
  only from `--trusted-proxies` with right-to-left walking and `/64` IPv6
  bucketing; signed stateless CSRF double-submit; strict CSP/nosniff/HSTS
  headers; body caps; admin endpoints only with `MOTO_ADMIN_TOKEN`. Callers
  can never supply a URL, path, or command. Rate limiting is abuse mitigation,
  not DDoS protection.
- **Requests by place** (`internal/geofabrik`, `catalog.RequestedStore`,
  `api/extracts.go`): `GET /api/v1/extracts?q=|lat=&lon=` and
  `POST /api/v1/builds {"extractId"|"query"|"lat","lon"}` resolve a place
  against Geofabrik's `index-v1.json` (24 h disk cache), pin the **dated**
  file on `download.geofabrik.de` (from the `-latest` redirect, else a 7-day
  probe) with its size and `.md5`, and enforce the state-sized limit:
  continents never, otherwise ≤ `requests.maxExtractBytes` (768 MiB default;
  Texas 723 MB passes, Australia/California do not and the error lists their
  sub-regions). Accepted places persist in `<state>/requested-regions.json`
  (cap 24), show in `/api/v1/catalog` with `requested: true`, and build
  through the unchanged queue/pipeline; the download is size-capped and
  MD5-checked, the observed SHA-256 is pinned after success (repeat requests
  are cache hits), and data older than `refreshDays` (30) is re-resolved. An
  operator catalog id always wins over a requested one. Geofabrik lists US
  states with the id as name; `displayName` repairs it. Job progress is
  persisted on every step change, and completed jobs persist their artifact
  path/size/SHA-256 so `downloadUrl` survives a restart.
- **Android integration**: `region/RegionRegistry.kt` owns
  `{filesDir}/regions` (`installs.json`, `active.json` with a monotonic
  generation, immutable `installs/<region>-<fingerprint12>/` directories).
  Activation only rewrites the small active record; a corrupt active install
  falls back to bundled Queensland. `InstalledRegion.bytes` is the real
  on-disk footprint (tiles + graph + geocoder + `manifest.json`), recomputed
  from the directory on every `list()`, never the compressed download size.
  `RegionManager` orchestrates server check → catalog → build request → polling
  → resumable private download → **copy to a user-chosen SAF file** (the
  download is never auto-installed) → explicit import → verified install →
  activation. A terminal build job (completed/failed/canceled) is informational:
  the catalog row keeps offering Download or Retry, and `RegionUiModel` is the
  JVM-tested decision for that. Failed/cancelled downloads keep their staged
  partial and a completed staged file from the old private flow is offered for
  save/import (`RegionDownloadStore` recovery), so no stale state traps the
  user without a retry. Deleting an installed map goes through
  `requestRemove`: an inactive map is removed directly; the active map first
  switches to the bundled dataset (generation bump) and is deleted only after
  `onDatasetReadersReleased` confirms `GraphHopperRouter.closeAsync()` drained
  (a persisted pending removal finishes on the next start; the UI reflects the
  pending state). Deletion is refused mid-ride and never touches a user-saved
  `.motomap` in Files. `RouteScreen` keys the `GraphHopperRouter`,
  `GeocodeSearchController`, and map style on `(installId, generation)`.
  Saved rides are untouched; out-of-region points produce an honest message
  (`describeRoutingFailure`). Legacy **Load map file** still imports a bare
  PMTiles archive and switches back to the bundled dataset.
- **Transfer reliability contract** (`region/net/RegionHttpClient.kt`,
  `region/RegionDownloadReliability.kt`, `RegionManager`): the ETag and size
  are persisted from `onResponse` the moment the server answers, never only
  after success, so an interrupted transfer always resumes; a partial is
  always resumed with `Range` (plus `If-Range` when the ETag is known), and a
  206 for the wrong offset discards the partial instead of appending.
  `RegionHttpException.transient` classifies failures: drops, stalls, 5xx,
  408/429 and truncation retry automatically (`DownloadRetryPolicy`: backoff
  2–30 s, `Retry-After` honoured, the counter resets whenever an attempt made
  progress, 5 stalled tries then a manual Resume with the partial kept);
  4xx, TLS trust, and redirects surface immediately with an actionable
  message. API GETs retry one transient failure. A finished download is
  SHA-256-verified against the catalog/job digest (`VERIFYING` phase) before
  anything is written to the user's file; a mismatch discards the staged bytes.
  Server-supplied download URLs must stay on the configured origin. Check
  server tries HTTPS then, for schemeless private addresses with Allow
  insecure HTTP on in debug, plain HTTP, and saves the address that worked;
  it also re-attaches to a build the server is still running. Job polling
  backs off while unreachable and stops (with a message) on a 404.
- **Maps screen layout**: one card per job (server, available regions,
  transfer, installed maps, offline import) on a 16/12/8/4 dp scale; the
  header block reserves the action rail's corner (`RailClearanceEnd`/
  `RailClearanceHeight`, pinned by `MapsSettingsTest`), and the active map is
  one accessibility node (`Active map <name>`).
- **Offline guarantee**: `INTERNET` is declared for downloads only. All network
  APIs and URL schemes live in `app/src/main/java/com/organicmoto/maps/region/net/`;
  `OfflineGuaranteeTest` fails if any other production source contains
  `HttpURLConnection`, `java.net.*` socket/URL APIs, `http://`, or `https://`,
  if an offline package imports `region.net`, or if the release manifest enables
  cleartext. Debug builds may opt in to plain HTTP for private/loopback
  addresses only (`LocalNetworkPolicy`, `BuildConfig.DEBUG`).
- **Tests**: Go `go vet` + `go test -race ./...` (manifest/package writer and
  verifier incl. tampered/duplicate/zip-slip entries, pipeline with stubbed
  processes + cache hit + cancellation, queue persistence/restart/dedup/
  cancellation, rate limits, trusted proxy, CSRF, download ranges, full HTTP
  surface). Android JVM: `RegionPackageTest`, `RegionArchiveImporterTest`
  (hostile archives + the real committed Monaco package), `RegionRegistryTest`
  (including the actual installed-bytes invariant), `RegionUiModelTest`
  (terminal-job retry/Download decisions and delete wording),
  `RegionDownloadStoreTest`/`RegionPendingRemovalStoreTest`/`RegionPackageTransferTest`
  (recovery, no silent staged-file deletion, byte-exact saves),
  `RegionDownloadNamingTest`, `VisualMapsScenariosTest`,
  `RegionHttpClientTest` (socket server: Range/If-Range/416/truncation/cancel),
  `RegionServerClientTest`, `LocalNetworkPolicyTest`, `RoutingFailureMessageTest`.
  Instrumented: `RegionSwitchOnDeviceTest` (installs the real Monaco package,
  routes with its graph, searches its geocoder, proves fallback + corruption
  handling), `MapsDownloadFlowOnDeviceTest` (a real loopback download staged
  privately and saved complete, import leaves the user's source file in place,
  recovered-package save, interrupted partial survival, inactive removal,
  active removal waiting for the drain, mid-ride refusal),
  `MapsSettingsScreenTest` (terminal job keeps Download/Retry, glove-sized
  rubbish bin, confirmation/cancel, saving phase, 2x font scale),
  `MapsSettingsTest` (controls, 48 dp targets, bundled honesty, Back). The fuzz
  campaign has `OpenMapsSettings` (`UiTarget.MAPS_SETTINGS`), a Maps oracle
  (now also pinning the bundled row), and a `maps-settings.json` replay seed;
  `SettingsMenuTest` includes the Maps row.
  `tools/test/visual.py maps-settings` captures the empty/address states and the
  debug-preview download/import/delete states without a server or SAF picker.
- **Docker deployment**: `map-server/Dockerfile` builds a non-root image with
  both JDKs, the pinned jars, and the standalone geocoder distribution.
  `Dockerfile.dockerignore` is an allowlist and must re-include every parent
  directory (`!map-server` before `!map-server/**`), because Docker's
  parent-directory matching means a bare `!dir/**` cannot rescue files under an
  excluded `dir`. The runtime stage must carry `geocoder-tool/build.gradle.kts`
  and `geocoder-tool/src/**` — `pipeline.ToolHashes` hashes them, so without
  those bytes every build request fails before running a tool; the
  `docker_context_test.go` guard pins context + runtime COPY coverage.
  `map-server/deploy/deploy.sh` is the repeatable remote path: it stages the
  allowlisted context, uploads one extract (SHA-256 checked), derives the
  catalog date from the PBF's own `osmosis_replication_timestamp` (never the
  file name), writes the pinned local-path catalog, and drives
  `docker compose` (bind address, port, volume, caps, watchtower disable all
  parameterized in `docker-compose.yml`). `deploy/build-watch.sh` samples
  `docker stats` and records elapsed/peak RAM/CPU per build; `deploy.sh verify`
  re-reads a published package through the CLI `verify` subcommand and checks
  the sidecar. `deploy/README.md` records the actual host deployment.
- **CI**: `.github/workflows/ci.yml` has a `map-server-tests` job; the light
  tier (`tools/test/ci.sh`) runs the Go suites when Go is present.

## Code facts that differ from defaults

- From/To are geocode-search fields (`GeocodeSearchField` in `SearchField.kt`: 300 ms debounce, results dropdown) backed by the offline index (`assets/geocoder/geocoder.dat` → `{filesDir}/geocoder/`, mmap via `GeocoderIndex`, `GeocodeSearchController` lazy-loads the index on the first search). Coordinates still work: type `lat,lon` (or `lat lon`) and either pick the geocoder's COORDINATE result or hit Route and let the `PointParser` fallback resolve it. `SearchText` normalization/tokenization is shared with `:geocoder-tool` — the `Text.kt` copies in `geocoder-tool/src/main/kotlin/com/organicmoto/geocoder/tool/Text.kt` and `app/src/main/java/com/organicmoto/maps/geocoding/Text.kt` must stay in sync. The ranking model constants are ported from Organic Maps' `ranking_info.cpp`.
- The app's only runtime network code is the regional package downloader/API client in `app/src/main/java/com/organicmoto/maps/region/net/` (see the regional packages section). Routing, place search, map rendering, saved rides, and media control are fully offline; `OfflineGuaranteeTest` pins the download-only boundary and forbids cleartext in release.
- MapLibre 13.5.0: GeoJSON classes are `org.maplibre.geojson.*` (NOT `com.mapbox.*`); the `android-sdk-geojson` artifact arrives transitively. Route drawing uses `MapLibreMap.getStyle {}` (style-race-safe), not `MapView.style`.
- slf4j-android must stay 1.7.36 — the 2.x line has no Android binding.
- Router init is lazy (first Route press): graph load happens on `Dispatchers.IO`, never the UI thread.
