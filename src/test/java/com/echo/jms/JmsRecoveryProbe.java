package com.echo.jms;

import com.echo.config.JmsProperties;
import com.echo.config.MonitoringProperties;
import com.echo.jms.target.ArtemisFactoryProvider;
import com.echo.jms.target.JmsTargetFactoryProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.core.server.ActiveMQServers;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Real TCP, persistent broker restarts; shares the exercise with the short regression test. */
public final class JmsRecoveryProbe {
    private static final String QUEUE = "RECOVERY.REQUEST";
    private static final int CALLERS_PER_ROUTE = 4;
    private static final String PAYLOAD = "x".repeat(128 * 1024) + "中文🙂";

    private JmsRecoveryProbe() { }

    public static void main(String[] args) throws Exception {
        int seconds = Integer.parseInt(args[0]);
        if (seconds < 1 || seconds > 86_400) throw new IllegalArgumentException("Recovery seconds must be 1..86400");
        Path output = Path.of(args[1]).toAbsolutePath();
        Files.createDirectories(output);
        Files.deleteIfExists(output.resolve("summary.json"));
        Files.writeString(output.resolve("cycles.ndjson"), "");
        Path work = Files.createTempDirectory(output, "Echo JMS Recovery ");
        var json = new ObjectMapper();
        try {
            Report report = exercise(work, 3, Duration.ofSeconds(seconds), point -> {
                try {
                    Files.writeString(output.resolve("cycles.ndjson"), json.writeValueAsString(point) + "\n",
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    System.out.println("JMS_RECOVERY_CYCLE " + point);
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
            Files.writeString(output.resolve("summary.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n");
            System.out.println("JMS_RECOVERY_DONE " + json.writeValueAsString(report));
        } finally {
            try (var paths = Files.walk(work)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    static Report exercise(Path data, int warmupCycles, Duration duration, Consumer<Checkpoint> observer) throws Exception {
        try (var fixture = new Fixture(data)) {
            for (int cycle = 0; cycle < warmupCycles; cycle++) fixture.cycle();
            int baselineCycle = fixture.cycle;
            long baselineReplies = fixture.replies;
            long baselineFailures = fixture.expectedFailures;
            long started = System.nanoTime();
            do {
                fixture.cycle();
                observer.accept(fixture.checkpoint());
            } while (System.nanoTime() - started < duration.toNanos());
            return new Report(true, fixture.cycle - baselineCycle, (System.nanoTime() - started) / 1e9,
                    fixture.replies - baselineReplies, fixture.expectedFailures - baselineFailures,
                    fixture.provider.created, fixture.checkpoint());
        }
    }

    public record Checkpoint(int cycle, int connections, int sessions, int liveFactories,
                             long checkedReplies, long expectedFailureReplies, long heapUsedBytes,
                             long directBytes, int liveThreads) { }
    public record Report(boolean passed, int measuredCycles, double measuredSeconds, long checkedReplies,
                         long expectedFailureReplies, int factoriesCreatedIncludingWarmup, Checkpoint finalCheckpoint) { }

    private static final class Fixture implements AutoCloseable {
        private final ActiveMQServer broker;
        private final String url;
        private final TrackingProvider provider = new TrackingProvider();
        private final Map<JmsForwardingRoute, JmsTargetForwarder> forwarders = new EnumMap<>(JmsForwardingRoute.class);
        private final Map<JmsForwardingRoute, JmsRuntimeMetrics> metrics = new EnumMap<>(JmsForwardingRoute.class);
        private final java.util.concurrent.ExecutorService callers = Executors.newFixedThreadPool(
                JmsForwardingRoute.values().length * CALLERS_PER_ROUTE);
        private final AtomicReference<Throwable> responderFailure = new AtomicReference<>();
        private volatile CountDownLatch interruptedReceived;
        private Responder responder;
        private int cycle;
        private long replies;
        private long expectedFailures;
        private long requestSequence;

        private Fixture(Path data) throws Exception {
            int port;
            try (var socket = new ServerSocket(0, 1, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}))) {
                port = socket.getLocalPort();
            }
            // Keep the provider's normal send budget: persistent large-message I/O on
            // Windows must not turn this correctness exercise into a 3-second speed gate.
            url = "tcp://127.0.0.1:" + port + "?callFailoverTimeout=3000"
                    + "&initialConnectAttempts=0&reconnectAttempts=0";
            var config = new ConfigurationImpl().setPersistenceEnabled(true).setSecurityEnabled(false)
                    .setJMXManagementEnabled(false).setJournalType(org.apache.activemq.artemis.core.server.JournalType.NIO)
                    .setJournalFileSize(1024 * 1024).setJournalMinFiles(2).setJournalPoolFiles(2)
                    .setJournalDirectory(data.resolve("journal").toString())
                    .setBindingsDirectory(data.resolve("bindings").toString())
                    .setPagingDirectory(data.resolve("paging").toString())
                    .setLargeMessagesDirectory(data.resolve("large").toString())
                    .addAcceptorConfiguration("recovery", "tcp://127.0.0.1:" + port);
            broker = ActiveMQServers.newActiveMQServer(config);
            try {
                broker.start();
                responder = new Responder();
                var properties = new JmsProperties();
                // Preserve Echo's default caching and provider-policy selection. These bounded
                // transport settings belong to this deliberately unavailable test destination.
                properties.getTarget().setEnabled(true);
                properties.getTarget().setType("artemis");
                properties.getTarget().setServerUrl(url);
                properties.getTarget().setQueue(QUEUE);
                properties.getTarget().setTimeoutSeconds(5);
                for (var route : JmsForwardingRoute.values()) {
                    var telemetry = new JmsRuntimeMetrics(new MonitoringProperties());
                    metrics.put(route, telemetry);
                    forwarders.put(route, route.createForwarder(properties, List.of(provider), telemetry));
                }
            } catch (Exception | Error error) {
                close();
                throw error;
            }
        }

        private void cycle() throws Exception {
            cycle++;
            healthyBurst();
            List<Future<String>> timeouts = new ArrayList<>();
            for (var route : JmsForwardingRoute.values()) {
                timeouts.add(callers.submit(() -> route.forward(forwarders.get(route), "no-reply:" + cycle)));
            }
            for (var timeout : timeouts) {
                assertThat(timeout.get(15, TimeUnit.SECONDS)).isEqualTo("<error>JMS response timeout</error>");
                expectedFailures++;
            }
            assertDrained();

            interruptedReceived = new CountDownLatch(JmsForwardingRoute.values().length);
            List<Future<String>> interrupted = new ArrayList<>();
            for (var route : JmsForwardingRoute.values()) {
                interrupted.add(callers.submit(() -> route.forward(forwarders.get(route), "interrupt:" + cycle)));
            }
            assertThat(interruptedReceived.await(10, TimeUnit.SECONDS)).isTrue();
            responder.close();
            responder = null;
            broker.stop();
            broker.start();
            responder = new Responder();
            // Reuse the same forwarders/revision, allowing recovery to overlap old cleanup.
            for (var route : JmsForwardingRoute.values()) {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (true) {
                    String request = "recovered:" + cycle + ":" + route + ":" + ++requestSequence;
                    String reply = route.forward(forwarders.get(route), request);
                    if (!reply.startsWith("<error>")) {
                        assertThat(reply).isEqualTo(request);
                        replies++;
                        break;
                    }
                    expectedFailures++;
                    assertThat(System.nanoTime()).as("same-target recovery deadline").isLessThan(deadline);
                    Thread.sleep(20);
                }
            }
            for (var pending : interrupted) {
                assertThat(pending.get(15, TimeUnit.SECONDS)).startsWith("<error>JMS ");
                expectedFailures++;
            }
            healthyBurst();
            assertDrained();
            assertThat(responderFailure.get()).isNull();
            assertThat(broker.getActiveMQServerControl().getConnectionCount()).isEqualTo(4);
            assertThat(broker.getSessions().size()).isLessThanOrEqualTo(30);
            assertThat(provider.liveCount()).isEqualTo(3);
        }

        private void healthyBurst() throws Exception {
            List<Future<?>> work = new ArrayList<>();
            for (var route : JmsForwardingRoute.values()) {
                for (int worker = 0; worker < CALLERS_PER_ROUTE; worker++) {
                    String request = ++requestSequence + ":" + cycle + ":" + route + ":" + worker + ":" + PAYLOAD;
                    work.add(callers.submit(() -> {
                        String reply = route.forward(forwarders.get(route), request);
                        String prefix = reply == null ? "<null>" : reply.substring(0, Math.min(160, reply.length()));
                        assertThat(reply).withFailMessage(
                                "JMS recovery reply mismatch: route=%s cycle=%d expectedPrefix=%s actualChars=%d actualPrefix=%s",
                                route, cycle, request.substring(0, 64), reply == null ? -1 : reply.length(), prefix)
                                .isEqualTo(request);
                    }));
                }
            }
            for (var task : work) {
                task.get(45, TimeUnit.SECONDS);
                replies++;
            }
        }

        private void assertNoActiveForwards() {
            for (var telemetry : metrics.values()) assertThat(telemetry.snapshot()).containsEntry("forwardActive", 0);
        }

        private void assertDrained() throws Exception {
            assertNoActiveForwards();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertThat(broker.getActiveMQServerControl().getQueueNames()).containsExactly(QUEUE);
                assertThat(broker.getActiveMQServerControl().getTotalMessageCount()).isZero();
            });
        }

        private Checkpoint checkpoint() {
            return new Checkpoint(cycle, broker.getActiveMQServerControl().getConnectionCount(), broker.getSessions().size(),
                    provider.liveCount(), replies, expectedFailures,
                    ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),
                    ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class).stream()
                            .filter(pool -> "direct".equals(pool.getName())).mapToLong(BufferPoolMXBean::getMemoryUsed).sum(),
                    ManagementFactory.getThreadMXBean().getThreadCount());
        }

        @Override
        public void close() throws Exception {
            try {
                forwarders.values().forEach(JmsTargetForwarder::cleanup);
                assertThat(provider.liveCount()).isZero();
            } finally {
                try {
                    if (responder != null) responder.close();
                } finally {
                    try { broker.stop(); }
                    finally {
                        callers.shutdownNow();
                        assertThat(callers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
                    }
                }
            }
        }

        private final class Responder implements AutoCloseable {
            private final ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(url);
            private Connection connection;

            private Responder() throws Exception {
                try {
                    connection = factory.createConnection();
                    for (int worker = 0; worker < 2; worker++) {
                        var session = connection.createSession(true, Session.SESSION_TRANSACTED);
                        var consumer = session.createConsumer(session.createQueue(QUEUE));
                        var producer = session.createProducer(null);
                        consumer.setMessageListener(message -> {
                            try {
                                String body = ((TextMessage) message).getText();
                                if (!body.startsWith("no-reply:") && !body.startsWith("interrupt:")) {
                                    // Atomic, uncorrelated duplicates exercise reply-queue isolation.
                                    producer.send(message.getJMSReplyTo(), session.createTextMessage(body));
                                    producer.send(message.getJMSReplyTo(), session.createTextMessage("duplicate:" + body));
                                }
                                session.commit();
                                if (body.startsWith("interrupt:")) interruptedReceived.countDown();
                            } catch (Throwable error) { responderFailure.compareAndSet(null, error); }
                        });
                    }
                    connection.start();
                } catch (Exception error) {
                    close();
                    throw error;
                }
            }

            @Override
            public void close() throws Exception {
                try { if (connection != null) connection.close(); }
                finally { factory.close(); }
            }
        }
    }

    private static final class TrackingProvider implements JmsTargetFactoryProvider {
        private final ArtemisFactoryProvider delegate = new ArtemisFactoryProvider();
        private final ConcurrentLinkedQueue<ActiveMQConnectionFactory> factories = new ConcurrentLinkedQueue<>();
        private int created;

        @Override public boolean supports(String type) { return delegate.supports(type); }

        @Override
        public synchronized ConnectionFactory create(JmsProperties.Target target) {
            var factory = (ActiveMQConnectionFactory) delegate.create(target);
            factories.add(factory);
            created++;
            return factory;
        }

        private int liveCount() {
            factories.removeIf(factory -> factory.getServerLocator().isClosed());
            return factories.size();
        }
    }
}
