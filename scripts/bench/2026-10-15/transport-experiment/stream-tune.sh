#!/usr/bin/env bash
# Opt-in native HTTP prototype vs WireMock 3.13.2, same JVM/heap/4 I/O/32 workers.
# Usage: PERF_MODE=matrix bash matrix.sh EXPERIMENT_JAR WIREMOCK_JAR OUT_DIR [ROUNDS]
# matrix: 13 workloads, 2010 rules, both request logs off; Echo/WM then WM/Echo.
# WireMock async responses enabled, 32 container and async threads.
# recovery: native Echo with its normal request-log defaults and burst/recovery phases.
# profile: native Echo only, separate JFR sample (not the throughput comparison).
# Requires java, jcmd, wrk, curl, python3, node. Uses ports 18080/18081 and fresh temp DBs.
# OUT_DIR must be new: per-run directories are replaced.
set -u
case "${PERF_MODE:-matrix}" in
  matrix|profile|recovery) ;;
  *) echo "Unknown PERF_MODE" >&2; exit 2 ;;
esac

if [ $# -lt 2 ] || [ $# -gt 4 ] || [ ! -f "$1" ] || [ ! -f "$2" ]; then
  echo "usage: $0 ECHO_JAR WIREMOCK_JAR [OUT_DIR] [ROUNDS]  (exactly one jar file for each)" >&2
  exit 2
fi
ECHO_JAR=$(realpath "$1")
WM_JAR=$(realpath "$2")
OUT_ROOT=$(realpath -m "${3:-./bench-vs-wiremock-$(date +%Y%m%d-%H%M%S)}")
ROUNDS=${4:-2}
if ! [[ "$ROUNDS" =~ ^[1-9][0-9]*$ ]]; then
  echo "ROUNDS must be a positive integer, got '$ROUNDS'" >&2
  exit 2
fi
for tool in java wrk curl python3 jcmd; do
  command -v "$tool" > /dev/null || { echo "missing required tool: $tool" >&2; exit 2; }
done
PATH_RULES=${ECHO_BENCH_PATH_RULES:-1}
TYPE_LAST=${ECHO_BENCH_TYPE_LAST:-0}
PORT=18080
BASE=http://127.0.0.1:$PORT
JVM=(-Xms1g -Xmx1g -XX:+UseG1GC)
# Echo must not pick up a profile, datasource or credentials from the caller's environment
CLEAN_ENV=()
while IFS= read -r name; do CLEAN_ENV+=(-u "$name"); done < <(env | grep -oE '^(SPRING|ECHO)_[A-Za-z0-9_]*' || true)

mkdir -p "$OUT_ROOT"
DATA=$(mktemp -d)
SERVER_PID=
DOWNSTREAM_PID=
WORK=

stop_server() {
  if [ -n "$SERVER_PID" ]; then kill "$SERVER_PID" 2>/dev/null; wait "$SERVER_PID" 2>/dev/null; fi
  SERVER_PID=
  if [ -n "$WORK" ]; then rm -rf "$WORK"; fi
  WORK=
}
trap 'stop_server; if [ -n "$DOWNSTREAM_PID" ]; then kill "$DOWNSTREAM_PID" 2>/dev/null; wait "$DOWNSTREAM_PID" 2>/dev/null; fi; rm -rf "$DATA"' EXIT
trap 'exit 130' INT TERM

if [ "${PERF_MODE:-matrix}" = matrix ]; then
  command -v node >/dev/null || exit 2
  if curl -s --max-time 2 http://127.0.0.1:18081/ >/dev/null; then echo "downstream port busy" >&2; exit 2; fi
  node "$(dirname "$(realpath "$0")")/downstream.mjs" > "$OUT_ROOT/downstream.log" 2>&1 &
  DOWNSTREAM_PID=$!
  ready=
  for attempt in $(seq 1 40); do
    if curl -fsS --max-time 1 http://127.0.0.1:18081/ >/dev/null 2>&1; then ready=1; break; fi
    sleep 0.1
  done
  if [ -z "$ready" ]; then echo "downstream not ready" >&2; exit 1; fi
  wrk -t4 -c64 -d5s http://127.0.0.1:18081/forward-fast >/dev/null
  wrk -t4 -c64 -d10s --latency http://127.0.0.1:18081/forward-fast > "$OUT_ROOT/downstream-direct.txt"
fi

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
last = '<Order><Id>1</Id>' + ('<Item sku="A1" qty="2" note="' + 'x'*48 + '"/>')*1300 + '<Type>VIP</Type></Order>'
open(f"{d}/last.xml", "w").write(last)
open(f"{d}/post-last.lua", "w").write('wrk.method="POST"\n' + f'local f=io.open("{d}/last.xml","r"); wrk.body=f:read("*a"); f:close()\n' + 'wrk.headers["Content-Type"]="application/xml"\nwrk.headers["X-Original-Host"]="bench.mock"\n')
json.dump({"protocol":"HTTP","targetHost":"bench.mock","matchKey":"/big-response","method":"GET","status":200,"responseBody":"x"*262144},open(f"{d}/big-rule.json","w"))
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
wm += [
    {"request":{"method":"GET","urlPath":"/"+path}, "response":{"proxyBaseUrl":"http://127.0.0.1:18081"}}
    for path in ("forward-fast", "forward-slow", "forward-error")
] + [
    {"request":{"method":"GET","urlPath":"/delay"}, "response":{"status":200,"body":"delayed","fixedDelayMilliseconds":50}},
    {"request":{"method":"POST","urlPath":"/x/last","bodyPatterns":[{"matchesXPath":"//Order/Type[text()='VIP']"}]}, "response":{"status":200,"body":"<ok/>"}},
    {"request":{"method":"GET","urlPath":"/big-response"}, "response":{"status":200,"body":"x"*262144}},
]
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
  rm -rf "$out"
  mkdir -p "$out"
  if curl -s -o /dev/null --max-time 2 "$BASE/"; then echo "port $PORT busy" > "$out/ABORTED"; return 1; fi
  WORK=$(mktemp -d)
  uptime > "$out/load-before.txt"
  start=$(date +%s.%N)
  if [ "$kind" = echo ]; then
    (cd "$WORK" && exec env ${CLEAN_ENV[@]+"${CLEAN_ENV[@]}"} java "${JVM[@]}" -jar "$ECHO_JAR" --server.port=$PORT \
      --spring.profiles.active=default \
      "--spring.datasource.url=jdbc:sqlite:$WORK/data/bench.sqlite?journal_mode=WAL&busy_timeout=10000&synchronous=NORMAL&foreign_keys=ON" \
      --echo.backup.enabled=false --echo.backup.on-shutdown=false --echo.cleanup.enabled=false \
      --echo.features.bulk-import-export-enabled=true "$@") > "$out/server.log" 2>&1 &
    ready=$BASE/api/admin/status; prefix=/mock
  else
    (cd "$WORK" && exec java "${JVM[@]}" -jar "$WM_JAR" --port $PORT --disable-banner "$@") > "$out/server.log" 2>&1 &
    ready=$BASE/__admin/health; prefix=
  fi
  SERVER_PID=$!
  ok=
  local ready_deadline=$((SECONDS + 60))
  while [ "$SECONDS" -lt "$ready_deadline" ]; do
    if [ "$(curl -s --max-time 2 -o /dev/null -w '%{http_code}' -H 'Accept: application/json' "$ready")" = 200 ]; then ok=1; break; fi
    kill -0 "$SERVER_PID" 2>/dev/null || break
    sleep 0.25
  done
  if [ -z "$ok" ]; then echo "server not ready within 60 s" > "$out/ABORTED"; stop_server; return 1; fi
  local startup
  startup=$(python3 -c 'import sys,time; print(time.time()-float(sys.argv[1]))' "$start") && [ -n "$startup" ] \
    || { echo "could not compute the startup time" > "$out/ABORTED"; stop_server; return 1; }
  echo "startupSec $startup" > "$out/startup.txt"

  local h='X-Original-Host: bench.mock'
  if [ "$kind" = echo ]; then
    curl -s --max-time 30 -c "$WORK/cookies" -o /dev/null -X POST -d 'username=admin&password=admin' "$BASE/api/auth/login"
    api() { curl -s --max-time 120 -b "$WORK/cookies" -H 'Accept: application/json' -H 'Content-Type: application/json' "$@"; }
    api -X POST "$BASE/api/admin/rules" -d '{"protocol":"HTTP","targetHost":"bench.mock","matchKey":"/fixed","method":"GET","status":200,"responseBody":"{\"ok\":true,\"name\":\"fixed\"}"}' > /dev/null
    api -X POST "$BASE/api/admin/rules" -d '{"protocol":"HTTP","targetHost":"bench.mock","matchKey":"/tpl","method":"GET","status":200,"responseBody":"{\"id\":\"{{request.query.id}}\",\"path\":\"{{request.path}}\"}"}' > /dev/null
    api -X POST "$BASE/api/admin/rules" -d '{"protocol":"HTTP","targetHost":"bench.mock","matchKey":"/x/small","method":"POST","status":200,"bodyCondition":"//Order/Type=VIP","responseBody":"<ok/>"}' > /dev/null
    api -X POST "$BASE/api/admin/rules" -d '{"protocol":"HTTP","targetHost":"bench.mock","matchKey":"/x/large","method":"POST","status":200,"bodyCondition":"//Order/Type=VIP","responseBody":"<ok/>"}' > /dev/null
    if [ "${PERF_MODE:-matrix}" = matrix ]; then
      api -X POST "$BASE/api/admin/http-target-connections" -d '{"name":"local-perf","baseUrl":"http://127.0.0.1:18081","authType":"NONE","connectTimeoutSeconds":2,"readTimeoutSeconds":3,"enabled":true,"tlsVerificationEnabled":true}' > "$out/target.json"
      local target_id
      target_id=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["id"])' "$out/target.json") || { echo "target setup failed" > "$out/ABORTED"; stop_server; return 1; }
      for path in forward-fast forward-slow forward-error; do
        api -X POST "$BASE/api/admin/rules" -d "{\"protocol\":\"HTTP\",\"targetHost\":\"bench.mock\",\"matchKey\":\"/$path\",\"method\":\"GET\",\"action\":\"FORWARD\",\"forwardTargetMode\":\"CONNECTION\",\"httpTargetConnectionId\":$target_id}" > "$out/setup-$path.json"
      done
      api -X POST "$BASE/api/admin/rules" -d '{"protocol":"HTTP","targetHost":"bench.mock","matchKey":"/delay","method":"GET","status":200,"delayMs":50,"responseBody":"delayed"}' > "$out/setup-delay.json"
      api -X POST "$BASE/api/admin/rules" -d '{"protocol":"HTTP","targetHost":"bench.mock","matchKey":"/x/last","method":"POST","status":200,"bodyCondition":"//Order/Type=VIP","responseBody":"<ok/>"}' > "$out/setup-last.json"
      api -X POST "$BASE/api/admin/rules" --data-binary @"$DATA/big-rule.json" > "$out/setup-big.json"
    fi
    if [ "$PATH_RULES" != 0 ]; then
      api -X POST "$BASE/api/admin/rules/import-batch" --data-binary @"$DATA/echo-items.json" > "$out/import.txt"
    fi
  else
    curl -s --max-time 120 -X POST -H 'Content-Type: application/json' "$BASE/__admin/mappings/import" \
      --data-binary @"$DATA/wm-mappings.json" > "$out/import.txt"
  fi
  # Every scenario must answer 200 with the expected body before it is measured
  # (the template must be rendered, the XML condition must match)
  local failed=
  check() {  # check NAME EXPECTED_BODY curl-args...
    local name=$1 expected=$2 actual; shift 2
    actual=$(curl -s --max-time 30 -w '|%{http_code}' -H "$h" "$@")
    echo "$name=$actual" >> "$out/setup-check.txt"
    [ "$actual" = "$expected|200" ] || failed="$failed $name"
  }
  : > "$out/setup-check.txt"
  check fixed '{"ok":true,"name":"fixed"}' "$BASE$prefix/fixed"
  check tpl '{"id":"7","path":"/tpl"}' "$BASE$prefix/tpl?id=7"
  check small '<ok/>' -H 'Content-Type: application/xml' --data-binary @"$DATA/small.xml" "$BASE$prefix/x/small"
  check large '<ok/>' -H 'Content-Type: application/xml' --data-binary @"$DATA/large.xml" "$BASE$prefix/x/large"
  if [ "$PATH_RULES" != 0 ]; then check item1999 '{"item": 1999}' "$BASE$prefix/item/1999"; fi
  check_workloads() {
    check forward-fast '{"upstream":true,"delayMs":0}' "$BASE$prefix/forward-fast"
    check forward-slow '{"upstream":true,"delayMs":50}' "$BASE$prefix/forward-slow"
    check delay delayed "$BASE$prefix/delay"
    check last '<ok/>' -H 'Content-Type: application/xml' --data-binary @"$DATA/last.xml" "$BASE$prefix/x/last"
    local error_response
    error_response=$(curl -s --max-time 5 -w '|%{http_code}' -H "$h" "$BASE$prefix/forward-error")
    echo "forward-error=$error_response" >> "$out/setup-check.txt"
    [ "$error_response" = '{"upstream":true,"delayMs":0}|503' ] || failed="$failed forward-error"
    curl -fsS --max-time 10 -H "$h" "$BASE$prefix/big-response" -o "$out/big-response.bin" || failed="$failed big-response"
    python3 -c 'import pathlib,sys; assert pathlib.Path(sys.argv[1]).read_bytes()==b"x"*262144' "$out/big-response.bin" || failed="$failed big-response-body"
    local elapsed
    elapsed=$(curl -fsS --max-time 5 -o /dev/null -w '%{time_total}' -H "$h" "$BASE$prefix/delay")
    echo "delaySeconds=$elapsed" >> "$out/setup-check.txt"
    python3 -c 'import sys; assert float(sys.argv[1]) >= 0.045' "$elapsed" || failed="$failed delay-duration"
  }
  if [ "${PERF_MODE:-matrix}" = matrix ]; then check_workloads; fi
  if [ -n "$failed" ]; then
    echo "unexpected answer from:$failed (see setup-check.txt)" > "$out/ABORTED"; stop_server; return 1
  fi

  wrk -t4 -c8 -d15s -H "$h" "$BASE$prefix/fixed" > /dev/null 2>&1
  wrk -t2 -c4 -d5s -s "$DATA/post-small.lua" "$BASE$prefix/x/small" > /dev/null 2>&1
  if [ "$PATH_RULES" != 0 ]; then wrk -t2 -c4 -d5s -s "$DATA/items-$kind.lua" "$BASE" > /dev/null 2>&1; fi
  measure() {
    local name=$1 duration=$2; shift 2
    echo "$(date -u +%FT%TZ) $label r$round $name" >> "$OUT_ROOT/progress.log"
    local prewarm=5
    if [ "${PERF_MODE:-matrix}" = matrix ]; then prewarm=3; fi
    wrk -t4 -d"${prewarm}s" "$@" > "$out/warm-$name.txt" 2>&1
    cp /sys/fs/cgroup/cpu.stat "$out/quota-$name-before.txt"
    cp "/proc/$SERVER_PID/stat" "$out/cpu-$name-before.txt"
    if ! wrk -t4 -d"${duration}s" --latency "$@" > "$out/wrk-$name.txt" 2>&1 \
        || ! grep -q 'Requests/sec' "$out/wrk-$name.txt"; then
      failed="$failed wrk-$name"
    fi
    cp /sys/fs/cgroup/cpu.stat "$out/quota-$name-after.txt"
    cp "/proc/$SERVER_PID/stat" "$out/cpu-$name-after.txt"
    grep -E 'VmRSS|VmHWM|Threads' "/proc/$SERVER_PID/status" > "$out/proc-$name.txt"
    if [ "${PERF_MODE:-matrix}" = recovery ]; then
      api "$BASE/api/admin/agents" > "$out/agents-$name.json"
    fi
  }
  if [ "${PERF_MODE:-matrix}" = recovery ]; then
    (
      while kill -0 "$SERVER_PID" 2>/dev/null; do
        date -u +%FT%TZ
        grep -E 'VmRSS|VmHWM|Threads' "/proc/$SERVER_PID/status" 2>/dev/null
        sleep 2
      done
    ) > "$out/memory-timeline.txt" &
    api "$BASE/api/admin/agents" > "$out/agents-initial.json"
  fi
  if [ "${PERF_MODE:-matrix}" = profile ]; then
    jcmd "$SERVER_PID" JFR.start name=perf settings=profile filename="$out/profile.jfr" > "$out/jfr-start.txt"
    measure fixed-c8 45 -c8 -H "$h" "$BASE$prefix/fixed"
    jcmd "$SERVER_PID" JFR.stop name=perf > "$out/jfr-stop.txt"
  elif [ "${PERF_MODE:-matrix}" = matrix ]; then
    measure fixed-c8 10 -c8 -H "$h" "$BASE$prefix/fixed"
    measure fixed-c64 10 -c64 -H "$h" "$BASE$prefix/fixed"
    measure fixed-c256 10 -c256 -H "$h" "$BASE$prefix/fixed"
    measure tpl 10 -c8 -H "$h" "$BASE$prefix/tpl?id=7"
    measure xml-large 10 -c8 -s "$DATA/post-large.lua" "$BASE$prefix/x/large"
    measure xml-last 10 -c8 -s "$DATA/post-last.lua" "$BASE$prefix/x/last"
    check_workloads
    sleep 2
    if [ "$kind" = echo ]; then
    api "$BASE/api/admin/http-target-connections/metrics" > "$out/forward-metrics-after.json"
    python3 - "$out/forward-metrics-after.json" <<'CHECK'
import json,sys
m=json.load(open(sys.argv[1])); p=m['pool']
assert m['activeForwards']==0 and p['leased']==0 and p['pending']==0 and p['bufferedResponseBytes']==0, m
assert m['rejectedForwards']==0, m
assert all(t['failed']==0 for t in m['targets'].values()), m
CHECK
    if [ $? -ne 0 ]; then failed="$failed forward-resources"; fi
    fi
  elif [ "${PERF_MODE:-matrix}" = io-compare ]; then
    measure fixed-c8 30 -c8 -H "$h" "$BASE$prefix/fixed"
    if [ "$round" = 2 ]; then
      jcmd "$SERVER_PID" JFR.start name=perf settings=profile filename="$out/profile.jfr" > "$out/jfr-start.txt"
      measure profile-c8 20 -c8 -H "$h" "$BASE$prefix/fixed"
      jcmd "$SERVER_PID" JFR.stop name=perf > "$out/jfr-stop.txt"
    fi
  elif [ "${PERF_MODE:-matrix}" = recovery ]; then
    measure before-c8 20 -c8 -H "$h" "$BASE$prefix/fixed"
    jcmd "$SERVER_PID" GC.run > /dev/null
    jcmd "$SERVER_PID" GC.heap_info > "$out/heap-before.txt"
    measure pressure-c256 60 -c256 -H "$h" "$BASE$prefix/fixed"
    measure after-c8 20 -c8 -H "$h" "$BASE$prefix/fixed"
    sleep 10
    api "$BASE/api/admin/agents" > "$out/agents-idle10s.json"
    measure recovered-c8 20 -c8 -H "$h" "$BASE$prefix/fixed"
    if [ "$kind" = echo ]; then
      api "$BASE/api/admin/status" > "$out/status-after.json"
      sleep 20
      api "$BASE/api/admin/agents" > "$out/agents-final-idle20s.json"
    fi
  else
    measure fixed-c8 20 -c8 -H "$h" "$BASE$prefix/fixed"
    measure tpl 10 -c8 -H "$h" "$BASE$prefix/tpl?id=7"
    measure xml-small 10 -c8 -s "$DATA/post-small.lua" "$BASE$prefix/x/small"
    measure xml-large 10 -c8 -s "$DATA/post-large.lua" "$BASE$prefix/x/large"
    if [ "$PATH_RULES" != 0 ]; then measure items2000 10 -c8 -s "$DATA/items-$kind.lua" "$BASE"; fi
  fi

  sleep 3
  jcmd "$SERVER_PID" GC.run > /dev/null || failed="$failed jcmd"
  sleep 2
  jcmd "$SERVER_PID" GC.heap_info > "$out/heap-after-gc.txt" || failed="$failed jcmd"
  grep -E 'VmRSS|VmHWM|Threads' "/proc/$SERVER_PID/status" > "$out/proc.txt" || failed="$failed proc"
  stop_server
  if [ -n "$failed" ]; then echo "failed:$failed" > "$out/ABORTED"; return 1; fi
  echo done > "$out/DONE"
}

failures=0
run native-w32-io4 1 echo --echo.http.transport=native-handoff-stream --echo.request-log.enabled=false --server.undertow.threads.io=4 --server.undertow.threads.worker=32 || failures=$((failures + 1))
run native-w8-io2 1 echo --echo.http.transport=native-handoff-stream --echo.request-log.enabled=false --server.undertow.threads.io=2 --server.undertow.threads.worker=8 || failures=$((failures + 1))
[ "$failures" -eq 0 ]
