# SQLite request-log insert experiment

[繁體中文](zh-TW/request-log-jdbc-experiment-2026-10-18.md)

Follow-up: [512 MiB capacity, large messages, rate ceilings and framework costs](capacity-and-framework-review-2026-10-18.md).

This experiment compares only the durable consumer's main-database inserts. Rules, responses,
configuration and request-log queries retain Spring Data JPA. The existing SQLite spool is unchanged.

1. **Problem:** `RequestLogBatchWriter` uses JPA `saveAll` with identity-generated IDs. A list of
   entities does not guarantee JDBC batching; replay also pays for Hibernate entity management.
2. **Change:** A SQLite-only path prepares one INSERT per delivery batch and uses JDBC batching
   on the existing Hibernate transaction connection. Column names and value binders come from
   the actual Hibernate mapping. Existing normalization/default-time callbacks are reused;
   checkpoint and retention stay in the same Spring transaction. Non-SQLite databases and
   inputs with existing IDs retain JPA behavior. Input objects do not receive generated IDs;
   the internal spool consumer does not use those IDs.
3. **Evidence:** Four alternating JVM runs pass content/count/checkpoint verification. With
   16 KiB in each body column and batches of 50, mean writer throughput increases 47.2%,
   CPU per row decreases 30.6% and caller-thread allocation per row decreases 19.1%.
   Single-row throughput increases 17.6% in the same payload case. Tests cover all fields
   against a JPA-written reference, non-default JDBC timezone/time-range queries, nulls,
   partial-batch rollback, checkpoint failure after inserts, outer rollback, retention,
   existing-ID updates and non-SQLite fallback.
4. **Cost/limits:** One isolated adapter depends on Hibernate's mapping SPI and must be checked
   when upgrading Hibernate. The test is a writer cost probe, not an HTTP/JMS capacity claim.
   Default asynchronous discard and writer-rate policies are unchanged, as are JMS ACK,
   transaction, retry and reply-isolation behavior. Actual licensed TIBCO EMS is not exercised.
   Failed JDBC work rolls back through the original replay flow; it does not silently retry
   the same batch through JPA. The packaged SQLite default enables this path;
   `ECHO_REQUEST_LOG_JDBC_BATCH_ENABLED=false` followed by restart restores JPA.

## Results

Mean of two observations per strategy, with the same retained contents and schema:

| Bytes in each body column | Batch | JPA rows/s | JDBC rows/s | Throughput change | CPU/row reduction | Allocation/row reduction |
|---|---:|---:|---:|---:|---:|---:|
| 0 | 1 | 7,579 | 9,883 | +30.4% | 22.7% | 33.0% |
| 0 | 50 | 23,906 | 59,456 | +148.7% | 59.4% | 74.9% |
| 16 KiB | 1 | 5,213 | 6,132 | +17.6% | 15.2% | 14.9% |
| 16 KiB | 50 | 9,863 | 14,522 | +47.2% | 30.6% | 19.1% |

For 16 KiB/body, mean run-level batch P99 is 0.687 → 0.684 ms for batch 1 and
21.201 → 18.678 ms for batch 50. These are entire batch-call latencies, not JMS reply
latencies or merged-sample percentiles. The production writer's default 1,000/s rate
cap remains unchanged; these uncapped figures do not increase that configured limit.
Two samples per strategy do not establish significance or performance on other hosts.

## Comparison method

Linux/JDK 17, G1, 512 MiB heap, SQLite WAL/`synchronous=NORMAL`, identical schema/indexes,
10,000 retained rows. Four fresh JVMs run JPA/JDBC/JDBC/JPA. Each case warms for at least
eight seconds and 10,000 rows, then measures for fifteen seconds. Batch sizes: 1 and 50;
request and response body columns: each 0 or 16 KiB. The writer is not rate capped in this
probe. There are no concurrent builds or load generators. Retained contents, retention
count and checkpoint are verified after each case. CPU includes the whole process;
allocation covers only the caller thread. Input construction is included in cost.

```bash
./gradlew requestLogWriteCost \
  -PrequestLogCostOutput=build/observations/writer-jpa \
  -PrequestLogCostMode=jpa
./gradlew requestLogWriteCost \
  -PrequestLogCostOutput=build/observations/writer-jdbc \
  -PrequestLogCostMode=jdbc
```

Windows: use `gradlew.bat` and PowerShell arguments, such as
`'-PrequestLogCostOutput=build/observations/writer-jdbc'`. Output directories must not
already exist. Raw logs/results remain outside Git under
`jdbc-request-log-experiment-20261018/comparison`; the manifest records order, source hashes,
raw timings and checks. `jdbc-request-log-experiment-20261018/probe-at-measurement.java`
preserves the measured probe. Later changes add startup-strategy verification, a Windows-safe
log location, initialization synchronization and composite-mapping fallback. The timed
write path for the measured basic-field mapping is unchanged. Temporary benchmark
databases are verified before application shutdown and removed after it closes.

## Application regression

Implementation source: `ab7b292`. Local JDK 17 `check`/SpotBugs passes: 2,209 tests,
zero failures and six skips. The [cross-platform CI](https://github.com/boloagegit/echo-mock-server/actions/runs/39118743651)
passes Linux JDK 17/21, Windows JDK 17 including a workspace path with spaces, and
container probes/stop/recreation.

The frozen default-enabled artifact completes 600.050 s uninterrupted at 200 combined
requests/s after six 15 s warmup cycles. All 120,000 measured responses are correct:
60,000 HTTP (fixed/template/forward/delay) and 60,000 JMS (mock/real Artemis forwarding).
Warmup adds 18,000 requests. ERROR, OOM, request-log drops/unconfirmed writes and storage
retries are zero. All queues/buffers drain and the downstream has no temporary queues.

All seven growth gates pass. Changes between early/late load windows are: natural
post-GC heap +0.78 MiB, NMT committed +7.06 MiB, direct +0.016 MiB, non-heap +6.73 MiB,
JVM threads +2.5, RSS +9.23 MiB and FDs unchanged. Fractional counts are window medians;
edge minute buckets may be partial. Pre-load/final-drained RSS is 895.9 → 913.5 MiB,
natural post-GC heap 109.99 → 111.48 MiB. No GC is requested; 210 natural events are
observed during measured load, 248 archived including warmup, with 232 history expirations.
Across startup/warmup/load/drain, 51 backups complete, zero fail and 48 are pruned;
three files/about 53.5 MiB remain. Eight measured stalled uploads recover, plus 48 warmup probes.

Measured HTTP fixed/template/forward/delay P99: 3.086/2.959/5.870/14.221 ms.
JMS mock/forward P99: 10.822/17.383 ms. This is functional/stability regression at
the stated fixed rate, not evidence of a 47% end-to-end JMS speedup. Ten minutes and
young-GC observations cannot establish permanent leak freedom. Existing startup/broker
WARN categories were inspected; there are no spool faults or unexpected runtime errors.
See the [observation method and thresholds](gc-observability.md).

Frozen application SHA-256:
`6cc5334607b3ca04d45003620530fc08b1c2b3795864ec05b8fb948150a7c87a`;
load fixture `b16759299ed35f361ac0965f494318cee13988cdc85a2d6ce0142845dd14eeee`.

Acceptance artifacts: `jdbc-request-log-experiment-20261018/continuous-10min`.
