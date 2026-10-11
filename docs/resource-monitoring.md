# Resource snapshots and JMS policy diagnostics

Resource snapshots are available to administrators in Settings and at
`GET /api/admin/resources`. The endpoint sends `Cache-Control: no-store`.
Settings loads once on entry, then only on explicit refresh or retry. There is
no polling, trend history, automatic recovery, or per-request telemetry store.
Supplemental transaction file-log counters are described in
[Transaction file diagnostics](transaction-diagnostics.md); their switches are
independent of resource snapshot collection and durable Request Log policy.
Export downloads the already collected, sanitized snapshot, not another query.
Cold entry to Settings uses an ADMIN-protected, database-independent access
check so a stalled legacy status query does not prevent diagnostic collection.

## Switches

All monitoring switches default to `true`. Configure them in the deployment
configuration and restart Echo to apply changes:

```yaml
echo:
  monitoring:
    enabled: true                 # false disables all new resource collectors
    jvm-enabled: true
    caches-enabled: true
    scheduler-enabled: true
    jms-enabled: true             # also disables the new JMS event counters
    http-enabled: true
    database-enabled: true
    request-log-enabled: true
    application-log-enabled: true
    storage-enabled: true
  jms:
    policy-summary-enabled: true  # startup description only
```

These flags disable this feature, not existing caches, meters, admission limits,
DB recovery, backups, request logging, or connection cleanup. Disabling a group
does not acquire its datasource, query its metrics, or perform its filesystem
probes. Disabled JMS telemetry does not increment its new counters. Resource
safety behavior is unchanged.

## Meaning and limits

- Each group reports AVAILABLE, PARTIAL, DISABLED, UNSUPPORTED, or FAILED.
  Missing values are unavailable, never fabricated zeroes. Failure responses do
  not contain exception messages, credentials, URLs, bodies, or full paths.
- Counts belong to this process lifetime. Collection timestamps are retained
  when a refresh fails; the UI marks that snapshot stale. Permission loss clears
  it. A snapshot is observational, not an atomic cross-metric consistency check
  or a guarantee of system health.
- JMS listener/forward exits include error handling and intentional no-reply
  behavior; they are not successful replies. `replySent` means the JMS send call
  returned, not that the caller consumed the reply. Receive timeouts do not bound
  connect, send, delete, or close. Active forwarding ends only after cleanup exits.
- HTTP non-cancelled completions can include error responses. Pool totals are
  capped at 64 client holders and 64 pools per holder. PARTIAL means those totals
  are incomplete; global request/buffer counters remain process-wide.
- Cache weights estimate configured capacity, not exact retained heap. The
  scheduler queue includes normal timers whose due time has not arrived.
- JVM non-heap and direct/mapped buffer values do not cover all native
  allocations, SQLite memory, or full process RSS. No GC or dump is triggered.
- Database metrics read local Hikari and SQLite writer counters only. They do
  not acquire a connection or execute SQL, PRAGMA, or integrity checks.
- Request-log spool and application-log ASYNC_FILE are separate queues. Queue
  occupancy does not prove that the disk is healthy. An unavailable appender
  (for example dev console-only logging) is reported as UNSUPPORTED.
- Storage reads free space for two fixed configured locations on recognized local
  filesystems only; no directory listing, recursive scan, or backup integrity
  operation. Missing locations and unsupported filesystem types remain unavailable.
  Operators with slow/unreliable mounts can disable `storage-enabled` independently.
  Filesystem probes occur before the type can be identified and may block on an
  unreliable mount. The browser deadline stops waiting; it cannot guarantee
  cancellation of an in-progress backend filesystem syscall.

Fixed counters and snapshot reads have low expected overhead, not zero overhead.
No monitoring-only SQL, network call, extra log line, or body copy is added to the
normal request path. Snapshot HTTP requests have a one-shot browser deadline,
not a repeating timer. Status and resource collection are independent.

## JMS warnings

The startup summary describes Echo's wildcard address policy. Finite retries
without a dead-letter address receive an explicit risk warning even if the info
summary is disabled. The existing PAGE, memory, disk, retry, expiry, and message
routing policies remain unchanged. AMQ222165/AMQ222166 remain enabled: their queue
names alone cannot safely identify temporary destinations. No DLQ/expiry routing
or broad warning suppression is introduced. Outbound temporary reply queues are
still explicitly deleted before session close; deletion failure retires the exact
connection as before.

## Reading the Settings panel

Settings has five tabs: Overview, Resource Monitoring, Forward Connections,
Data & Backups, and Service Configuration. Overview shows six compact summaries
(heap, JMS, HTTP, database waits, request-log persistence, and free storage).
Resource Monitoring has six keyboard-operable disclosures: Memory & GC, Caches,
JMS, HTTP, Database & Persistence, and Delay Scheduler. Together they retain all
display fields across the original nine collector groups, grouped into current
usage, lifetime counters, and limits/configuration/timestamps. Database &
Persistence also shows the independent supplemental diagnostic writer, queue,
omissions and byte budgets; it does not read diagnostic file contents.

The same monitoring component remains mounted when switching Settings tabs.
Opening a disclosure or following an overview detail link does not collect
another snapshot. Collapsing details reduces visual/DOM complexity, not backend
collection cost. Counter details show the process start time. Cache hit rates
come from the separate legacy status response, not the resource snapshot.
Collection state describes availability, not a health verdict. Zero temporary
queue cleanup failures does not establish that no queues remain: downstream
broker connection/temporary-queue inventory is not collected. Persistent pooled
connections alone are not leak evidence. Scheduled backups do not establish
that restore has been verified.

Forward Connections uses one shared HTTP/JMS table with source, destination,
default role, last test, and actions. Tests are explicit; the displayed result
belongs to this page session. Configuration-backed JMS entries remain read-only.
Data & Backups keeps retention, file inventory, manual backup, and collapsed
destructive operations separate from service configuration. These presentation
changes do not change endpoints, permissions, routing, or monitoring switches.

## Verification

```bash
./gradlew t spotbugsMain
node --test src/test/js/*.test.cjs
```

Tests cover switches, permissions, sanitization, failure isolation, no polling,
JMS timeout/invalid replies/cleanup concurrency, and unchanged resource cleanup.
Embedded/mocked checks do not establish the cause of an external broker incident.
