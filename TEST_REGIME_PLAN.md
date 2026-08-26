# Test Regime for Organic Moto Maps

## Context

The user supplied a 2153-line generic testing/fuzzing spec for offline Android apps and asked to tailor it to this repository and plan its implementation. Deliverable: a production-grade deterministic + property + fuzzing test regime for Organic Moto Maps (offline Kotlin/Compose + MapLibre + GraphHopper motorcycle route planner), implemented as JVM unit tests, instrumented androidTest suites, and local test-tier scripts (one light CI tier + one manual full-suite trigger). The regime proves the offline guarantee, generated-artifact compatibility (GraphHopper profile/helper ABI), routing invariants, complexity-dial behavior, single-flight/stale-generation safety, storage truthfulness, geocoder robustness, and UI consistency (carousel↔map, dial, fixed panel). It includes a seeded Compose-semantics fuzzer with failure recording/replay/shrinking, and encodes a LIVING-SUITE mandate into AGENTS.md: every future feature/logic change must ship with tests at this same rigor.

## Grounding facts (measured 2026-08-26)

- Working tree has uncommitted edits in: RouteMiniMap.kt, RouteScreen.kt, SaveRouteBubble.kt, SavedRouteIcons.kt, SavedRoutesSheet.kt, map/RouteLine.kt, routing/ComplexityWeighting.kt, routing/GraphHopperRouter.kt, routing/PointParser.kt. Tests must target current working-tree state; do not revert or commit these.
- Generated assets present locally: `app/src/main/assets/` — geocoder 27M, glyphs 5.6M, graph-cache 110M, sprites 132K, style.json 16K, tiles 268M. APK: 383M. `data/` (1.9G) has queensland.osm.pbf + graph-cache + tiles + style. `tools/gh/` has graphhopper-web-11.0.jar, config.yml, GenerateWeighting.java, generate-weighting.sh, motorcycle.json.
- Build: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew` (AGENTS.md's "wrapper 8.13 / AGP 8.7.3" is STALE: wrapper is Gradle 9.5.0, AGP 9.3.1, Kotlin 2.2.10, composeBom 2024.12.01, minSdk 26, JVM target 17). Instrumented test methods MUST be plain camelCase (D8 rejects backticks at minSdk 26). Single AVD 1280x2856; drive via `orca emulator` or raw adb (`~/Library/Android/sdk/platform-tools/adb`).
- Existing tests: 30 JVM + 6 instrumented (PolylineCodec, RouteSimilarity, SavedRouteRepository with private InMemorySavedRouteStore fake, MiniMapLayout, SqlSavedRouteStore). `kotlinx-coroutines-test` 1.9.0 already in testImplementation. NO compose ui-test artifacts, no androidx.test:rules, no org.json, no mocking/property libs.
- Verified via javap against tools/gh/graphhopper-web-11.0.jar: `com.graphhopper.config.Profile(String)` has `getVersion(): Int` and `getHints(): PMap`; `Weighting` interface = calcMinWeightPerDistance/calcEdgeWeight/calcEdgeMillis/calcTurnWeight/calcTurnMillis/hasTurnCosts/getName; `BaseGraph.Builder(EncodingManager)` via `EncodingManager.start().add(EncodedValue).build()`; `EdgeIteratorState.setWayGeometry(PointList)/setDistance/set(BooleanEncodedValue,bool)/setReverse/...`; `SimpleBooleanEncodedValue(String, boolean)`; `EnumEncodedValue(String, Class)`; `ResponsePath` is a settable POJO with `addPathDetails(Map<String,List<PathDetail>>)`; `PathDetail(Object value)` + setFirst/setLast; `TurnCostProvider` in package `com.graphhopper.routing.weighting`.

## Decisions (binding)

- TWO tiers, two entry points (user revision of the earlier no-tiers decision): `tools/test/ci.sh` is the ONE light CI tier — no emulator, JVM-only, automation-friendly; `tools/test/run-all.sh` is the single MANUAL trigger that first runs `tools/test/ci.sh` and then everything else (emulator suites, offline airplane-mode proof, fuzz campaign, memory/perf). No .github/ infra is added; the scripts are CI-wireable later.
- Screenshots: captured as fuzz-failure artifacts via `UiAutomation.takeScreenshot()` (no new dependency). NO automated pixel-diff baselines in this iteration.
- Process-death: `ActivityScenario.recreate()` in instrumented tests + `tools/test/process-death.sh` (adb `am kill` + relaunch + uiautomator dump) as a documented manual pre-release check. No in-test process killing (it kills the instrumentation process).
- New dependencies ONLY: compose ui-test-junit4 + ui-test-manifest (BOM-managed), androidx.test:rules 1.6.1, org.json:json:20240303 (testImplementation), junit4 (testImplementation in :geocoder-tool). No Robolectric, kotest, jqwik, mockk, turbine. Fakes are hand-written; fuzz randomness is `kotlin.random.Random(seed)`.
- Generated-asset-dependent JVM tests FAIL LOUDLY with the exact regeneration command when assets are absent. Preflight is the primary clean-clone gate so pure-JVM work on a clean clone does not fail the unit suite.
- The documented restoration contract (RouteScreen.kt:256-257 comment) is the test contract: fromText/toText/complexity survive recreation via rememberSaveable; maxRoadShare/blockUnpaved survive via SharedPreferences `route_preferences`; the computed route, selectedIndex, dialogs, and transient UI state do NOT survive. Tests assert exactly this.

## Approach

Phases are ordered so the tree builds and all suites pass after each. After Phases 1-3 run `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:testDebugUnitTest :geocoder-tool:test`; from Phase 4 additionally `:app:assembleDebug` and the named connected suites.

### Phase 0 — Dependencies and observability seams (no behavior change)

1. `gradle/libs.versions.toml`: add versions `androidxTestRules = "1.6.1"`, `orgJson = "20240303"`; add libraries `compose-ui-test-junit4 = { group = "androidx.compose.ui", name = "ui-test-junit4" }` (BOM-managed), `compose-ui-test-manifest = { group = "androidx.compose.ui", name = "ui-test-manifest" }` (BOM-managed), `androidx-test-rules = { group = "androidx.test", name = "rules", version.ref = "androidxTestRules" }`, `org-json = { group = "org.json", name = "json", version.ref = "orgJson" }`.
2. `app/build.gradle.kts` dependencies block: add `androidTestImplementation(libs.compose.ui.test.junit4)`, `debugImplementation(libs.compose.ui.test.manifest)`, `androidTestImplementation(libs.androidx.test.rules)`, `testImplementation(libs.org.json)`. `geocoder-tool/build.gradle.kts`: add `testImplementation(libs.junit4)`.
3. New file `app/src/main/java/com/organicmoto/maps/UiSemantics.kt`: `SemanticsPropertyKey`s `RouteUiStateKey: String`, `RouteGenerationKey: Int`, `SelectedRouteKey: Int`, `RouteCountKey: Int`, `FocusedRouteIndexKey: Int`, `MapRouteCountKey: Int`, `MapReadyKey: Boolean`. Attach in RouteScreen.kt: root `Column` gets `.semantics { this[RouteUiStateKey] = stateName; this[RouteGenerationKey] = coordinator.generation; this[SelectedRouteKey] = selectedIndex; this[RouteCountKey] = routeCount }` (stateName = "idle"/"loading"/"success"/"error"); map `Box` gets `.semantics { this[FocusedRouteIndexKey] = selectedIndex; this[MapRouteCountKey] = routeCount; this[MapReadyKey] = mapRef.value != null && styleJson != null }`. These are the fuzzer/oracle/test seams; they double as accessibility state.
4. Accessibility fixes (paste §39): add `Modifier.minimumInteractiveComponentSize()` to the Saved-routes and Route-settings `IconButton`s in RoutePlanPanel (currently 32.dp) and to each search-result row in RoutePlanSearchResults; add `Modifier.semantics { contentDescription = label }` to the BasicTextField in both SearchField composables and `contentDescription = result.name` to result rows.
5. `rememberMapView` in RouteScreen.kt: add `MapLibre.getInstance(context)` as first statement of the factory (idempotent singleton; keeps MainActivity's existing call). Enables compose-rule-hosted RouteScreen tests (fontScale) without MainActivity.
6. Run `:app:assembleDebug` to confirm the dependency graph resolves (first build check).

### Phase 1 — Pure routing/geocoder seams + JVM tests (no behavior change)

1. New file `app/src/main/java/com/organicmoto/maps/routing/MotorcycleProfile.kt`: move `internal const val MOTORCYCLE_PROFILE = "motorcycle"` and `private fun motorcycleProfile(): Profile` VERBATIM from GraphHopperRouter.kt (lines 154-180) → `internal fun motorcycleProfile(): Profile`. GraphHopperRouter imports it; delete the originals. Hint order (remove custom_model → putHint custom_model_files → setCustomModel) is ABI-critical; do not reorder.
2. New file `app/src/main/java/com/organicmoto/maps/routing/RouteRequests.kt`: `internal fun buildGhRequest(from: GHPoint, to: GHPoint, detent: Int, blockUnpaved: Boolean, maxRoadShare: Double, attempt: Int, previousEdgeIds: Set<Int>): GHRequest` — move the request-construction block from GraphHopperRouter.route() (lines 288-323) VERBATIM (profile, pathDetails ["edge_id"], CH.DISABLE=true, MOTO_COMPLEXITY=detent.toDouble(), BLOCK_UNPAVED, and the detent>0 ALT_ROUTE hint set with constants at 624-646). The route() loop replaces it with `val request = buildGhRequest(from, to, detent, blockUnpaved, safeMaxRoadShare, attempt, previousEdgeIds)`.
3. New file `app/src/main/java/com/organicmoto/maps/routing/AlternativePolicy.kt` (GraphHopper imports only, no Android): move these data classes here — `EdgeSection(edgeId: Int, forward: Boolean, distance: Double)`, `EdgeTraversal(edgeId: Int, forward: Boolean)`, `CachedRoute(path: ResponsePath, edgeDistances: Map<Int, Double>, edgeSections: List<EdgeSection>)`, `RouteDiversity(maxOverlap: Double, longestDistinctStretch: Double)`. Make internal and move: `edgeSections(path): List<EdgeSection>` (:505-525), `cachedRoute(path): CachedRoute` (:496-502), `sharedRoadFraction` (:607-614), `routeDiversity` (:452-464), `longestDistinctStretch` (:479-494), `isMeaningfullyDifferent` (:466-477), `withinGlobalDetourBudget` (:534-548), `hasExcessiveLocalDetour` (:558-605), `fallbackTier` (:437-450), `maxAlternativeWeight` (:616-617), `previousRoadPenalty` (:619-622), and every policy constant they use (:624-651). GraphHopperRouter keeps RouteKey, CachedRouteSet, the cache, the request/acceptance loops, detent-0 path, fallback sorting, RouteResult; update all references to `AlternativePolicy.*`.
4. New internal function in `app/src/main/java/com/organicmoto/maps/map/RouteLine.kt`: `internal fun buildRouteFeatureCollection(routes: List<ResponsePath>, focusedIndex: Int): FeatureCollection` — move the ResponsePath→FeatureCollection mapping from `drawRoutes` (:105-152) verbatim (route_index property, colors, opacity, widths incl. ROUTE_SELECTED_WIDTH = 8f, casing, sort keys). `drawRoutes` calls it and writes the result to the GeoJsonSource. `focusedIndex` clamped to `routes.indices` (if empty, return empty FeatureCollection).
5. Geocoder JVM enablement:
   - `GeocoderIndex.kt`: change `private fun parse(buf: ByteBuffer): GeocoderIndex` → `internal fun parse(buf: ByteBuffer): GeocoderIndex` (companion, :424). No other change; the class body is already pure NIO.
   - `SearchEngine.kt`: constructor becomes `class SearchEngine(private val index: GeocoderIndex, private val clockMs: () -> Long = { SystemClock.elapsedRealtime() }, private val logTiming: (String) -> Unit = { Log.d(TAG, it) })`; replace the 2 SystemClock uses with clockMs() and the 1 Log.d with logTiming(...). Defaults are byte-for-byte identical to current behavior. JVM tests MUST pass explicit clockMs/logTiming (android.util.Log must never resolve on the JVM).
   - New file `app/src/main/java/com/organicmoto/maps/geocoding/GeocodeController.kt`: `interface GeocodeController { suspend fun search(query: String, limit: Int = 8): List<GeocodeResult> }`. `GeocodeSearchController` implements it. Change `GeocodeSearchField`/`RoutePlanSearchField` param types to `GeocodeController`. RouteScreen still constructs `GeocodeSearchController` (no rename).
6. New JVM tests (all `app/src/test/java/com/organicmoto/maps/...`):
   - `routing/MotorcycleProfileTest.kt`: `profileVersionMatchesStoredGraph` — `motorcycleProfile().name == "motorcycle"` and `.version == 198752012` (the stored graph's ABI hash; if this fails, the extraction broke hint order — fix the extraction, NEVER change the constant); `hintsHaveCanonicalShape` — `hints.getObject("custom_model_files", emptyList<String>()) == listOf("motorcycle.json")` and no `custom_model` hint; `customModelMatchesCanonicalReference` — parse classpath `/com/graphhopper/custom_models/motorcycle.json` and `../tools/gh/motorcycle.json` (repo-root-relative via walk-up from `File(".")` to `settings.gradle.kts`; try `tools/gh/motorcycle.json` from there) with org.json, assert distance_influence and `priority`/`speed` arrays deep-equal (mirrors GenerateWeighting.sameModel); `helperClassIsPresentAndInstantiable` — `Class.forName("com.graphhopper.routing.weighting.custom.MotorcycleWeightingHelper")` loads and is not abstract.
   - `routing/RouteRequestsTest.kt`: detent 0 → profile "motorcycle", pathDetails == ["edge_id"], `hints.getBool(Parameters.CH.DISABLE, false) == true`, MOTO_COMPLEXITY == 0.0, no ALT_ROUTE algorithm, no MOTO_PREVIOUS_EDGES/MOTO_PREVIOUS_EDGE_PENALTY keys. detent 2 attempt 1 → algorithm ALT_ROUTE, MAX_PATHS == 20 (8+2*3+1*6), MAX_SHARE == min(0.98, share+0.20), MAX_WEIGHT == min(4.00, maxAlternativeWeight(2)+0.75), exploration factor == 1.2+2*0.15+1*0.75, min_plateau_factor == 0.10, MOTO_PREVIOUS_EDGE_PENALTY == previousRoadPenalty(share, 2, 1).
   - `routing/AlternativePolicyTest.kt` (synthetic ResponsePaths via `ResponsePath().setPoints(PointList()).setDistance(d).setTime(t)` + `addPathDetails(mapOf("edge_id" to listOf(PathDetail(edgeId).apply { setFirst(i); setLast(i) })))`): sharedRoadFraction identical→1.0 / disjoint→0.0 / partial→computed / empty→1.0; longestDistinctStretch consecutive-run and empty-set cases; isMeaningfullyDifferent threshold clamp(5% of distance, 750m, 3000m) boundaries + no-previous→true; withinGlobalDetourBudget detent1→1.50x time / 1.65x distance exact boundary accept/reject + detent≥5 caps at 2.00x/2.25x + reference time≤0→ratio 1.0; hasExcessiveLocalDetour <2 anchors→false, baseline>15000m ignored, extra≥1500m && ratio>2.25→true, boundaries; fallbackTier tier0-4 ordering cases; previousRoadPenalty/maxAlternativeWeight exact formula values.
   - `map/RouteCollectionTest.kt`: N features == routes.size; each feature LineString coords == path.points (lat,lon) list; `route_index` property == index; focused feature carries ROUTE_SELECTED_WIDTH/sortKey/opacity values, others the unfocused values; empty routes → empty FeatureCollection.
   - `geocoding/SyntheticGeocoderIndex.kt` (test helper, not a test): `fun buildIndex(docs: List<TestDoc>, terms: Map<String, List<Int>>): ByteArray` where `data class TestDoc(name: String, type: Int, subType: Int, rank: Int, latE7: Int, lonE7: Int, localityId: Int, city: String)` — writes little-endian bytes per tools/geocoder/README.md (header 0-28, `MAGIC = "OMGEO01\n"`, docsOffset == 32+4*docCount, absolute doc offsets, doc records with u16 name/token/city lengths, sorted unsigned-byte term dict entries with u16 len + relOffset + count, LEB128 delta posting ids relative to postingsOffset). MUST end with an assertion inside helper tests that `GeocoderIndex.parse` accepts the buffer.
   - `geocoding/GeocoderIndexParseTest.kt`: magic/version rejection (wrong bytes, too-small buffer, truncated header); header invariants (docCount≤0, localityCount>docCount, docsOffset mismatch, offset ordering); doc-offset out-of-range rejection; dict walk ending != postingsOffset rejection; overlong varint rejection; lookups on synthetic index: findTerm hit/miss, termRange prefix bounds, termAt, docType 0/1/2 + out-of-range type rejection, doc() field decoding round-trip (latE7/1e7, localityId, tokens).
   - `geocoding/GeocoderIndexCorruptionTest.kt`: seeded `Random(seed)` (seeds 1..50) mutations of a valid buffer — flip 1 byte / truncate at random offset in each section; for every mutant, `parse` + full lookup sweep must either succeed or throw ONLY `IllegalStateException` (assert exception type, never anything else, never hang — `@Test(timeout = 5000)`).
   - `geocoding/SearchEngineJvmTest.kt` (synthetic index, `clockMs = { 0L }`, `logTiming = {}`): exact-token match ranks above prefix/partial; blank and limit≤0 → empty; coordinate query "-27.4698, 153.0251" → exactly one COORDINATE result score 100.0; "27;153" (semicolon) → coordinate result (SearchEngine accepts semicolons — documented intentional difference from PointParser); pivot distance reorders equal-name candidates; Unicode corpus (emoji, CJK, Arabic RTL, combining marks, zero-width, newlines, 10k-char string) → no crash, deterministic per seed; seeded fuzz: 500 `Random(seed)` queries complete in < 5s and equal results across two runs with the same seed.
   - `routing/PointParserTest.kt`: valid — "-27.4698, 153.0251", "-27.4698 153.0251", "0,0", "-90,180", "90,-180", tab/newline separators → exact GHPoint values; invalid — "-91,153", "27,181", "NaN,NaN", "Infinity,0", ".", ",", "-", "27", "27,", ",153", "27;153" (semicolon REJECTED — assert exact error message), 3 fields, empty — each throws IllegalArgumentException with one of the 5 exact message forms from the source.
   - `geocoder-tool/src/test/kotlin/com/organicmoto/geocoder/tool/ParityTest.kt` (in the tool module): Text.kt copies — read both files, `readLines().drop(1)` equal (package line differs by design); PoiKeywords — `PoiKeywords.load()` parsed rules == embedded rules (the class is runnable in this module).
7. `routing/ComplexityWeightingTest.kt` + `routing/MotorcycleWeightingHelperTest.kt` (JVM, real GraphHopper fixtures — no fake weighting needed for the curve/fastest half):
   - Fixture (shared private helper in ComplexityWeightingTest): `val em = EncodingManager.start().add(SimpleBooleanEncodedValue("car_access", true)).add(EnumEncodedValue("surface", Surface::class.java)).build()`; `val graph = BaseGraph.Builder(em).create()`; nodes via `graph.nodeAccess.setNode(id, lat, lon)`; edges via `graph.edge(a, b).setDistance(d).setWayGeometry(PointList with tower + pillar points).set(accessEnc).set(true).setReverse(accessEnc).set(false).set(surfaceEnc).set(Surface.ASPHALT)` (setReverse for directional cases). `FakeWeighting` in test sources implements the 7-method `Weighting` interface with configurable per-edge weight/millis.
   - Tests: access==false (either direction) → calcEdgeWeight == POSITIVE_INFINITY at complexity 0 AND 2 (hard constraint); blockUnpaved + surface in UNPAVED_SURFACES → infinity, not for COBBLESTONE/PAVING_STONES; complexity 0 → w == fastest + reusePenalty only (custom difference ignored); positive complexity → w == wFastest + complexity*(max(0, wCustom-wFastest) + wFastest*1.5*straightness) + reusePenalty; previousEdgeIds edge adds wFastest*previousEdgePenalty; straight geometry (2-point or no-bend ≥3 points) → curve term == wFastest*1.5*1.0; 180°-per-km accumulated heading geometry → curve term ≈ wFastest*1.5*0.0 (tolerance 1e-6); reversing the SAME geometry (flipped PointList) → identical weight; tiny zero-length segments (duplicate consecutive points) → finite, no division blow-up; calcEdgeMillis == fastest millis; calcMinWeightPerDistance == fastest's. MotorcycleWeightingHelperTest: same fixture extended with `road_access`/`road_class`/`track_type`/`car_average_speed` encodings; `helper.init(CustomModel(), em, emptyMap())` then per-edge asserts: getSpeed caps (0.9*avg, 120, 30 for each rough surface in {COBBLESTONE, GRASS, GRAVEL, SAND, PAVING_STONES, DIRT, GROUND, UNPAVED, COMPACTED}, else min(0.9*avg,120)); getPriority zeroing (!car_access, track_type>1, PRIVATE, 0.1 for DESTINATION and MOTORWAY/TRUNK, else 1.0); getTurnPenalty == 0.

### Phase 2 — RouteSearchCoordinator extraction (single-flight supersession, deterministic race tests)

1. New file `app/src/main/java/com/organicmoto/maps/RouteSearchCoordinator.kt` (same package; NO Android imports; imports RouteResult, GHPoint, kotlinx.coroutines):
   - Move `RouteUiState` from RouteScreen.kt:167-171 here unchanged (Idle / Loading / Success(result, selectedIndex=0) / Error(message)).
   - `data class RouteParams(val from: GHPoint, val to: GHPoint, val complexity: Double, val maxRoadShare: Double, val blockUnpaved: Boolean, val preferredGeometry: String? = null)`.
   - `sealed interface RouteOutcome { data class Success(val result: RouteResult, val matchedIndex: Int) : RouteOutcome; data class Failure(val message: String) : RouteOutcome }`.
   - ```kotlin
     class RouteSearchCoordinator(
         private val scope: CoroutineScope,
         private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
         private val gate: Semaphore = Semaphore(1),
         private val backend: suspend (RouteParams) -> RouteOutcome,
     ) {
         private val _state = MutableStateFlow<RouteUiState>(RouteUiState.Idle)
         val state: StateFlow<RouteUiState> = _state.asStateFlow()
         var generation: Int = 0; private set
         private var job: Job? = null

         fun submit(params: RouteParams) {
             generation++
             val gen = generation // captured NOW, before launch — same as current prod
             job?.cancel()
             job = scope.launch {
                 _state.value = RouteUiState.Loading
                 val outcome: RouteOutcome? = withContext(ioDispatcher) {
                     gate.withPermit {
                         if (gen != generation || !isActive) return@withPermit null
                         try {
                             val res = backend(params)
                             if (gen != generation) null else res
                         } catch (e: CancellationException) {
                             throw e
                         } catch (e: Exception) {
                             if (gen != generation) null else RouteOutcome.Failure(e.message ?: "Routing failed")
                         }
                     }
                 }
                 if (outcome != null) {
                     _state.value = when (outcome) {
                         is RouteOutcome.Success -> RouteUiState.Success(outcome.result, outcome.matchedIndex)
                         is RouteOutcome.Failure -> RouteUiState.Error(outcome.message)
                     }
                 }
             }
         }

         fun selectRoute(index: Int) {
             val current = _state.value
             if (current is RouteUiState.Success && index in current.result.routes.indices &&
                 current.selectedIndex != index
             ) {
                 _state.value = current.copy(selectedIndex = index)
             }
         }

         fun invalidate() {
             generation++
             job?.cancel()
             // Loading implies the cancelled job owned the UI; otherwise keep the
             // displayed route (editing a field after a Success must NOT clear the map).
             if (_state.value is RouteUiState.Loading) _state.value = RouteUiState.Idle
         }

         fun publishError(gen: Int, message: String) {
             // Does NOT bump generation: mirrors current prod, where a resolution
             // error leaves an in-flight older search able to complete normally.
             if (gen == generation) _state.value = RouteUiState.Error(message)
         }
     }
     ```
     `gen` is captured inside submit() immediately after the increment, BEFORE `scope.launch` — a superseded job must compare against its own stale generation, never the current one.
2. RouteScreen.kt migration (exact list):
   - Delete: `routeState` MutableStateFlow (253), `state by routeState.collectAsState()` → `val state by coordinator.state.collectAsState()`; delete `routeGeneration` (288), `routingGate` (293), `routeJob` (296). Add after `scope`: `val coordinator = remember { RouteSearchCoordinator(scope, backend = { params -> routeBackend(params) }) }` where `routeBackend` is a local `suspend` lambda defined inside RouteScreen: try { `router.route(params.from, params.to, params.complexity, params.maxRoadShare, params.blockUnpaved)`; the existing timing/debug logging (started/SystemClock, debugLogs, TAG logs, RouteSimilarity.bestMatchIndex + TAG_SAVED log when params.preferredGeometry != null); `RouteOutcome.Success(result, matched ?: 0)` } catch CE rethrow / catch Exception → `RouteOutcome.Failure(e.message ?: "Routing failed")` }.
   - `submitRoute` (385-459) becomes: `activePlan = from to to; Log.i(TAG, "Route submitted...")` (keep the two existing submit logs, using complexityValue, fromText, toText); `coordinator.submit(RouteParams(from, to, complexityValue.toDouble(), roadSharePercent.toDouble() / 100.0, blockUnpavedRoads, preferredGeometry))`.
   - `selectRoute` (304-311): the existing lambda body moves into `coordinator.selectRoute` (see skeleton); RouteScreen keeps `val selectRoute = coordinator::selectRoute` + the existing `rememberUpdatedState` wiring.
   - Map click listener (315-331): `routeState.value` reads become `coordinator.state.value`.
   - `onRoute` (461-501): at entry capture `val gen = coordinator.generation`; resolution-error branch (480-489) → `coordinator.publishError(gen, e.message ?: "Routing failed")`; samePlace branch (495-497) → `coordinator.publishError(gen, "From and To are the same place")`. Keep the stale-text check (491) and CE rethrow. This closes the existing gap where resolution errors were NOT generation-guarded.
   - Endpoint edit handlers (601-621): replace `activePlan = null; routeGeneration++` with `activePlan = null; coordinator.invalidate()` — this also fixes the stuck-Loading bug (editing fields while routing left state Loading forever and START disabled, because no path reset the state), while leaving a displayed Success route intact.
   - All other call sites (dial release 637-645, settings apply 663-671, loadSavedRoute 561-568, saveProposedRoute) unchanged except they use `state`/`coordinator`.
3. New JVM tests `routing/RouteSearchCoordinatorTest.kt` (runTest + StandardTestDispatcher via `TestScope`; injectable gate + ioDispatcher; fake backend with per-call `CompletableDeferred<Unit>` latches and counters `calls: MutableList<RouteParams>`, `maxActive`, `active`):
   - `latestGenerationWinsWhenEarlyCompletes`: submit A (backend blocks on latchA), submit B (backend returns immediately); `advanceUntilIdle`; assert state == Success(B), B's result applied; open latchA → `advanceUntilIdle` → state still Success(B).
   - `staleCompletionDiscardedWithoutCancellation`: backend A wraps await in `withContext(NonCancellable)` (models the real non-cancellable router.route); submit A, submit B (immediate); B completes → Success(B); A returns Success(A) later → discarded (state stays Success(B)); assert gate never ran A and B concurrently (maxActive == 1).
   - `staleFailureDiscarded`: same but A fails → Error(A) never applied.
   - `invalidateResetsToIdle`: submit A (blocked) → Loading; `invalidate()` → state Idle; release A → advanceUntilIdle → state stays Idle.
   - `publishErrorStaleness`: capture gen, invalidate(), publishError(staleGen, "x") → state stays Idle; publishError(currentGeneration, "y") → Error("y").
   - `gateSerializesBackendCalls`: 5 rapid submits with instant backend; assert maxActive == 1, final state == Success(5th), calls == 1 (each submit cancels the previous before it runs — assert final call params == 5th params).
   - `invalidateKeepsDisplayedSuccess`: state == Success(B) (no job in flight) → `invalidate()` → state stays Success(B), generation incremented (endpoint edits must not clear a displayed route).
   - `preferredGeometryForwarded`: params.preferredGeometry == sent value.
4. Run the full JVM suite; existing RouteScreen compilation is the integration proof (UI migration must compile).

### Phase 3 — JVM invariant and integrity suites

1. `offline/OfflineGuaranteeTest.kt` (JVM; resolve repo root by walking up from `File(".")` to `settings.gradle.kts`):
   - `manifestDeclaresOnlyLocationPermissions`: parse `app/src/main/AndroidManifest.xml` (regex `uses-permission\s+android:name="([^"]+)"`) → exact set {ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION}; assert INTERNET and ACCESS_NETWORK_STATE absent.
   - `productionSourcesContainNoNetworkApis`: walk `app/src/main/java` and `app/src/main/kotlin`; assert zero occurrences of: `HttpURLConnection`, `okhttp3`, `retrofit2`, `java.net.URL`, `java.net.Socket`, `https://`, `http://`. NO allowlist — any match is a test failure and must be removed from production code (none are known to exist; assets and dependency jars are out of scope by construction).
2. `assets/StyleAndSpritesIntegrityTest.kt` (JVM, org.json): every string value in `app/src/main/assets/style.json` matches no `^https?://`; source `omt.url == "{tiles_path}"`; the source attribution contains "OpenMapTiles.org" and "OpenStreetMap"; every `asset://glyphs/{stack}/…` and `asset://sprites/…` URL has a corresponding file under `app/src/main/assets/`; every `text-font` value has a matching directory under `app/src/main/assets/glyphs/`.
3. `assets/PmtilesIntegrityTest.kt` (JVM): file + `.sha256` sidecar exist (else fail with "run tools/tiles/build-tiles.sh"); first 7 bytes == "PMTiles"; recomputed SHA-256 == sidecar content; size > 100_000_000 bytes (guards near-empty archives; current 268M).
4. `assets/GraphAndGeocoderAssetIntegrityTest.kt` (JVM): graph-cache contains files `nodes`, `edges`, `properties` and `properties` text contains `motorcycle|198752012` (else fail with "rebuild via tools/gh import"); map `app/src/main/assets/geocoder/geocoder.dat` with the same NIO approach as GeocoderIndex.parseFile and assert parse succeeds with docCount>0, termCount>0, localityCount>0; then SearchEngine (JVM, explicit clock/logger) on the real index: query "brisbane" → ≥1 result with a LOCALITY whose name lowercase contains "brisbane"; query "queen street" → ≥1 STREET result; query "fuel" → ≥1 POI result; query "-27.4698, 153.0251" → single COORDINATE result. Contingency: if a chosen query returns empty against the current extract, substitute an equivalent query verified via `./gradlew :geocoder-tool:run --args="--inspect app/src/main/assets/geocoder/geocoder.dat <term>"` and update the test with the verified term (the assertion categories — locality/street/poi/coordinate — must not change).
5. `storage/SavedRouteStoreContractAlignment` (prod + tests): change `SqlSavedRouteStore.insertComment` to pre-check route existence with `require(exists) { "Cannot comment on route $routeId: no such saved route" }` (IllegalArgumentException, matching the KDoc contract and the JVM fake); remove reliance on the FK exception path for this check (FK stays as backstop). Update `SqlSavedRouteStoreTest.insertingCommentOnUnknownRouteFails` to expect IllegalArgumentException. Add to BOTH JVM repository suite and instrumented SQL suite: `deleteUnknownRouteIsNoOp` (no throw, store unchanged); store-level `commentTextIsStoredVerbatim` (store does no trimming — trimming belongs to repository/UI); `twoSavesProduceTwoIndependentRows` (distinct IDs, both listed).
6. `storage/SavedRouteStoreTruthfulnessTest.kt` (JVM): `FailingSavedRouteStore` fake (insertRoute/insertComment/delete throw IllegalStateException) — `repository.save(...)` propagates the exception (assertFailsWith), `addComment` propagates, `delete` propagates. This is the API-level honesty proof: no path swallows storage failure into a success. (UI-level proof is Phase 5's SaveRouteBubble test.)
7. `storage/RouteSimilarityPropertyTest.kt`: identical route → score 0.0; corridor shifted by d km → score increases monotonically with d (test d = 0.1, 0.5, 1, 5 km — non-strict monotonic due to striding, assert each larger shift ≥ each smaller); reversed geometry (B→A) scores > 0 — encode as intended: direction matters; empty reference/candidate → MAX_VALUE / no match; one-point geometry → defined finite score; NaN coordinates → haversine yields NaN and `bestMatchIndex` never selects a NaN candidate when any finite candidate exists (if the current implementation behaves differently, encode the OBSERVED behavior as the contract and add a comment — invalid points must never be selectable over valid ones; that property is non-negotiable).
8. `PolylineCodecTest.kt` (extend existing): antimeridian-adjacent deltas, lat ±90 / lon ±180 exact grid round-trip, 200 seeded `Random(7)` geometries within 5.1e-6 degrees.
9. Run full JVM suites (app + geocoder-tool).

### Phase 4 — Instrumented core suites (device + real graph + real assets)

Corpus calibration rule (applies to steps 1-2): implementer first writes `RouteCorpus.kt` entries with the listed coordinates and placeholder baseline fields, runs `RouteCorpusTest.calibrationProbe` (a @Test that routes every pair at detent 0 and prints `distance_meters`, `duration_ms`, route count, and whether ≥2 candidates appear at detent 2), records the printed values into the entries as `baselineKm`/`baselineHours`/`alternativesExpected`, then deletes the probe or marks it `@Ignore` with a comment "re-run after graph rebuilds to recalibrate". Bounds in assertions are: distance ∈ [0.5×, 2.5×] baseline; duration ∈ [0.4×, 4×] baseline. Pairs (coords are approximate; refine via geocoder if snapping fails — keep the corridor intent):
1. Brisbane CBD (-27.4698, 153.0251) → Mount Glorious (-27.3353, 152.7720)
2. Brisbane CBD → Cairns (-16.9203, 145.7710) [long]
3. Samford (-27.3727, 152.8864) → Brisbane CBD
4. Toowoomba (-27.5598, 151.9507) → Warwick (-28.2167, 152.0333)
5. Gympie (-26.1900, 152.6650) → Noosa (-26.3980, 153.0600)
6. Tamborine Mountain (-27.9797, 153.1878) → Springbrook (-28.2270, 153.2700)
7. Mount Isa (-20.7253, 139.4927) → Townsville (-19.2589, 146.8169) [long remote]
8. Charleville (-26.4017, 146.2422) → Longreach (-23.4420, 144.2490) [remote]
9. Brisbane CBD → Toowoomba
10. Cairns → Port Douglas (-16.4840, 145.4610) [coastal]
11. identical endpoints: Brisbane CBD → Brisbane CBD
12. out-of-region: Sydney (-33.8688, 151.2093) → Brisbane CBD

1. `routing/RouteCorpusTest.kt` (androidTest, plain camelCase methods):
   - `corpusRoutesAreValidAtEachDetent`: entries 1-10 × detents [0, 1, 2, 4] via `GraphHopperRouter(targetContext).route(from, to, detent.toDouble())`; per path: points.size ≥ 2; distance > 0; time > 0; all coordinates finite, lat ∈ [-90,90], lon ∈ [-180,180]; first point within 5.0 km of from and last within 5.0 km of to (haversine via RouteSimilarity.haversineMeters); distance/duration within the calibrated bounds; `PolylineCodec.decode(encode(path.points.toGeoPoints()))` round-trips to ≥2 points within 5.1e-6 degrees.
   - `identicalEndpointsAreRejectedCleanly`: entry 11 → assertThrows IllegalStateException (detent 0 "No route was found" or "Routing failed: ..."); no crash, no hang (timeout 120s).
   - `outOfRegionIsRejectedCleanly`: entry 12 → assertThrows IllegalStateException with message containing "Routing failed".
   - `positiveDetentsKeepRoutesDistinctWhenMultipleCandidatesExist`: entries flagged `alternativesExpected` at detent 2: when routes.size ≥ 2, pairwise `AlternativePolicy.sharedRoadFraction(AlternativePolicy.cachedRoute(a).edgeDistances, ...(b)...) < 0.999` and each path drawable (≥2 points).
   - `detentResultsAreCachedAndDeterministic`: same params twice → identical distance/time for every route.
   - `savedGeometryMatchingSelectsClosestCandidate`: route entry 1 at detent 2 → take result.routes, pick candidate k>0 when available, encode its geometry; `RouteSimilarity.bestMatchIndex(decoded, candidates)` == k (validates the restore-selection math against real candidates).
2. `routing/GraphCopyTest.kt` (androidTest; prod change: `copyGraphFromAssetsIfNeeded()` private → internal in GraphHopperRouter.kt, one word). Tests operate on `File(targetContext.filesDir, "gh-cache")`, sibling `gh-cache.tmp`, marker `.copy-complete`:
   - `cleanCopyCreatesMarkerAndGraph`: delete both dirs → call → gh-cache non-empty + marker exists.
   - `secondRunDoesNotRecopy`: after clean copy add sentinel file to gh-cache → call → sentinel survives (marker short-circuit).
   - `leftoverStagingIsDiscarded`: valid gh-cache + leftover gh-cache.tmp → call → final dir untouched (marker present), tmp gone.
   - `missingMarkerForcesRecopy`: gh-cache without marker → call → replaced (sentinel gone).
   - `routeAfterFreshCopyLoadsGraph`: delete gh-cache (+marker) → route entry 1 → Success (full copy+load on device). First run copies 110M — timeout 300s.
   - In-process kill-mid-copy cannot be simulated; atomicity is asserted structurally (staging dir name used, marker written last, rename into place). State this in a test comment, not an assertion.
3. `map/OfflineMapSmokeTest.kt` (MainActivity rule, `createAndroidComposeRule<MainActivity>()`): `waitUntil` MapReadyKey semantics == true (timeout 180s — first launch copies 268M tiles); assert attribution Text node exists ("© OpenMapTiles.org © OpenStreetMap contributors · Noto (OFL) · icons CC BY 4.0"); pan via `performTouchInput { swipeLeft(); swipeRight() }` on the map box → attribution still exists, no crash; zoom via ZoomPill clicks → no crash. This is the offline-render runtime proof; run-all.sh runs this class inside airplane mode.
4. `map/CarouselMapConsistencyTest.kt` (MainActivity rule): route corpus entry flagged alternativesExpected at detent 2 (type coordinates into both fields — coordinate strings avoid geocoder flakiness — tap START, waitUntil RouteUiStateKey == "success", timeout 300s); read root Column semantics (SelectedRouteKey, RouteCountKey) and map Box semantics (FocusedRouteIndexKey, MapRouteCountKey) → equal pairs; swipe carousel left (performTouchInput swipeLeft on the pager node) → waitUntil settled (SelectedRouteKey changed) → semantics pairs equal again; assert status-slot text equals the selected card's `contentDescription` "Route N: <metrics>" metrics substring (parse both strings and compare).
5. `storage/SqlSavedRouteStoreTest.kt` updates per Phase 3 step 5.
6. `geocoding/GeocoderOnDeviceTest.kt`: `GeocoderIndex.load(targetContext)` succeeds with docCount>0 (real asset copy path); `corruptCacheRecoversFromAssets`: write garbage to `filesDir/geocoder/geocoder.dat` → load → succeeds and file fingerprint == BuildConfig.GEOCODER_SHA256; `controllerSearchReturnsLocality`: `GeocodeSearchController(targetContext).search("Brisbane")` → ≥1 result, first result type LOCALITY or name contains "Brisbane" (case-insensitive).
7. Run: `./gradlew :app:assembleDebug`, boot AVD, `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.organicmoto.maps.routing.RouteCorpusTest,com.organicmoto.maps.routing.GraphCopyTest,com.organicmoto.maps.map.OfflineMapSmokeTest,com.organicmoto.maps.map.CarouselMapConsistencyTest,com.organicmoto.maps.geocoding.GeocoderOnDeviceTest` (comma-separated class list form).

### Phase 5 — Compose UI, lifecycle, accessibility (instrumented)

All MainActivity-rule tests must tolerate the pre-warmed first launch (run-all.sh pre-warms). Tap retry discipline per AGENTS.md: no loop-retrying taps beyond 3 attempts; prefer semantics and waitUntil.

1. `ui/PlannerPanelTest.kt`: START present and enabled in Idle; type invalid text in From + valid To → START → Error text visible in status slot, START re-enabled; 5 rapid START taps with empty fields → Error each time, no crash, state never stuck at "loading"; valid coordinates both fields → START → RouteUiStateKey "loading" observed (waitUntil) → "success" within 300s (first route includes graph copy); START bounds stay within window and its bottom edge above the navigation-bar inset (boundsInRoot check), unchanged between Idle and Success (fixed-panel invariant).
2. `ui/ComplexityDialTest.kt`: locate knob via `hasContentDescription("Ride complexity level N", substring = true)`; circular drag 1/8 turn (down at knob center, move along 45° arc, up) → contentDescription "Ride complexity level 1"; counter-clockwise drag → level 0 (hard stop, no negative); multi-turn drag (2 full revolutions + 1 click) → level 17; `scenario.recreate()` → knob retains level (rememberSaveable); after a Success at level 0, one clockwise click → new Success with RouteGenerationKey incremented (waitUntil).
3. `ui/SaveBubbleTest.kt` (createComposeRule + setContent { SaveRouteBubble(visible=true, metrics="1 h · 45 km", onSave={true}, onDismiss={}) }): click "Save route" → text "Saved" appears → after mainClock advance 900ms bubble hidden (onDismiss called); with onSave={false} → "Couldn't save this route" shown, bubble stays, click again retries (callback counter == 2).
4. `ui/SavedRoutesSheetTest.kt` (createComposeRule + setContent with fake suspend providers): comment add success → draft cleared, comment listed; comment provider throws → no crash, draft preserved, add button re-enabled (submittingComment reset in finally); typing 600 chars → field holds exactly 500 (MAX_COMMENT_CHARS enforced in UI); delete confirm calls provider with the route; delete provider throws → no crash, sheet stays open, summaries refreshed. PROD CHANGE (same phase): wrap the delete coroutine in SavedRoutesSheet (RouteScreen's `onDeleteRoute` await site) in try/catch that rethrows CancellationException and logs others (Log.e under TAG_SAVED), leaving pendingDelete already cleared and calling refreshSummaries() in finally — currently a SQLite delete failure would crash the app.
5. `ui/SearchDebounceTest.kt` (createComposeRule + RoutePlanSearchField with FakeGeocodeController implementing GeocodeController, recording queries and per-query `CompletableDeferred` result gates; `composeTestRule.mainClock.autoAdvance = false`): type "M","o","u","n","t" advancing 300ms per keystroke → only "Mount" queried; stale-result race: first query's deferred completes AFTER second's → results show the second query's results and the first's never render (cancellation); blank input → no results view.
6. `ui/LifecycleRecreationTest.kt` (MainActivity rule): with valid coords typed + START → during "loading": `scenario.recreate()` → fromText/toText restored, state "idle" (route not restored — documented contract), START enabled, no duplicate dialogs; after Success + knob level 3 → recreate → texts + level 3 restored, state "idle"; SavedRoutesSheet open → recreate → closed, no crash; RouteSettingsDialog open → recreate → closed.
7. `ui/AccessibilityTest.kt`: asserts existence of contentDescriptions: "Ride complexity level N" (knob), "Maximum shared roads N percent", "Block unpaved roads", "Save route", "Delete saved route", "Add comment", "Saved routes", "Route settings", "Route N: <metrics>" (cards), and the Phase-0 field/result-row descriptions; touch targets ≥ 48.dp bounds for START, Saved routes, Route settings, knob, cards (Phase 0 added minimumInteractiveComponentSize where needed).
8. `ui/FontScaleTest.kt` (createComposeRule + setContent { CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 2f)) { MaterialTheme { RouteScreen() } } } — enabled by Phase 0's MapLibre.getInstance in rememberMapView): START visible + has click action at fontScale 2.0; attribution text visible; type invalid From → Error text node visible within window bounds. Also run the same assertions at fontScale 1.3.
9. `ui/SavedRouteFlowTest.kt` (MainActivity rule, end-to-end): type coords → START → wait "success" → long-press the selected card (performTouchInput longClick on "Route 1" node) → bubble appears → click "Save route" → "Saved" appears; open Saved routes (semantics "Saved routes") → row with from/to names exists; add comment "test note" → listed; delete route (confirm dialog "Delete saved route?") → row gone; re-save → load it (tap row) → wait "success" → From/To fields show saved names, knob level equals saved complexity (0), status shows metrics (restore fidelity).

### Phase 6 — Fuzz framework, memory, perf (instrumented)

Files under `app/src/androidTest/java/com/organicmoto/maps/fuzz/`:

1. `FuzzActions.kt`: `sealed interface FuzzAction` — `Click(target: UiTarget)`, `LongClick(target)`, `TypeText(target, value: String)`, `SwipeCarousel(direction: Direction)`, `RotateKnob(detents: Int)`, `TapStart`, `Back`, `OpenSavedRoutes`, `ToggleSettings`, `PanMap(direction: Direction)`, `BackgroundForeground`; `enum class UiTarget { FROM_FIELD, TO_FIELD, START, KNOB, CAROUSEL, MAP, SAVED_ROUTES, SETTINGS_COG, SAVE_BUTTON, CARD_0, CARD_1, CARD_2, COMMENT_FIELD, ADD_COMMENT, DELETE_CONFIRM }`; `class FuzzRandom(seed: Long)` wrapping `kotlin.random.Random` with `nextInt(bound)`, `nextAction(weights: Map<Class<out FuzzAction>, Int>)`, `nextText()` (from a fixed corpus: coordinates, place names, emoji, CJK, 10k-char string, punctuation, empty). All randomness derives from this — no `Random.Default` anywhere. Actions serialize via `FuzzActions.serialize(action): String` / `deserialize(string): FuzzAction` (used by FailureRecorder and replay).
2. `FuzzState.kt`: `data class StateFingerprint(stateName: String, generation: Int, selectedIndex: Int, routeCount: Int, complexityLevel: Int, fromText: String, toText: String, mapReady: Boolean, focusedRouteIndex: Int, mapRouteCount: Int, dialog: String?, sheetOpen: Boolean)`; `class StateExtractor(rule: ComposeTestRule)` reads the Phase-0 semantics keys + text fields (via editableText semantics) + presence of AlertDialog text; `CoverageTracker` maintains `Map<Pair<StateFingerprint, UiTarget>, Int>` and scores novelty per paste §30 weights (new state +10, new transition +8, unused action in state +5, new detent +5, repeat -10, identical recent action -5).
3. `FuzzOracles.kt`: hard oracles (fail run): `NoCrash` (implicit — any uncaught exception fails the instrumentation), `NoPermanentLoading` (stateName == "loading" across > 20 consecutive steps without generation change), `CarouselMatchesMap` (when routeCount > 0: SelectedRouteKey == FocusedRouteIndexKey && RouteCountKey == MapRouteCountKey), `NoDuplicateBlockingDialogs` (≤1 AlertDialog), `StartReachable` (START node exists + hasClickAction), `AttributionVisible` (attribution Text node exists). Soft oracles (recorded, not failing): `MapReadyBeforeRouteRender` (mapReady false while success — warns). `NoOnePointRoute` cannot be checked from semantics — covered by RouteCorpusTest; oracle omitted.
4. `FailureRecorder.kt`: on hard failure or run end writes `context.filesDir/fuzz/run-<seed>-<timestamp>/failure.json` ({seed, steps, actionSequence: List<String>, stateSequence, androidApi, generation, selectedIndex, error}) + `screenshot.png` via `InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()` + `semantics.txt` (unmerged tree dump).
5. `FuzzCampaignTest.kt`: `@Test fun runSeededCampaign()`; reads instrumentation args `fuzzSeeds` (comma list, default "42"), `fuzzSteps` (default 60), `fuzzFailFast` (default false). Per seed: fresh activity (recreate scenario between seeds), run steps: extract state → novelty-weighted action choice (30% random) → execute via composeTestRule (performTextInput/performClick/performTouchInput for swipes and knob arcs) → oracles → record. Collect all hard failures; `Assert.fail` with the report path at end if any.
6. `FuzzReplayTest.kt` + `FuzzMinimizerTest.kt`: `replayFailure(seedFile: String)` reads `app/src/androidTest/assets/fuzz_seeds/<seedFile>.json`, executes the action sequence in a fresh activity, asserts no crash. Minimizer (in FuzzMinimizerTest): greedy single-elimination — while any single action can be removed and the failure still reproduces (fresh scenario per trial, max 20 trials), remove it; write the minimized sequence next to the original; assert the minimized sequence still fails. Seed `app/src/androidTest/assets/fuzz_seeds/` contains one initial curated seed (type coords → START → rotate knob) checked in so replay runs on a clean clone.
7. `MemoryStressTest.kt` (instrumented): 15 sequential `GraphHopperRouter(targetContext).route(corpus[0], detent 1.0)` with `System.gc()` + Runtime heap sampling every 5; assert heap growth < 96 MB from baseline and no OOM; 50 repository save/delete cycles (SavedRouteRepository over SqlSavedRouteStore); 200 geocoder searches ("Brisbane", "Mount", "fuel", coordinates); final heap delta < 96 MB.
8. `PerfSmokeTest.kt`: cold route (fresh gh-cache) ceiling 240s; warm route ceiling 30s; geocoder first load ceiling 60s; query ceiling 3s; writes `filesDir/perf.json` {coldRouteMs, warmRouteMs, geocoderLoadMs, queryMs} for run-all.sh to pull. Hard-fail only above ceilings; no percentile infrastructure.

### Phase 7 — Orchestration scripts and documentation

1. `tools/test/preflight.sh` (executable, `set -euo pipefail`, JDK check: use `$JAVA_HOME` or `/opt/homebrew/opt/openjdk@17`; each failure prints the exact regeneration command):
   - assets: `app/src/main/assets/graph-cache/{nodes,edges,properties}` (→ "run the GraphHopper import: JAVA_HOME=/opt/homebrew/opt/openjdk@17 $JAVA_HOME/bin/java -Xmx12g -jar tools/gh/graphhopper-web-11.0.jar import tools/gh/config.yml; then cp -R data/graph-cache app/src/main/assets/graph-cache");
   - `app/src/main/assets/tiles/queensland.pmtiles` + sha256 match (→ "tools/tiles/build-tiles.sh");
   - `app/src/main/assets/geocoder/geocoder.dat` (→ "JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :geocoder-tool:run --args=\"data/queensland.osm.pbf app/src/main/assets/geocoder\"");
   - `app/src/main/assets/glyphs` + `sprites` (→ "tools/style/fetch-style-assets.sh");
   - run `./gradlew :geocoder-tool:run --args="--validate app/src/main/assets/geocoder/geocoder.dat"` and `--args="--check-sync"` and `tools/style/fetch-style-assets.sh --verify`.
2. `tools/test/ci.sh` — the ONE light CI tier (executable, `set -euo pipefail`, no emulator; intended to stay cheap enough to wire into any future CI):
   1. `tools/test/preflight.sh`;
   2. `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :geocoder-tool:test :app:testDebugUnitTest :app:assembleDebug`.
   This single gradle invocation runs every JVM suite (offline guarantee, asset/style/pmtiles/graph/geocoder integrity incl. real-index searches, profile-ABI parity, weighting math, coordinator races, storage truthfulness) plus the preBuild asset validators and proves the APK still assembles.
3. `tools/test/run-all.sh` — the single MANUAL full-suite trigger (executable, ordered):
   1. `tools/test/ci.sh` (the whole light tier first);
   2. emulator: `~/Library/Android/sdk/platform-tools/adb wait-for-device` then loop until `adb shell getprop sys.boot_completed` == 1 (boot via `~/Library/Android/sdk/emulator/emulator @<avd>` if no device);
   3. install + pre-warm: `adb install -r app/build/outputs/apk/debug/app-debug.apk` → `adb shell am start -n com.organicmoto.maps/.MainActivity` → sleep 180 → `adb shell am force-stop com.organicmoto.maps` (warms tile/graph/geocoder copies);
   4. offline proof: `adb shell cmd connectivity airplane-mode enable` → `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.organicmoto.maps.map.OfflineMapSmokeTest,com.organicmoto.maps.routing.RouteCorpusTest` → `adb shell cmd connectivity airplane-mode disable`;
   5. full instrumented suite + fuzz: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.fuzzSeeds=42,1337 -Pandroid.testInstrumentationRunnerArguments.fuzzSteps=120`;
   6. pull reports: `adb shell run-as com.organicmoto.maps ls files/fuzz` then `adb exec-out run-as com.organicmoto.maps cat files/fuzz/...` into `build/test-report/fuzz/`; same for `files/perf.json`; aggregate `app/build/test-results/**/*.xml` with jq into `build/test-report/summary.txt` (counts, failures, fuzz states discovered).
4. `tools/test/process-death.sh`: `adb shell am kill com.organicmoto.maps` → relaunch → `adb shell uiautomator dump` → print From/To field contents and knob level for manual verification (documented pre-release check; no automated assertions).
5. `AGENTS.md` updates:
   - Rewrite the "Tests exist for the route-storage layer only" paragraph into the full suite inventory with run commands (`tools/test/ci.sh` = light CI tier; `tools/test/run-all.sh` = manual full trigger that includes the CI tier; per-suite gradle invocations, fuzz instrumentation args, airplane-mode step); correct the stale build facts (wrapper 9.5.0, AGP 9.3.1, Kotlin 2.2.10); document the new seams (RouteSearchCoordinator, GeocodeController, UiSemantics keys) as test/observability seams; restate the backtick-naming rule for all new androidTest files.
   - Add a LIVING SUITE mandate paragraph: this test suite is permanent repository infrastructure, not a one-off deliverable. Every new feature, UI element, routing/geocoder/storage logic change, and generated-data pipeline MUST land with tests at the same rigor as this suite. Concrete mapping reviewers must enforce: pure logic → JVM invariant/property tests in the matching package; Android-bound behavior → instrumented tests; route-visible changes → corpus entry added and recalibrated (Phase 4 rule); new composables/controls → contentDescription semantics plus PlannerPanel/Accessibility/oracle coverage; weighting or custom-model changes → MotorcycleProfileTest parity + ComplexityWeightingTest math updated together; storage schema changes → BOTH store contract suites extended; new generated pipelines → an integrity test + a preflight.sh check; every fuzz-discovered bug → deterministic regression test or checked-in seed before the fix is called done.
6. Final gate: both tiers green on this machine — `tools/test/ci.sh` completes with 0 failures without an emulator, then `tools/test/run-all.sh` (which re-runs the CI tier first) completes end-to-end (see Verification).

## Critical files & anchors

- `app/src/main/java/com/organicmoto/maps/RouteScreen.kt` — all UI state, submission (:249-459), endpoint handlers (:599-621), dial/settings call sites (:631-671); the coordinator migration and semantics seams land here. Re-read :240-501 before editing.
- `app/src/main/java/com/organicmoto/maps/routing/GraphHopperRouter.kt` — profile builder (:154-180), request construction (:286-330), policy functions + constants (:437-651), `copyGraphFromAssetsIfNeeded` (:183-222, private→internal). Re-read before extracting.
- `app/src/main/java/com/organicmoto/maps/routing/ComplexityWeighting.kt` — constructor :71-80, calcEdgeWeight :84-114, straightness :136-159, STRAIGHT_ROAD_PENALTY :165-166; the fixture tests must match this math exactly.
- `app/src/main/java/com/organicmoto/maps/geocoding/GeocoderIndex.kt` — parse/lookups (:29-283), companion load/copy (:284-492); `parse` visibility change only.
- `app/src/main/java/com/organicmoto/maps/geocoding/SearchEngine.kt` — constructor + search() (:48-67); clock/logger injection points.
- `app/build.gradle.kts` + `gradle/libs.versions.toml` — all dependency additions (Phase 0).

## Verification

Per-phase gates (each phase ends with the green build/suite commands listed in its step 6/7/9):

1. Phase 1: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:testDebugUnitTest :geocoder-tool:test` — new tests include `profileVersionMatchesStoredGraph` passing (asserts `version == 198752012`) and the synthetic-index tests passing.
2. Phase 2: same command; `RouteSearchCoordinatorTest` passes all 7 deterministic race tests; `:app:assembleDebug` compiles the migrated RouteScreen.
3. Phase 3: same command; `OfflineGuaranteeTest` (manifest + source scan) green; `GraphAndGeocoderAssetIntegrityTest` searches the real 27M index on JVM (locality/street/poi/coordinate results non-empty).
4. Phase 4: booted AVD + `connectedDebugAndroidTest` for the 5 named classes: corpus passes all pairs × 4 detents; GraphCopyTest atomicity cases pass; OfflineMapSmokeTest passes (and, via run-all.sh, passes with airplane mode ON — the offline-runtime proof); CarouselMapConsistencyTest proves selected card == focused map route.
5. Phase 5: connected runs for ui/ classes: dial drag → "Ride complexity level 1"→17 + recreate retention; SaveBubble "Saved"/"Couldn't save this route" states; sheet 500-char cap + delete-failure no-crash; debounce only-final-query + stale-drop; recreation contract; accessibility contentDescriptions + 48dp targets; fontScale 2.0 START reachable.
6. Phase 6: `FuzzCampaignTest` with 2 seeds × 120 steps → 0 hard failures, report JSON + screenshots in `files/fuzz/`; replay of the checked-in seed passes; minimizer shrinks a seeded failure; MemoryStressTest heap delta < 96 MB; PerfSmokeTest within ceilings + perf.json written.
7. Phase 7: both tiers green on this machine — `tools/test/ci.sh` completes with 0 failures and NO emulator running (light tier), then `tools/test/run-all.sh` (which re-runs the CI tier first) completes end-to-end; `summary.txt` shows unit + instrumented counts with 0 failures.

End-to-end acceptance (mapping of the spec's "definition of done" to evidence):
- offline routing/map/search with airplane mode ON → run-all.sh step 5 (OfflineMapSmokeTest + RouteCorpusTest under airplane mode);
- model/helper ABI cannot drift → MotorcycleProfileTest (version + statements) + GraphAndGeocoderAssetIntegrityTest (stored `motorcycle|198752012`) + preflight's `--check-sync`;
- invalid requests never produce undrawable geometry → RouteCorpusTest rejection cases + Phase 4 invariants;
- complexity objective math → ComplexityWeightingTest formula/geometry/access tests;
- detent alternatives distinct when topology permits → AlternativePolicyTest + RouteCorpusTest distinctness;
- curvy judged vs own detent optimum → withinGlobalDetourBudget/fallbackTier tests (reference = detent primary);
- CH never used for user routes → RouteRequestsTest CH.DISABLE assertion;
- no stacked searches / stale results → RouteSearchCoordinatorTest races + PlannerPanelTest rapid-tap + MemoryStressTest;
- save honesty → SavedRouteStoreTruthfulnessTest + SaveBubbleTest;
- restore selects closest candidate → savedGeometryMatchingSelectsClosestCandidate + SavedRouteFlowTest;
- carousel == map route → CarouselMapConsistencyTest + Fuzz oracle;
- graph copy atomicity → GraphCopyTest;
- geocoder cannot read OOB → GeocoderIndexCorruptionTest (only IllegalStateException ever escapes);
- lifecycle disruption coherent → LifecycleRecreationTest + fuzz BackgroundForeground/rotate actions;
- every fuzz failure has seed + replay → FailureRecorder + FuzzReplayTest + fuzz_seeds/.

## Assumptions & contingencies

- Assets are present locally (measured). On a clean clone: run `tools/test/preflight.sh`; asset-dependent JVM tests fail with remediation text by design (documented in AGENTS.md update).
- Emulator tap flakiness: per AGENTS.md, no tap-retry loops beyond 3 attempts; prefer semantics + waitUntil. Fuzz uses semantic actions; PanMap/SwipeCarousel use touch input on node bounds (not screen coordinates).
- Corpus calibration rule (Phase 4) is self-calibrating; if a listed pair cannot snap or routes empty, refine the coordinates via the geocoder and re-probe — the corridor intent (urban→mountain, long regional, remote, coastal) must be preserved.
- Contingency (Phase 1 profile test): if `version == 198752012` fails, the extraction changed hint order or model statements — restore verbatim order; NEVER change the constant (the bundled graph stores it).
- Contingency (geocoder queries): if a Phase 3 query returns empty on the current extract, substitute a verified term via `--inspect`; the result-category assertions must not change.
- Contingency (dependency conflict): if androidx.test:rules 1.6.1 conflicts with runner 1.6.2, align rules to the version runner 1.6.2 pulls transitively (check with `./gradlew :app:dependencies`), update the catalog, re-run Phase 0 build check.
- Contingency (Compose semantics in androidTest): custom SemanticsPropertyKeys defined in main are readable from androidTest; if the merged (not unmerged) tree hides them, read via `onRoot(useUnmergedTree = true)` and set `mergeDescendants = false` on the annotated nodes — the UiSemantics attach points are chosen to avoid merging collisions.
- First connected run is slow (graph 110M + tiles 268M copies); run-all.sh pre-warms. Individual connected test classes should be run with the app already installed + launched once.
