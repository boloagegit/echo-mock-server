# Bounded transaction diagnostics

## Default behavior

Normal HTTP/JMS/SSE transactions add **zero diagnostic file bytes**. Each enabled
transaction gets a short local correlation ID. The existing durable Request Log
stores only this nullable ID; no JMS headers, second body copy, stage history, or
post-reply database UPDATE is added. Historical rows/spool records have a null ID.

A completed anomalous transaction emits one bounded `ECHO_TX event=END` summary.
The summary keeps the observed route, target selection, inbound/outbound/reply
IDs and ReplyTo, wait outcome/time, caller reply outcome, failure stage/class,
available JMS provider error code and cleanup/metadata limitations. It does not
retain Message/body/exception objects, credentials, headers, or exception text.
Business messages, forwarding selection, retries, ACK/transactions and legacy
XML responses are unchanged.

## Configuration

```yaml
echo:
  diagnostics:
    enabled: true
    jms-enabled: true
    http-enabled: true
    sse-enabled: true
    queue-capacity: 1024
    detailed-enabled: false
    detailed-seconds: 300
    daily-bytes: 10485760
    retained-bytes: 52428800
    retention-days: 7
    records-per-second: 20
```

Restart after changing configuration. The environment switch
`ECHO_DIAGNOSTICS_ENABLED=false` disables supplemental diagnostics; original
warning/error fallbacks remain. Monitoring switches are independent.

The output is separate from the application logger:
`<logging.file.path>/diagnostics/transactions-YYYY-MM-DD.log` (default
`./logs/diagnostics/`). Dates use the host's local calendar. These files are not
duplicated into `echo.log`, console or `echo-error.log`.

- Daily default: **10 MiB of actual UTF-8 bytes, including newlines**.
- Total default: **50 MiB across owned diagnostic day files**.
- Maximum age: seven calendar days including today; capacity can shorten history.
- Each emitted line is at most 2 KiB including newline; bounded field values,
  with `truncated=true` when the overall line cannot retain every field.
- One nonblocking queue (32..4096), one daemon writer, aggregate 20 records/second.
- Current-day file length preserves the daily quota across application restarts.
- A directory lock prevents concurrent application writers sharing the budget.
- Only strictly named regular diagnostic files are pruned; unknown files,
  symlinks, current-day and future files are not deleted.
- An idle writer checks an existing directory once per minute for day rollover
  and age cleanup; healthy traffic does not create a diagnostic directory.

These limits apply to **supplemental diagnostics only**, not existing application,
broker, security logs, SQLite WAL/spool, or backups. Files manually modified by
another process and storage allocated by the filesystem are outside this budget.

## Temporary detailed tracing

Set `detailed-enabled: true` for a troubleshooting restart. It automatically
returns to anomaly-only behavior after `detailed-seconds` (maximum 600 seconds)
from startup, not per request. Stage checkpoints may include healthy requests
during that window. Detail uses the **same** queue, byte and rate budgets; it is
not a bypass. A busy system can exhaust the quota before the time window ends.
Do not assume a complete history when any omission counter is nonzero.

## How to investigate

1. Find the request in Request Logs; the overview has a copyable diagnostic ID.
   Existing keyword search also accepts that ID. Old/disabled records have no ID.
2. Search that ID in the diagnostic day file. No server-side file parsing or UI
   polling is added.
3. Compare outbound ID, target queue, ReplyTo and time with downstream broker /
   application records when the failure is beyond Echo's observation boundary.

| Summary evidence | Meaning and limitation |
| --- | --- |
| `sendReturned=true` | producer.send returned, **not** proof the downstream application consumed it |
| `waitResult=NO_REPLY` | receive returned null; timeout or consumer closure is possible |
| `waitResult=INVALID_TYPE` | A reply arrived but was not TextMessage; legacy XML is unchanged |
| `stage=CONNECT/CREATE_SESSION/SEND/CREATE_CONSUMER/RECEIVE_REPLY` | Observed operation when the forwarding failure occurred |
| `callerReply=SEND_RETURNED` | Reply send returned, **not** proof the caller consumed it |
| `callerReply=SEND_FAILED` | Original-caller reply attempt failed |
| `callerReply=NO_REPLY_TO` | One-way/no reply address; not inherently an anomaly |
| `cleanupFailure=true` | Temporary queue deletion failed; existing connection retirement runs |
| `metadataPartial=true` | Provider metadata was unavailable; observed business result is preserved |

The Request Log still describes processing, not end-to-end delivery. Its original
handoff happens before reply send; an early pipeline/admission failure can have a
diagnostic summary but no UI row. No new persistence attempt is forced into that
failure path, and durable logging failure keeps the existing redelivery contract.

If Echo regards a call as healthy but the caller sees no response, default mode
may have no diagnostic summary. Temporarily enable detail and compare the caller /
downstream records. A crash, never-returning provider call, exhausted quota,
full queue or disk failure can leave missing/incomplete evidence. Supplemental
diagnostics cannot retroactively reconstruct an older incident or prove ESB/LU
root cause alone.

## Monitoring and resource boundaries

The existing ADMIN-only manual snapshot shows writer/queue state, detail mode,
queue omissions, rate omissions, byte-budget omissions, output failures, current
day/retained bytes and their limits. Before the first write, output availability
is unknown, not proof the disk is writable. No periodic UI refresh is added.
Omission counters are process-local; byte quotas persist through file lengths.

Healthy requests format/enqueue no diagnostic lines. In-flight metadata is
bounded and request-scoped; there is no global transaction-ID registry. A full
queue or output failure never waits/retries synchronously on business threads.
Disk I/O is independent of the application's FILE appender. Costs are not zero:
short IDs enter the existing Request Log/spool and metadata has transient CPU /
allocation cost. This design is not an entire-JVM OOM or delivery guarantee.

## Verification / short cost probe

```bash
./gradlew t spotbugsMain bootJar
./gradlew diagnosticCost -PdiagnosticOutput=artifacts/diagnostics-cost -PdiagnosticRequests=3000
./gradlew diagnosticApplicationCost -PdiagnosticOutput=artifacts/diagnostics-cost -PdiagnosticRequests=1000
docker pull eclipse-temurin:17-jdk
python3 scripts/test-container-diagnostics.py
```

The short probe uses the Java 17 toolchain, a real in-VM Artemis responder and
512 MiB heap. It warms the same measured operation in both modes, retains three
ABBA blocks, and compares
enabled/disabled producer and forwarding paths, checks temporary queue cleanup
and reports latency, CPU, allocations, diagnostic bytes and omissions. It is not
a TIBCO/SIT capacity or endurance result. The second probe starts four disposable
HTTP applications with SQLite and the durable Request Log spool, warms each, then
measures an ABBA sequence. Its process CPU includes the local HTTP client and
asynchronous persistence. UI verification remains a separate gate. Do not infer
zero overhead from a noisy pair.

The Docker harness runs the actual SQLite application with persistent embedded
Artemis, the existing durable Request Log spool, and diagnostics enabled by
default. An isolated real downstream returns text, sends no reply for 30 seconds,
or replies with BytesMessage; the caller checks unchanged business XML and JMS
correlation. It verifies one final diagnostic summary per injected anomaly,
no healthy diagnostic bytes, matching inbound/outbound/ReplyTo identities and
the Request Log diagnostic ID, cleanup of all downstream temporary queues,
concurrent processing during the wait, and normal replies after failures. CPU,
container memory and heap caps are recorded separately, as are cgroup v2 OOM
events and pre-shutdown container state. See [script instructions](../scripts/README.md).
The fixture runs outside the Echo container, so its workload generator and
downstream memory are not mistaken for Echo's resource use. Tests with small
synthetic payloads cannot establish maximum throughput, production-native
provider behavior, worst-case payload memory, absence of all leaks or permanent
crash immunity. No forced OOM is part of this short diagnostics test.
