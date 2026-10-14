#!/usr/bin/env bash
# Echo vs WireMock throughput benchmark with wrk.
#
# Starts each server in turn on the same port with the same JVM settings, loads
# equivalent rules / stubs, checks that every scenario answers 200, warms up, then
# runs the same wrk scenarios against both:
#   fixed JSON (4, 8 and 20 connections), a response template, ~1.5 KB and ~100 KB XML
#   bodies matched by an XPath condition, and a random one of 2,000 path rules.
# After each run it records heap after a full GC, RSS, thread count and startup time.
#
# Usage:
#   scripts/bench-vs-wiremock.sh ECHO_JAR WIREMOCK_JAR [OUT_DIR] [ROUNDS]
#
# Options (environment):
#   ECHO_BENCH_PATH_RULES=0  do not load the 2,000 path rules (measures the cost of many rules)
#   ECHO_BENCH_TYPE_LAST=1   put the XPath condition field at the end of the XML bodies
#
# Linux only. Requires: java 17+, wrk, curl, python3, jcmd, bc. Uses port 18080, which must be free.
# Variants per round: Echo (request log on), WireMock (request journal on),
# Echo with the request log off, WireMock with --no-request-journal and with
# --max-request-journal-entries 10000 (Echo keeps 10,000 request logs by default).
# Results: one directory per run with wrk output; print a summary with
#   python3 scripts/bench-vs-wiremock-summary.py OUT_DIR
set -u

if [ $# -lt 2 ] || [ $# -gt 4 ] || [ ! -f "$1" ] || [ ! -f "$2" ]; then
  echo "usage: $0 ECHO_JAR WIREMOCK_JAR [OUT_DIR] [ROUNDS]  (exactly one jar file for each)" >&2
  exit 2
fi
ECHO_JAR=$(realpath "$1")
WM_JAR=$(realpath "$2")
OUT_ROOT=$(realpath -m "${3:-./bench-vs-wiremock-$(date +%Y%m%d-%H%M%S)}")
ROUNDS=${4:-2}
PATH_RULES=${ECHO_BENCH_PATH_RULES:-1}
TYPE_LAST=${ECHO_BENCH_TYPE_LAST:-0}
PORT=18080
BASE=http://127.0.0.1:$PORT
JVM=(-Xms1g -Xmx1g -XX:+UseG1GC)

mkdir -p "$OUT_ROOT"
DATA=$(mktemp -d)
SERVER_PID=
WORK=

stop_server() {
  if [ -n "$SERVER_PID" ]; then kill "$SERVER_PID" 2>/dev/null; wait "$SERVER_PID" 2>/dev/null; fi
  SERVER_PID=
  if [ -n "$WORK" ]; then rm -rf "$WORK"; fi
  WORK=
}
trap 'stop_server; rm -rf "$DATA"' EXIT
trap 'exit 130' INT TERM

# ---- test data -------------------------------------------------------------
python3 - "$DATA" "$PATH_RULES" "$TYPE_LAST" <<'PY'
import json, sys
d, path_rules, type_last = sys.argv[1], sys.argv[2] != "0", sys.argv[3] == "1"

def order(items):
    if type_last:
        return f"<Order><Id>1</Id>{items}<Type>VIP</Type></Order>"
    return f"<Order><Id>1</Id><Type>VIP</Type>{items}</Order>"

# ~1.5 KB and ~100 KB orders
open(f"{d}/small.xml", "w").write(order('<Item sku="A1" qty="2"/>' * 60))
open(f"{d}/large.xml", "w").write(order(('<Item sku="A1" qty="2" note="' + "x" * 48 + '"/>') * 1300))
count = 2000 if path_rules else 0
json.dump([{"protocol": "HTTP", "targetHost": "bench.mock", "matchKey": f"/item/{i}", "method": "GET",
            "status": 200, "responseBody": json.dumps({"item": i})} for i in range(count)],
          open(f"{d}/echo-items.json", "w"))
wm = [
    {"request": {"method": "GET", "urlPath": "/fixed"},
     "response": {"status": 200, "body": '{"ok":true,"name":"fixed"}', "headers": {"Content-Type": "application/json"}}},
    {"request": {"method": "GET", "urlPath": "/tpl"},
     "response": {"status": 200, "body": '{"id":"{{request.query.id}}","path":"{{request.path}}"}',
                  "transformers": ["response-template"], "headers": {"Content-Type": "application/json"}}},
    {"request": {"method": "POST", "urlPath": "/x/small", "bodyPatterns": [{"matchesXPath": "//Order/Type[text()='VIP']"}]},
     "response": {"status": 200, "body": "<ok/>"}},
    {"request": {"method": "POST", "urlPath": "/x/large", "bodyPatterns": [{"matchesXPath": "//Order/Type[text()='VIP']"}]},
     "response": {"status": 200, "body": "<ok/>"}},
] + [{"request": {"method": "GET", "urlPath": f"/item/{i}"},
      "response": {"status": 200, "body": json.dumps({"item": i}), "headers": {"Content-Type": "application/json"}}}
     for i in range(count)]
json.dump({"mappings": wm}, open(f"{d}/wm-mappings.json", "w"))
for size in ("small", "large"):
    open(f"{d}/post-{size}.lua", "w").write(
        'wrk.method = "POST"\n'
        f'local f = io.open("{d}/{size}.xml", "r"); wrk.body = f:read("*a"); f:close()\n'
        'wrk.headers["Content-Type"] = "application/xml"\n'
        'wrk.headers["X-Original-Host"] = "bench.mock"\n')
for kind, prefix in (("echo", "/mock"), ("wm", "")):
    open(f"{d}/items-{kind}.lua", "w").write(
        'math.randomseed(os.time())\n'
        'wrk.headers["X-Original-Host"] = "bench.mock"\n'
        'request = function()\n'
        f'  return wrk.format("GET", "{prefix}/item/" .. math.random(0, 1999))\n'
        'end\n')
PY

# ---- one run ---------------------------------------------------------------
run() {  # run LABEL ROUND echo|wm [server args...]
  local label=$1 round=$2 kind=$3; shift 3
  local out=$OUT_ROOT/$label-r$round prefix ready start ok
  mkdir -p "$out"
  if curl -s -o /dev/null --max-time 2 "$BASE/"; then echo "port $PORT busy" > "$out/ABORTED"; return 1; fi
  WORK=$(mktemp -d)
  uptime > "$out/load-before.txt"
  start=$(date +%s.%N)
  if [ "$kind" = echo ]; then
    (cd "$WORK" && exec java "${JVM[@]}" -jar "$ECHO_JAR" --server.port=$PORT --echo.backup.enabled=false \
      --echo.backup.on-shutdown=false --echo.cleanup.enabled=false \
      --echo.features.bulk-import-export-enabled=true "$@") > "$out/server.log" 2>&1 &
    ready=$BASE/api/admin/status; prefix=/mock
  else
    (cd "$WORK" && exec java "${JVM[@]}" -jar "$WM_JAR" --port $PORT --disable-banner "$@") > "$out/server.log" 2>&1 &
    ready=$BASE/__admin/health; prefix=
  fi
  SERVER_PID=$!
  ok=
  for _ in $(seq 1 240); do
    if [ "$(curl -s -o /dev/null -w '%{http_code}' -H 'Accept: application/json' "$ready")" = 200 ]; then ok=1; break; fi
    sleep 0.25
  done
  if [ -z "$ok" ]; then echo "server not ready within 60 s" > "$out/ABORTED"; stop_server; return 1; fi
  echo "startupSec $(echo "$(date +%s.%N) - $start" | bc)" > "$out/startup.txt"

  local h='X-Original-Host: bench.mock'
  if [ "$kind" = echo ]; then
    curl -s -c "$WORK/cookies" -o /dev/null -X POST -d 'username=admin&password=admin' "$BASE/api/auth/login"
    api() { curl -s -b "$WORK/cookies" -H 'Accept: application/json' -H 'Content-Type: application/json' "$@"; }
    api -X POST "$BASE/api/admin/rules" -d '{"protocol":"HTTP","targetHost":"bench.mock","matchKey":"/fixed","method":"GET","status":200,"responseBody":"{\"ok\":true,\"name\":\"fixed\"}"}' > /dev/null
    api -X POST "$BASE/api/admin/rules" -d '{"protocol":"HTTP","targetHost":"bench.mock","matchKey":"/tpl","method":"GET","status":200,"responseBody":"{\"id\":\"{{request.query.id}}\",\"path\":\"{{request.path}}\"}"}' > /dev/null
    api -X POST "$BASE/api/admin/rules" -d '{"protocol":"HTTP","targetHost":"bench.mock","matchKey":"/x/small","method":"POST","status":200,"bodyCondition":"//Order/Type=VIP","responseBody":"<ok/>"}' > /dev/null
    api -X POST "$BASE/api/admin/rules" -d '{"protocol":"HTTP","targetHost":"bench.mock","matchKey":"/x/large","method":"POST","status":200,"bodyCondition":"//Order/Type=VIP","responseBody":"<ok/>"}' > /dev/null
    if [ "$PATH_RULES" != 0 ]; then
      api -X POST "$BASE/api/admin/rules/import-batch" --data-binary @"$DATA/echo-items.json" > "$out/import.txt"
    fi
  else
    curl -s -X POST -H 'Content-Type: application/json' "$BASE/__admin/mappings/import" \
      --data-binary @"$DATA/wm-mappings.json" > "$out/import.txt"
  fi
  # Every scenario must answer 200 before it is measured
  { curl -s -o /dev/null -w 'fixed=%{http_code}\n' -H "$h" "$BASE$prefix/fixed"
    curl -s -o /dev/null -w 'tpl=%{http_code}\n' -H "$h" "$BASE$prefix/tpl?id=7"
    curl -s -o /dev/null -w 'small=%{http_code}\n' -H "$h" -H 'Content-Type: application/xml' --data-binary @"$DATA/small.xml" "$BASE$prefix/x/small"
    curl -s -o /dev/null -w 'large=%{http_code}\n' -H "$h" -H 'Content-Type: application/xml' --data-binary @"$DATA/large.xml" "$BASE$prefix/x/large"
    if [ "$PATH_RULES" != 0 ]; then curl -s -o /dev/null -w 'item1999=%{http_code}\n' -H "$h" "$BASE$prefix/item/1999"; fi
  } > "$out/setup-check.txt" 2>&1
  if grep -vqE '=200$' "$out/setup-check.txt"; then
    echo "a scenario did not answer 200 (see setup-check.txt)" > "$out/ABORTED"; stop_server; return 1
  fi

  wrk -t4 -c8 -d15s -H "$h" "$BASE$prefix/fixed" > /dev/null 2>&1
  wrk -t2 -c4 -d5s -s "$DATA/post-small.lua" "$BASE$prefix/x/small" > /dev/null 2>&1
  if [ "$PATH_RULES" != 0 ]; then wrk -t2 -c4 -d5s -s "$DATA/items-$kind.lua" "$BASE" > /dev/null 2>&1; fi
  for c in 4 8 20; do
    wrk -t4 -c$c -d10s --latency -H "$h" "$BASE$prefix/fixed" > "$out/wrk-fixed-c$c.txt" 2>&1
  done
  wrk -t4 -c8 -d10s --latency -H "$h" "$BASE$prefix/tpl?id=7" > "$out/wrk-tpl.txt" 2>&1
  wrk -t4 -c8 -d10s --latency -s "$DATA/post-small.lua" "$BASE$prefix/x/small" > "$out/wrk-xml-small.txt" 2>&1
  wrk -t4 -c8 -d10s --latency -s "$DATA/post-large.lua" "$BASE$prefix/x/large" > "$out/wrk-xml-large.txt" 2>&1
  if [ "$PATH_RULES" != 0 ]; then
    wrk -t4 -c8 -d10s --latency -s "$DATA/items-$kind.lua" "$BASE" > "$out/wrk-items2000.txt" 2>&1
  fi

  sleep 3
  jcmd "$SERVER_PID" GC.run > /dev/null
  sleep 2
  jcmd "$SERVER_PID" GC.heap_info > "$out/heap-after-gc.txt"
  grep -E 'VmRSS|VmHWM|Threads' "/proc/$SERVER_PID/status" > "$out/proc.txt"
  stop_server
  echo done > "$out/DONE"
}

for round in $(seq 1 "$ROUNDS"); do
  run echo "$round" echo; sleep 3
  run wiremock "$round" wm; sleep 3
  run echo-log-off "$round" echo --echo.request-log.enabled=false; sleep 3
  run wiremock-no-journal "$round" wm --no-request-journal; sleep 3
  run wiremock-journal-10k "$round" wm --max-request-journal-entries 10000; sleep 3
done
echo "Results in $OUT_ROOT"
