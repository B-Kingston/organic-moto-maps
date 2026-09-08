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

The Gradle wrapper jar is not committed — run `gradle wrapper` once, or let
Android Studio repair it on open.

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
