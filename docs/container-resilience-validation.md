# Container resilience validation

`scripts/test-container-resilience.py` is a disposable black-box harness for
JMS XML pressure, restart recovery, and bounded storage behavior. It owns one
generated Docker Compose project per run. The primary service uses a
project-scoped named volume for persistence; separate same-image services use
tmpfs named volumes for bounded capacity/full checks. It never mounts or edits
the repository's `mockdb.*` files.

## Run it

Prerequisites are a Docker daemon with Compose v2, Python 3, and a built
application jar. Build with the repository wrapper before the first run:

```bash
./gradlew bootJar
python3 scripts/test-container-resilience.py --mode quick
```

The normal profile uses 256 MiB fixed JVM heap (`-Xms256m -Xmx256m`), a 768 MiB
container memory limit, a project-scoped Docker-managed named volume for
persistent embedded Artemis and SQLite WAL data, and random host HTTP/JMS
ports. The disk phase starts a separate same-image service with a 256 MiB
tmpfs by default; the disk-guard phase uses another separate 64 MiB tmpfs.
Override values explicitly when needed:

```bash
# Reuse the already built image and run a longer bounded load.
python3 scripts/test-container-resilience.py --mode soak --skip-build

# Also run the separate deliberately-small (64 MiB by default) disk-guard phase.
python3 scripts/test-container-resilience.py --mode quick --disk-full

# Exercise a persistent XML body larger than 100 KiB through the same
# SIGKILL/start restart path (the ID remains inside the retained request body).
python3 scripts/test-container-resilience.py --mode quick --messages 8 \
  --recovery-messages 2 --payload-bytes 131072 --max-body-bytes 262144

# See the exact argv, parameters, evidence locations, and cleanup contract.
python3 scripts/test-container-resilience.py --mode quick --dry-run
```

Useful parameters include `--messages`, `--recovery-messages`,
`--payload-bytes`, `--max-body-bytes`, `--delay-ms`, `--send-concurrency`, `--heap`,
`--container-memory`, `--data-size`, `--full-data-size`, `--drain-timeout`,
`--output-dir`, `--keep`, and `--skip-disk`. `--full-data-size` must remain
smaller than `--data-size`. `--keep` leaves the uniquely named project running
for inspection; otherwise cleanup is exactly that project's
`docker compose down --volumes --remove-orphans`.

## Runtime protection being validated

- JMS listener sessions are transactional. A transient processing, memory, or
  request-log storage failure rolls the delivery back to Artemis instead of
  acknowledging it early.
- Redelivery uses automatic exponential backoff: 250 ms initially, doubled up
  to 5 seconds, with no delivery-count discard. These defaults work without
  extra configuration and can be overridden with
  `ECHO_JMS_REDELIVERY_DELAY_MS`, `ECHO_JMS_REDELIVERY_MULTIPLIER`,
  `ECHO_JMS_MAX_REDELIVERY_DELAY_MS`, and
  `ECHO_JMS_MAX_DELIVERY_ATTEMPTS`.
- Artemis persists messages, pages broker pressure to disk, limits each
  consumer's prefetch window, and synchronizes completed large-message writes.
- Artemis reserves 512 MiB of free disk by default and checks once per second.
  Before the filesystem reaches zero bytes it flow-controls producers; after
  space returns they can continue. Override the defaults only when the volume
  is deliberately smaller with `ECHO_JMS_MIN_DISK_FREE_BYTES` and
  `ECHO_JMS_DISK_SCAN_PERIOD_MS`.
- Request and reply processing share a bounded heap budget. Durable request-log
  admission is bounded by bytes, not only row count; overload becomes producer
  backpressure and is exposed through the agent status endpoint.
- A request that can never fit the configured hard message budget is treated as
  poison input: Echo does not parse or forward it, sends an explicit error when
  a reply destination exists, and completes it so it cannot create an endless
  redelivery loop.

Delivery is intentionally **at least once** during transient failures. If an
external downstream forward succeeds and the later local request-log write
fails, retry can forward the same request again because the two brokers do not
share one transaction. Downstream consumers should therefore remain
idempotent; the implementation prioritizes avoiding silent loss over claiming
unsupported exactly-once behavior.

## What is exercised

The primary service creates a delayed JMS XML rule, sends unique IDs through
`POST /api/admin/jms/test`, and samples `/api/admin/status` and
`/api/admin/agents`. Before the intentional `SIGKILL`, the harness records the
accepted IDs, durable JMS log count, estimated consumer backlog, agent pressure,
and Artemis data/paging paths. It then uses Compose `start`, checks the public
status healthcheck and rule persistence, waits for the queue/log agent to drain,
and fetches request-body details to account for every ID.

The accounting distinguishes missing IDs, duplicate IDs, unknown IDs, and log
details without an ID. All accepted primary and post-restart IDs must be
observed. A duplicate caused by crash redelivery is retained as optional
indeterminate evidence rather than silently treated as loss-free exactly-once
delivery. It does not fail an otherwise complete at-least-once recovery run.

When the backend exposes the pressure contract, every sampled agent must expose
these exact fields and non-negative numeric values:

```text
queueBytes, queueCapacityBytes, inFlightBytes, inFlightByteLimit,
waitingProducers, backpressureActive
```

The harness also records `droppedCount` and fails if accepted work is reported
as dropped. Absence of the optional pressure fields is reported as
indeterminate without changing the exit code, so older images remain diagnosable.

The normal disk phase starts a separate same-image service backed by a bounded
tmpfs, first drives real JMS traffic through that disk service, and records the
Echo SQLite and request-log spool files. It then fills a high-water file while
retaining the configured reserve, checks that the process remains responsive,
removes only that exact filler with `unlink`, sends another JMS message through
the same service, and verifies both the recovery ID and storage artifacts. The
`--disk-full` is retained as the command-line name for compatibility, but this
phase deliberately stops at the configured 8 MiB safety threshold instead of
forcing ENOSPC. It starts another service with a separate small volume, starts
one unique-ID send while the broker is flow-controlling producers, records
whether the producer remains pending/rejected, checks health, removes the
filler, awaits the same request, and verifies the exact ID after recovery. If
the endpoint clearly rejects under pressure, the same ID is retried only after
capacity returns; an uncertain transport result is never retried.

## Evidence and result semantics

Each run writes `result.json` and one stdout/stderr log pair per Docker command
under `artifacts/container-resilience/<run-id>/` (or `--output-dir`). The JSON
contains parameters, generated project name, command argv, return codes,
container state, status/agent samples, storage inventories, ID accounting, and
cleanup evidence. Cleanup evidence includes project-scoped probes for remaining
containers, volumes, and networks; a failed cleanup command or non-empty probe
is a required failure and makes the process exit non-zero. The result is also
printed with `--json`.

The process exit code is:

| Exit | Meaning |
|---:|---|
| 0 | Required checks passed. Optional Artemis paging or pressure observations may be indeterminate. |
| 1 | A required check failed: startup, health, backlog, kill/restart, drain, ID accounting, heap bound, OOM check, or requested disk recovery. |
| 2 | A requested capability was skipped or could not produce meaningful evidence, such as `--skip-disk`, Docker/Compose unavailability, or unsupported tmpfs. |

The disk-guard phase is marked skipped, without affecting the exit code, unless
`--disk-full` was requested. Paging and pressure absence are optional because
their paths/fields can vary by image and load timing; when present, their exact
observations remain in `result.json`.

## Boundaries and limitations

- The harness uses the existing authenticated admin HTTP enqueue endpoint, not
  an independent external JMS client. Backlog is inferred from accepted IDs
  versus durable request-log count, with `/api/admin/agents` pressure samples
  as additional evidence.
- The delayed consumer creates a controlled backlog; the harness does not
  directly inject a database outage, network partition, or external database
  slowdown. SQLite is embedded in the isolated container volume.
- The primary named volume is empirically checked with a run-owned sentinel,
  rule GET, and retained Artemis/SQLite state across Compose `kill`/`start`.
  The separate tmpfs volumes prove application-visible capacity guarding,
  Echo storage artifacts, and recovery only; they do not prove physical
  host-disk exhaustion, host filesystem reclamation, or durable storage
  behavior after a Docker daemon/host loss. `df`/`du` free-space recovery after
  the run-owned filler is unlinked is recorded as container evidence; host OS
  file reclamation remains explicitly indeterminate. An abrupt external
  ENOSPC can still trigger Artemis's critical-I/O shutdown path; the production
  VM therefore still needs host disk alerts and a process supervisor/restart
  policy. The in-process guarantee is prevention before ENOSPC, not survival
  after every possible storage-device failure.
- Artemis paging files may be removed as soon as the queue drains. The harness
  captures storage before the kill as well as after load, but a run with no
  visible paging path is reported as optional indeterminate rather than
  invented as a paging pass.
- Deleting old SQLite rows makes pages reusable by later writes, but SQLite's
  default mode does not promise that the database file immediately becomes
  smaller at the host filesystem level. The harness therefore verifies usable
  capacity recovery without claiming host-file shrinkage or running an
  automatic `VACUUM` during traffic.
- A successful run is automated/container evidence only. It is not deployment,
  production, human-acceptance, or exactly-once-delivery evidence.
