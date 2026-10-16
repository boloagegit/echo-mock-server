package com.echo.jms;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import com.echo.config.JmsProperties;
import com.echo.jms.target.ArtemisFactoryProvider;
import jakarta.jms.*;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.server.ActiveMQServers;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Opt-in transport A/B, with real TCP Artemis, unique payload checks and isolated data. Not a capacity SLA. */
public final class JmsForwardingCostProbe {
    private JmsForwardingCostProbe() { }

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(output);
        var logging = (LoggerContext) LoggerFactory.getILoggerFactory();
        logging.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).setLevel(Level.ERROR);
        int port;
        try (var socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }
        String url = "tcp://127.0.0.1:" + port;
        var config = new ConfigurationImpl().setPersistenceEnabled(false).setSecurityEnabled(false)
                .setJMXManagementEnabled(false).setJournalDirectory(output.resolve("broker/journal").toString())
                .setBindingsDirectory(output.resolve("broker/bindings").toString())
                .setPagingDirectory(output.resolve("broker/paging").toString())
                .setLargeMessagesDirectory(output.resolve("broker/large").toString())
                .addAcceptorConfiguration("probe", url);
        var broker = ActiveMQServers.newActiveMQServer(config);
        broker.start();
        var failure = new AtomicReference<Throwable>();
        var rows = new ArrayList<String>();
        rows.add("round,cache,concurrency,body_chars,requests,rps,p50_ms,p95_ms,p99_ms,remaining_reply_queues");
        try (var factory = new ActiveMQConnectionFactory(url); var responder = factory.createConnection()) {
            for (int worker = 0; worker < 8; worker++) {
                var session = responder.createSession(false, Session.AUTO_ACKNOWLEDGE);
                var consumer = session.createConsumer(session.createQueue("PROBE.REQUEST"));
                var producer = session.createProducer(null);
                consumer.setMessageListener(message -> {
                    try { producer.send(message.getJMSReplyTo(), session.createTextMessage(((TextMessage) message).getText())); }
                    catch (Throwable error) { failure.compareAndSet(null, error); }
                });
            }
            responder.start();
            // Warm both paths before collecting any row; a per-round warmup alone leaves the
            // first uncached round paying JVM compilation costs for shared Artemis code.
            for (int cache : new int[]{0, 8}) {
                var warmProperties = new JmsProperties();
                warmProperties.getForward().setSessionCacheSize(cache);
                var target = warmProperties.getTarget();
                target.setEnabled(true); target.setType("artemis"); target.setServerUrl(url);
                target.setQueue("PROBE.REQUEST"); target.setTimeoutSeconds(5);
                var warmForwarder = new JmsTargetForwarder(warmProperties, List.of(new ArtemisFactoryProvider()));
                try {
                    measure(warmForwarder, 8, 1024, 1000);
                    measure(warmForwarder, 8, 102400, 150);
                } finally { warmForwarder.cleanup(); }
            }
            for (int size : new int[]{1024, 102400}) {
                for (int concurrency : new int[]{1, 8}) {
                    for (int round = 0; round < 8; round++) {
                        // ABBA, including cold client creation outside the measured interval.
                        int cache = round % 4 == 1 || round % 4 == 2 ? 8 : 0;
                        var properties = new JmsProperties();
                        properties.getForward().setSessionCacheSize(cache);
                        var target = properties.getTarget();
                        target.setEnabled(true); target.setType("artemis"); target.setServerUrl(url);
                        target.setQueue("PROBE.REQUEST"); target.setTimeoutSeconds(5);
                        var forwarder = new JmsTargetForwarder(properties, List.of(new ArtemisFactoryProvider()));
                        try {
                            measure(forwarder, concurrency, size, 150);
                            int each = size == 1024 ? 2000 : 500;
                            Measurement result = measure(forwarder, concurrency, size, each);
                            if (failure.get() != null) throw new AssertionError("responder failed", failure.get());
                            long replyQueues = Arrays.stream(broker.getActiveMQServerControl().getQueueNames())
                                    .filter(name -> !name.equals("PROBE.REQUEST")).count();
                            if (replyQueues != 0) throw new AssertionError("Temporary queues leaked: " + replyQueues);
                            String row = String.format(Locale.ROOT, "%d,%d,%d,%d,%d,%.2f,%.3f,%.3f,%.3f,%d",
                                    round + 1, cache, concurrency, size, result.latencies.length,
                                    result.latencies.length * 1e9 / result.elapsed,
                                    percentile(result.latencies, .5), percentile(result.latencies, .95),
                                    percentile(result.latencies, .99), replyQueues);
                            rows.add(row);
                            System.out.println(row);
                            Files.write(output.resolve("results.csv"), rows);
                        } finally { forwarder.cleanup(); }
                    }
                }
            }
        } finally { broker.stop(); }
    }

    @SuppressWarnings("ArrayRecordComponent") // Private measurement storage; never used as a value key.
    private record Measurement(long elapsed, long[] latencies) {}

    private static Measurement measure(JmsTargetForwarder forwarder, int concurrency, int size, int each)
            throws Exception {
        var executor = Executors.newFixedThreadPool(concurrency);
        var start = new CountDownLatch(1);
        var ready = new CountDownLatch(concurrency);
        long[] latencies = new long[concurrency * each];
        var futures = new ArrayList<java.util.concurrent.Future<?>>();
        Measurement result;
        boolean stopped;
        try {
            for (int worker = 0; worker < concurrency; worker++) {
                int id = worker;
                futures.add(executor.submit(() -> {
                    String payload = "x".repeat(size);
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("start timeout");
                    for (int n = 0; n < each; n++) {
                        String body = id + ":" + n + ":" + payload;
                        long before = System.nanoTime();
                        String reply = forwarder.forward(body, null);
                        latencies[id * each + n] = System.nanoTime() - before;
                        if (!body.equals(reply)) throw new AssertionError("Wrong reply for " + id + ":" + n);
                    }
                    return null;
                }));
            }
            if (!ready.await(10, TimeUnit.SECONDS)) throw new AssertionError("workers not ready");
            long before = System.nanoTime();
            start.countDown();
            for (var future : futures) future.get(90, TimeUnit.SECONDS);
            long elapsed = System.nanoTime() - before;
            Arrays.sort(latencies);
            result = new Measurement(elapsed, latencies);
        } finally {
            start.countDown();
            executor.shutdownNow();
            stopped = executor.awaitTermination(10, TimeUnit.SECONDS);
        }
        if (!stopped) throw new AssertionError("worker leak");
        return result;
    }

    private static double percentile(long[] values, double quantile) {
        return values[Math.min(values.length - 1, (int) Math.ceil(values.length * quantile) - 1)] / 1e6;
    }
}
