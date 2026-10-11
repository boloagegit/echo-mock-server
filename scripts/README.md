# Benchmark & Test Scripts

Python scripts for performance benchmarking, migration, and regression testing. They require Python 3 and use only the Python standard library.

All scripts default to `http://localhost:8080` if no URL is provided.
Scripts that call the Admin API use the development credentials by default. Override them without editing source:

```bash
export ECHO_TEST_USERNAME=admin
export ECHO_TEST_PASSWORD='your-local-password'
```

## Scripts

| Script | Description |
|--------|-------------|
| `stress-test-rps.py` | RPS throughput test — measures requests per second; positional CLI plus optional machine-readable JSON |
| `stress-test-scenario1.py` | Single complex HTTP matching scenario latency |
| `stress-test-1600-rules.py` | Matching performance with 1,600 rules |
| `stress-test-xml-body.py` | XML vs JSON body size impact on matching |
| `stress-test-vs-wiremock.py` | Echo vs a separately running WireMock instance; reports RPS, latency, and errors |
| `bench-2000-jms.py` | JMS matching with 2,000 rules (ServiceName + CustId conditions) |
| `stress-test-jms-match.py` | JMS matching with 2,000 rules and 20-field XML body |
| `stress-test-memory.py` | Worst-case memory usage test |
| `stress-test-cache-isolation.py` | Verify HTTP/JMS cache isolation after split |
| `test-match-scenarios.py` | End-to-end regression — 55 scenarios / 138 assertions covering HTTP, JMS, logs, SSE, fault injection, and Scenario |
| `migrate-h2-to-sqlite.py` | Offline, staged H2-to-SQLite migration with row/digest/integrity verification and startup smoke test |
| `test-sqlite-crash-resilience.py` | SQLite WAL crash/restart and request-log durability regression |
| `test-sqlite-recovery-resilience.py` | Disposable SQLite lock, bounded JVM OOM, corruption fail-fast, verified-backup restore, and post-recovery writes |
| `test-rdbms-matrix.py` | Disposable Docker Compose matrix for H2, SQLite, PostgreSQL, MySQL, MariaDB, SQL Server, and Oracle; runs E2E/persistence checks, restart verification, and evidence collection |
| `test-container-resilience.py` | Disposable Compose JMS XML pressure, fixed-heap, SIGKILL/restart, ID accounting, Artemis paging, and bounded tmpfs high-water/full/recovery validation |
| `test-container-diagnostics.py` | Short real Echo + SQLite + JMS forwarding matrix under CPU/memory caps; 30s no-reply, non-text reply, correlation, bounded anomaly output and cleanup/recovery checks |
| `test-container-resource-soak.py` | Finite 10-minute fixed-rate HTTP/JMS forwarding resource probe, with two idle minutes, GC floors, RSS/cgroup/FD/connection/queue measurements and real caller reply verification |
| `perf-test-downstream.py` | Local downstream HTTP server for forwarding latency and body-limit tests |
| `tests/test_windows_script_compatibility.py` | Windows path, command, and process compatibility checks for Python scripts |

## Usage

```bash
# Start Echo first
./gradlew bootRun

# Run a benchmark
python3 scripts/stress-test-rps.py [URL] [DURATION] [CONCURRENCY]

# Run regression tests
python3 scripts/test-match-scenarios.py [URL]

# Run every disposable RDBMS profile (Docker is required).
# The run gets a unique Compose project name and temporary host ports.
python3 scripts/test-rdbms-matrix.py

# Run a selected profile without rebuilding the image; keep it running for inspection.
python3 scripts/test-rdbms-matrix.py --databases oracle --skip-build --keep

# Print the exact per-profile plan without invoking Docker.
python3 scripts/test-rdbms-matrix.py --databases h2,sqlite --dry-run

# Run the same RPS conditions for every selected database after functional
# and restart-persistence checks. Results are written into each DB result JSON.
python3 scripts/test-rdbms-matrix.py --databases h2,postgresql --performance

# The RPS script keeps its original positional form. Add --json for automation
# (non-2xx responses and request/transport errors return a non-zero exit code).
python3 scripts/stress-test-rps.py http://localhost:8080 10 20 --json

# If Echo was intentionally started with ECHO_REQUEST_LOG_ENABLED=false,
# declare that mode so the benchmark does not wait for a nonexistent log agent.
python3 scripts/stress-test-rps.py http://localhost:8080 10 20 \
  --request-log-disabled --json

# Validate cross-platform script behavior
python3 -m unittest scripts/tests/test_windows_script_compatibility.py

# Run the disposable container resilience harness (Docker and a built jar required)
./gradlew bootJar
python3 scripts/test-container-resilience.py --mode quick

# Include the separate small-volume disk-full experiment
python3 scripts/test-container-resilience.py --mode quick --skip-build --disk-full

# Exercise a persistent XML body larger than 100 KiB through kill/restart
python3 scripts/test-container-resilience.py --mode quick --messages 8 \
  --recovery-messages 2 --payload-bytes 131072 --max-body-bytes 262144

# Print the exact plan without starting Docker
python3 scripts/test-container-resilience.py --dry-run

# Validate SQLite lock recovery, deliberate bounded JVM OOM/restart, corruption
# fail-fast, and opt-in verified-backup restore. All databases are temporary.
./gradlew bootJar
python3 scripts/test-sqlite-recovery-resilience.py
```

Use disposable databases and ports for benchmarks. Do not point destructive or crash-resilience scripts at a production database.

For a short real-JMS anomaly-only diagnostics on/off comparison (no existing Echo service or
database required), run `./gradlew diagnosticCost -PdiagnosticRequests=3000`.
Results and broker files stay under ignored `artifacts/diagnostics-cost`.
For the actual HTTP application plus disposable SQLite/durable spool comparison,
run `./gradlew diagnosticApplicationCost -PdiagnosticRequests=1000`.
Both probes are short local measurements, not production capacity guarantees.
See [transaction diagnostic measurement limits](../docs/transaction-diagnostics.md).

For short default-enabled diagnostics checks under Docker resource limits:

```bash
docker pull eclipse-temurin:17-jdk
python3 scripts/test-container-diagnostics.py --dry-run
python3 scripts/test-container-diagnostics.py
python3 -m unittest discover -s scripts/tests -p test_container_diagnostics.py
```

This builds an opt-in test-only fixture jar with `./gradlew`, freezes the local
Java image identity, and runs two disposable Echo instances sequentially:
1 CPU / 1 GiB container / 512 MiB heap, and 0.5 CPU / 768 MiB container / 256 MiB
heap. Each performs 472 small synthetic JMS calls, including a full 30-second
no-reply wait with concurrent healthy traffic and a BytesMessage reply. HTTP
inspection stays inside an internal Docker network; no host ports or existing
database directories are exposed. Diagnostic defaults, listener concurrency,
durable request logging, and broker persistence are retained; scheduled backups
and cleanup are disabled only in the disposable instances. The harness collects
cgroup v2 memory/CPU/OOM evidence, actual caller responses, downstream identities,
Request Logs, monitoring snapshots and diagnostic files under ignored
`artifacts/transaction-diagnostics-docker/<run>/`. It removes only exact resources
with its unique ownership label. It never performs global Docker cleanup. This
is a bounded functional/resource-pressure check, not long-run or TIBCO/SIT
capacity certification. Cgroup v2 and curl inside the selected JDK image are
required. `--image` and `--output` are available; `--skip-build` requires the
application jar built with `-PreleaseVersion=container-diagnostics` and its
matching fixture jar. Overall container memory is **not** Java's heap limit.
An optional stricter `--tiers half-cpu-512m` investigation uses 512 MiB for the
whole container and 256 MiB heap; it is not an assumed supported capacity and
must report an OOM/exit as a failure. To distinguish startup capacity from
diagnostic output, use that tier with `--startup-only --diagnostics-off`; this
control does not run or certify business scenarios.

For a finite resource-retention investigation (not a maximum-throughput test):

```bash
python3 scripts/test-container-resource-soak.py --dry-run
python3 scripts/test-container-resource-soak.py
python3 -m unittest discover -s scripts/tests -p test_container_resource_soak.py
```

The default window is **600 measured seconds** at 5 JMS plus 5 HTTP forwards/sec,
with synthetic 8 KiB requests, four disposable HTTPS origins rotated every 30
seconds, a 1 CPU / 1 GiB Echo container, and 512 MiB Java heap. Request logs
retain at most 2,000 rows so insertion/retention cleanup is exercised. Diagnostics
remain default-enabled/anomaly-only. A separate fixture verifies response bodies
and JMS correlation; it retains only fixed-size timing histograms. The harness
captures monitoring snapshots, process RSS/threads/file descriptors, cgroup CPU,
memory/anon/file-cache/OOM events and downstream broker queues/connections at
approximately 15-second intervals. A ten-second warmup and two idle minutes are
**outside** the measured window. Explicit `jcmd GC.run` is run once after warmup
and once after natural idle; these interventions are labelled and are never used
during measured traffic. Natural GC logs retain the heap floor between those
interventions. It checks idle HTTP pool reclamation, temporary JMS queue deletion,
drained write queues, configured DB connection bounds and request-log retention.
Only the uniquely labelled containers/network are removed, including the tested
Echo service. Evidence stays under ignored `artifacts/resource-soak/<run>/`.
Scheduled backups and cleanup are disabled only in this disposable instance.
No production endpoints, local database files or company incident details are
used. A plateau in this bounded synthetic workload cannot establish permanent
leak freedom, SQLite/host memory safety, or real TIBCO/SIT workload capacity.

The container resilience harness generates an owned Compose project name and
random host ports, stores primary data in a project-scoped named volume and
capacity fixtures in separate tmpfs named volumes, and captures command
stdout/stderr under
`artifacts/container-resilience/<run>/`. By default it removes only that exact
project with `down --volumes --remove-orphans`; `--keep` is available for
inspection. See [`docs/container-resilience-validation.md`](../docs/container-resilience-validation.md)
for the pass/fail evidence contract and limitations.

## RDBMS matrix contract

`test-rdbms-matrix.py` uses `docker-compose.rdbms.yml` and runs profiles
sequentially. It creates a unique project name for the run, maps the HTTP/JMS
ports to free host ports by default, waits for the host-mapped
`/api/admin/status`, runs `test-match-scenarios.py`, then runs the persistence
test before and after an Echo restart. Unless `--keep` is supplied, each
project is cleaned up with `down --volumes --remove-orphans` using that exact
project name. It never performs a global Compose cleanup.

The persistence script supplied by the repository implements this stable
interface:

```bash
python3 scripts/test-rdbms-persistence.py BASE_URL \
  --state-file path/to/persistence.state.json

# After the matrix restarts Echo:
python3 scripts/test-rdbms-persistence.py BASE_URL \
  --restart --state-file path/to/persistence.state.json
```

The matrix calls it once to create and verify durable records, and once with
`--restart` to verify those records after restart. Both invocations must return
exit code 0 on success. The state JSON is kept in the per-database evidence
directory; the matrix's `result.json` additionally records every command and
its captured stdout/stderr.

The matrix stores command stdout/stderr, Compose logs and service state under
`artifacts/rdbms-matrix/<run>/`. Credentials are inherited from the process
environment for the test scripts and are not written to the result JSON.

### Optional performance pass

`--performance` runs `stress-test-rps.py` only after the normal matching check
and the before/after restart persistence check. Every database receives exactly
the same duration and concurrency; change them with
`--performance-duration` and `--performance-concurrency`. The values and the
machine-readable stress result are recorded in both each database's
`result.json` and the matrix `matrix-result.json`. The per-database
`performance.json` is retained as evidence.

After the timed requests stop, the benchmark waits for the durable request-log
queue to drain before deleting its test logs. This drain time is recorded but
is not included in RPS; a queue that does not drain within two minutes fails the
benchmark instead of racing the database cleanup endpoint.

The matrix result records the host `platform` and `machine`. On an ARM host,
SQL Server uses x86 emulation in its container: its performance result is
explicitly marked as not fairly comparable with native runs. This limitation
does not invalidate the SQL Server functional or restart-persistence checks.
