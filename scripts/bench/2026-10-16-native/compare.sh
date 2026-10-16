#!/usr/bin/env bash
# Production jar, same 4 CPUs/JVM/heap; no profiler or concurrent builds.
# taskset -c 0-3 bash compare.sh ECHO_JAR WIREMOCK_JAR NEW_OUTPUT_DIR latency|matrix [rounds=2]
set -euo pipefail
if [ "$(uname -s)" != Linux ] || [ ! -r /sys/fs/cgroup/cpu.stat ]; then
  echo 'This benchmark requires Linux with cgroup v2 CPU statistics. Build and develop Echo on Windows with gradlew.bat; run deployment benchmarks on Linux.' >&2
  exit 2
fi
if [ "$#" -lt 4 ] || [ "$#" -gt 5 ] || [ -e "$3" ]; then
  echo 'usage: compare.sh ECHO_JAR WIREMOCK_JAR NEW_OUTPUT_DIR latency|matrix [rounds]' >&2
  exit 2
fi
case "$4" in latency|matrix) ;; *) exit 2 ;; esac
mkdir -p "$3"
sha256sum "$1" "$2" > "$3/artifacts.sha256"
java -version > "$3/java-version.txt" 2>&1
taskset -pc $$ > "$3/affinity.txt"
printf '%s\n' "${LATENCY_INCLUDE_PEAK:-0}" > "$3/latency-include-peak.txt"
cp /sys/fs/cgroup/cpu.stat "$3/quota-before.txt"
export NATIVE_HTTP_MODE=native
export ECHO_BENCH_WORKERS=8 ECHO_BENCH_IO=2
export WM_BENCH_WORKERS="${WM_BENCH_WORKERS:-32}" WM_LATENCY_WORKERS="${WM_LATENCY_WORKERS:-32}"
export PERF_MODE="$4"
# Historical workload definitions are kept unchanged to preserve the comparison contract.
bash "$(dirname "$0")/../2026-10-15/transport-experiment/matrix.sh" "$1" "$2" "$3" "${5:-2}"
cp /sys/fs/cgroup/cpu.stat "$3/quota-after.txt"
