# curveMaps

Offline-first motorcycle routing for Android.
Kotlin + Jetpack Compose + MapLibre Android SDK (map) + GraphHopper core (routing).

The app makes **zero runtime network calls**. The map style, glyphs/sprites,
place-search index, and routing graph ship inside the APK. The much larger
PMTiles basemap is downloaded separately and imported with **Load map file**.

## Publish an APK to fdka

The project includes a `deploy-apk` skill for Codex in
`.agents/skills/deploy-apk/`. OMP uses the same skill through
`.omp/skills/deploy-apk/`. These files apply only to this project.
Start a new agent session to load the skill, then ask:
“Deploy this project's APK to fdka instance NAME.”

To publish an existing APK directly:

```sh
tools/publish-apk.sh NAME app/build/outputs/apk/debug/app-debug.apk
# Override the instance's configured endpoint with an SSH target:
tools/publish-apk.sh NAME app/build/outputs/apk/debug/app-debug.apk user@host
```

Install `fdka` (or `f-droideka`) and configure the instance first.
The command publishes the APK, runs `up` to update the endpoint, and prints
the repository URL and status. It stops if a command fails. It does not build
the APK, change signing keys, or install the APK on a device.
The skill tells the agent to choose increasing release versions, preserve
the app signing identity, and verify the served index, APK, and icon.

## Features

- **Offline basemap** — a separately distributed OpenMapTiles PMTiles archive,
  selected from the phone and rendered with the APK's local style.
- **Offline place search** — From/To fields use a prebuilt on-device geocoder
  index. The accessible current-location action in From uses a fresh device fix
  and requests location permission when needed; raw `lat,lon` entry still works.
- **Offline motorcycle routing** — GraphHopper with a motorcycle custom model,
  shipped as a pre-compiled weighting helper because Janino bytecode cannot load
  on ART.
- **Ride complexity dial** — an endless click-detent knob. Level 0 is the
  fastest route; each click adds one step of curve preference. Positive levels
  request genuinely different alternatives, softly penalizing roads used by
  lower levels, with a configurable maximum shared-road target (10–90%).
- **Ride media control center** — while riding, the data bar's far-left button
  opens a compact player panel above the bar: previous, explicit state-driven
  play/pause, next, phone-or-player volume, track info, optional player
  selection, and a close button. It uses the platform `MediaSessionManager`
  (no Spotify SDK, no privileged access, no network); the user grants
   notification access from an optional startup prompt (only when not already
   granted), and a clearly labelled limited
  system-media-key fallback covers session discovery being unavailable. When
  the playback state is genuinely unknown (no player, or a player that reports
  no state) the panel shows explicit Pause and Play controls instead of a
  toggle whose label would have to lie. System Back closes the panel without
  leaving the ride. The panel closes 15 seconds after the last panel
  interaction; touch, scroll, and control actions restart its inactivity timer,
  while playback and volume observations alone do not. A white pie shows
  remaining time. The panel joins the square-topped data bar seamlessly, and
  the bar covers the system navigation area. Every control is a glove-sized
  target (64 dp; 80 dp for play/pause) with visible separation; the data bar lays
  out as a readable grid instead of shrinking them to an ellipsis on narrow
  screens or large font scales. Volume always works through the local music
  stream, and the panel follows the real readback when hardware keys change it
  while it is open.
- **Black-and-white ride map** — the moon toggle in the ride HUD switches the
  ride surface to a monochrome palette for night riding: solid grayscale
  streets (round caps/joins, one layer per drivable class including
  service/track roads, widths that keep growing from z14 to z18 where the
  separately distributed basemap stops) with no pedestrian-path or sidewalk
  clutter, readable street-name labels that stay upright in the 58° guidance
  tilt, and the white route line on its dark casing.
- **Startup location** — requests foreground precise or approximate location
  on first open, starts the offline location stream after approval, and centers
  the map on the first fix. Android retains a normal grant until it is revoked.

## Building

Requires JDK 17 and an Android SDK.

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.

The repository includes the Gradle wrapper and its wrapper JAR.

## Running and debugging from the CLI

`tools/android.sh` builds, installs, launches, and logs the app.

```sh
tools/android.sh run
```

If no Android device is online, `run` starts the `Pixel_10_Pro` emulator.
Set `ANDROID_AVD` to choose another installed emulator. Set `ANDROID_SERIAL`
to choose a connected device.

To build and launch the app, then stream its logs:

```sh
tools/android.sh debug
```

To stream logs from an app that is already running:

```sh
tools/android.sh logs
```

Press Ctrl-C to stop the log stream. This CLI shows app-process logs. It does
not attach a source-level debugger for breakpoints. The script reads the SDK
path from `ANDROID_SDK_ROOT`, `ANDROID_HOME`, or `local.properties`. It uses
Homebrew JDK 17 when `JAVA_HOME` is unset and that JDK is installed.

### Agent-driven visual routing

`tools/test/visual.py` boots or reuses the ADB emulator, installs the debug app,
and provides repeatable UI controls that leave the app open for inspection.
To route the default Brisbane CBD to Mount Glorious corridor and enter ride
mode:

```sh
tools/test/visual.py route \
  --from '-27.4698,153.0251' \
  --to '-27.3353,152.7720'
tools/test/visual.py current-location --to '-27.3353,152.7720' --plan-only
```

Use place names or coordinates, and set the route controls on the same command:

```sh
tools/test/visual.py route \
  --from 'Brisbane' \
  --to 'Mount Glorious' \
  --complexity 2 \
  --road-share 45 \
  --block-unpaved
```

The route command captures the launch, entered endpoints, routing progress,
ready route, and active ride. Screenshots and an event log go under
`build/visual-inspection/<run-id>/`; `build/visual-inspection/latest.png` is
updated after every capture. Add `--plan-only` to leave the route ready at the
planner. A subsequent run can reuse the built APK with `--no-build`.

Inspect the From selector's POI icon and search results with the keyboard open
and dismissed using `tools/test/visual.py search-results --query Brisbane`.
Use `--field To` for destination search. Results sit in a narrower, centered
card above the From/To block, with rounded top corners and a flush bottom
edge; longer lists scroll within the space above the keyboard. The runner
temporarily enables the on-screen keyboard even when a hardware keyboard is
connected, then restores the emulator setting. It waits for the known query
to finish and captures the query entry, complete results, and keyboard dismissal.

Set initial progress on the same command, or move an active ride later:

```sh
tools/test/visual.py route --from Brisbane --to 'Mount Glorious' --progress 50
tools/test/visual.py progress --percent 75
tools/test/visual.py route --from Brisbane --to 'Mount Glorious' --black-and-white both
tools/test/visual.py map-mode --black-and-white on
tools/test/visual.py icon-states
tools/test/visual.py nav-camera --from Brisbane --to 'Mount Glorious'
# Includes compact ride sound/B&W controls in both toggle states.
tools/test/visual.py nav-camera --from Brisbane --to 'Mount Glorious' --black-and-white both
tools/test/visual.py nav-camera --from '-27.4700,153.0250' --to 'Mount Glorious' --black-and-white on
```

Progress follows the selected route geometry through the live navigation
session. The runner captures intermediate route positions and the final frame,
and updates `latest.png` after each capture. Backward jumps restart
guidance at the route start and advance to the requested point. This requires a
debug build and an Android emulator; route geometry and the ADB control bridge
exist only in debug builds.

`nav-camera` starts a ride and captures the guidance camera as a repeatable
sequence. It captures navigation start, street-close framing, rider-lock
release while injected fixes advance, and relock at the latest rider position.
It also pans the locked map, captures the 1.5-second pause, and proves that
follow returns automatically in both map styles.
It then captures one frame per speed band (`--speed-cases`, default
`0,8,15,23,32` m/s, whose guidance zooms run z18 down to z14), an off-rider
inspection at a changed tilt with another lock cycle, a real heading turn
(`--turn-threshold`, `--turn-step`, `--turn-speed`), and the planning-camera
restore after END. Each step reads the debug camera probe and fails if the
settled camera misses the injected fix or does not match the expected zoom,
forward tilt, heading, or restore. The screenshots include assertions as well
as pixels. Queued debug fixes own the guidance session while they flow, so the
emulator's live provider cannot pull the camera away between injections. The
runner uses accessible text controls (RIDE/END and the lock button) and the
existing debug fix bridge. It needs an emulator and a debug build.

During rides, the right-side pill contains sound settings and the B&W toggle.
Each unchanged-size icon has a 64 dp touch target. The directions card is
narrower, and the bottom ride bar has matching space above and below its
content, including the system navigation inset.

The ride avatar uses the supplied rounded arrow with a rear notch. Its shaded
faces, centre ridge, side walls, and contact shadow suggest depth (2.5D).
The colour map uses blue. The B&W map uses grayscale with a dark outline to
separate the avatar from the white routed road. The avatar follows the road
heading but keeps its drawn depth as the camera tilts. Normal location tracking
keeps its existing disc marker.
`nav-camera --black-and-white both` captures both avatars over the road at the
street-close stop and during the speed and heading changes.

During guidance, the rider-lock button reports the current camera state. Tap it
to release or restore camera follow. A pan or two-finger tilt pauses follow until 1.5 seconds after the gesture
finishes, then restores the normal camera angle. The button stays locked
during that pause; tap it to release follow indefinitely. Route
tracking and the rider marker continue while the camera is unlocked. Relocking
uses the current rider position and guidance camera framing.

`--black-and-white on|off` sets the ride map style during a route run. Use
`both` to save screenshots of both styles for comparison. On `nav-camera`,
`both` re-captures **every** guidance frame in colour and B&W — the
street-close stop, each speed band's z14–z18 zoom, and the turn — writing
`-color` / `-bw` suffixed files (for example
`nav-camera-02-speed-8-color` and `nav-camera-02-speed-8-bw`), so the B&W
ride map's full-width white routed road, subtly dimmed surrounding streets,
and labels can be compared against the
colour map at identical camera states. `map-mode` changes
the style on an already active ride and saves a screenshot for each requested
state. `icon-states` captures the moon outline/fill, voice settings dialog, and
speaker enabled/muted states during an active ride, then restores both preferences.

Capture the planner's settings cog card, a screen opened from it, and their
dismissed states with:

```sh
tools/test/visual.py settings-menu
```

This saves `settings-menu-open`, `settings-screen-open`,
`settings-screen-back-to-planner`, and `settings-menu-dismissed` screenshots
under the normal visual-inspection run directory. Use `--no-build` to reuse the
existing debug APK, or `--skip-map` when no PMTiles archive is available.

Capture the regional package screen (server address, catalog, download/import
intermediate states, installed-map list with delete confirmation, and the
offline import control) with:

```sh
tools/test/visual.py maps-settings
tools/test/visual.py maps-settings --server https://maps.example.net
```

This saves `maps-settings-empty`, `maps-settings-address`, and
`maps-settings-back-to-planner`, plus deterministic intermediate states through
the debug Maps-preview bridge: `maps-preview-server-error` (dismissible,
actionable connection error), `maps-preview-catalog-ready` (a terminal build
job still shows Download), `maps-preview-build-failed` (Retry stays visible),
`maps-preview-download-progress`, `maps-preview-download-retrying` (automatic
resume countdown after a dropped connection), `maps-preview-download-verifying`
(SHA-256 check before the file is saved), `maps-preview-download-interrupted`,
`maps-preview-package-saved`, `maps-preview-recovered-package`,
`maps-preview-installed`, `maps-delete-confirmation`, `maps-delete-cancelled`,
and `maps-preview-removal-pending` (the active map switching to bundled data
while the routing graph drains). It only types an address into the field and
never taps Check server, so no map server, network access, or SAF picker is
required. The real download/import/delete lifecycle is exercised on device by
`MapsDownloadFlowOnDeviceTest` and `MapsSettingsScreenTest`.

Capture the ride media control center across its black-and-white player states
(playing, paused, no player, and notification-access needed) with:

```sh
tools/test/visual.py media-controls
```

This routes the default corridor, enters ride mode, and saves
`media-panel-color-playing`, `media-panel-bw-playing`,
`media-panel-bw-paused`, `media-panel-bw-no-player`,
`media-panel-bw-permission-needed`, `media-panel-dismissed-with-back`,
`media-panel-inactivity-full`, `media-panel-inactivity-half`, and
`media-panel-inactivity-closed` screenshots (the blank-panel touch restarts the
timer before the half frame; the final frame proves it closes after inactivity).
A denied-access startup also captures `00-media-access-startup-prompt` before
choosing **Not now**.
It drives the debug-only synthetic MediaSession broadcast
(`DEBUG_VISUAL_MEDIA_SESSION`) so a machine with no real media app can exercise
every state; release builds create no synthetic session. The runner records
the device's notification-listener access before it changes anything and
restores exactly that value in a `finally` — it never force-grants access.
Pass `--reuse-app` to reuse a running ride, or `--from`/`--to` to choose
another corridor.

For only the 15-second inactivity behavior, without synthetic player or map
style transitions, run the focused repeatable capture:

```sh
tools/test/visual.py media-controls --inactivity-only
```

It saves the same full, reset-halfway, and closed screenshots.

For step-by-step exploration, use `launch`, then call `tree`, `screenshot`,
`tap`, `type`, `key`, `swipe`, or `options` as needed. `watch` records changing
screens while another gesture or command runs. These commands use accessible
text and content descriptions, so the planning settings cog, route settings,
saved routes, voice options, GPX import, and other visible app controls can be
inspected and operated without fixed screen coordinates. Set `ANDROID_SERIAL` when more than one
device is online; set `ANDROID_AVD` to select another emulator.

### Transparent offline 3D buildings

The normal map keeps flat building footprints at zoom 13 and adds translucent
3D boxes at zoom 14 and above (including overzoom beyond the archive's z14).
Ordinary houses without an explicit height use 5 m; bases/heights are bounded
to prevent malformed features creating giant or inverted boxes. `hide_3d=true`
outlines stay flat. Planning remains flat, and riding keeps its existing tilt,
bearing and speed-dependent zoom. The minimal black-and-white ride map
deliberately has **no buildings**. No dataset rebuild or network access is needed.

Use an **exclusively owned AVD**, with distinct emulator/debugger ports. Never
run these examples against another agent's device. The `buildings` scenario
requires an explicit serial and does not auto-select or boot a device:

```sh
ANDROID_SERIAL=emulator-5580 tools/test/visual.py --serial emulator-5580 \
  --output-dir build/visual-buildings buildings
# Inspect another deterministic pitched view in a debug APK:
ANDROID_SERIAL=emulator-5580 tools/test/visual.py --serial emulator-5580 camera \
  --lat=-27.4616 --lon=153.0466 --zoom=18.5 --tilt=58
```

The scenario captures flat overview, both sides of the z14 threshold, CBD
towers, default-height residential houses near James/Hawthorne Streets,
a real CBD route at all five navigation zoom bands, opposing tower-silhouette
views, moving progress, B&W/normal switches, landscape navigation with END,
and installed-map reuse after relaunch. It runs in airplane mode and restores
airplane mode, rotation settings, initial location grants, parked GPS position
and ride appearance. Queued fixes also update the owned emulator's GPS provider
so a long UI inspection cannot fall back to its old parked location.
Style-switch captures wait for a debug-only, request-driven native probe to
find basemap/rider render features and the selected-route source/layers before saving pixels;
they do not assume that an accessibility toggle means tiles finished repainting.
This base resets navigation on rotation, so the bounded landscape check starts
a fresh ride rather than claiming session persistence. PNGs, accessibility
states and camera probes are emitted under the chosen output directory.
The `camera` command is a debug-only inspection seam, not a production control.

`BuildingStyleTest` evaluates the shipped height/base/filter expressions.
`BuildingRenderingTest` requires a seeded PMTiles map and compares native
snapshot pixels with extrusions shown/hidden for real towers and fallback-height
houses; it also checks route/rider ink at z14/16/18 and normal → B&W → normal
style reloads. Run `visual.py launch` on the explicit owned serial first, build
`:app:assembleDebugAndroidTest`, install its APK with `adb -s <serial>`, then:

```sh
~/Library/Android/sdk/platform-tools/adb -s emulator-5580 shell am instrument -w -r \
  -e class com.organicmoto.maps.map.BuildingRenderingTest \
  com.organicmoto.maps.test/androidx.test.runner.AndroidJUnitRunner
```

Rendered screenshots remain necessary: feature queries alone do not prove
visibility, and layer order alone does not guarantee 3D occlusion safety.
The CBD visual corridor also has a committed routing-corpus gold baseline and
an on-device full guidance replay (upcoming turns through arrival).
See [validation results and screenshot inventory](tools/test/buildings-validation.md)
for the isolated-AVD evidence and remaining limitations.

### Map performance benchmark (native frames)

`visual.py perf` measures the real offline map while the camera moves
continuously: it queues deterministic `easeCamera` sweeps through the debug
receiver, records each native `OnDidFinishRenderingFrame` timestamp, the
per-frame renderer counters, and the declared rendering backend, then writes
`build/brisbane-performance-report.md` plus one JSON per sweep under the
inspection run. It needs an explicitly owned serial and a debug build.

```sh
# Acceptance matrix: dense CBD at z14/z16/z18 and the houses area at z18.5,
# each cold (fresh process) and twice warmed, with settled building screenshots.
tools/test/visual.py --serial emulator-5554 perf \
  --targets cbd,houses --zooms cbd=14,cbd=16,cbd=18,houses=18.5 --repeat 2 --screenshots

# Extrusion diagnostics on one case, without touching shipped rendering.
tools/test/visual.py --serial emulator-5554 perf --targets cbd --zooms 18 \
  --building-modes current,opaque,hidden --repeat 2
```

Read the numbers with the semantics in the report: `fps` is the elapsed
frame-timestamp average, `nativeFps.harmonic` is the listener series after
discarding its first (pre-sweep idle) interval, `enc` is MapLibre's
`encodingTime` converted from seconds and on Vulkan includes
`Context::beginFrame` fence waits (encode+GPU-wait, not pure CPU), and
`upload KB/f` is the per-frame delta of the cumulative buffer-upload counter.
`windowFrameMetrics` describes the activity window, not the map SurfaceView.
A warm sweep with an interrupted leg or a settle timeout is reported INVALID,
never counted as a pass (a sweep cancelled mid-plan reports fewer legs than it
planned and is marked interrupted, so a canceled native camera leg can never be
counted as a completed benchmark).

The dense-CBD fix came from the drawable count, not from hiding content: the
POI layers' data-driven `symbol-sort-key` made MapLibre 13.5 emit roughly one
drawable per POI feature (~880 of 929 draw calls, ~1.2 s render-thread encode
per frame at CBD z18). The style now uses a bounded rank band, so priority
order survives while equal keys batch; `SymbolBatchingStyleTest` (JVM) enforces
that contract and `SymbolPriorityOnDeviceTest` proves the important labels
still win placement against the real archive. Measured before/after on the same
matrix — CBD z18: 0.8 → 59.9 fps (p95 1587 → 18.9 ms, zero warm stalls) on
debug OpenGL, and 1.0 → 46.3 fps on the Vulkan artifact; houses z18.5:
3.8 → 60.1 fps. CBD z14 remains the wide-view case at ~32 fps / p95 ~48 ms
(zero stalls over 100 ms): ~350 far-tile draw calls and their label uploads,
which is the whole frame at the pitched wide view. MapLibre's native camera
tile LOD (default pitch threshold 60°, inactive at this app's 58° camera) was
tried there and is deliberately **not** shipped — its zoom shift hid the wide
extrusions and the no-shift variant did not reproduce; the debug sweep runner
retains the override for experiments only. The release APK keeps the published
Vulkan backend; this emulator renders Vulkan in software (llvmpipe), so its
release-backend numbers are environment-bound, while debug builds use the
OpenGL artifact purely as emulator tooling. Merge runs with
`tools/test/visual.py perf-merge --labels before-opengl,after-opengl ...`.

The rendering backend comes from the MapLibre artifact, and it is real, not a
runtime setting: `android-sdk` is Vulkan-only and `android-sdk-opengl` is
OpenGL-ES-only (a runtime switch throws `UnsupportedOperationException`). Debug
builds default to OpenGL because the emulator rasterizes Vulkan in software
(`cmd gpu vkjson` reports llvmpipe) while its OpenGL ES path uses the host GPU;
release builds keep the published Vulkan default. Force either side with
`-PmaplibreBackend=vulkan|opengl`, for example to reproduce the baseline:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:assembleDebug -PmaplibreBackend=vulkan
```

Every sweep report names `backend.renderer` (the value
`RenderingEngine.getCurrentType()` actually initialized) and the loaded
MapLibre flavor/revision, so a number can never be attributed to the wrong
backend. The JVM `MapPerfSweepTest` and the on-device
`MapPerformanceOnDeviceTest` pin the report contract, the building-diagnostic
restore (verified during and after a sweep), cancellation/interruption
cleanup, and the backend declaration.

## Data pipelines (required before first build)

The generated assets are gitignored; a fresh clone has none of them until the
pipelines are re-run. All take an OSM `.osm.pbf` extract placed at
`data/queensland.osm.pbf`:

1. **Routing graph** — delete any old `data/graph-cache`, then import with
   `java -Xmx12g -cp tools/gh/graphhopper-web-11.0.jar tools/gh/MotoGraphImport.java import tools/gh/config.yml`
   (GraphHopper's import plus the lane-guidance parser) and copy
   `data/graph-cache` into `app/src/main/assets/graph-cache`.
2. **Basemap tiles** — `tools/tiles/build-tiles.sh` (Planetiler, needs JDK 21)
   writes `data/tiles/queensland.pmtiles`. Distribute that file separately;
   users download it to their phone and select **Load map file** in the app.
3. **Style glyphs & sprites** — `tools/style/fetch-style-assets.sh`.
4. **Geocoder index** — `./gradlew :geocoder-tool:run` writes
   `app/src/main/assets/geocoder/geocoder.dat`.

Exact commands, version pins, and hard constraints live in [AGENTS.md](AGENTS.md).

## Regional packages and the map server

The app can install **complete regional data packages** (`.motomap`): vector
tiles, the routing graph, and the offline place-search index in one verified
archive. Packages come from a self-hosted map server (`map-server/`, its own Go
module) that generates them with the same pinned tools as the CI pipeline, or
from an exported file installed offline.

- **Maps settings** (settings cog → Maps): configure the server address, check
  the catalog, request a region build, follow its progress, and download the
  package to a file you choose in the system dialog (Downloads by default).
  Installation is a separate, explicit step: **Import package file** copies the
  `.motomap` into app-managed storage after verifying it, and leaves your file
  where it is. A failed or cancelled download keeps its resumable partial in app
  storage and always offers a retry; a terminal build job never hides the
  Download action. HTTPS is required; debug builds can explicitly opt in to a
  plain-HTTP LAN server for private addresses.
- **Installed maps** lists every app-managed copy with its source date and
  actual installed size, plus the immutable bundled Queensland data. Each entry
  has a rubbish bin: deleting an inactive map removes only that app-managed
  directory (your downloaded `.motomap` in Files is untouched), and deleting the
  active map first switches back to the bundled dataset, waits for the routing
  graph to be released, and then removes it. Deletion is refused during a ride.
- **Switching regions** rebuilds the router, geocoder, and map style from the
  new package; a ride must be stopped first. Saved rides are never modified —
  a ride outside the active region fails with an honest "outside the active
  region" message.
- **Offline guarantee**: routing, place search, and map rendering never touch
  the network. The only network code is the downloader/API client under
  `app/src/main/java/com/organicmoto/maps/region/net/`, and
  `OfflineGuaranteeTest` pins that boundary.
- **Load map file** still works exactly as before for a bare PMTiles archive,
  and the bundled Queensland graph/geocoder remains the fallback.

Run the server locally:

```sh
cd map-server
go build ./cmd/map-server
./map-server serve                 # HTMX UI + JSON API on :8080
./map-server build --region queensland --out dist/
```

See [map-server/README.md](map-server/README.md) for the catalog format, the
package schema, rate limits, Docker deployment, and the security model (the
server is not authentication; run it on a private network or behind an
authenticating proxy).

## Attribution & licenses

- Map data and style: © OpenMapTiles.org © OpenStreetMap contributors
  (shown in-app; required by their licenses).
- Noto fonts: SIL OFL 1.1. OSM Bright sprites: CC BY 4.0.
- GraphHopper core: Apache-2.0, embedded for offline routing only — no hosted
  GraphHopper service is contacted.
