#!/usr/bin/env bash
# Route through a published package's GraphHopper graph.
#
# Runs a throwaway container with no network access that extracts the graph
# component from the .motomap, starts the pinned GraphHopper 11 jar on it with
# the canonical config (only the location keys rewritten, exactly like the
# generator does), and requests a real route. Evidence lands in the data
# volume at /data/smoke/ (route.json, gh.log) and is printed here.
#
#   graph-smoke.sh --url http://127.0.0.1:8082 --region queensland \
#     --image curvemaps-map-server:local --volume curvemaps-map-data \
#     [--points "-27.4679,153.0281;-16.9203,145.7710"]
set -euo pipefail

URL="http://127.0.0.1:8082"
REGION="queensland"
IMAGE="curvemaps-map-server:local"
VOLUME="curvemaps-map-data"
POINTS="-27.4679,153.0281;-16.9203,145.7710"

while [ $# -gt 0 ]; do
  case "$1" in
    --url) URL="$2"; shift 2 ;;
    --region) REGION="$2"; shift 2 ;;
    --image) IMAGE="$2"; shift 2 ;;
    --volume) VOLUME="$2"; shift 2 ;;
    --points) POINTS="$2"; shift 2 ;;
    *) echo "graph-smoke.sh: unknown option $1" >&2; exit 2 ;;
  esac
done

meta="$(curl -fsS "$URL/api/v1/catalog")"
fp="$(printf '%s' "$meta" | jq -r --arg r "$REGION" '.regions[] | select(.id == $r) | .artifact.fingerprint // empty')"
if [ -z "$fp" ]; then
  echo "graph-smoke.sh: no cached artifact for $REGION" >&2
  exit 1
fi
echo "region=$REGION fingerprint=$fp"

docker run --rm --network none --no-healthcheck --user root \
  -v "$VOLUME:/data" \
  --entrypoint bash "$IMAGE" -c '
    set -euo pipefail
    region="$1"; fp="$2"; points="$3"
    pkg="/data/artifacts/$region/$fp/$region.motomap"
    work="/data/smoke"
    rm -rf "$work"
    mkdir -p "$work/extract"
    unzip -q "$pkg" "graph/*" "geocoder/*" -d "$work/extract"
    touch "$work/dummy.osm.pbf"
    echo "geocoder validate:"
    JAVA_HOME=/opt/java17 /app/geocoder/bin/geocoder-tool --validate "$work/extract/geocoder/geocoder.dat"
    sed -e "s|^\( *datareader.file:\).*|\1 \"/data/smoke/dummy.osm.pbf\"|" \
        -e "s|^\( *graph.location:\).*|\1 \"/data/smoke/extract/graph\"|" \
        /app/repo/tools/gh/config.yml > "$work/smoke.yml"
    echo "graph files: $(find "$work/extract/graph" -type f | wc -l)"
    grep -E "datareader.file|graph.location" "$work/smoke.yml"

    /opt/java17/bin/java -Xmx4g -jar /app/repo/tools/gh/graphhopper-web-11.0.jar \
      server "$work/smoke.yml" > "$work/gh.log" 2>&1 &
    pid=$!
    port=""
    for _ in $(seq 1 90); do
      if grep -qi "Started Server" "$work/gh.log" 2>/dev/null; then
        port="$(grep -oE "application@[^}]*\}\{[^}]*:[0-9]+" "$work/gh.log" | grep -oE "[0-9]+$" | head -1 || true)"
        [ -n "$port" ] || port="$(grep -oE "http://[^ ]*:[0-9]+" "$work/gh.log" | head -1 | grep -oE "[0-9]+$" || true)"
        [ -n "$port" ] || port=8080
        break
      fi
      kill -0 "$pid" 2>/dev/null || { echo "GraphHopper exited early"; tail -50 "$work/gh.log"; exit 1; }
      sleep 2
    done
    if [ -z "$port" ]; then
      echo "no listening port found"; tail -50 "$work/gh.log"; kill "$pid" 2>/dev/null || true; exit 1
    fi
    echo "GraphHopper server listening on port $port"
    grep -E "Started Server|profiles" "$work/gh.log" | tail -5

    qs=""
    IFS=";" read -ra pts <<< "$points"
    for p in "${pts[@]}"; do qs="$qs&point=$p"; done
    code="$(curl -s -o "$work/route.json" -w "%{http_code}" \
      "http://127.0.0.1:$port/route?profile=motorcycle&ch.disable=true&instructions=false&calc_points=false${qs}")"
    echo "route HTTP $code"
    head -c 1200 "$work/route.json"; echo
    kill "$pid" 2>/dev/null || true
    rm -rf "$work/extract"
    [ "$code" = "200" ]
  ' _ "$REGION" "$fp" "$POINTS"
