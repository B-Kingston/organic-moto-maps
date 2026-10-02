#!/usr/bin/env bash
# Deploy the curveMaps map server as a Docker container on a remote host.
#
# The script is intentionally boring: it stages exactly the files the Docker
# build context needs (mirroring map-server/Dockerfile.dockerignore), copies
# them plus one OSM extract to an isolated directory on the host, then drives
# `docker compose` over SSH. It never touches other containers, images, or
# volumes, and it never removes unrelated data.
#
#   map-server/deploy/deploy.sh sync      # stage sources + extract, write catalog
#   map-server/deploy/deploy.sh build     # docker compose build
#   map-server/deploy/deploy.sh up        # start/recreate the service
#   map-server/deploy/deploy.sh smoke     # health/catalog/UI checks
#   map-server/deploy/deploy.sh trigger   # queue a build through the live API
#   map-server/deploy/deploy.sh watch     # sample docker stats until the job ends
#   map-server/deploy/deploy.sh status [job-id]
#   map-server/deploy/deploy.sh verify    # re-verify the published package
#   map-server/deploy/deploy.sh graph-smoke  # route through the package's graph
#   map-server/deploy/deploy.sh logs
#   map-server/deploy/deploy.sh down      # stop (keeps the data volume)
#
# Everything is configurable through MOTO_* environment variables; the
# defaults target the project's own deployment host:
#
#   MOTO_SSH            bailee@192.168.8.223
#   MOTO_REMOTE_DIR     curvemaps-map-server   (relative to $HOME, or absolute)
#   MOTO_BIND           127.0.0.1              (set to the LAN address to serve)
#   MOTO_PORT           8080                   (the host uses 8082)
#   MOTO_URL            http://$MOTO_BIND:$MOTO_PORT
#   MOTO_PBF            data/queensland.osm.pbf
#   MOTO_PBF_DATE       derived from the PBF header when unset
#   MOTO_REGION         queensland
#   MOTO_IMAGE          curvemaps-map-server:local
#   MOTO_CONTAINER_NAME curvemaps-map-server
#   MOTO_VOLUME         curvemaps-map-data
#   MOTO_MEM_LIMIT      13g
#   MOTO_CPUS           2.5
#
# See deploy/README.md for the recorded host deployment.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

REMOTE="${MOTO_SSH:-bailee@192.168.8.223}"
REMOTE_DIR="${MOTO_REMOTE_DIR:-curvemaps-map-server}"
BIND="${MOTO_BIND:-127.0.0.1}"
PORT="${MOTO_PORT:-8080}"
URL="${MOTO_URL:-http://$BIND:$PORT}"
REGION="${MOTO_REGION:-queensland}"
REGION_NAME="${MOTO_REGION_NAME:-Queensland}"
REGION_COVERAGE="${MOTO_REGION_COVERAGE:-Queensland, Australia}"
PBF="${MOTO_PBF:-$REPO_ROOT/data/queensland.osm.pbf}"
IMAGE="${MOTO_IMAGE:-curvemaps-map-server:local}"
CONTAINER="${MOTO_CONTAINER_NAME:-curvemaps-map-server}"
VOLUME="${MOTO_VOLUME:-curvemaps-map-data}"
MEM_LIMIT="${MOTO_MEM_LIMIT:-13g}"
CPUS="${MOTO_CPUS:-2.5}"

SSH_OPTS=(-o BatchMode=yes -o ConnectTimeout=10 -o ServerAliveInterval=30)

ssh_run() { ssh "${SSH_OPTS[@]}" "$REMOTE" "$@"; }

CLEANUP_DIRS=()
cleanup() {
  if [ "${#CLEANUP_DIRS[@]}" -gt 0 ]; then
    rm -rf "${CLEANUP_DIRS[@]}"
  fi
  return 0
}
trap cleanup EXIT

mktemp_dir() {
  local dir
  dir="$(mktemp -d)"
  CLEANUP_DIRS+=("$dir")
  printf '%s' "$dir"
}

remote_abs() {
  if [[ "$REMOTE_DIR" == /* ]]; then
    printf '%s' "$REMOTE_DIR"
  else
    ssh_run "printf '%s' \"\$HOME/$REMOTE_DIR\""
  fi
}

compose() {
  local abs="$1"
  shift
  ssh_run "cd '$abs/src/map-server' && docker compose --env-file '$abs/deploy.env' $*"
}

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d' ' -f1
  else
    shasum -a 256 "$1" | cut -d' ' -f1
  fi
}

json_field() {
  # json_field FILE KEY: read a top-level string field without requiring jq.
  python3 - "$1" "$2" <<'PY'
import json, sys
with open(sys.argv[1]) as f:
    doc = json.load(f)
print(doc.get(sys.argv[2], ""))
PY
}

cmd_sync() {
  local abs; abs="$(remote_abs)"
  local date sha
  [ -f "$PBF" ] || { echo "OSM extract not found: $PBF" >&2; exit 1; }
  sha="$(sha256_of "$PBF")"
  date="${MOTO_PBF_DATE:-}"
  if [ -z "$date" ]; then
    date="$(python3 "$SCRIPT_DIR/pbf_date.py" "$PBF")"
  fi
  local remote_file="$abs/input/$REGION-$date.osm.pbf"

  echo "host:      $REMOTE:$abs"
  echo "extract:   $PBF"
  echo "           sha256=$sha date=$date"

  ssh_run "mkdir -p '$abs/src' '$abs/input' '$abs/logs'"

  local stage; stage="$(mktemp_dir)"
  mkdir -p "$stage/src/map-server" "$stage/src/geocoder-tool/standalone" \
    "$stage/src/tools/gh" "$stage/src/tools/tiles"
  # Exactly the allowlist in map-server/Dockerfile.dockerignore.
  rsync -a --exclude 'state/' --exclude 'dist/' --exclude '__pycache__/' \
    "$REPO_ROOT/map-server/" "$stage/src/map-server/"
  rsync -a "$REPO_ROOT/gradlew" "$stage/src/"
  rsync -a --exclude '__pycache__/' "$REPO_ROOT/gradle/" "$stage/src/gradle/"
  rsync -a --exclude 'build/' --exclude '.gradle/' \
    "$REPO_ROOT/geocoder-tool/src/" "$stage/src/geocoder-tool/src/"
  cp "$REPO_ROOT/geocoder-tool/build.gradle.kts" "$stage/src/geocoder-tool/"
  cp "$REPO_ROOT/geocoder-tool/standalone/settings.gradle.kts" "$stage/src/geocoder-tool/standalone/"
  cp "$REPO_ROOT/tools/gh/config.yml" "$stage/src/tools/gh/"
  cp "$REPO_ROOT/tools/gh/motorcycle.json" "$stage/src/tools/gh/"
  cp "$REPO_ROOT/tools/gh/MotoGraphImport.java" "$stage/src/tools/gh/"
  cp "$REPO_ROOT/tools/tiles/build-tiles.sh" "$stage/src/tools/tiles/"

  # COPYFILE_DISABLE/--no-mac-metadata keep bsdtar from writing AppleDouble
  # `._*` siblings; those would otherwise leak into the image and into the
  # fingerprint's geocoder source walk.
  COPYFILE_DISABLE=1 tar --no-mac-metadata --exclude '._*' --exclude '.DS_Store' \
    -C "$stage/src" -czf - . | ssh_run "
    rm -rf '$abs/src.new' '$abs/src.old' &&
    mkdir -p '$abs/src.new' &&
    tar -C '$abs/src.new' -xzf - &&
    if [ -d '$abs/src' ]; then mv '$abs/src' '$abs/src.old'; fi &&
    mv '$abs/src.new' '$abs/src' &&
    rm -rf '$abs/src.old'"
  echo "context:   staged to $abs/src"

  local remote_sha=""
  remote_sha="$(ssh_run "sha256sum '$remote_file' 2>/dev/null | cut -d' ' -f1" || true)"
  if [ "$remote_sha" != "$sha" ]; then
    echo "extract:   uploading to $remote_file"
    rsync -a --partial --progress "$PBF" "$REMOTE:$remote_file.part"
    ssh_run "mv '$remote_file.part' '$remote_file'"
  else
    echo "extract:   already present and verified ($sha)"
  fi

  cat > "$stage/catalog.json" <<EOF
{
  "regions": [
    {
      "id": "$REGION",
      "name": "$REGION_NAME",
      "coverage": "$REGION_COVERAGE",
      "maxZoom": 14,
      "notes": "Local-path extract pinned by deploy.sh (sha256 + date derived from the PBF header).",
      "source": {
        "path": "/input/$REGION-$date.osm.pbf",
        "date": "$date",
        "sha256": "$sha"
      }
    }
  ]
}
EOF
  rsync -a "$stage/catalog.json" "$REMOTE:$abs/catalog.json"

  cat > "$stage/deploy.env" <<EOF
COMPOSE_PROJECT_NAME=curvemaps-map-server
MOTO_IMAGE=$IMAGE
MOTO_CONTAINER_NAME=$CONTAINER
MOTO_BIND=$BIND
MOTO_PORT=$PORT
MOTO_INPUT_DIR=$abs/input
MOTO_CATALOG_FILE=$abs/catalog.json
MOTO_VOLUME=$VOLUME
MOTO_MEM_LIMIT=$MEM_LIMIT
MOTO_CPUS=$CPUS
MOTO_SOURCE_DATE=$date
MOTO_SOURCE_SHA256=$sha
EOF
  rsync -a "$stage/deploy.env" "$REMOTE:$abs/deploy.env"
  echo "catalog:   $abs/catalog.json (pins /input/$REGION-$date.osm.pbf)"
  echo "next:      $0 build && $0 up"
}

cmd_build() {
  local abs; abs="$(remote_abs)"
  compose "$abs" build
}

cmd_up() {
  local abs; abs="$(remote_abs)"
  compose "$abs" up -d
  compose "$abs" ps
}

cmd_down() {
  local abs; abs="$(remote_abs)"
  compose "$abs" down
}

cmd_logs() {
  local abs; abs="$(remote_abs)"
  compose "$abs" logs --tail=200
}

# bind_target returns the URL the host itself can use for the published port.
# A loopback-only bind is unreachable through the LAN address from the host's
# own perspective only if the bind is a specific interface; curl to the bound
# address always works.
bind_target() {
  if [ "$BIND" = "127.0.0.1" ] || [ "$BIND" = "localhost" ]; then
    printf 'http://127.0.0.1:%s' "$PORT"
  else
    printf 'http://%s:%s' "$BIND" "$PORT"
  fi
}

cmd_smoke() {
  # When the service binds a LAN address, curl it from the workstation and from
  # the host; when it binds loopback only, the host check is the only one that
  # can work.
  local target; target="$(bind_target)"
  echo "== health ($target, from the host)"
  ssh_run "curl -fsS '$target/api/v1/health'"
  echo
  echo "== catalog (from the host)"
  ssh_run "curl -fsS '$target/api/v1/catalog'"
  echo
  echo "== UI root HTTP status (from the host)"
  ssh_run "curl -s -o /dev/null -w '%{http_code}\n' '$target/'"
  if [ "$target" != "http://127.0.0.1:$PORT" ]; then
    echo "== health from this workstation ($URL)"
    curl -fsS "$URL/api/v1/health" || echo "unreachable from here (check MOTO_BIND/MOTO_URL)"
  fi
}

cmd_trigger() {
  local tmp; tmp="$(mktemp_dir)"
  curl -fsS -c "$tmp/cookies" "$URL/api/v1/csrf" > "$tmp/csrf.json"
  local token; token="$(json_field "$tmp/csrf.json" token)"
  curl -fsS -b "$tmp/cookies" -H "X-CSRF-Token: $token" \
    -H 'Content-Type: application/json' \
    -d "{\"regionId\":\"$REGION\"}" "$URL/api/v1/builds"
}

cmd_status() {
  local id="${1:-}"
  if [ -n "$id" ]; then
    curl -fsS "$URL/api/v1/builds/$id"
  else
    curl -fsS "$URL/api/v1/builds?regionId=$REGION&limit=5"
  fi
}

cmd_verify() {
  local abs; abs="$(remote_abs)"
  local tmp; tmp="$(mktemp_dir)"
  curl -fsS "$URL/api/v1/catalog" > "$tmp/catalog.json"
  local fp; fp="$(python3 - "$tmp/catalog.json" "$REGION" <<'PY'
import json, sys
with open(sys.argv[1]) as f:
    doc = json.load(f)
for region in doc["regions"]:
    if region["id"] == sys.argv[2] and region["artifact"].get("available"):
        print(region["artifact"]["fingerprint"])
        break
PY
)"
  [ -n "$fp" ] || { echo "no cached artifact for $REGION" >&2; exit 1; }
  local pkg="/data/artifacts/$REGION/$fp/$REGION.motomap"
  echo "== package verification (re-reads every file and manifest claim)"
  ssh_run "docker exec '$CONTAINER' /app/map-server verify --package '$pkg'"
  echo "== sidecar vs on-disk sha256"
  local sidecar real
  sidecar="$(curl -fsS "$URL/api/v1/artifacts/$REGION/$fp/$REGION.motomap.sha256")"
  real="$(ssh_run "docker exec '$CONTAINER' sha256sum '$pkg' | cut -d' ' -f1")"
  if [ "$sidecar" = "$real" ]; then
    echo "sidecar OK: $real"
  else
    echo "sidecar MISMATCH: sidecar=$sidecar disk=$real" >&2
    exit 1
  fi

  # Resumed downloads: a fresh Range, and a resumed Range conditioned on the
  # ETag. Both chunks must hash to the same bytes the container holds.
  echo "== Range/ETag resume"
  local url="$URL/api/v1/artifacts/$REGION/$fp/$REGION.motomap"
  local etag
  etag="$(curl -fsSI "$url" | tr -d '\r' | awk -F': ' 'tolower($1) == "etag" { print $2 }')"
  [ -n "$etag" ] || { echo "artifact response has no ETag" >&2; exit 1; }
  local status
  status="$(curl -fsS -o "$tmp/head.bin" -w '%{http_code}' -r 0-65535 "$url")"
  [ "$status" = "206" ] || { echo "first Range returned $status, want 206" >&2; exit 1; }
  status="$(curl -fsS -o "$tmp/tail.bin" -w '%{http_code}' -H "Range: bytes=-65536" -H "If-Range: $etag" "$url")"
  [ "$status" = "206" ] || { echo "resumed Range returned $status, want 206" >&2; exit 1; }
  local head_real tail_real
  head_real="$(ssh_run "docker exec '$CONTAINER' head -c 65536 '$pkg' | sha256sum | cut -d' ' -f1")"
  tail_real="$(ssh_run "docker exec '$CONTAINER' tail -c 65536 '$pkg' | sha256sum | cut -d' ' -f1")"
  [ "$(sha256_of "$tmp/head.bin")" = "$head_real" ] || { echo "first 64 KiB chunk mismatch" >&2; exit 1; }
  [ "$(sha256_of "$tmp/tail.bin")" = "$tail_real" ] || { echo "last 64 KiB chunk mismatch" >&2; exit 1; }
  echo "Range OK: ETag $etag, first and last 64 KiB chunks match on-disk bytes"
}

cmd_graph_smoke() {
  local abs; abs="$(remote_abs)"
  local target; target="$(bind_target)"
  rsync -a "$SCRIPT_DIR/graph-smoke.sh" "$REMOTE:$abs/graph-smoke.sh"
  ssh_run "chmod +x '$abs/graph-smoke.sh' && '$abs/graph-smoke.sh' \
    --url '$target' --region '$REGION' --image '$IMAGE' --volume '$VOLUME'"
}

cmd_watch() {
  local abs; abs="$(remote_abs)"
  local job="${1:-}"
  local target; target="$(bind_target)"
  rsync -a "$SCRIPT_DIR/build-watch.sh" "$REMOTE:$abs/build-watch.sh"
  ssh_run "chmod +x '$abs/build-watch.sh' && nohup '$abs/build-watch.sh' \
    --url '$target' --out '$abs/logs' --container '$CONTAINER' \
    --region '$REGION' ${job:+--job '$job'} > '$abs/logs/watch.out' 2>&1 & echo \"watch started (pid \$!)\""
}

usage() {
  sed -n '2,45p' "$0" | sed 's/^# \{0,1\}//'
}

command="${1:-}"
shift || true
case "$command" in
  sync)    cmd_sync "$@" ;;
  build)   cmd_build "$@" ;;
  up)      cmd_up "$@" ;;
  down)    cmd_down "$@" ;;
  logs)    cmd_logs "$@" ;;
  smoke)   cmd_smoke "$@" ;;
  trigger) cmd_trigger "$@" ;;
  status)  cmd_status "$@" ;;
  verify)  cmd_verify "$@" ;;
  graph-smoke) cmd_graph_smoke "$@" ;;
  watch)   cmd_watch "$@" ;;
  ""|-h|--help|help) usage ;;
  *) echo "unknown command: $command" >&2; usage >&2; exit 2 ;;
esac
