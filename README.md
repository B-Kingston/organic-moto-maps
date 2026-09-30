# curveMaps

Offline-first motorcycle routing for Android.
Kotlin + Jetpack Compose + MapLibre Android SDK (map) + GraphHopper core (routing).

The app makes **zero runtime network calls**. The map style, glyphs/sprites,
place-search index, and routing graph ship inside the APK. The much larger
PMTiles basemap is downloaded separately and imported with **Load map file**.

## Features

- **Offline basemap** — a separately distributed OpenMapTiles PMTiles archive,
  selected from the phone and rendered with the APK's local style.
- **Offline place search** — From/To fields backed by a prebuilt on-device
  geocoder index; raw `lat,lon` entry also works.
- **Offline motorcycle routing** — GraphHopper with a motorcycle custom model,
  shipped as a pre-compiled weighting helper because Janino bytecode cannot load
  on ART.
- **Ride complexity dial** — an endless click-detent knob. Level 0 is the
  fastest route; each click adds one step of curve preference. Positive levels
  request genuinely different alternatives, softly penalizing roads used by
  lower levels, with a configurable maximum shared-road target (10–90%).
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

Set initial progress on the same command, or move an active ride later:

```sh
tools/test/visual.py route --from Brisbane --to 'Mount Glorious' --progress 50
tools/test/visual.py progress --percent 75
tools/test/visual.py route --from Brisbane --to 'Mount Glorious' --black-and-white both
tools/test/visual.py map-mode --black-and-white on
tools/test/visual.py icon-states
tools/test/visual.py nav-camera --from Brisbane --to 'Mount Glorious'
```

Progress follows the selected route geometry through the live navigation
session. The runner captures intermediate route positions and the final frame,
and updates `latest.png` after each capture. Backward jumps restart
guidance at the route start and advance to the requested point. This requires a
debug build and an Android emulator; route geometry and the ADB control bridge
exist only in debug builds.

`nav-camera` starts a ride and captures the immersive guidance camera as a
repeatable sequence: navigation start, street-close framing (stationary),
one frame per speed band (`--speed-cases`, default `0,8,15,23,32` m/s), a real
heading turn (`--turn-threshold`, `--turn-step`, `--turn-speed`), and the
planning-camera restore after END. Each step reads the debug camera probe and
fails when the settled camera does not reach the injected fix or does not
match the expected zoom band, forward tilt, heading, or restore, so the
screenshots come with assertions instead of only pixels. Queued debug fixes
own the guidance session while they flow, so the emulator's live provider
cannot pull the camera away between injections. It drives only accessible
text controls (RIDE/END) and the existing debug fix bridge, and it needs an
emulator plus a debug build.

`--black-and-white on|off` sets the ride map style during a route run. Use
`both` to save screenshots of both styles for comparison. `map-mode` changes
the style on an already active ride and saves a screenshot for each requested
state. `icon-states` captures the moon outline/fill, voice settings dialog, and
speaker enabled/muted states during an active ride, then restores both preferences.

For step-by-step exploration, use `launch`, then call `tree`, `screenshot`,
`tap`, `type`, `key`, `swipe`, or `options` as needed. `watch` records changing
screens while another gesture or command runs. These commands use accessible
text and content descriptions, so route settings, saved routes, voice options,
GPX import, and other visible app controls can be inspected and operated
without fixed screen coordinates. Set `ANDROID_SERIAL` when more than one
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

## Data pipelines (required before first build)

The generated assets are gitignored; a fresh clone has none of them until the
pipelines are re-run. All take an OSM `.osm.pbf` extract placed at
`data/queensland.osm.pbf`:

1. **Routing graph** — import with `tools/gh/graphhopper-web-11.0.jar`, copy
   `data/graph-cache` into `app/src/main/assets/graph-cache`.
2. **Basemap tiles** — `tools/tiles/build-tiles.sh` (Planetiler, needs JDK 21)
   writes `data/tiles/queensland.pmtiles`. Distribute that file separately;
   users download it to their phone and select **Load map file** in the app.
3. **Style glyphs & sprites** — `tools/style/fetch-style-assets.sh`.
4. **Geocoder index** — `./gradlew :geocoder-tool:run` writes
   `app/src/main/assets/geocoder/geocoder.dat`.

Exact commands, version pins, and hard constraints live in [AGENTS.md](AGENTS.md).

## Attribution & licenses

- Map data and style: © OpenMapTiles.org © OpenStreetMap contributors
  (shown in-app; required by their licenses).
- Noto fonts: SIL OFL 1.1. OSM Bright sprites: CC BY 4.0.
- GraphHopper core: Apache-2.0, embedded for offline routing only — no hosted
  GraphHopper service is contacted.
