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
| `test-rdbms-matrix.py` | Disposable Docker Compose matrix for H2, SQLite, PostgreSQL, MySQL, MariaDB, SQL Server, and Oracle; runs E2E/persistence checks, restart verification, and evidence collection |
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

# Validate cross-platform script behavior
python3 -m unittest scripts/tests/test_windows_script_compatibility.py
```

Use disposable databases and ports for benchmarks. Do not point destructive or crash-resilience scripts at a production database.

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
