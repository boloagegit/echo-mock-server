# Artemis large-message upgrade validation

[繁體中文](zh-TW/artemis-large-message-validation-2026-10-18.md) · [Previous spool experiment](request-log-spool-read-experiment-2026-10-18.md)

## Problem and change

Echo's earlier JFR contained 823 leaf samples in `ChannelBufferWrapper.writeByte`, reached through inherited `OutputStream.write` loops and `ClientLargeMessageImpl$ActiveMQOutputStream`. Official [ARTEMIS-6009 / PR 6369](https://github.com/apache/artemis/pull/6369), merged April 21, 2026, fixes this exact path by overriding bulk `write(byte[], offset, length)`. Its author's performance number is not an Echo measurement. Tagged sources show the fix first in 2.54.0.

[Artemis large-message documentation](https://artemis.apache.org/components/artemis/documentation/latest/large-messages.html) explains the default 100 KiB encoded-size threshold and two-byte character encoding. A roughly 64 Ki-character text body can already take this path; UTF-8 byte count, Java character count and broker encoded size differ.

The change uses the official 2.57.0 BOM and uniform `org.apache.artemis` Maven coordinates, following the [group migration](https://artemis.apache.org/artemis-tlp-groupid-migration). Java packages remain the same. Netty moves from 4.1.135 to 4.1.137, matching Artemis's POM. As recommended by [Spring Boot's JMS guide](https://docs.spring.io/spring-boot/3.5/reference/messaging/jms.html), the embedded module becomes `artemis-jakarta-server`; this also removes the old duplicate `artemis-jms-client` implementation alongside the Jakarta client. Removing that duplication is not separately measured as a speed gain.

Application-class CRCs are identical between the two frozen JARs. Persistence, `largeMessageSync=true`, listener transactions, ACK/retry policy, message type, IDs, correlation, reply queues and memory admission are unchanged. No provider fork or custom message decoder is added. The provider's transitive dependencies also change, so the comparison does not isolate the single upstream patch.

## Whole-application comparison

Four fresh Echo JVMs, old/new/new/old order, Linux four-CPU quota, Java 17.0.20.1/G1, `-Xms512m -Xmx512m`, identical main-DB seed, 10,000 retained logs, 1,000/s main-log writer ceiling and 128 KiB padding. Each JVM first completes 12,000 mixed warmup requests. Each JMS case then warms for five seconds and measures for 30 seconds with eight callers. HTTP warms for five seconds and measures for ten seconds with 16 connections. Clients and the shared downstream stay on Artemis 2.40.0 for both versions. No local builds or JFR run during timing. Backups are off only for peak comparisons.

| Scenario | Old / new mean responses/s | Change | Old / new mean run P99, ms |
|---|---:|---:|---:|
| JMS mock | 301.6 / 578.8 | +91.9% | 40.7 / 22.2 |
| JMS forward | 108.5 / 130.7 | +20.4% | 110.9 / 95.4 |
| HTTP forward, short phases | 954.5 / 955.5 | +0.1% | **53.3 / 76.4** |

Individual responses/s in execution order:

| JVM | JMS mock | JMS forward | HTTP forward | All-phase log drops |
|---|---:|---:|---:|---:|
| Old 1 | 266.5 | 96.8 | 938.5 | 138 |
| New 1 | 582.5 | 132.2 | 973.1 | 106 |
| New 2 | 575.2 | 129.2 | 937.9 | 71 |
| Old 2 | 336.7 | 120.2 | 970.4 | 1 |

All response-body/status/correlation checks pass. Every JMS case has zero log drops and unconfirmed writes. Formal HTTP drop deltas in that order are 0/106/71/1; the first old run's 138 drops occur outside its formal window. End totals include warmup, all cases and drain. Storage retries and unconfirmed writes remain zero, resources drain and database `quick_check` passes. High-pressure response throughput is not indefinitely sustainable complete-record throughput.

P99 cells average the two run percentiles, rather than pooling requests. Two JVMs/version are finite evidence: old JMS throughput varies substantially, forwarding still includes old clients/downstream, and the short HTTP tail is worse. This is not a claim that every protocol or payload gets faster, nor a fresh WireMock comparison.

### Independent HTTP follow-up

To investigate the worse short-phase tail, run four new JVMs in old/new/new/old order with the same seed, heap, recording settings and old downstream. JMS remains enabled but idle. Warm HTTP for 30 seconds, drain recording, then measure HTTP forwarding for 30 seconds with 16 connections. No JFR or competing local load runs.

| JVM | Responses/s | P99, ms | Formal drops, including retention skips | Retention skips within those drops |
|---|---:|---:|---:|---:|
| Old 1 | 1,074.6 | 47.830 | 3,693 | 3,288 |
| New 1 | 1,079.3 | 47.128 | 3,295 | 2,841 |
| New 2 | 1,044.6 | 47.404 | 3,100 | 2,243 |
| Old 2 | 1,027.4 | 48.369 | 2,202 | 1,652 |

Old/new means are 1,051.0/1,062.0 responses/s (+1.0%) and 48.10/47.27 ms mean run P99. All response checks, final drains and database checks pass; retries and unconfirmed writes remain zero. All-phase drops are 3,982/3,669/3,713/2,305, including warmup. Retention skips avoid persisting records already outside the configured history window; they are included in the drop counter, not added again. These are saturation runs, not complete-record capacity claims.

The isolated longer-warmup control does not reproduce the short HTTP tail increase. Its warmup, load duration and absence of preceding JMS load differ, so it neither invalidates the original negative result nor establishes universal equivalence. No material HTTP speed gain is claimed.

## Compatibility and correctness

Ten separate checks pass: all four 2.40/2.57 TCP client/broker combinations reject an incorrect password and correctly handle a durable, escaped Unicode/emoji TextMessage with rollback/redelivery. They verify body, message ID, correlation, custom property and persistent delivery mode. Additionally, an old broker writes a durable large message, shuts down cleanly, and the new broker reopens that same journal and successfully reads/rolls back/redelivers/commits it. This is not crash/power-loss or cluster validation.

The permanent `ArtemisLargeMessageIntegrationTest` exercises persistent Unicode text across broker restart and rollback using temporary directories and portable NIO. Full local tests: 2,219 main + 9 migration, zero failures, six skips; SpotBugs passes. [CI](https://github.com/boloagegit/echo-mock-server/actions/runs/39140666233) passes Linux JDK 17/21, Windows JDK 17 from a path containing spaces, and Docker deployment checks.

## Ten-minute mixed observation

Eight paced callers, 200 total requests/s, HTTP/JMS 50:50, mock and real forwarding, 128 KiB padding, 512 MiB heap, 10,000 retained logs and 1,000/s writer ceiling. Six 20-second warmups precede 600 seconds of uninterrupted measured traffic; old 2.40 clients/downstream stay pinned. Scheduled backups, housekeeping and admin list/detail/traffic queries remain active. No forced GC occurs during measured traffic.

120,000 formal responses complete correctly in 600.071 seconds (60,000 HTTP / 60,000 JMS). Including warmup, 144,000 logs are processed with zero drops, storage retries, unconfirmed writes or JMS failures; resources drain. Seven backups complete, five are pruned, none fail. Database `quick_check` passes, retaining 9,761 rows. GC history records 1,117 natural events during measured load and expires 1,258 older events overall.

**Raw result `passed=false`: six of seven growth gates pass.** Thread minute statistics rise 186→190.5 (+4.5), exceeding the original +4 allowance; no threshold or result is rewritten. Start/end dumps show Artemis server workers 20→25, remoting 10→11, paging 4→5 and client flow-control 5→6; JMS listener count stays equal. This is consistent with pool warmup but does not prove permanent safety. The earlier 2.40 observation also failed its thread gate (+5); these separate runs do not isolate a cause.

Follow-up inspection of the frozen 2.57 JAR confirms default maxima of 30 server workers, 5 paging workers and 10 client flow-control workers; Echo does not override those settings. Observed end counts are 25/5/6. All newly observed workers in these families and remoting are waiting for executor work (`TIMED_WAITING` in `ThreadPoolExecutor.getTask`), rather than blocked inside a message callback. Comparing thread IDs also finds four old remoting workers and all four old paging workers have exited; JMS listeners stay 30→30. Remoting uses a cached executor with a 60-second idle timeout, not a small fixed maximum. These facts support normal pool expansion/retirement and show why a total-thread +4 warning cannot alone establish a leak. They do not prove every pool has a hard cap or that future growth must plateau; the raw failed gate is retained.

Natural post-GC heap minute statistics grow 5.05 MiB, below the 24 MiB allowance; native committed grows 0.21 MiB, direct 0.025 MiB and RSS 13.20 MiB. Passing these finite gates is not zero-growth proof. One diagnostic GC after drain and queries leaves 104.48 MiB heap and **1,100.07 MiB process RSS**.

## Costs, applicability and provenance

This fix applies where Echo consumes large Artemis messages. An all-EMS source/target flow does not execute the Artemis copy path. TIBCO provider code and client JAR are unchanged; actual licensed EMS performance, ACK and failover are not validated here. Keeping standard `TextMessage` processing avoids creating a provider-specific replacement for the JMS contract.

[2.57 upgrade notes](https://artemis.apache.org/components/artemis/documentation/latest/versions.html) include secured Core handshake/cluster changes, disabled discovery by default and a default limit of five `%` selector wildcards. These checks cover standalone Echo request/reply, not every selector or secured cluster. Preserve a broker-data backup before upgrade; successful forward migration does not establish safe in-place downgrade. A 512 MiB heap is not a 512 MiB process-memory limit. Ten minutes cannot prove permanent freedom from leaks or OOM.

Before production source: `9927869`; after: `8e354ea`. JAR SHA-256 before `2aa310f13e0faabed77714a512f965aa6bdcc279b77880461854aa7ef05fbbf8`; after `72fe33085ab0565db6541c89f999ff902109fbfcbf0b3d21353b684e41959133`. The earlier spool optimization exists on both sides. Gains are not added to previous JDBC/spool or WireMock results. Raw JARs, databases, recordings and request records remain outside Git; only this compact report is committed.
