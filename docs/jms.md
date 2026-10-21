# JMS

[← Documentation index](README.md)

How Echo receives JMS requests, replies, and forwards to a real broker.

Echo can act as a JMS Proxy, intercepting JMS messages in development environments:

```
Application ──JMS──▶ Echo (Artemis)  ──JMS──▶ ESB (TIBCO/Artemis)
                     Queue: ECHO.REQUEST      Queue: TARGET.REQUEST
                     Match found → Mock Response
                     No match    → Forward to Target ESB
```

Administrators can create multiple Artemis/TIBCO profiles under **System Settings → Forward Connections → JMS**, test them, and select one database default. A valid, enabled `application.yml` target takes precedence; the database default is used only when that configuration is absent. This is configuration precedence, not automatic failover. Unmatched messages and rules selecting the default JMS connection use this outbound default; rules selecting a specific profile use that profile. Echo's inbound Embedded Artemis connection is unchanged. Passwords are stored with AES-GCM encryption and are never returned by the API. Set a stable `ECHO_JMS_CREDENTIAL_KEY` in production; changing it requires re-entering every stored password.

System Settings separates **Overview**, **Resource Monitoring**, **Forward Connections**, **Data & Backups**, and **Service Configuration**. Settings collects data on entry and explicit refresh/retry only, without polling. Resource details distinguish current usage, lifetime counters, and configured limits; see [resource snapshot semantics](resource-monitoring.md) before interpreting them as health or leak evidence.

[Transaction diagnostics](transaction-diagnostics.md) adds bounded anomaly-only file summaries
and Request Log correlation IDs. See the linked guide for configuration, quotas and evidence limits.

Artemis large-message reads use the official bulk-copy fix; see the [upgrade validation](artemis-large-message-validation-2026-10-18.md). Keep the client's default 100 KiB encoded-size threshold as the starting point. Raising the sender URL's `minLargeMessageSize`, for example to `524288`, can reduce large-message file I/O but holds more message bodies in memory. Measure payload size and concurrency before making that tradeoff, especially with a small heap. The upgrade does not require changing this **sender-side** setting; it is not an Echo `application.yml` property.

## Reply failure policy

`echo.jms.rollback-on-reply-failure` defaults to `false`, preserving the existing policy: reply-send failures are logged and counted without asking the source listener to roll back, and an unavailable reply template is skipped. The source transaction can therefore commit even if the caller receives no reply. Other reasons for source rollback, including interruption, temporary memory pressure and unavailable request logging, are unchanged.

To opt in, configure the following or set the environment variable `ECHO_JMS_ROLLBACK_ON_REPLY_FAILURE` to `true`, then restart Echo:

```yaml
echo:
  jms:
    rollback-on-reply-failure: true
```

When enabled, reply delivery failures (including an unavailable template or a failed error reply) propagate to the source listener's transaction owner for rollback. Echo does not attempt a second reply after a send failure. Successful replies, missing `JMSReplyTo` and intentional `CONNECTION_RESET` faults keep their existing policy in either mode. This startup setting applies to both embedded and external JMS sources; it is not a live UI toggle. Set it back to `false` and restart to restore the legacy reply-failure policy.

Enabling rollback can repeat mock/scenario processing, log entries and downstream side effects on redelivery. A send returning successfully is not proof of commit, and cross-broker forwarding is not exactly-once. Configure finite retries and a dead-letter queue on the source broker for permanently deleted reply destinations. Embedded Artemis retains failed messages indefinitely by default, with backoff; external brokers use their own retry/DLQ settings. With this option enabled, Echo does not silently discard a permanent reply failure. Both modes are tested with Artemis and JMS substitutes; TIBCO EMS still needs separate acceptance.

## Forwarding resource reuse

Outbound forwarding keeps up to 8 idle sessions per target by default (`echo.jms.forward.session-cache-size`; set to `0` to disable reuse). Each request still owns its temporary reply queue and consumer, so late or duplicate replies cannot become another request's response. Session reuse preserves source transaction ownership; reply failure and redelivery policy are described above. See [JMS performance and correctness](jms-performance-and-correctness.md) for measurements and lifecycle checks.

## Receiving JMS from a Shared Broker (Multi-Instance)

By default each Echo instance receives JMS on its own embedded Artemis, so senders must pick one instance. To run several instances for load sharing and failover, let all of them listen on the same queue of an existing broker (for example the company ESB). The broker hands each message to only one instance; replies go to the message's `JMSReplyTo`, which is on the same broker.

```
Application ──JMS──▶ ESB queue ECHO.REQUEST ──▶ Echo A
                                            └─▶ Echo B
```

```yaml
echo:
  jms:
    enabled: true
    queue: ECHO.REQUEST            # queue on the broker that the instances share
    source:
      type: tibco                  # embedded (default) | artemis | tibco
      server-url: tcp://esb-server:7222
      username: echo               # ECHO_JMS_SOURCE_USERNAME
      password: ${ECHO_JMS_SOURCE_PASSWORD}
      reconnect-interval-ms: 5000  # retry interval while the broker is unreachable
```

- The embedded Artemis is not started and `echo.jms.port` is not opened.
- Echo starts even when the broker is down and keeps reconnecting; **System Settings → Service Configuration** shows whether JMS is receiving.
- Connection recovery reuses the source provider factory. Final application shutdown closes cached resources and then the provider factory when it implements `AutoCloseable`.
- Messages are consumed in a transaction: if an instance stops or crashes mid-message, the broker gives the message to another instance. A crash after the reply was sent but before the commit can produce a second reply (at-least-once delivery).
- Redelivery delays, attempt limits and dead-letter handling are the broker's own settings; `echo.jms.redelivery-*`, `max-delivery-attempts`, paging and disk settings only apply to the embedded broker.
- TIBCO EMS needs the EMS 10.3+ client (`tibjms.jar`, `jakarta.jms`) in `libs/`, the same as for forwarding.
