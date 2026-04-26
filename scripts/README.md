# Benchmark & Test Scripts

Python scripts for performance benchmarking and regression testing. Requires `python3` and the `requests` library.

```bash
pip install requests
```

All scripts default to `http://localhost:8080` if no URL is provided.

## Scripts

| Script | Description |
|--------|-------------|
| `stress-test-rps.py` | RPS throughput test — measures requests per second |
| `stress-test-scenario1.py` | Single complex HTTP matching scenario latency |
| `stress-test-1600-rules.py` | Matching performance with 1,600 rules |
| `stress-test-xml-body.py` | XML vs JSON body size impact on matching |
| `stress-test-vs-wiremock.py` | Echo vs WireMock RPS comparison (requires `libs/wiremock-standalone.jar`) |
| `bench-2000-jms.py` | JMS matching with 2,000 rules (ServiceName + CustId conditions) |
| `stress-test-jms-match.py` | JMS matching with 2,000 rules and 20-field XML body |
| `stress-test-memory.py` | Worst-case memory usage test |
| `stress-test-cache-isolation.py` | Verify HTTP/JMS cache isolation after split |
| `test-match-scenarios.py` | Regression test — 69 matching scenarios covering all features |

## Usage

```bash
# Start Echo first
./gradlew bootRun

# Run a benchmark
python3 scripts/stress-test-rps.py [URL] [DURATION] [CONCURRENCY]

# Run regression tests
python3 scripts/test-match-scenarios.py [URL]
```
