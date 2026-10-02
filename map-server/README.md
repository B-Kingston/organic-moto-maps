# curveMaps map server

A small, robust Go server that generates and hosts **complete regional data
packages** for the curveMaps Android app: vector tiles, the GraphHopper routing
graph, and the offline geocoder index in one versioned `.motomap` archive.

- **`serve`** — HTTP server with an embedded HTMX UI and a JSON API. Add
  `--headless` for API-only operation.
- **`build`** — local generation and export, no HTTP involved.
- **`verify`** — re-read a published `.motomap` and check every manifest claim.
- **`catalog`** — validate, list, and pin the operator-approved region catalog.
- **`admin`** — inspect the persistent queue and artifact cache.

The server does not reimplement the generation pipeline. It invokes the same
pinned tools the app's CI uses — `tools/tiles/build-tiles.sh` (Planetiler
0.10.2, JDK 21), `tools/gh/config.yml` + the GraphHopper 11.0 jar (JDK 17), and
`:geocoder-tool` built as a standalone JVM distribution — with configurable work
directories, heaps, and outputs. The GraphHopper profile, custom-model file
order, and stored profile signature (`motorcycle|198752012`) are preserved
byte-for-byte, so packages load with the app's pre-compiled weighting helper.

## Quick start

```sh
cd map-server
go build ./cmd/map-server

# Local UI + API on :8080, state under ./state
./map-server serve

# API only
./map-server serve --headless --addr 127.0.0.1:8080

# Build and export a package without HTTP
./map-server build --region queensland --out dist/

# Inspect the operator catalog
./map-server catalog list --catalog catalog.example.json

# Re-verify a published package (every file, digest, and compatibility marker)
./map-server verify --package dist/queensland.motomap

# Look up Geofabrik extracts exactly as an API request would
./map-server extracts search tasmania
./map-server extracts locate -42.88 147.33
./map-server extracts resolve us/texas
```

Requirements for generation: a JDK 17 (`MOTO_JAVA17` or `JAVA_HOME`), a JDK 21
(`MOTO_JAVA21` or `TILE_JAVA_HOME`), the pinned Planetiler and GraphHopper jars
(downloaded by `tools/tiles/build-tiles.sh` and `tools/gh/generate-weighting.sh`
respectively, or fetched by the Dockerfile), and the repository checkout that
contains `tools/` and `geocoder-tool/`. Serving already-built artifacts needs
none of that. There is no Android SDK requirement anywhere.

## Configuration

Everything is optional; flags override environment variables, which override a
JSON config file (`--config`).

| Setting | Env | Flag | Default |
|---|---|---|---|
| Listen address | `MOTO_ADDR` | `--addr` | `:8080` |
| State directory | `MOTO_STATE_DIR` | `--state` | `<repo>/map-server/state` |
| Catalog | `MOTO_CATALOG` | `--catalog` | built-in Queensland catalog |
| Headless | `MOTO_HEADLESS` | `--headless` | false |
| Trusted proxies | `MOTO_TRUSTED_PROXIES` | `--trusted-proxies` | none |
| Admin token | `MOTO_ADMIN_TOKEN` | `--admin-token` | none (admin API off) |
| JDK 17 | `MOTO_JAVA17`, `JAVA_HOME` | — | auto-detected |
| JDK 21 | `MOTO_JAVA21`, `TILE_JAVA_HOME` | — | auto-detected |
| Geocoder dist | `MOTO_GEOCODER_DIST` | — | `geocoder-tool/build/install/geocoder-tool` |
| Repository root | `MOTO_REPO_ROOT` | `--repo-root` | auto-detected |
| Disable place requests | `MOTO_DISABLE_REQUESTS` | — | requests on |
| Geofabrik index URL | `MOTO_GEOFABRIK_INDEX` | — | `https://download.geofabrik.de/index-v1.json` |

A config file can also set heaps, work/artifact/cache directories, disk
headroom, archive verification, and every rate limit:

```json
{
  "addr": ":8080",
  "stateDir": "/srv/curvemaps",
  "catalogPath": "/srv/curvemaps/catalog.json",
  "trustedProxies": ["10.0.0.0/8"],
  "pipeline": { "graphHeap": "12g", "tilesHeap": "12g", "minFreeDiskBytes": 8589934592 },
  "limits": { "buildBurst": 2, "buildPerSeconds": 3600, "searchBurst": 20, "searchPerSeconds": 60 },
  "requests": { "enabled": true, "maxExtractBytes": 805306368, "maxRegions": 24, "refreshDays": 30 }
}
```

## Region catalog

Build requests name a region from the operator catalog, or a place that the
server resolves itself against Geofabrik's index (see *Requests by place*).
The server never accepts a caller-supplied URL, path, command, or refresh
instruction.

```json
{
  "regions": [
    {
      "id": "queensland",
      "name": "Queensland",
      "coverage": "Queensland, Australia",
      "maxZoom": 14,
      "source": {
        "url": "https://download.geofabrik.de/australia-oceania/australia/queensland-260928.osm.pbf",
        "date": "260928",
        "sha256": "<optional but recommended pin>"
      }
    }
  ]
}
```

- `source.url` must be `https`; redirects are followed only to the same host.
- `source.path` selects a local extract instead (relative paths resolve against
  the repo root).
- Pin the extract with `map-server catalog pin --region queensland --sha256 …`
  after the first verified download. An unpinned URL still works: the server
  records the observed hash, verifies the PBF magic, and keys the artifact cache
  on those bytes.
- Disable a region with `"disabled": true`.

## Requests by place

Clients can ask for any place Geofabrik publishes, by name or coordinate,
without the operator adding it to the catalog first:

1. The server loads Geofabrik's `index-v1.json` (cached in
   `<state>/cache/geofabrik-index-v1.json` for 24 h; a stale copy is used if a
   refresh fails) and matches the name, id, or ISO code (case- and
   accent-insensitive), or finds the polygons containing the point, smallest
   first.
2. It pins the extract to Geofabrik's **dated** file (the `-latest` alias's
   redirect, or a probe of the last 7 days), on `download.geofabrik.de` only,
   with its `Content-Length` and published `.md5`.
3. **State-sized limit:** continents are never built, and an extract larger
   than `requests.maxExtractBytes` (default 768 MiB) is refused with its
   smaller sub-regions listed. For scale: Tasmania 54 MB, Queensland 198 MB,
   New South Wales 268 MB, Texas 723 MB build; Australia 966 MB and California
   1.33 GB do not (pick e.g. Queensland or Southern California instead).
4. The place is stored in `<state>/requested-regions.json` (at most
   `requests.maxRegions`, default 24) and built by the normal queue. The
   download is size-capped and MD5-checked before the pipeline runs. After a
   successful build the observed SHA-256 is pinned, so a repeat request is a
   cache hit with no download; data older than `requests.refreshDays`
   (default 30) is re-resolved and rebuilt.

Requested regions appear in `/api/v1/catalog` with `"requested": true` and
their Geofabrik `extractId`, so the app lists them like catalog regions. An
operator catalog region with the same id (e.g. `queensland`) always wins.
Disable the feature with `"requests": {"enabled": false}` or
`MOTO_DISABLE_REQUESTS=1`.

## Package format

A `.motomap` file is a ZIP64 archive:

```
manifest.json                 schema, region, source, component digests, compatibility
tiles/basemap.pmtiles         PMTiles v3 vector basemap
graph/…                       GraphHopper graph cache (MMAP-ready)
geocoder/geocoder.dat         offline inverted place-search index
```

`manifest.json` records the schema version, region identity, source date and
SHA-256, generator pipeline fingerprint, per-file lengths and SHA-256 for every
component file, the graph profile signature, the geocoder format magic, and the
OSM/OpenMapTiles attribution. The server re-reads every written package and
verifies all of it before publishing; the Android importer enforces the same
contract.

## Queue, cache, and deduplication

- One worker by default; the admission bound counts queued + running jobs.
- A persistent `jobs.json` survives restarts: jobs that were `running` are
  requeued with an honest message.
- The artifact cache is keyed by a pipeline fingerprint that includes the source
  identity and hash, the canonical GraphHopper config and motorcycle model, the
  tile script, the geocoder sources, the pinned tool jars, the max zoom, and the
  package schema. An identical request is served from cache, not rebuilt.
- Progress is reported per step (source → tiles → graph → geocoder → package →
  verify). Failures record the failing step and the tool log tail.
- Cancellation kills the whole process group (Planetiler/JVM children included).

## HTTP API

| Method | Path | Notes |
|---|---|---|
| GET | `/api/v1/health` | status, generation availability, queue depth |
| GET | `/api/v1/csrf` | issues the CSRF cookie and returns a token |
| GET | `/api/v1/catalog` | regions + cached artifact info |
| GET | `/api/v1/extracts?q=…` or `?lat=…&lon=…` | Geofabrik candidates with size and `eligible`/`reason` (`limit` 1–25) |
| POST | `/api/v1/builds` | exactly one of `{"regionId"}`, `{"extractId"}`, `{"query"}`, `{"lat","lon"}` (202 queued / 200 cached) |
| GET | `/api/v1/builds/{id}` | job status |
| GET | `/api/v1/builds?regionId=…` | recent jobs |
| POST | `/api/v1/builds/{id}/cancel` | cancel |
| GET | `/api/v1/artifacts/{region}/{fingerprint}/{file}` | resumable download |
| GET | `/api/v1/admin/jobs` | bearer-token admin (only when configured) |

Place requests fail with a readable `error`: 404 unknown place, 409 an
ambiguous name (with `candidates`, e.g. `georgia` is the country and the US
state), 413 above the size limit (names smaller choices), 422 a continent or a
point with nothing buildable, 503 Geofabrik unreachable, 507 the requested
region list is full.

State-changing requests need a CSRF token: fetch `/api/v1/csrf` and send
`X-CSRF-Token` (API clients), or use the hidden form field (browser). Artifact
downloads support `Range`, `If-Range`, and `ETag`, so the app can resume an
interrupted transfer; artifacts are immutable and served with long-lived cache
headers.

## Security model and limits

This server is **not authenticated** and **not a DDoS defense**. Run it on a
private network, or put an authenticating reverse proxy in front of it.

- Rate limits are per-IP token buckets (separate classes for build, poll,
  search, and download) plus per-client and global concurrent-transfer caps. Buckets are
  bounded and expire.
- `X-Forwarded-For` is honored **only** when the direct peer is inside
  `--trusted-proxies`; the chain is walked right to left. IPv4-mapped IPv6
  collapses to the IPv4 address and IPv6 is bucketed by `/64`.
- CSRF uses a signed, stateless double-submit token (persisted secret in the
  state directory).
- Responses carry a strict CSP, `nosniff`, `X-Frame-Options: DENY`, a referrer
  policy, and HSTS when TLS terminates here.
- Request bodies are size-capped; the HTTP server sets header/read/idle
  timeouts but no write timeout, because package downloads are legitimately long.
- Admin endpoints exist only when `MOTO_ADMIN_TOKEN` is set, and require a
  bearer token (constant-time compare).
- The HTMX UI is embedded and vendored (`htmx.min.js`, Zero-Clause BSD); there
  is no CDN and no build step.

## Docker

```sh
docker build -f map-server/Dockerfile -t curvemaps-map-server .
docker run --rm -p 8080:8080 \
  -v curvemaps-map-data:/data \
  -v "$PWD/data/queensland.osm.pbf:/input/queensland.osm.pbf:ro" \
  curvemaps-map-server
```

The image includes the Go server, both JDKs (17 for GraphHopper, 21 for
Planetiler), the pinned generation jars, the geocoder sources needed by the
fingerprint, and a pre-built standalone geocoder distribution. It runs as a
non-root user (UID 10001) with `/data` as its only writable volume. The baked
`/app/config.json` sets conservative pipeline heaps (tiles/graph 8g, geocoder
6g); mount another JSON config over it and override the command to change them.
A `HEALTHCHECK` probes `/api/v1/health`.

`docker-compose.yml` parameterizes the container name, image tag, bind address,
port, input directory, catalog file, volume name, and resource caps through
`MOTO_*` environment variables (safe loopback defaults), and disables
watchtower updates for the locally built image. The server's `catalog` pins an
extract with `source.path` (container path) or `source.url`; a path entry is
resolved against the repository root, so mount extracts at `/input` and use
`/input/…` in the catalog (see `deploy/README.md`).

`map-server/deploy/deploy.sh` is the repeatable remote deployment: it stages
the build context, uploads one extract with its SHA-256, derives the catalog
date from the PBF header, builds, starts, and can trigger/watch/verify a real
generation over the live API. `deploy/README.md` records the project's own
host deployment and the resource settings.

## Tests

```sh
cd map-server
go test ./...
go test -race ./...
```

The suites cover the manifest/package writer and verifier (including tampered,
duplicate, absolute, and zip-slip entries), the pipeline with stubbed external
processes (including a cache hit and cancellation), queue persistence and
restart recovery, deduplication, admission bounds, cancellation, rate limiting,
trusted-proxy parsing, IPv6 normalization, CSRF, download ranges, and the full
HTTP surface. `internal/geofabrik` runs against a TLS test server with a
synthetic index (search ranking, point lookup with holes, dated-file pinning,
foreign redirects, size limit, MD5, index and size caches); `internal/api`'s
`extracts_test.go` covers every request form and error status.

To exercise the real generation pipeline end to end, use a small extract and the
`build` command with a local catalog; the same code path is what `serve` runs.
