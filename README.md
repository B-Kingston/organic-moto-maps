# Organic Moto Maps

Offline-first motorcycle routing for Android. Kotlin + Jetpack Compose + MapLibre
Android SDK (map) + GraphHopper core (offline routing).

## Status / Scope

- Full-screen map rendering MapLibre demo tiles (`https://demotiles.maplibre.org/style.json`).
  This is the only runtime network call in the app.
- Routing runs fully offline against a GraphHopper graph built from a local OSM extract.
- Geocoding is NOT implemented. The From/To fields take plain `lat,lon` pairs
  (e.g. `48.137,11.575`), parsed by a small helper in `routing/PointParser.kt`.
- Location permissions are declared but no runtime permission request / GPS display yet.

## Building

Requires JDK 17 and Android SDK 35 (installs via Android Studio or `sdkmanager`).

```
./gradlew assembleDebug
```

Note: the Gradle wrapper jar is not committed. If you have a local Gradle
installation, run `gradle wrapper` once to generate `gradle/wrapper/gradle-wrapper.jar`
(the wrapper properties file is already present and points at Gradle 8.13).
Android Studio can also repair/generate the wrapper on open.

## Supplying map data

The routing graph is built on the desktop and shipped in the APK. The app never
imports OSM data on the device.

1. Download an OSM extract in PBF format for your region (e.g. from Geofabrik).
2. Point `tools/gh/config.yml` at the extract (`datareader.file`).
3. Build the graph (needs JDK 17; ~5-10 min for a state-sized extract):

   ```
   /opt/homebrew/opt/openjdk@17/bin/java -Xmx12g -jar tools/gh/graphhopper-web-11.0.jar import tools/gh/config.yml
   ```

4. Copy the result into the app assets:

   ```
   cp -R data/graph-cache app/src/main/assets/graph-cache
   ```

5. `./gradlew assembleDebug`. On first launch the app copies the graph from
   assets to `{filesDir}/gh-cache` and then loads it with mmap — no import on
   device, routing works immediately.
6. Without `assets/graph-cache` the app shows a clear "Graph data not found" error.

Graph files are stored uncompressed in assets; the APK is large by design.

## Routing profile

GraphHopper is configured with profile name `motorcycle` and the bundled
`motorcycle.json` custom model (see `tools/gh/config.yml` and
`routing/GraphHopperRouter.kt`). Graph version and profile must match between
the import tool and `graphhopper-core` in `gradle/libs.versions.toml` (11.0).

## License note

GraphHopper core is Apache-2.0 licensed. This app embeds the library for
offline routing only; no GraphHopper Directions API or other hosted service
is contacted at runtime.
