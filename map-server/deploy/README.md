# Deploying the map server on a host

`deploy.sh` stages the Docker build context and one OSM extract on a remote
host, builds the image there, and drives `docker compose` over SSH. It is the
repeatable path used for the project's own deployment; `docker-compose.yml` in
`map-server/` is the single compose definition for both local and remote use.

## Recorded deployment

| | |
|---|---|
| Host | `bailee@192.168.8.223` (Ubuntu x86_64, 3 CPUs, 19 GiB RAM) |
| Directory | `~/curvemaps-map-server/` (`src/`, `input/`, `logs/`, `catalog.json`, `deploy.env`) |
| Container | `curvemaps-map-server` (image `curvemaps-map-server:local`) |
| Volume | `curvemaps-map-data` mounted at `/data` (UID 10001, non-root) |
| Bind | `192.168.8.223:8082` → container `:8080` (8080/8081 were already taken) |
| Input | `/input/queensland-<date>.osm.pbf`, mounted read-only |
| Limits | `mem_limit=13g`, `cpus=2.5`; heaps tiles/graph 8g, geocoder 6g |

The compose service carries `com.centurylinklabs.watchtower.enable=false`, so
the host's watchtower never tries to update this locally built image. The
compose project is named `curvemaps-map-server` and the volume has an explicit
`name:`, so nothing depends on the current directory.

## Usage

```sh
cd map-server/deploy

MOTO_BIND=192.168.8.223 MOTO_PORT=8082 MOTO_URL=http://192.168.8.223:8082 \
  ./deploy.sh sync      # stage src/, upload data/queensland.osm.pbf, write catalog+env
MOTO_BIND=192.168.8.223 MOTO_PORT=8082 ./deploy.sh build
MOTO_BIND=192.168.8.223 MOTO_PORT=8082 ./deploy.sh up
MOTO_BIND=192.168.8.223 MOTO_PORT=8082 ./deploy.sh smoke

# Queue a build and watch it (watcher samples docker stats and writes evidence)
MOTO_URL=http://192.168.8.223:8082 ./deploy.sh trigger
MOTO_BIND=192.168.8.223 MOTO_PORT=8082 ./deploy.sh watch
MOTO_URL=http://192.168.8.223:8082 ./deploy.sh status

# Re-verify the published package (re-reads every file and manifest claim)
MOTO_BIND=192.168.8.223 MOTO_PORT=8082 ./deploy.sh verify

# Route through the published package's graph in a throwaway network-less
# container (Brisbane → Cairns by default)
MOTO_BIND=192.168.8.223 MOTO_PORT=8082 ./deploy.sh graph-smoke
```

Put the settings in a small env file instead of repeating them:

```sh
cat > deploy.env.local <<'EOF'
export MOTO_BIND=192.168.8.223
export MOTO_PORT=8082
export MOTO_URL=http://192.168.8.223:8082
EOF
. ./deploy.env.local
./deploy.sh sync && ./deploy.sh build && ./deploy.sh up
```

`deploy.sh` needs `ssh`, `rsync`, `tar`, `curl`, and `python3` (for the PBF
header date and JSON reading). The host needs Docker with the compose plugin;
nothing else.

## What the sync does

- Stages exactly the files the Docker build context needs, mirroring
  `map-server/Dockerfile.dockerignore`: `map-server/`, `gradlew`, `gradle/`,
  `geocoder-tool/{build.gradle.kts,src,standalone/settings.gradle.kts}`,
  `tools/gh/{config.yml,motorcycle.json}`, `tools/tiles/build-tiles.sh`.
  Build outputs, `.gradle`, `state/`, and `dist/` are excluded.
- Uploads the extract to `<dir>/input/<region>-<date>.osm.pbf` only when the
  remote SHA-256 differs; the transfer lands as `.part` and is renamed after
  the check. The catalog pins the path, date, and SHA-256, so a rebuild cannot
  silently use different bytes.
- Derives `<date>` from the extract's own OSM header
  (`deploy/pbf_date.py`, `osmosis_replication_timestamp` in UTC, e.g.
  `260928` for the September 28 extract). Pass `MOTO_PBF_DATE=YYMMDD` to
  override. This is deliberately not the local file's name: a stale file
  pinned to a date it does not contain would make the manifest lie.
- Writes `deploy.env` with the compose settings and the pinned source date and
  SHA-256.

## Resource settings

`config.docker.json` (baked into the image at `/app/config.json`) sets the
pipeline heaps to 8g (tiles), 8g (graph), 6g (geocoder). `docker-compose.yml`
caps the container at `mem_limit=13g` / `cpus=2.5`, leaving the rest of the
host's ~19 GiB for its other services. The two large steps run sequentially,
so the peak is one heap plus JVM overhead. To raise the heaps, mount another
JSON config and override the command:

```yaml
    volumes:
      - ./config.json:/app/config.json:ro
```

The watcher records peak memory/CPU from `docker stats` per build under
`<dir>/logs/` (`stats-<stamp>.csv`, `build-<stamp>.log`,
`before-<stamp>.txt`, `after-<stamp>.txt`), bounded to the newest samples.

## Recorded run (2026-10-01)

First full Queensland generation through the deployed HTTP API:

| | |
|---|---|
| Job | `a1575e4b6fd941d81c10ab05`, `completed` |
| Elapsed | 321.6 s (04:31:07.58 → 04:36:29.16 UTC); watcher sampled 290 s |
| Peak memory | 3,117,072,515 B (2.90 GiB) of the 13 GiB cap |
| Peak CPU | 251.8% (the 2.5-core cap; generation is CPU-bound) |
| Disk | 96 GB → 98 GB used on `/`; no swap pressure (268 KiB) |
| Fingerprint | `1ea03fcfc31051c5f16a55855557d6ffb705b1581c28fc352142a15d55c83a14` |
| Package | 357,091,613 B, sha256 `b3e0b4cf98f8b9a4ef54a7c461be3b9471a64624ea3001229a585043942bd3fa` |
| Components | tiles 280,609,925 B · graph 115,390,196 B (10 files) · geocoder 27,809,765 B |
| Source | local `data/queensland.osm.pbf`, 196,857,477 B, sha256 `b1c0d120…`, header date `260815` |

The 8g/8g/6g heaps never came close to the cap, so they are a safe ceiling for
this extract. Verification: `deploy.sh verify` re-read every file and manifest
claim, matched the sidecar, and proved Range/ETag resume on first and last
64 KiB chunks; `deploy.sh graph-smoke` validated the packaged geocoder and
loaded the packaged graph in the pinned GraphHopper 11 jar (807,640 nodes,
986,256 edges, Queensland bounds) and routed Brisbane → Cairns (2,048.8 km).
A second build request returned HTTP 200 with the existing completed job
(fingerprint unchanged, no rebuild queued).

The extract used is the repository's local file, whose OSM header says
`2026-08-15T20:21:20Z` (`260815`) — **not** the CI-pinned `queensland-260928`
extract. The catalog records the true date; see the source's `notes`. Point
`MOTO_PBF` at a newer extract and re-run `sync` to rebuild against it.

## Rebuilding the extract

To deploy a different extract, point `MOTO_PBF` at the new file (or pass
`MOTO_PBF_DATE` when the file has no replication timestamp) and re-run `sync`
before `build`/`up`. A new extract changes the pipeline fingerprint, so the
first build after the switch is a full generation; the previous artifact stays
in the immutable cache under `/data/artifacts/`.

## Teardown

```sh
./deploy.sh down                 # stops the container, keeps the data volume
docker compose ... down -v       # (on the host) also removes the volume
```

`deploy.sh down` never removes unrelated containers, images, volumes, or
networks; it only stops this compose project.
