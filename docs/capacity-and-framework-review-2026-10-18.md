# 512 MiB capacity and framework cost review

[繁體中文](zh-TW/capacity-and-framework-review-2026-10-18.md)

The operating recommendation is based on large messages, not only the earlier 16 KiB writer probe. SQLite retention primarily consumes disk; `-Xmx512m` is not a 512 MiB whole-process limit. This review does not change production retention, recording limits, or JMS semantics.

Recommendation: start with **10,000 retained database records and the existing 1,000/s writer ceiling**. The 20,000-record observation also passes, but its main DB plus two retained snapshots needs about 7.4 GiB, with another snapshot/WAL/spool space during backup. The mixed 10k case is not a worst-case per-record disk budget. Uncapping is an evaluated setting, not a new default; choose it only against required traffic/latency and storage capacity. Observed whole-process RSS is about 1.0–1.1 GiB despite the 512 MiB heap.

## Large-message operating capacity

Linux/JDK 17/G1, four-core quota, 512 MiB initial/maximum heap, native HTTP with 2 I/O/32 worker threads, JMS concurrency 10–30. Actual Artemis request/reply forwarding uses a separate downstream broker with two consumers. Each input has 128 KiB padding; complete forwarded bodies and JMS correlation IDs are checked. Recording keeps the existing 65,536-character per-body limit plus the truncation marker (maximum observed 65,550 characters). This does not test retaining complete 128 KiB bodies or arbitrary Unicode/large metadata.

SQLite databases use the workspace overlay filesystem, not `/tmp` tmpfs. Backups run every two minutes with two retained files/8 GiB total; housekeeping runs every second to stress maintenance, versus the one-hour production default. GC history expires after 60 seconds. Formal traffic is uninterrupted for ten minutes, without requested GC. A separate diagnostic full GC follows traffic, queue drain, ordinary resource checkpoints and default log-summary queries. It is excluded from natural-GC growth assessments.

| Retention limit / final rows | Writer ceiling/s | Formal verified / actual req/s | Final diagnostic heap / RSS MiB | Main DB MiB | Backups / pruned / failed |
|---|---:|---:|---:|---:|---:|
| 2,000 / 1,931 | 1,000 | 120,000 / 199.98 | 104.6 / 1050.7 | 144.3 | 6 / 4 / 0 |
| 10,000 / 9,777 | ineffective | 120,000 / 199.97 | 106.5 / 1087.1 | 647.0 | 6 / 4 / 0 |
| 20,000 / 19,925 | ineffective | 48,000 / 79.99 | 99.0 / 1013.0 | 2,517.5 | 9 / 7 / 0 |

Backup/pruning counts include startup, warm-up and drain.

The 2,000/10,000 workloads split HTTP/JMS equally: HTTP fixed/template/forward/delay equally, JMS mock/forward equally. The 20,000 workload uses only real JMS forwarding, with both bodies reaching the recording limit. Its load and rate differ: do not attribute disk, memory or latency differences solely to retention. Warm-up inserts at least 24,000 records before each formal phase; pruning headroom means retained counts can be below the configured maximum.

All three runs have zero ERROR/OOM lines, recording drops, unconfirmed writes, storage retries or JMS failures; queues drain. All seven growth gates pass, but growth is not zero. In retention order, natural post-GC heap window changes are +6.39 / +5.63 / +11.00 MiB and RSS changes +6.34 / +11.99 / +14.31 MiB. Formal durations are 600.056 / 600.085 / 600.040 seconds. Final default-all-summary calls take 133.0 / 346.7 / 871.3 ms.

The first all-forwarding 200/s attempt was stopped during warm-up: 4,000 verified replies took 47.722 s (83.82/s), with subsequent sampled rate around 95/s. It is retained as canceled, with no accepted formal result. The 80/s retry does not establish 200/s capacity.

| Scenario P99 ms | 2,000 mixed | 10,000 mixed | 20,000 JMS-only |
|---|---:|---:|---:|
| httpFixed | 6.398 | 6.787 | — |
| httpTemplate | 5.571 | 6.607 | — |
| httpForward | 30.360 | 16.941 | — |
| httpDelay | 20.808 | 17.482 | — |
| jmsMock | 28.787 | 30.228 | — |
| jmsForward | 73.531 | 76.481 | 130.089 |

Page-of-50, one-body detail and traffic APIs are exercised every minute. Timings include the local HTTP client and JSON decoding; they are not browser-rendering measurements. Default `/api/admin/logs` returns all retained **summaries**, not all bodies. Database integrity checks pass. Short observations and young-GC trends do not prove permanent leak freedom. This is database mode, not memory mode; it does not establish a safe 20,000-entry in-memory configuration.

Growth allowances are heap 24 MiB, NMT/RSS 128 MiB, direct 8 MiB, non-heap 16 MiB, four JVM threads and eight FDs. Comparisons use early/late minute-window medians; see the [observation method](gc-observability.md). Passing is not a zero-growth assertion.

## Continuous-write backup defect and correction

1. **Original problem:** the SQLite online backup could restart whenever another connection committed. In the first 2,000-entry/128 KiB mixed run, a backup started at 11:28 and completed at 11:38 only after load stopped. Responses were correct (120,000/600.102 s, no recording drops), but backup/pruning acceptance failed. That run remains failed.
2. **Change:** pin a `DEFERRED` read transaction on the freshly borrowed source connection while copying. Keep WAL writers running, release the snapshot, restore pool settings, and retain integrity verification, publication and retention.
3. **Measured result:** the concurrency regression fails against the old implementation at its 15-second timeout and passes with the fix while its writer remains active. All accepted capacity runs exercise backups during traffic. Full `check`/SpotBugs passes: 2,221 tests, zero failures, six skips. [CI](https://github.com/boloagegit/echo-mock-server/actions/runs/39125026609) passes Linux JDK 17/21, Windows JDK 17 and Docker.
4. **Cost/limits:** WAL can grow while the read snapshot is pinned; slow storage still lengthens copy/verification and increases temporary disk requirements. This does not alter JMS ACK, transactions, forwarding or reconnect behavior. Actual licensed TIBCO EMS is not available for validation.

## Recording-rate ceiling versus retention

The existing default is 1,000 main-log writes/s. Raising retention changes history length; raising this ceiling changes background CPU/I/O demand. At 200 total requests/s the default ceiling is not reached, so a successful unthrottled continuous run cannot demonstrate the benefit of removing it. `0` currently clamps to **1/s**; these experiments use `2147483647` to make the ceiling ineffective.

Fresh 512 MiB JVMs, identical seed within each retention setting, 128 KiB padding, eight JMS callers or 16 HTTP connections, five-second per-case warm-up and ten-second formal phases. Backups are off only for this peak comparison. Order: 2k capped, 10k uncapped, 20k capped/uncapped/uncapped/capped, 10k capped, 2k uncapped. The 20k pairs have two JVMs per setting; other pairs have only one and are exploratory. Tables show means of run metrics, not merged percentiles.

| Retention | Ceiling/s | JVMs | JMS mock req/s | JMS forward req/s | HTTP forward req/s | HTTP main writes/s | Final all-phase drops per JVM |
|---|---:|---:|---:|---:|---:|---:|---|
| 2,000 | 1,000 | 1 | 291.8 | 96.9 | 781.0 | 494.7 | 1880 |
| 2,000 | ineffective | 1 | 196.4 | 98.0 | 795.4 | 564.0 | 1685 |
| 10,000 | 1,000 | 1 | 191.6 | 88.1 | 886.1 | 519.6 | 88 |
| 10,000 | ineffective | 1 | 185.6 | 90.1 | 897.5 | 581.2 | 100 |
| 20,000 | 1,000 | 2 | 174.0 | 81.3 | 911.9 | 519.6 | 90, 112 |
| 20,000 | ineffective | 2 | 282.1 | 101.8 | 870.8 | 637.1 | 0, 76 |

The 20k uncapped means improve JMS forwarding by 25.2% and mock throughput by 62.1%, while HTTP forwarding decreases 4.5%. These short observations do not isolate causality: the JMS rates are below 1,000/s, JVMs vary, and only two samples exist per setting. This is not evidence of a 1,000-message/s JMS hard limit or a universal speedup. Direct downstream JMS controls before/after are 254.5/280.4 req/s; the backend itself contributes latency.

The 20k mean run P99 values (capped → uncapped, ms) are jms-mock 79.528 → 45.154; jms-forward 140.902 → 121.864; http-forward 54.428 → 57.534.

HTTP writer counts cover the formal wrapper (which can include prior pending work); JMS counter timing additionally includes five-second warm-up and client setup. Final drop totals include initial warm-up, every case and drain, not only formal responses. All received bodies/correlations are correct; retries/unconfirmed writes remain zero. The 20k HTTP phases end with 3,741–6,346 pending logs, then drain in 3.0–7.6 s. Ten-second response throughput, even with zero drops, is not indefinitely sustainable complete-record throughput.



An independent later HTTP control, with the identical downstream fixture, 15-second warm-up/ten-second measurement and 16 connections, verifies 7,318.7 req/s with zero errors. Compared with the recorded Echo path, substantial forwarding/recording cost remains; the backend is not a sufficient explanation. Different achieved rates prevent subtracting latency percentiles to assign that cost.

## Framework costs: measured candidates, not wholesale removal

A separate post-benchmark JMS JFR run records 1,737 execution samples: nearest recognized library frame is Artemis in 988 and Netty in 572. Inclusive stack counts are Jackson 63, Hibernate 25, Spring bean lookup 7, Spring Data 3 and AOP 1. Counts overlap and are not additive CPU/wall-time shares. The prominent stack is inherited `OutputStream.write` loops → `ClientLargeMessageImpl$ActiveMQOutputStream.write` → `ChannelBufferWrapper.writeByte` (823 leaf samples). Allocation sampling also points to Jackson text/byte buffers. Prioritize investigating provider large-message bulk copying and spool serialization allocations; neither is changed here, and Artemis findings do not establish TIBCO behavior.

The isolated lookup probe uses one JVM, five-second warm-up per path and current/direct/direct/current eight-second phases. Both paths retrieve fresh snapshots from the same live holder; candidate equality and subsequent rule invalidation are verified. It measures the complete service lookup versus direct holder access, not only bean lookup.

| Lookup | Current ns/op | Direct holder ns/op | Current / direct caller allocation bytes/op |
|---|---:|---:|---:|
| HTTP | 218.4 | 70.7 | 376 / 120 |
| JMS | 189.1 | 42.1 | 272 / 16 |

The saving is about 0.15 microseconds/256 bytes per lookup, not a measured end-to-end HTTP/JMS gain. A second ABBA experiment uses the real spool task with two 65,536-character bodies, identical serialized bytes and verified JSON round-trip. Generic versus cached typed reader/writer averages 0.361/0.365 ms and 749.9/749.9 KiB allocation per encode/decode round-trip: no observed improvement. No codec change or framework removal is adopted. The first probe wrongly asserted list-container identity; its failed result is retained and the content-equality retry passes.



The earlier [JPA/JDBC writer experiment](request-log-jdbc-experiment-2026-10-18.md) measures the main-database insert path. Do not add its 47.2% isolated improvement to whole-server throughput. Native HTTP already avoids eligible MVC dispatch; rule snapshots already avoid per-request database queries. Spool body deduplication already exists. Spring JMS may share the listener's transactional session with `JmsTemplate`; annotation removal cannot be justified by assuming eager body conversion or a new session per message.

## Updated WireMock HTTP check

Echo production source `ab7b292` (before the subsequent backup-only fix) versus WireMock OSS standalone 3.13.2, same Java 17/G1/1 GiB heap, four rules, loopback HTTP/1.1 and 16 connections. Eight successful primary runs: off E/W/W/E, recording-on W/E/E/W, two fresh JVMs per product/mode. Initial fixed warm-up is 30 seconds; each case then has five-second warm-up/15-second measurement. Every received status/body is verified, with zero socket/content errors. Values are means of run results, including mean run-level P99 rather than merged percentiles.

| Case | Recording off: Echo / WireMock req/s | Off: Echo / WireMock P99 ms | Recording on: Echo / WireMock req/s |
|---|---:|---:|---:|
| fixed | 70,131 / 56,606 | 1.816 / 4.273 | 46,032 / 2,393 |
| template | 69,204 / 32,285 | 2.297 / 5.083 | 43,080 / 2,574 |
| xml16k | 29,924 / 21,847 | 7.823 / 5.735 | 17,655 / 2,772 |
| forward16k | 11,858 / 9,050 | 4.942 / 4.631 | 7,065 / 1,955 |

Echo has higher mean throughput in these cases; XML and forwarding P99 are worse. No claim of superiority in every metric.

Recording-on results are response throughput, **not complete-log persistence throughput**: Echo uses asynchronous SQLite with its default 1,000/s ceiling and reports 3,181,717 / 3,236,021 dropped records across the two complete runs (retention skips plus handoff drops). WireMock synchronously maintains a bounded in-memory journal of 10,000 entries. Guarantees differ. Databases for this peak comparison were on `/tmp` **tmpfs**; these numbers cannot establish physical-disk performance.

After the four cases/drain, mean RSS (MiB) is Echo/WireMock 1159.7/822.1 with recording off and 1233.3/1216.9 with recording on. These are brief end snapshots at unequal achieved throughput, not matched long-term memory baselines; Echo is not lower in either pair.

An additional Echo memory-mode saturation attempt failed response verification during warm-up (1,357,254 failures/1,390,004 replies, existing queue-full 503 behavior); it is not a valid speed result. The overall comparison script therefore did not pass, although the eight primary cases did. A planned matched ten-minute HTTP memory rerun was canceled before formal traffic when the scope changed to 512 MiB/large messages. Keep the [earlier paired memory/stability observation](wiremock-comparison-2026-10-18.md) separate; no new paired leak/stability advantage is established here. WireMock OSS has no JMS equivalent.

## Artifacts and scope

Accepted capacity/follow-up artifact: production `35e9aa197ba35964fb8e3d600a9cfd09138411f5`, SHA-256 `a470d382e0cd046aba7a7e5fa6e7378aefa839b5f80fa87375f2045857baed85`. The earlier HTTP comparison uses SHA-256 `6cc5334607b3ca04d45003620530fc08b1c2b3795864ec05b8fb948150a7c87a`.

Raw harnesses/manifests, failed/canceled attempts, JSON, GC logs, databases and JFR remain outside Git under `capacity-512m-20261018` and `wiremock-latest-20261018`. Only compact reports are committed. These Linux synthetic observations do not establish a production SLA, Windows throughput, or EMS compatibility/performance. Cross-platform CI tests correctness separately. All changes remain on the work branch; no PR or merge is created.
