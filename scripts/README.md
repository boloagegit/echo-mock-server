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
| `stress-test-rps.py` | RPS throughput test — measures requests per second |
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

# Validate cross-platform script behavior
python3 -m unittest scripts/tests/test_windows_script_compatibility.py
```

Use disposable databases and ports for benchmarks. Do not point destructive or crash-resilience scripts at a production database.
