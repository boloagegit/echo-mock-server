# Echo / WireMock performance, memory and stability comparison

[繁體中文](zh-TW/wiremock-comparison-2026-10-18.md)

The measured Echo production source is `f9a60fc82cc4200a6d66e159e344251c919f3714`; WireMock standalone is **3.13.2**. All observations use Linux/JDK 17 on one host with a four-core CPU quota. These synthetic workloads do not establish deployment capacity or permanent leak freedom.

Follow-up inspection found that `echo.jms.enabled=false` stopped Echo's JMS handling but Spring Boot still started an idle embedded Artemis broker. The tables retain the original pre-fix measurements, including that overhead. The [subsequent fix](long-running-stability-2026-10-18.md#disabled-jms-and-embedded-broker-startup) has separate validation; these are not measurements of the corrected artifact.

## HTTP throughput

Both products use G1, a 1 GiB heap, four equivalent rules and HTTP/1.1 loopback. Each case has five seconds of warm-up and ten seconds of measurement. Each product/logging mode uses two fresh JVMs in alternating order. Every received status/body is validated; there are no content or socket errors. P99 is the mean of run-level P99 values, not a percentile of merged samples.

### Request recording disabled

| Scenario | Echo req/s | WireMock req/s | Throughput ratio | Echo P99 ms | WireMock P99 ms |
|---|---:|---:|---:|---:|---:|
| fixed-c16 | 72,058 | 56,452 | 1.28× | 8.060 | 5.101 |
| fixed-c64 | 118,557 | 67,186 | 1.76× | 3.721 | 5.954 |
| template-c16 | 78,864 | 30,790 | 2.56× | 1.976 | 5.454 |
| xml16k-c16 | 37,231 | 21,248 | 1.75× | 4.871 | 6.184 |
| forward16k-c16 | 12,095 | 9,304 | 1.30× | 6.784 | 5.654 |

Echo has higher throughput in these five cases, but its c16 fixed-response and forwarding P99 values are worse. Throughput does not establish superiority in every metric. Four rules do not cover TLS, complex templates or large rule sets.

### Request recording enabled, 10,000 retained entries

| Scenario | Echo req/s | WireMock req/s | Throughput ratio | Echo P99 ms | WireMock P99 ms |
|---|---:|---:|---:|---:|---:|
| fixed-c16 | 35,826 | 2,603 | 13.76× | 11.564 | 34.987 |
| fixed-c64 | 68,696 | 2,597 | 26.45× | 8.505 | 85.307 |
| template-c16 | 41,212 | 2,634 | 15.65× | 4.190 | 90.532 |
| xml16k-c16 | 16,266 | 2,658 | 6.12× | 6.490 | 30.657 |
| forward16k-c16 | 6,416 | 1,819 | 3.53× | 10.723 | 32.479 |

Echo uses its existing default asynchronous SQLite logging and 1,000/s writer cap. WireMock uses an in-memory journal with `--max-request-journal-entries 10000`, with text notifier logging disabled. Persistence and queueing guarantees differ. Echo reports approximately 2.26 million dropped entries per run. Reviewing the warning totals separates 2,211,662 / 2,236,567 retention skips from 46,362 / 28,912 handoff drops. Allowing records to be dropped under overload is the default design tradeoff to bound memory and prioritize responses; it is not a response-correctness failure. This is not a complete-record-persistence throughput comparison.

In this WireMock version, `AbstractRequestJournal.removeOldEntries()` calls `getAllKeys().count()`, traversing its `ConcurrentLinkedDeque` for each request. This confirms an additional bounded-journal cost, not its complete measured causal contribution or behavior under every WireMock configuration.

## Matched HTTP memory and 30-minute stability

Both compared processes receive HTTP traffic only, with JMS handling and backups disabled. Echo nevertheless started the idle embedded broker described above; WireMock has no broker. Each receives 100 req/s over four connections and five equivalent rules: fixed, variable-query template, 16 KiB XML matching, real 16 KiB forwarding and a 10 ms delay, 20% each. Both use Java 17/G1/1 GiB heap/NMT summary and retain 10,000 entries. After 120 seconds of warm-up, six five-minute segments stop both generators for 15-second idle/drain and diagnostic-GC checkpoints.

Both compared processes run concurrently. A separate 100 req/s Echo HTTP/JMS acceptance ran on the same host during most of this observation and finished before its final segment ended. This is paced stability observation below capacity, with no overlap with peak-throughput testing.

| Metric | Echo | WireMock |
|---|---:|---:|
| Verified measured requests | 180000 | 180000 |
| Post-GC heap: baseline → final MiB | 58.2 → 50.7 | 128.9 → 128.6 |
| Post-idle RSS: baseline → final MiB | 1182.7 → 1233.2 | 710.7 → 1204.5 |
| Peak process RSS MiB | 1233.2 | 1204.5 |
| OS threads: baseline → final | 108 → 109 | 38 → 35 |
| FDs: baseline → final | 48 → 48 | 15 → 15 |
| Natural GC pause count | 72 | 66 |
| Natural GC total pause ms | 522.3 | 1519.311 |
| Natural GC maximum pause ms | 38.017 | 123.851 |

**Resource thresholds:** not all passed; preserve the original individual assessments below. A threshold flag alone does not establish a crash or leak.

| Product | Heap | NMT committed | RSS | OS threads | FD |
|---|---|---|---|---|---|
| echo | no-growth-detected-in-window | no-growth-detected-in-window | no-growth-detected-in-window | no-growth-detected-in-window | no-growth-detected-in-window |
| wiremock | no-growth-detected-in-window | no-growth-detected-in-window | growth-detected | no-growth-detected-in-window | no-growth-detected-in-window |

Both products completed 180,000 measured requests and seven response-rewrite probes without body errors, OOM or unexpected exits. Echo passed all five resource thresholds. WireMock flagged only RSS: it grew 484.9 MiB in the first five-minute segment and only 8.9 MiB over the following twenty-five minutes, without matching post-GC heap or NMT-committed growth. This is more consistent with resident-page warm-up than ongoing retained-object accumulation; it does not establish leak freedom or change the original RSS flag to a pass.

Post-GC heap and RSS measure different things. RSS includes resident heap pages, classes, JIT, stacks and native allocation; GC does not necessarily return those pages to the OS. Seven checkpoints and per-GC raw logs remain in the measurement artifacts. Natural pauses include startup and warm-up and are separated from seven idle diagnostic collections. The first incomplete comparison rejected warm-up pacing; its failure is retained. The retry applies the same pacing gate only to measured load and preserves warm-up diagnostics.

The comparison harnesses deleted their temporary data directories before stopping the JVMs. Echo consequently emitted SQLite spool warnings during teardown, after measurement. Those warnings are a harness lifecycle defect, not evidence of storage failure during load. The original error scan checked `ERROR`/OOM and did not capture these `WARN` lines; future runs must stop the JVM before deleting its data and inspect warnings as well. This comparison does not validate graceful draining at shutdown.

## Separate HTTP/JMS operating acceptance

Echo completed 180,000 requests over 1,800 measured seconds: 90,000 HTTP and 90,000 JMS. HTTP is 75% mock/25% forwarding; JMS is 50% mock/50% real Artemis forwarding. Overall acceptance: passed. Administrative operations: 66 cycles, 1716 HTTP/API checks, 132 real JMS rewrite checks. Backups: 138; maintenance-lock conflicts: 0.

This separate test uses a 384 MiB heap, JMS concurrency 2–4, 1,000 retained logs and 60-second GC history to exercise reclamation. Its memory is not directly compared with the two HTTP-only processes. Idle diagnostics separate each five-minute segment; it is not uninterrupted thirty-minute load.

Separate synchronous-record JMS timing observed before/after mock throughput of approximately 904/892 req/s (-1.3%) and real-Artemis forwarding of 478/492 req/s (+2.8%). Two samples per version do not establish a small improvement or regression. The original exact request-count gate failed because wrk can stop with in-flight requests; raw failures remain. A separate accounting audit found 24–30 extra DB rows within two c16 runs' maximum 32 outstanding requests, matching processed/DB totals and zero drops.

WireMock has no JMS equivalent. Actual TIBCO EMS throughput, ACK and failover were not validated. See [stability hardening](long-running-stability-2026-10-18.md) for both fixes, regressions and costs.

## Validation and artifacts

The full JDK 17 check reported 2,188 tests, zero failures and six skips, plus successful SpotBugs. The production-source [CI run](https://github.com/boloagegit/echo-mock-server/actions/runs/39103053156) passed Linux JDK 17/21, Windows JDK 17 including paths with spaces, and container stop/restart validation. Subsequent report-only changes do not alter measured artifacts.

Raw artifacts are kept outside the repository:

- `runtime-fixes-wiremock-20261018`: manifests, alternating HTTP runs, summary and journal bytecode evidence.
- `runtime-fixes-http-stability-retry-20261018`: matched lifecycle workload, per-process samples, JVM checkpoints, GC logs and report.
- `runtime-fixes-30min-20261018`: mixed HTTP/JMS/admin/backup observation, GC history and report.

Each directory contains the isolated harness and frozen source/JAR identifiers. The observation tools do not change production defaults.
