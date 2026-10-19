# Request-log JSON / spool read experiment

[繁體中文](zh-TW/request-log-spool-read-experiment-2026-10-18.md) · [Previous framework review](capacity-and-framework-review-2026-10-18.md)

This reduces background log-transfer cost. **It is not a demonstrated JMS throughput improvement or an all-green soak result.**

1. **Problem:** Around the default 64 Ki-character body boundary, Jackson repeatedly allocates text segments and assembles strings. This creates CPU and GC work. A preliminary Reader-based alternative reduced allocation but slowed ASCII decoding, so it was rejected.
2. **Change:** Retain Jackson and the existing JSON format. Only spool-task reads use a separate bounded recycler with a contiguous 128 Ki-character text buffer. Retain at most one idle recycler; each retained character array is at most 128 Ki characters and each byte array at most 16 KiB. Larger input remains readable without retaining oversized arrays. Shared mapper settings, truncation, candidate deduplication, byte admission and transaction behavior are preserved.
3. **Evidence:** Real SQLite reads with 65,550 characters in each body column improve ASCII / escaped JSON / Unicode throughput by 76.0% / 35.3% / 19.0%. CPU per row falls 43.5% / 26.7% / 15.9%; reader-thread allocation falls 59.7% / 56.4% / 43.2%. Same-process JMS switching is approximately flat. All 120,000 soak responses are correct, but its thread-growth gate fails.
4. **Cost/limits:** A usual idle contiguous text buffer occupies 256 KiB. Concurrent active readers own separate buffers. Small 1 Ki-character rows have flat throughput and about 88 extra allocated bytes per row (+1.2%). Recheck Jackson's public recycler API on upgrades. These small, finite observations do not prove freedom from OOM or leaks. JMS ACK, transactions, retry and reply flow are unchanged; real Artemis, not licensed TIBCO EMS, was exercised.

## SQLite reads

Means of two fresh JVMs per version in old/new/new/old order. CPU covers the process; allocation covers only the reader thread.

| Characters per body column | Old / new rows/s | CPU/row reduction | Allocation/row reduction |
|---|---:|---:|---:|
| 1,024 ASCII | 86,766 / 86,780 | 0.2% | **+1.2% allocation** |
| 65,550 ASCII | 4,184 / 7,363 | 43.5% | 59.7% |
| 65,550 escaped JSON | 2,202 / 2,978 | 26.7% | 56.4% |
| 65,550 Unicode/emoji | 835 / 994 | 15.9% | 43.2% |

Linux, four-CPU quota, JDK 17.0.20.1, G1, 512 MiB heap and workspace overlay filesystem. Each case commits 50 rows, verifies bodies/timestamps/headers, warms for 3 seconds and measures for 5 seconds, repeatedly reading the same batch with a 32 MiB read limit. Both bodies are equal; analysis body is deduplicated with request body; candidate lists are empty. These are hot read costs, not sustainable write capacity. The initial probe incorrectly used a 16 MiB limit, returning only 35 Unicode rows; its preserved failure is excluded.

The branch provides `requestLogSpoolReadCost`, runnable with `./gradlew` or Windows `.\gradlew.bat`; `-PspoolReadCostOutput=artifacts/spool-read-cost` selects output. Accepted A/B runs load the same probe JAR into both frozen Echo JARs through PropertiesLauncher. No concurrent local builds/load run during timed windows.

## Application comparisons and soak

All use 512 MiB heap, 10,000 retained logs, a 1,000/s writer ceiling, 128 KiB padding and real Artemis request/reply/forwarding. Reply bodies, status and correlations are verified. Fresh-JVM short runs use an identical main-DB seed; log drops are distinct from response correctness.

| Method | Old / new mean responses/s | Interpretation |
|---|---:|---|
| Four fresh JVMs, JMS mock, 5s internal warmup + 10s measured | 303.4 / 208.9 | New is 31.1% lower; this result is retained |
| Same short runs, JMS forward | 100.1 / 94.0 | 6.1% lower |
| Same short runs, HTTP forward | 815.2 / 848.8 | 4.1% higher; insufficient for an overall claim |
| Longer warmup, two fresh JVMs, JMS mock, JFR enabled | 235.5 / 276.1 | Direction reverses; one JVM/version, two 30s phases each |
| **One JVM, alternating old/new/new/old JMS mock** | **283.9 / 285.2** | **+0.4%; no consistent whole-JMS gain established** |

Longer-warmup runs first complete 12,000 mixed requests. JFR CPU samples are dominated by Artemis/Netty large-message paths; sample proportions are not precise CPU shares. The same-process test uses an external bootstrap installing one SwitchingReader before traffic; an AtomicBoolean selects the original generic mapper or candidate reader. Logs drain before switches; call counters verify both paths execute. No production switch or test API is added. Each phase has 5s internal warmup, 20s measured traffic and eight JMS callers. Old P99 spans 40.5–43.5 ms; new 39.0–43.6 ms. All four phases have zero log drops or unconfirmed writes. Two phases per strategy do not establish universal equivalence.

In the four-JVM saturation comparison, formal HTTP-window log drops are old 619/0 and new 203/68; JMS mock/forward drops are zero. HTTP leaves a log backlog that subsequently drains. Response throughput is not sustainable log-write throughput. Counters and phases are not pooled across different methods.

The separate soak uses eight paced callers at 200 total requests/s: HTTP/JMS 50/50, HTTP fixed/template/forward/delay and JMS mock/forward. After six 20s warmups, 120,000 formal responses complete in 600.106s. Including warmup, 144,000 logs are processed with zero drops/retries/unconfirmed writes/JMS failures and a final drain. Backups complete 7 times, prune 5 files and fail 0 times; main DB quick_check is ok, retention ends at 9,763 rows, and GC-history expiry runs.

**Raw soak `passed=false`:** six of seven resource-growth gates pass. Thread minute statistics rise 184→189 (+5), above the original +4 allowance; neither threshold nor raw result is changed. Thread dumps show existing Artemis server workers 18→23 (actual configured maximum 30), Artemis IO workers 5→8 and HTTP workers 1→2 (configured maximum 32); JMS listener counts are equal. This supports delayed pool warmup, not a proof of unbounded-lifetime safety. No forced GC occurs during formal load. Natural post-GC heap minute statistics grow 5.68 MiB. One diagnostic GC after drain/query checks leaves 99.22 MiB heap and 1,066.77 MiB RSS. A 512 MiB heap is not a 512 MiB process limit.

## Provenance and checks

Baseline main is `69e04ed`; the frozen baseline JAR comes from `35e9aa1`, whose production source/build settings match that main. All dependency JARs and other application-class CRCs match between artifacts. Candidate production source is `9927869`; corrected probe is `32e766f`.

Tests cover long escaped Unicode, restart, invalid UTF-8, configured JSON limits, pool bounds/oversized buffers/Java serialization, and existing rollback/recovery/candidate deduplication/read budgets. [CI passes Linux JDK 17/21, Windows 17 and Docker](https://github.com/boloagegit/echo-mock-server/actions/runs/39135213759); this does not turn the soak's failed thread gate green.

Baseline JAR SHA-256: `a470d382e0cd046aba7a7e5fa6e7378aefa839b5f80fa87375f2045857baed85`; candidate: `2aa310f13e0faabed77714a512f965aa6bdcc279b77880461854aa7ef05fbbf8`.

Raw JARs, DBs, JFR and per-request records stay outside Git. WireMock is not remeasured here; these gains are not added to the earlier JDBC 47.2% result.
