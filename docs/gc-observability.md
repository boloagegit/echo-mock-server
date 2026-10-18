# GC history and memory regression observations

Administrators can open **Settings → Resource Monitoring → Memory & GC** to see
individual GC events and a chart of heap used after each event. Collection runs
while the page is closed; opening or refreshing the page only reads observations.
There is no force-GC button, heap dump, object traversal, or per-request hook.

Each event includes the collector, collector-local ID, action, cause, completion
time, duration, heap before/after, net reclaimed bytes and individual memory-pool
usage. Heap totals exclude non-heap pools. Missing coverage is shown as unknown,
never zero. Net reclaimed bytes may be negative during concurrent collection.

**Remaining heap is not itself a leak.** Objects still reachable from requests,
caches, rules or the broker normally survive GC. Young collections do not reclaim
the whole heap. Compare the same collector after warm-up and under comparable
load. Concurrent GC duration is not necessarily application pause time. The chart
does not diagnose a leak or guarantee that OOM cannot happen.

## Collection and retention

Echo uses the JDK's GC notification API on supported JVMs (tested with HotSpot /
Temurin on Windows and Linux). The callback only calculates a small record and
adds it to a bounded in-memory ring; it performs no disk/network/SQL IO. The
history starts when the monitoring service subscribes, not at JVM process birth.
For early startup GC or durable JVM-level history, also configure JVM GC logging.

```yaml
echo:
  monitoring:
    enabled: true
    jvm-enabled: true
    gc-enabled: true
    gc-history-capacity: 512  # 16..4096 events; restart to change
    gc-history-max-age-seconds: 86400  # 60..604800 seconds; 24h default
```

Disabling any of the first three switches prevents listener registration. History
is process-local, clears on restart, and removes its oldest event at capacity or
after the retention age, whichever comes first. Age uses monotonic elapsed time
since recording, so wall-clock changes cannot prolong retention. Idle housekeeping
runs every minute; reads and new events also prune expired entries. A record can
remain in memory until the next cleanup tick, but is never returned after expiry.
High GC rates can fill the count limit well before 24 hours; this is a maximum age,
not a promise of 24-hour coverage. No history is written to the database or app log.
The UI displays the newest 512 events with collector filtering and 25-row pages.
It reports recording start, total recorded, capacity evictions, expired records and failed notifications;
unsupported collectors are explicit. The JSON export contains the displayed
window, not a durable all-time archive. Browser errors mark the old window stale;
permission loss clears it. The listener detaches when the service closes.

`GET /api/admin/resources/gc` is ADMIN-only and sends `Cache-Control: no-store`.
There is no POST action. Without `after`, it returns the newest `limit` events
(default/max 512). `?after=123&limit=200` returns the oldest retained events after
sequence 123, ascending by notification arrival. Continue from `nextAfter` while
`hasMore` is true. A collector's ID is not globally unique; use `sequence` plus
`processStartedAt`. A restart requires resetting the cursor. `missedEvents` makes
overwritten or expired unread events visible; `notificationFailures` reports decode errors.
Persist these pages outside Echo when longer retention is needed. JVM notification
delivery is best effort; this endpoint is not an audit log of every JVM activity.

If separate JVM GC files are enabled, bound them independently, for example
`-Xlog:gc*:file=logs/gc.log:time,uptime,level,tags:filecount=5,filesize=10M`.
Create the log directory first. JVM file rotation is separate from the UI history;
do not configure an unlimited external archive unintentionally.

## Resources beyond heap

The existing resource snapshot shows non-heap and direct/mapped buffer memory,
threads, HTTP/JMS active operations, pending work and request-log buffering. It now
also exposes `nativeActive`, `nativeBufferedBytes`, `nativeQueued`, `nativeCapacity`
and `nativeBufferLimitBytes` for the default HTTP transport. `nativeWorkerActive`
and `nativeWorkerCapacity` show actual busy workers and their configured limit;
draining admission counters alone does not prove that blocking work has exited. These counters expose
reservations, not a precise object-size calculation. Native memory, thread stacks,
SQLite and container/process limits can cause failure even when heap is stable.
No finite history can prove the absence of a leak; rising comparable baselines
should lead to object-retention/native-memory investigation.

The Memory & GC page also compares current resource counters with the previous
manual snapshot from the same process: body/template caches, rule counts, HTTP/JMS
buffer reservations, log queues, pending work, direct/non-heap memory and threads.
Only two snapshots are held in the browser, cleared on permission loss or unmount;
restart resets the comparison. Their timestamps differ from GC events. Increasing
counters narrow the investigation but cannot identify an object owner or prove a leak.

## Repeatable observation test

The harness launches **its own disposable Echo, database, downstream broker and
load generator**. It cannot target an existing deployment or delete its rules.
Windows development needs a full JDK 17 (including `jcmd`) and Python 3; Docker is
not required. Linux is the deployment acceptance environment.

```powershell
.\gradlew.bat bootJar memoryObservationFixtureJar
if ($LASTEXITCODE -ne 0) { throw 'Build failed' }
$echoJars = @(Get-ChildItem build/libs/echo-server-*.jar -File)
if ($echoJars.Count -ne 1) { throw 'Expected exactly one Echo JAR' }
python scripts/test-memory-observability.py --jar ($echoJars[0].FullName) --output build/observations/run-1
```

```bash
./gradlew bootJar memoryObservationFixtureJar
python3 scripts/test-memory-observability.py \
  --jar build/libs/echo-server-0.0.0-SNAPSHOT.jar \
  --output build/observations/run-1
# Substitute the actual versioned JAR name when building a release.
```

Use `--java` to select a full JDK's `bin/java` or `bin/java.exe`. The output directory
must be new. Defaults: 384 MiB heap, 60-second warm-up, 10 cycles of 60-second load
and 15-second natural idle, 100 total HTTP/JMS requests/second, 16 KiB payloads.
The disposable Echo uses 60-second GC-history retention to exercise expiry; the
sampler archives events every two seconds and fails on lost unread events or no expiry.

Add `--http-stalls` to hold eight incomplete HTTP uploads open during each warm-up and measured
load cycle (two chunked, six declared-length), alongside the normal HTTP/JMS traffic.
This mode uses an eight-second request deadline, so cycles must last at least ten
seconds, including warm-up cycles. This starts the same bounded HTTP workers before
the baseline. Before closing the test clients, it requires the server to close them and
all native HTTP workers, buffers and queues to drain. The summary records
`httpStallConnectionsRecovered`; warm-up recoveries are counted separately in
`warmupHttpStallConnectionsRecovered`. This distinguishes real recovery from merely
returning admission leases while a worker is still blocked. For example, append
`--http-stalls --warmup-cycles 6 --warmup-seconds 15` to either platform's command.
When testing reconnect cycles, use `--warmup-cycles 6 --warmup-seconds 15` (or
longer for the deployment's pool sizes) to exercise lazy broker network workers
before the baseline. Each warm-up result is preserved; do not raise growth limits
to hide an unexplained increase. Inspect the saved thread dumps and NMT categories.
Increasing `--cycles 60 --seconds 300` extends coverage to roughly five hours;
run longer in a Linux test environment matching deployment and realistic traffic.

Add `--backup-maintenance` to run SQLite backups every 15 seconds and independent
housekeeping every second alongside traffic. The disposable instance retains at most
three completed backups / 256 MiB. At idle the observer checks the retained inventory
and both health probes, then requires successful backup and pruning activity with no
backup failures. An unexpected scheduled-task error also fails the observation,
including a busy rejection before the backup's own failure logger. Use at least 90 seconds of measured load. Backup logs and inventory
checkpoints are included in the output; the production defaults are unchanged by this
test option.

Each load result also includes `latencyMs` for fixed/template/delayed/forwarded HTTP
and mocked/forwarded JMS. The caller times the actual request/reply, checks body and
correlation, and records successful samples in bounded HdrHistogram instances with
three significant digits. Counts must equal completed requests. Warm-up timings are
separate. These fixed-rate observations describe response times under that workload,
not maximum capacity; NMT, idle GC and class inspection remain enabled as selected.

The test checks exact HTTP responses, JMS reply content/correlation, cleared
request/buffer/writer counters, and downstream temporary-queue cleanup. It archives
natural GC notifications throughout. **Explicit GC occurs only after natural idle
and verified drain**, to compare retained heap at equivalent checkpoints; it is
never used to keep the service alive during load. Native Memory Tracking is enabled
for this disposable JVM, so this is not a production throughput benchmark.

Output includes `summary.json`, GC/resource NDJSON timelines, original JVM GC log,
per-cycle NMT summaries and thread dumps, plus child-process logs. Growth assessment
uses the first/last two checkpoint medians and a linear trend. Default allowances
are 24 MiB retained heap, 8 MiB direct, 16 MiB non-heap, 128 MiB NMT committed/RSS,
4 threads and 8 Linux file descriptors. Heap/native allowances are configurable.
These are regression tripwires, not universal safe limits. Stable cache/pool
retention is expected; small leaks below these allowances require longer runs.
RSS and file descriptors are collected only on Linux; NMT works on both platforms.

Add `--class-histograms` to collect class instance counts and shallow byte sizes at
idle checkpoints, plus the 30 largest positive class-size changes from baseline in
`classGrowthSuspects`. Raw `*-classes.txt` files preserve the full result.
This opt-in uses `jcmd GC.class_histogram -all` **only on the disposable test JVM**;
inspection pauses the JVM. `-all` avoids requesting another GC but includes
unreachable objects, including allocations since the checkpoint. A growing `byte[]`
or `String` is a clue, not proof of retention or its owning module. Exact retaining
references require a controlled heap dump and offline analysis (for example Eclipse
MAT); the production admin API intentionally does not trigger these operations.
Observation output is a finite test artifact: use CI artifact retention or remove
old run directories as part of the test environment's housekeeping.

A failed workload, missing GC coverage, restarted process, unread overwritten
events, undrained resources or fewer than four checkpoints cannot produce a pass.
`no-growth-detected-in-window` means only that the finite test stayed within its
declared allowances. The regression tests include a deliberately increasing data
series to check that the analyzer actually rejects retained-memory growth.
