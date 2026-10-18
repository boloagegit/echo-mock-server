# Long-running HTTP/JMS stability hardening

[繁體中文](zh-TW/long-running-stability-2026-10-18.md)

Follow-up: [continuous ten-minute load, disk reserve and idle resource recovery](stability-followup-2026-10-18.md).

The acceptance goal is sustained service under slow clients and downstream failures,
with resources returning to their baseline after work ends. A finite observation
does not prove leak freedom. Linux/JDK 17 measurements below compare main `3ede241`
with the hardening implementation; Windows CI covers functional compatibility.

## 1. Stalled HTTP uploads

1. **Original problem:** a declared-length body used a blocking input stream. Closing
   the connection at timeout returned its lease but left the worker in `awaitReadable`.
   With 32 workers and 32 stalled uploads, an unrelated small request exceeded six
   seconds despite a one-second configured timeout.
2. **Change:** Undertow's bounded non-blocking receiver handles declared and chunked
   bodies. One deadline on the owning I/O thread covers upload, queueing, pipeline,
   delay and response delivery. Expired queued work is removed; busy-worker metrics
   are exposed separately from request admission.
3. **Measured effect:** the same 32-worker fault probe returned the small response in
   approximately 2.6 ms. Workers, leases, queues and reservations drained without
   first closing the test clients. Integration tests cover both framing modes.
4. **Costs and limits:** each native request has a deadline and cancellation bookkeeping.
   Already-running arbitrary CPU/database code cannot be safely killed. Multipart,
   HTTP/2, SSE and other existing Servlet fallback requests keep their own behavior.

## 2. Clients that stop reading HTTP responses

1. **Original problem:** pipeline completion canceled the old timeout before response
   delivery. In a four-slot test, four stalled 4 MiB responses retained all slots and
   48 MiB of buffer reservations beyond 4.5 seconds; a small request received 503.
2. **Change:** the request deadline remains active through response completion.
   Incomplete uploads and partial responses close on expiry. A complete request whose
   response has not started receives the existing 504 response.
3. **Measured effect:** with a one-second deadline, all four reservations and leases
   were observed released after approximately 1.1 seconds, with the test clients
   still open. The small request returned 200. Existing delay, size/capacity, TLS,
   reset and tracing regression tests are retained.
4. **Costs and limits:** the configured deadline now includes delivery, so a healthy
   but sufficiently slow ordinary download can be cut off. Increase the deadline
   when that behavior is intentional; explicit SSE remains on its existing path.

### Normal HTTP throughput check

Fixed `ok` responses, request logging disabled, 384 MiB heap, two I/O threads and
eight workers. Each of four fresh JVMs ran a 15-second warm-up and 15-second
measurements at concurrency 16 and 64, in before/after/after/before order. Echo used
three pinned CPUs and the verifying wrk client one other CPU. No concurrent builds
or load generators ran. Every measured response was checked for status and body;
there were no socket, timeout or content errors.

| Concurrency | Before mean req/s | After mean req/s | Change | Before mean per-run P99 | After mean per-run P99 |
|---|---:|---:|---:|---:|---:|
| 16 | 90,301 | 93,691 | +3.8% | 1.66 ms | 1.63 ms |
| 64 | 108,913 | 108,773 | -0.1% | 2.55 ms | 2.19 ms |

These are two observations per variant, not universal improvements or a WireMock
comparison. P99 values are averages of run-level percentiles, not a merged percentile.
The change is intended to retain normal performance while correcting fault behavior.

## 3. JMS downstream connection waits

1. **Original problem:** the configured reply timeout began only at `receive`.
   A peer accepting TCP without a broker handshake still held the caller after
   three seconds with a one-second reply timeout.
2. **Change:** providers have an opt-in outbound-specific factory path. The default
   `echo.jms.forward.bounded-waits-enabled=false` retains existing provider waits,
   retries and failover settings. When explicitly enabled, Artemis caps connect,
   broker-call and failover-call waits, preserves stricter limits, and leaves failed
   client rebuilding to the forwarder. Source factory/recovery settings are preserved.
   The target connection-test API uses the same outbound policy.
3. **Measured effect:** with bounded waits explicitly enabled, legacy, default and selected target tests complete failure
   handling, restore a real broker on the same address, successfully forward again,
   and leave no temporary queues. The complete tests took approximately 1.18–1.29 s
   each for a one-second target timeout.
4. **Costs and limits:** the default retains the original potentially long waits.
   Opting in curtails longer outbound URI retry policies and may fail a request that
   previously survived reconnect/failover. Echo's existing error-reply path completes
   its source transaction without automatically retrying that request. Individual
   stages have limits, not one strict end-to-end JMS deadline. A timeout does not prove
   non-delivery. EMS per-factory connection-attempt setters are configured without a
   process-wide timeout mutation, but licensed EMS send/ACK and failover behavior has
   not been tested here. Shared source-queue starvation by slow work remains possible.
   This mode requires acceptance with the actual ESB/provider before deployment.

See [JMS contracts](jms-performance-and-correctness.md) for the provider distinctions.

## 4. Orphan-response housekeeping

1. **Original problem:** deleting by ID loaded every full response entity, then issued
   individual deletes inside one transaction. Response `LAZY` annotations alone did
   not prevent body loading in this build.
2. **Change:** keyset pages contain at most 100 IDs. A guarded bulk delete rechecks age
   and HTTP/JMS references in each batch. Batches commit separately, publishing cache
   invalidation with their commit. A maximum-ID boundary keeps the run finite.
3. **Measured effect:** in ABBA runs deleting 500 orphan responses with 64 KiB bodies,
   after a 100-row warm-up, elapsed time averaged 149.3 → 41.6 ms (about 72% less).
   Response entities loaded: 500 → 0. Prepared statements for the cleanup job:
   1,004 → 14. All eligible rows were deleted. Separate SQLite tests cover referenced
   and extended rows, a later-batch failure/rollback and a successful repeat run.
4. **Costs and limits:** the run is no longer one atomic transaction: successful earlier
   batches remain committed if a later batch fails. The next run processes the
   remainder. More commits and invalidation events trade some overhead for bounded
   response-cleanup memory and shorter continuous writer ownership. Rule/Scenario
   cleanup still uses its existing operations.

## 5. Redundant rule-snapshot rebuilds

1. **Original problem:** Windows CI exposed a reader keeping an expired snapshot
   while another reader completed its rebuild. The delayed reader could then claim
   the released rebuild slot and perform an unnecessary third database build.
2. **Change:** recheck the latest snapshot after claiming the rebuild slot. Release
   an unnecessary claim and hand off any generation change arriving during the check.
   Normal fresh reads keep their existing path.
3. **Measured effect:** a deterministic two-reader regression test fails before the
   fix (three builds) and passes afterward (two: initial plus required refresh).
   This is a race-specific reduction, not a general throughput measurement.
4. **Costs and limits:** a claimed rebuild performs an extra freshness check; normal
   reads do not. Stale-while-rebuilding behavior, change generations, update waits and
   failure backoff remain the existing contract.

## Load/idle observation

A Linux/JDK 17 run completed ten 60-second measured cycles (60,000 verified HTTP/JMS
requests) after six reconnecting warm-up cycles. All 80 stalled-upload connections
were recovered with clients still open; request-log drops and JMS forward/reply/
temporary-queue cleanup failures were zero. GC-after-idle heap was 99.37 MiB at
baseline and 93.20 MiB after the tenth cycle; 148 GC events were archived and 139
expired from the bounded in-process history.

All seven resource series passed the observer's existing growth limits. This does
not mean zero growth: the first/last-window comparison reported approximately
9.2 MiB more non-heap memory, 37.9 MiB more RSS and three more JVM threads. The
observation was approximately 16 minutes, with explicit GC only after idle/drain;
it does not establish leak freedom or 24/7 ESB stability.

The frozen artifact used the bounded Artemis policy before the compatibility
opt-in gate was added. Treat this as evidence for that mode and the HTTP resource
fixes, not a measurement of the final default-policy artifact or a licensed EMS
deployment. The final default policy is checked separately by the forwarding,
connection-test and source-policy regression tests.

## Reproducing lifecycle acceptance

Run the integration tests and `check` with JDK 17, including on Windows. For the
isolated load/idle observer, follow [the GC observation guide](gc-observability.md)
and append `--http-stalls --warmup-cycles 6 --warmup-seconds 15`. The default ten
60-second cycles exercise HTTP/JMS traffic, logging, repeated stalled uploads,
temporary-queue cleanup, actual busy workers, GC expiry and post-idle resource trends.
Use longer runs and deployment-like traffic for small or workload-specific leaks.

Raw measurement output belongs outside the source repository; benchmark helper
scripts under `scripts/bench` remain available for deployment measurements.

## Further operating fixes on the same branch

### JMS target isolation

1. **Original problem:** factory creation and retirement of idle/superseded clients held the map lock shared by all named targets. A stalled provider could block a healthy target's first request.
2. **Change:** reserve a use before publishing a client; stop admission and detach old clients under the map lock; create/retire resources under the individual lifecycle afterward. Published-client monitors and provider calls never run under the map lock. No extra executor, forced interruption, ACK/transaction change or retry-policy change.
3. **Observed result:** three controlled stalls (factory creation, idle eviction, superseded-client close) exceeded a two-second acceptance window before the fix. All now let an unrelated target reply while the other provider is still held. The focused JMS run completed 273 tests, zero failures, six skips, including real Artemis transactions, forwarding, reply isolation and cleanup.
4. **Cost and limits:** factory creation is lazy per client; retirement still waits for active users. The affected target can still wait for its provider, and the source listener pool is not partitioned per target. These stalls validate Echo's locking, not a licensed EMS installation or normal throughput.

### Backup retention through failures

1. **Original problem:** expiry ran only after a successful new backup, with no byte/count budget. A full destination could prevent both copying and the cleanup needed to free space.
2. **Change:** independent hourly housekeeping, serialized maintenance and pre-copy size/free-space checks. Defaults: 7 days, 10 GiB, 100 completed files, 512 MiB free reserve. Preserve the newest verified healthy copy even if expired; a newer corrupt file cannot displace it. If none can be verified, preserve existing recovery material. Exclude unrelated files, directories and symlinks.
3. **Observed result:** 14 retention/capacity tests plus the original three backup tests cover unavailable storage, injected low space and recovery, byte/count pruning, corrupt backups, concurrent calls and Windows-compatible paths. Online backup, integrity/foreign-key checks and atomic publication remain.
4. **Cost and limits:** capacity may shorten history; a copy that cannot fit while preserving the last valid backup is refused. Verification adds maintenance I/O. Admission checks do not reserve disk against other processes or database growth; completed-file limits exclude unrelated files and crash-left partial files. A verified published backup stays successful if subsequent pruning fails.

### Liveness and readiness

1. **Original problem:** container checks used the management statistics endpoint, which returned 200 while JMS reconnected and performed DB statistics work for a process check.
2. **Change:** standard Actuator liveness checks application availability; readiness adds DB, free disk space and the existing JMS source listener. Only these two GET probes allow anonymous status-only access. The built-in JMS connection-test indicator is disabled. Docker uses readiness, runs Java as PID 1 and Compose allows 60 seconds for shutdown. CI supports manual branch runs and a container stop/restart smoke test before opening a PR.
3. **Observed result:** stopping a real external Artemis source yields readiness 503 and liveness 200. Restart restores readiness 200 and correct JMS replies. Tests cover routing, status-only output, disabled JMS and DB failure mapping. The local full JDK 17 build passed 2,177 tests with zero failures and six skips, plus static analysis.
4. **Cost and limits:** readiness uses datasource timeouts; liveness does not depend on DB/ESB availability. Listener state cannot diagnose every blocked operation. Compose restarts exited containers, not running unhealthy ones; automatic restart of an unresponsive process needs a supervisor checking liveness. Probes do not force-close transactions.

Add `--backup-maintenance` to the observer to copy SQLite every 15 seconds, prune independently to three completed files / 256 MiB, verify retained inventories and check both probes at idle. No completed backups/pruning or any backup failure makes the observation fail. This remains a finite regression window; use deployment-like 24–72-hour runs for extended acceptance.

### Warm shared-response rewrites

1. **Original problem:** after a response had been used, editing its existing body through a rule saved the new database value but HTTP and JMS could keep replying with the old cached value. JPA returned the same already-mutated entity during the old-value comparison. This was also reproduced on the original `main`.
2. **Change:** conservatively invalidate when the comparison returns the same entity instance. Keep the existing pre/post-commit invalidation and cross-instance notification. Detached metadata-only edits still skip body invalidation; request processing gains no extra database read.
3. **Validation:** regressions first failed for two HTTP rules sharing a warm body, clearing that body, and a real Artemis JMS reply sharing it with HTTP. The same checks now pass; SSE validation, atomic rollback and optimistic-conflict tests remain part of acceptance.
4. **Cost and limits:** re-saving a managed entity can clear its body cache even if its content ultimately stayed the same, so the next use reloads it. This is an administrative write cost, not a per-request check. Actual TIBCO EMS remains unverified.

### Scheduled backup versus housekeeping

1. **Original problem:** the 30-minute mixed observation recorded three scheduled copies rejected because independent housekeeping held the maintenance lock. Subsequent ticks recovered, but a daily schedule could lose that recovery point.
2. **Change:** the scheduled job waits interruptibly for maintenance and creates the verified copy after acquiring the same lock. Manual duplicates still fail immediately and housekeeping still skips an active copy. No executor or retry queue is added.
3. **Validation:** a deterministic real-SQLite test holds housekeeping, verifies that the scheduled copy waits, releases it and checks a healthy published snapshot. A second test interrupts the waiter and proves that a later copy still works. Both tests fail before the fix.
4. **Cost and limits:** a waiting scheduled copy occupies one background scheduler thread. It does not hold a JDBC connection while waiting or block the HTTP/JMS worker pools. It waits as long as ongoing maintenance takes; shutdown can interrupt the wait, and physical storage failures remain explicit failures.

## Final observation and WireMock baseline

The `f9a60fc` production artifact completed 180,000 verified HTTP/JMS requests over thirty measured minutes, 66 administrative cycles and 132 real JMS shared-response rewrite checks. All seven resource thresholds passed; 138 verified backups completed with no maintenance-lock collision. See the [HTTP throughput, memory and lifecycle comparison with WireMock](wiremock-comparison-2026-10-18.md) for the separate matched HTTP run, logging-policy caveats and original resource flags.

## Disabled JMS and embedded broker startup

1. **Original problem:** `echo.jms.enabled=false` disabled Echo's listeners and handlers, but Boot's independent Artemis auto-configuration still started an idle embedded broker. The packaged-default HTTP run had nine broker-related threads despite reporting JMS disabled.
2. **Change:** bind `spring.artemis.embedded.enabled` to `${echo.jms.enabled:false}` in the existing application configuration. This uses Boot's supported broker switch; no per-request work, executor, ACK, transaction, retry or forwarding-policy change is introduced.
3. **Validation:** a new integration assertion failed before the fix because `EmbeddedActiveMQ` was present. After the fix, disabled JMS has no broker/listener, HTTP mocks and readiness work, and JMS rule creation remains rejected. Twenty-four focused tests cover that behavior, embedded request handling, external Artemis replies/reconnection and rollback redelivery. The full local JDK 17 check passes 2,190 tests with zero failures and six skips, including SpotBugs.
4. **Cost and limits:** resource savings apply when JMS is disabled. Enabled embedded and external-source modes keep their existing configuration and behavior; this is not a JMS throughput improvement or actual EMS validation. The older WireMock comparison retains its pre-fix resource and throughput measurements. Runtime evidence is kept outside Git in `echo-results-review-20261018`; no images, JARs or raw JVM dumps are added to the repository.
