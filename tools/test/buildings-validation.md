# Transparent offline buildings: validation

Branch: `B-Kingston/transparent-3d-buildings`, based on `60d3a69`.
All implementation and generated-asset copies are in this worktree. The source
checkout's code and unrelated work were not modified. No push or merge.

## Behavior and scope

- Normal local style: flat footprints from z13; translucent 3D from z14 through
  vector overzoom. Neutral `#918b82` sides/roofs, 28% extrusion opacity, vertical
  lighting gradient. Ground opacity falls from the original 90% at z13 to 45%
  at z14 so close-up boxes do not become heavy opaque masses.
- `omt` / `building`, `render_height` / `render_min_height`. Missing or
  nonnumeric height/base defaults to 5 m / 0 m; heights are bounded to 0–400 m,
  bases to 0–height. Only boolean `hide_3d=true` suppresses extrusion.
- Existing planning/follow/heading/tilt/speed-zoom policies are unchanged.
  The minimal B&W ride style intentionally has no buildings.
- No dependencies, permissions, network URLs, data-generation inputs, routing
  weights or graph changes. PMTiles remains separately installed, not in the APK.
- Uniform transparency was sufficient in the inspected city silhouettes;
  no GPS-driven building mutation/filtering or dishonest elevated route was added.

## Isolation

Dedicated config-only AVD: `TransparentBuildings`. Its mutable disks/config live
under `build/isolated-avd/TransparentBuildings.avd`; only the installed immutable
system image is reused. The other running `Pixel_10_Pro` / `emulator-5554` was
not controlled, installed over, stopped or attached to.

Owned instance command (first confirm the AVD/ports are free):

```sh
~/Library/Android/sdk/emulator/emulator -avd TransparentBuildings \
  -port 5580 -grpc 8580 -no-snapshot -no-audio -no-window
```

All device operations use `-s emulator-5580` or explicit `ANDROID_SERIAL`.
JDK 17 `jdb` attached only through that serial's `--no-rebind tcp:8780` JDWP
forward to its app PID; thread inspection succeeded, debugger exited and its
forward was removed. Evidence: `build/isolated-avd/debugger.log`.
After final validation, airplane/rotation settings were verified restored and
only `emulator-5580` was shut down. The dedicated AVD is retained for replay.

## Repeatable verification

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_SERIAL=emulator-5580 tools/test/ci.sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_SERIAL=emulator-5580 \
  ./gradlew :app:assembleDebugAndroidTest
ANDROID_SERIAL=emulator-5580 tools/test/visual.py --serial emulator-5580 \
  --output-dir build/visual-buildings buildings --no-build
```

Instrumented APKs were installed explicitly with `adb -s emulator-5580 install
-r`, then tests run using `adb -s emulator-5580 shell am instrument -w -r -e
class ... com.organicmoto.maps.test/androidx.test.runner.AndroidJUnitRunner`.
Targeted classes: `BuildingRenderingTest`, `NavigationHudTest`,
`NavigationSessionOnDeviceTest`, `OfflineMapSmokeTest`, plus
`RouteCorpusTest#fastestRoutesMatchCommittedGoldBaselines`. Routing/navigation
regressions ran with airplane mode enabled, restored afterward.

The added CBD corridor (-27.4698,153.0251 → -27.4570,153.0350) was measured on
the installed real graph: **2,431.4543 m / 238,865 ms**. Its gold baseline is
committed and its full guidance replay asserts upcoming turns and arrival.

## Evidence and interpretation

Final results:

| Check | Result | Log/artifact |
| --- | --- | --- |
| Asset preflight + JVM suites + `assembleDebug` (`ci.sh`) | PASS: 217 app + 2 geocoder JVM tests | `build/buildings-ci-verified.log` |
| `assembleDebugAndroidTest` | PASS | `build/building-test-build-verified.log` |
| Targeted Android rendering/navigation/gold suites | PASS: 12 tests, airplane mode | `build/buildings-instrumented-final.log` |
| `visual.py buildings --no-build`, explicit owned serial | PASS: 45 screenshots, airplane mode | `build/visual-buildings-evidence.log` |
| Runner syntax, explicit-serial safety gate, `git diff --check` | PASS | command output |

Final screenshot directory:
`build/visual-buildings/20260930T142720Z-42836/`.
Representative inspected PNGs (relative to that directory):

- `02-buildings-flat-overview.png`, `03-buildings-below-threshold.png`,
  `04-buildings-threshold.png`, `06-buildings-city-towers.png`.
- `07-buildings-residential-houses.png` (small box homes near James/Hawthorne Streets).
- `16-buildings-city-navigation-speed-0.png` through
  `20-buildings-city-navigation-speed-32.png` (all five zoom bands).
- `21-buildings-route-silhouette-45.png`, `22-buildings-route-silhouette-225.png`.
- `24-ride-map-black-and-white-on.png`, `25-ride-map-black-and-white-off.png`
  (normal buildings, orange route and rider successfully return).
- `34-buildings-landscape-navigation.png`, `35-buildings-landscape-end-reachable.png`
  (197 m George Street guidance, route/rider, HUD and END remain readable/reachable).
- `45-buildings-relaunch-installed-map-houses.png`.

Screenshots were actually opened and inspected, not just generated. Native
rendering assertion PNGs from the final test run are also copied to
`build/building-rendering-pixels-final/`.

- JVM expression tests evaluate the actual shipped filter/height/base JSON,
  including missing/false/true hide flags, houses, towers, bad types, negative
  and excessive dimensions, and 1,000 seeded dimension invariants.
- Native snapshots compare the same pitched camera with extrusion shown/hidden
  for actual tall CBD features and actual 5 m residential features. This is
  pixel evidence, not merely a successful tile query. A controlled renderer
  fixture keeps live GPS from replacing the deliberately positioned overlays.
- Native route/rider snapshots at z14/16/18 cross projected building faces.
  Both orange selected-route ink and blue rider ink are asserted. PNGs:
  `build/building-rendering-pixels-final/`.
- Real navigation screenshots cover all five speed/zoom bands, opposing CBD
  silhouette views, moving progress, an upcoming turn, guidance HUD and END,
  B&W → normal return, landscape END, and house visibility after relaunch.
- The runner waits for native basemap/rider features and route source/layers after style switches,
  refreshes the same replay fix across reloads, and then saves screenshots for
  actual pixel inspection. Queries are a readiness gate, not visual proof.
  Pitched line queries sometimes returned zero even for clearly visible orange
  routes; route readiness therefore checks source/layer installation, while the
  native pixel assertions and inspected screenshots prove the actual route ink.

Earlier inspection exposed the runner's 20 s debug-fix authority expiring while
UI trees/screenshots were collected: the emulator's parked California fix could
pull the camera outside the Queensland archive. The runner now mirrors replay
fixes to this owned emulator's GPS provider and restores its parked position.
The app's production GPS policy was not altered to hide that test-fixture issue.

## Limitations

- This base recreates the planner on rotation; an active ride is not persisted.
  Landscape verification explicitly starts a new ride and exercises END.
- Boxes and default heights are approximate OSM representations, not surveyed
  roof models. Building coverage depends on the installed OSM-derived tiles.
- Inspected AVD views demonstrate readability for sampled tall/house/route
  silhouettes, not a universal occlusion guarantee on every device/viewpoint.
  Physical-device/GPU coverage and the full emulator fuzz/performance campaign
  were not run for this style-only change.
