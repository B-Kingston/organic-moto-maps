# Organic Moto Maps

Offline-first motorcycle routing for Android. Kotlin + Jetpack Compose + MapLibre
Android SDK (map) + GraphHopper core (offline routing).

## Status / Scope

- Full-screen map rendering MapLibre demo tiles (`https://demotiles.maplibre.org/style.json`).
  This is the only runtime network call in the app.
- Routing runs fully offline against a GraphHopper graph built from a local OSM extract.
- From/To are geocode-search fields backed by an offline index
  (`assets/geocoder/geocoder.dat`, built by `:geocoder-tool`, see AGENTS.md).
  Plain `lat,lon` pairs still work (geocoder COORDINATE result or `PointParser` fallback).
- Location permissions are declared but no runtime permission request / GPS display yet.

## Building

Requires JDK 17 and Android SDK 35 (installs via Android Studio or `sdkmanager`).

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug
```

`JAVA_HOME` points at the keg-only Homebrew JDK 17; use any JDK 17 installation.
The Gradle wrapper (8.13) is required — a plain `gradle` on a newer JDK breaks AGP.

Note: the Gradle wrapper jar is not committed. If you have a local Gradle
installation, run `gradle wrapper` once to generate `gradle/wrapper/gradle-wrapper.jar`
(the wrapper properties file is already present and points at Gradle 8.13).
Android Studio can also repair/generate the wrapper on open.

## Supplying map data

The routing graph is built on the desktop and shipped in the APK. The app never
imports OSM data on the device.

1. Download an OSM extract in PBF format for your region (e.g. from Geofabrik)
   and place it at `data/queensland.osm.pbf` (or point `tools/gh/config.yml`'s
   `datareader.file` at your file — relative paths resolve against the repo root).
2. Build the graph (needs JDK 17; ~5-10 min for a state-sized extract), from the
   **repo root** so the relative paths in `config.yml` resolve:

   ```
   JAVA_HOME=/opt/homebrew/opt/openjdk@17
   $JAVA_HOME/bin/java -Xmx12g -jar tools/gh/graphhopper-web-11.0.jar import tools/gh/config.yml
   ```

3. Copy the result into the app assets:

   ```
   cp -R data/graph-cache app/src/main/assets/graph-cache
   ```

4. `./gradlew assembleDebug`. On first launch the app copies the graph from
   assets to `{filesDir}/gh-cache` and then loads it with mmap — no import on
   device, routing works immediately.
5. Without `assets/graph-cache` the app shows a clear "Graph data not found" error.

Graph files are stored uncompressed in assets; the APK is large by design.

Sanity-check a rebuilt graph by serving it and routing (binds port **8080**):

```
$JAVA_HOME/bin/java -jar tools/gh/graphhopper-web-11.0.jar server tools/gh/config.yml
curl "http://localhost:8080/route?point=-27.4679,153.0281&point=-16.9203,145.7710&profile=motorcycle"
```

## Routing profile and the canonical custom model

GraphHopper is configured with profile name `motorcycle` (see `tools/gh/config.yml`
and `routing/GraphHopperRouter.kt`). The import reads
`custom_model_files: [motorcycle.json]`, which GraphHopper 11 resolves to the
**JAR's built-in** model (`/com/graphhopper/custom_models/motorcycle.json` classpath
resource) — it does not read `tools/gh/motorcycle.json` from disk, and a filesystem
file with that name would abort the import ("already used for built-in profiles").

`tools/gh/motorcycle.json` is the checked-in **canonical reference copy** of that
built-in model and must stay identical to it. Everything that implements the model
must change together with it:

- `tools/gh/motorcycle.json` — canonical model (reference copy of the built-in)
- `GraphHopperRouter.motorcycleProfile()` — runtime profile; its content string is
  hashed into the graph's stored profile version (`motorcycle|198752012`), so it
  must stay bit-for-bit identical to the import-time profile
- `tools/gh/GenerateWeighting.java` — desktop mirror used to dump the app's
  pre-compiled weighting helper
- `app/src/main/java/com/graphhopper/routing/weighting/custom/MotorcycleWeightingHelper.java`
  — the Janino-generated helper shipped as plain Java (ART cannot load Janino bytecode)
- `MotorcycleWeightingFactory.kt` — max-speed math mirroring the speed statements

`./tools/gh/generate-weighting.sh` verifies the first three agree before dumping the
helper source. Graph version and profile must match between the import tool and
`graphhopper-core` in `gradle/libs.versions.toml` (11.0).

## Geocoding index

The offline search index is prebuilt and shipped in APK assets, mirroring the
graph pipeline. Build it with `:geocoder-tool` before `assembleDebug` (see
AGENTS.md, "Geocoding data pipeline"). The generated `geocoder.dat` is
intentionally gitignored, and the Android build fails clearly if it is absent
instead of silently producing an APK without place search.

## License note

GraphHopper core is Apache-2.0 licensed. This app embeds the library for
offline routing only; no GraphHopper Directions API or other hosted service
is contacted at runtime.
