#!/usr/bin/env bash
# Watch a map-server build job on the deployment host.
#
# Waits for the job to leave queued/running while sampling `docker stats`, then
# writes an evidence summary: elapsed time, peak memory and CPU, and disk/free
# before and after. Started in the background by `deploy.sh watch`; safe to run
# by hand.
#
#   build-watch.sh --url http://127.0.0.1:8082 --out ~/curvemaps-map-server/logs \
#                  --container curvemaps-map-server --region queensland [--job ID]
set -uo pipefail

URL="http://127.0.0.1:8080"
OUT="./logs"
CONTAINER="curvemaps-map-server"
REGION="queensland"
JOB=""
INTERVAL=15
TIMEOUT=14400

while [ $# -gt 0 ]; do
  case "$1" in
    --url) URL="$2"; shift 2 ;;
    --out) OUT="$2"; shift 2 ;;
    --container) CONTAINER="$2"; shift 2 ;;
    --region) REGION="$2"; shift 2 ;;
    --job) JOB="$2"; shift 2 ;;
    --interval) INTERVAL="$2"; shift 2 ;;
    --timeout) TIMEOUT="$2"; shift 2 ;;
    *) echo "build-watch.sh: unknown option $1" >&2; exit 2 ;;
  esac
done

mkdir -p "$OUT"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
csv="$OUT/stats-$stamp.csv"
summary="$OUT/build-$stamp.log"
before="$OUT/before-$stamp.txt"
after="$OUT/after-$stamp.txt"

echo "timestamp,container,cpu_percent,mem_usage,mem_percent,net_io,block_io,pids" > "$csv"
{ date -u; echo "== free -h"; free -h; echo "== df -h /"; df -h /; } > "$before"

start_epoch="$(date +%s)"
if [ -z "$JOB" ]; then
  JOB="$(curl -fsS "$URL/api/v1/builds?regionId=$REGION&limit=1" 2>/dev/null | jq -r '.jobs[0].id // empty')"
fi
if [ -z "$JOB" ]; then
  echo "build-watch.sh: no job found for $REGION" >&2
  exit 1
fi
echo "watching job $JOB ($URL)"

state="unknown"
while :; do
  now="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  stats="$(docker stats --no-stream --format '{{.CPUPerc}},{{.MemUsage}},{{.MemPerc}},{{.NetIO}},{{.BlockIO}},{{.PIDs}}' "$CONTAINER" 2>/dev/null | head -1)"
  echo "$now,$CONTAINER,${stats:-,not-running}" >> "$csv"

  json="$(curl -fsS "$URL/api/v1/builds/$JOB" 2>/dev/null || true)"
  state="$(printf '%s' "$json" | jq -r '.state // "unknown"' 2>/dev/null)"
  [ -n "$state" ] || state="unknown"

  elapsed=$(( $(date +%s) - start_epoch ))
  if [ "$state" != "queued" ] && [ "$state" != "running" ] && [ "$state" != "unknown" ]; then
    break
  fi
  if [ "$elapsed" -ge "$TIMEOUT" ]; then
    echo "build-watch.sh: timed out after ${TIMEOUT}s" >&2
    break
  fi
  sleep "$INTERVAL"
done

{ date -u; echo "== free -h"; free -h; echo "== df -h /"; df -h /; } > "$after"
end_epoch="$(date +%s)"

peak="$(awk -F, '
  NR > 1 {
    split($4, parts, " / ")
    v = parts[1]; u = substr(v, length(v) - 2, 3)
    sub(/[A-Za-z]+$/, "", v)
    mult = (u == "GiB") ? 1073741824 : (u == "MiB") ? 1048576 : (u == "KiB") ? 1024 : 1
    b = v * mult
    if (b > peak) peak = b
    c = $3; sub(/%/, "", c); if (c + 0 > cpu) cpu = c + 0
  }
  END { printf "%d %s", peak, cpu }
' "$csv")"

{
  echo "job=$JOB state=$state region=$REGION container=$CONTAINER"
  echo "elapsed_seconds=$((end_epoch - start_epoch))"
  echo "peak_mem_bytes=${peak%% *}"
  echo "peak_cpu_percent=${peak##* }"
  echo "stats_csv=$csv"
  echo "job_json=$json"
} | tee "$summary"

# Bound the log directory: keep the newest samples only.
ls -1t "$OUT"/stats-*.csv 2>/dev/null | tail -n +21 | xargs -r rm -f
ls -1t "$OUT"/before-*.txt "$OUT"/after-*.txt 2>/dev/null | tail -n +41 | xargs -r rm -f
