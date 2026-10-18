# HTTP/JMS stability follow-up

[繁體中文](zh-TW/stability-followup-2026-10-18.md) · [Previous work](long-running-stability-2026-10-18.md)

Subsequent experiment: [SQLite request-log JDBC insert comparison](request-log-jdbc-experiment-2026-10-18.md).

The [large-message capacity review](capacity-and-framework-review-2026-10-18.md) documents a subsequent continuous-write backup defect, its correction, and 512 MiB retention tests.

Implementation: `97aa408`, with disk-retry boundary correction `517578e`, on `codex/long-running-stability`; no PR or merge.
This round checks failure boundaries and resource recovery, using a ten-minute sustained test.
JMS providers, source listener configuration, ACK, transactions and retry policy are unchanged.

## 1. Slow JMS targets

1. **Problem:** Target locks are separated, but a source queue still shares a listener pool. Slow targets can occupy every listener and queue healthy work.
2. **Change:** Added real external Artemis tests distinguishing target-lock isolation from shared source capacity. No forced scheduling or shorter waits were introduced.
3. **Evidence:** With two fixed source listeners, holding one slow request leaves healthy JMS and HTTP within 5 s/2 s bounds. Holding both prevents a healthy JMS reply within 300 ms; release restores all replies with correct bodies/correlation IDs and an empty source queue. Thirteen focused tests including lock isolation and transactional redelivery pass.
4. **Cost/limit:** This verifies the boundary; it **does not provide hard isolation between targets sharing one source queue**. Transaction policies remain. Licensed TIBCO EMS could not be exercised.

## 2. Continuous load and natural GC

1. **Problem:** Load/idle cycles with requested GC compare retention but do not represent uninterrupted traffic.
2. **Change:** Added `--continuous`: no requested GC at baseline, during load, or at the end. Seven resource series use minute windows, natural GC and minute NMT summaries; insufficient observations cannot pass.
3. **Evidence:** The pre-change baseline completed 600.036 s and 120,000 correct requests, split equally between HTTP and JMS, including real forwarding. All growth gates passed, with zero discarded logs, ERROR/OOM lines or failed backups; 50 backups completed. Natural post-GC heap window change was about +0.9 MiB, RSS +5.0 MiB. Final-artifact results follow below.
4. **Cost/limit:** Ten minutes detects obvious failures under this workload, not weeks of leak freedom. Young-GC heap is not fully retained heap. NMT and monitoring have overhead; this is not a capacity benchmark.

## 3. Disk reserve and database recovery

1. **Problem:** Pending spool bytes were bounded, but new writes had no free-space reserve on their filesystem. Main-database lock recovery also needed verification.
2. **Change:** Default 512 MiB reserve, checked at most once per second by the existing spool writer. Requests and monitoring read cached state. Low space or probe failure rejects unattempted batches; background checks allow recovery without new traffic. Already-attempted batches retain existing uncertain-outcome retries rather than being declared unwritten and triggering caller retries. Added a real SQLite write-lock test.
3. **Evidence:** Injected low-space/probe failures stop new writes and recover while idle. During a real main-database write lock, five HTTP and five Artemis JMS requests reply correctly. After unlock, all twelve records including warmup are present without duplicates and queues drain. A test fails before the retry-boundary correction and passes after it, verifying that low space does not declare an attempted batch unwritten. Forty-one focused tests pass.
4. **Cost/limit:** New logging becomes unavailable earlier when space is low. An already-attempted bounded batch may still retry to preserve the existing outcome contract. Default asynchronous logging retains its bounded discard policy; opt-in strict mode retains existing failure/redelivery behavior. This is not a filesystem quota or reservation against other writers. Low space was safely injected, rather than physically filling the disk.

`ECHO_REQUEST_LOG_SPOOL_MIN_FREE_BYTES=0` disables the reserve check. Monitoring includes the reserve, cached usable space, blocked state and probe-failure count.

## 4. Separate request-log counters

1. **Problem:** The old `dropped` aggregate conflated retention skips, buffer eviction and unconfirmed writes.
2. **Change:** Keep that aggregate and expose separate cumulative counters for retention skips, overflow eviction, unconfirmed writes, other drops and storage retries in the API and both UI languages. Fixed counters add no per-request history.
3. **Evidence:** Assertions independently cover seven retention skips, sixteen evictions, one unconfirmed write and other rejection; storage outages increase retries. Related backend tests (156) and monitoring UI tests (15) pass.
4. **Cost/limit:** Observability does not make asynchronous logging lossless. An unconfirmed write may commit later; retries count attempts, not lost records. Concurrent snapshots need not align atomically.

## 5. Retire idle HTTP workers

1. **Problem:** Native HTTP workers remained after peak traffic, retaining thread resources during complete idle.
2. **Change:** Retire workers after 60 s idle and recreate on demand. Busy work is not interrupted. Capacity, buffer bounds and JMS threads remain unchanged. Monitoring exposes the actual worker count.
3. **Evidence:** After verified packaged HTTP load, 32 workers become zero following 65 s idle. The first resumed request returns the correct body in about 2.48 ms and creates one worker. NMT thread committed decreases from about 6.91 to 3.33 MiB. Twenty-eight focused tests pass, including busy work surviving beyond the idle timeout.
4. **Cost/limit:** The first request after idle incurs thread creation. RSS need not decrease immediately; this does not reclaim all process memory. `ECHO_HTTP_NATIVE_WORKER_IDLE_TIMEOUT_SECONDS=0` preserves previous behavior.

## 6. HTTP forwarding tail latency

1. **Problem:** Mixed-load forwarding P99 was about 46 ms; direct calls to the test downstream also showed a roughly 44 ms delay.
2. **Change:** Keep the Echo artifact fixed and change only `TCP_NODELAY` on the test JDK HTTP backend. Preserve the old mode. Compare forwarding separately against WireMock using a Node backend. Production forwarding code is unchanged.
3. **Evidence:** With 16 KiB and 250 verified samples per case, direct-backend P99 changes from 48.20 to 5.14 ms, through Echo from 58.03 to 9.27 ms. The test backend's TCP settings explain most of that delay; **this is not an Echo product speedup**. WireMock results follow below.
4. **Cost/limit:** Final mixed observations use the corrected backend and tool commit `b0d4bf3` explicitly selects HTTP/1.1. The earlier tool attempted h2c upgrade and could use Servlet instead. Their latency difference from the baseline is not a pure product A/B. `--downstream-nagle` reproduces the old backend setting. The diagnostic samples do not establish capacity.

## Final measurements and reproduction

Final artifact `517578e` completes **600.060 s of uninterrupted load and 120,000 correct responses**, split equally between HTTP and JMS. Another 18,000 warmup requests are excluded. All seven resource-growth gates pass, with zero ERROR/OOM/unexpected exits, discarded request logs or storage retries. Requests, buffers and write queues drain; the downstream retains only its service queue, with no temporary queues.

No GC is requested. There are 209 natural-GC observations during measured load, 246 archived events including warmup and 232 history expirations. Eight measured stalled uploads recover server-side, plus 48 during warmup. Across the full run including warmup and drain, backups total 50 completed, zero failed, 47 pruned, retaining three files/about 54.5 MiB.

| Resource | Load-window change | Gate |
|---|---:|---|
| Natural post-GC heap | +0.85 MiB | Pass |
| NMT committed | +3.35 MiB | Pass |
| Direct buffer | +0.035 MiB | Pass |
| Non-heap | +6.10 MiB | Pass |
| JVM threads | -2 | Pass |
| RSS | +6.32 MiB | Pass |
| Linux FDs | -0.5 (window medians) | Pass |

Changes compare the first/last two minute-bucket medians during load; edge buckets may be partial. NMT uses first/last two samples. Passing does not mean no growth: pre-load/final-drained RSS is 911.7 → 932.3 MiB and natural post-GC heap 107.87 → 109.36 MiB. See [thresholds and limitations](gc-observability.md). Young GC does not establish fully retained heap.

| Scenario | Verified samples | P99 ms | Maximum ms |
|---|---:|---:|---:|
| HTTP fixed | 15,000 | 2.882 | 86.508 |
| HTTP template | 15,000 | 3.054 | 12.763 |
| HTTP real forwarding | 15,000 | 5.992 | 65.077 |
| HTTP 10 ms delay | 15,000 | 14.344 | 154.927 |
| JMS mock | 30,000 | 10.945 | 142.213 |
| JMS real Artemis forwarding | 30,000 | 18.022 | 91.292 |

These latencies describe 200 combined requests/s with concurrent backups and monitoring, not maximum capacity or a guarantee for all tails. Backend TCP/protocol corrections prevent treating baseline/final latency differences as pure product speedups. Existing startup/test-broker WARN categories were inspected; there are no spool faults, failed backups or unexpected runtime errors in this run.

Local JDK 17 `check`/SpotBugs for final production source `517578e` passes: 2,199 tests, zero failures, six skips. JavaScript tests (175) and Python tool tests (72) pass. After the HTTP/1.1 tool correction, the fixture was rebuilt and Python tests rerun successfully.
The [production-source CI](https://github.com/boloagegit/echo-mock-server/actions/runs/39115087495) passes Linux JDK 17/21, Windows JDK 17 including paths with spaces, and Docker probe/stop/recreation checks. Windows validates functionality/compatibility; the performance and sustained-load observations here are Linux results.

Final continuous-test artifact SHA-256:
`ff929d2ae87371362dd838bb526420fbcb595a439312d36fe97e22c927f82f8f`;
fixture `b16759299ed35f361ac0965f494318cee13988cdc85a2d6ce0142845dd14eeee`.

### WireMock forwarding comparison

Echo `97aa408` versus WireMock standalone 3.13.2: Linux/JDK 17, G1, 1 GiB heap, recording disabled on both, four equivalent rules. Same Node backend, 16 KiB POST, HTTP/1.1, 16 connections, two wrk threads. Four fresh JVMs run Echo/WireMock/WireMock/Echo, each with 30 s warmup and 15 s measurement. Shared four-core quota; no concurrent build or other load generator.

| Product | Run 1/2 req/s | Mean req/s | Run 1/2 P99 ms | Mean run P99 ms |
|---|---:|---:|---:|---:|
| Echo | 12,432 / 12,392 | 12,412 | 4.342 / 4.855 | 4.599 |
| WireMock | 9,211 / 9,681 | 9,446 | 7.368 / 6.122 | 6.745 |

Echo throughput is 31.4% higher and mean run P99 31.8% lower under these conditions. Echo verifies 372,497 responses; WireMock 284,065, with zero status/body or socket errors. Direct backend: about 29,806 req/s, P99 1.432 ms. Two observations per product do not establish significance or general superiority; mean run P99 is not a merged percentile. This round does not repeat paired RSS measurement. The earlier [memory/stability comparison](wiremock-comparison-2026-10-18.md) retains its original versions and limitations and is not a memory result for this new artifact.

This comparison measures `97aa408`. Subsequent `517578e` changes only the low-space retry boundary in request logging, which is disabled here; results are not relabeled as a remeasurement of that artifact. Compared SHA-256: Echo
`616502c807b0df86b3cebb6a7f0d44b22e983074697ad3f555fd82b14906c3d8`;
WireMock `d097b19bd483c5038479b13a5c71e9faf8f2f5106584f0c120a7770ab0bdb367`.

### Reproduce continuous observation

The isolated test uses Linux/JDK 17, G1, 384 MiB heap, two HTTP I/O/eight workers and 2–4 JMS listeners. It sends 200 combined requests/s with 16 KiB data after six 15 s warmup cycles, then runs uninterrupted for 600 s. It does not use company ESB infrastructure.

```bash
./gradlew bootJar memoryObservationFixtureJar
python3 scripts/test-memory-observability.py \
  --jar build/libs/echo-server-0.0.0-SNAPSHOT.jar \
  --output build/observations/continuous-10min \
  --continuous --cycles 1 --seconds 600 --rate 200 \
  --warmup-cycles 6 --warmup-seconds 15 \
  --http-stalls --backup-maintenance
```

On Windows use `gradlew.bat` and `python`; `--java` can select a full JDK's `java.exe`. The output directory must not already exist. See the [GC observation guide](gc-observability.md). Raw logs, JARs, JVM summaries and time series remain outside Git; only text summaries are committed.

Raw workspace: `stability-next-six-20261018`. `continuous-10min` is the pre-change baseline;
`continuous-10min-accepted` is final acceptance. `forward-wiremock`, `forward-diagnosis` and
`http-idle-v2` retain forwarding, TCP and idle-recovery evidence. Two interrupted preliminary
runs retain their reasons and non-passing status and are excluded from acceptance.
`reserve-retry-red.log`, `reserve-retry-green.log` and `full-check-final.log` retain regression evidence.
