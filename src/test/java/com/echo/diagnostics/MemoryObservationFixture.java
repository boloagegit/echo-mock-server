package com.echo.diagnostics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.server.ActiveMQServers;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.HdrHistogram.ConcurrentHistogram;
import org.HdrHistogram.Histogram;

import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/** Separate-process load/downstream fixture: never shipped or measured in Echo's heap. */
public final class MemoryObservationFixture {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> HTTP_SCENARIOS = List.of("httpFixed", "httpTemplate", "httpForward", "httpDelay");
    private MemoryObservationFixture() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 3 && args[0].equals("downstream")) {
            downstream(Integer.parseInt(args[1]), Integer.parseInt(args[2]));
        } else if (args.length == 6 && args[0].equals("load")) {
            load(args[1], args[2], Integer.parseInt(args[3]), Integer.parseInt(args[4]), Integer.parseInt(args[5]));
        } else if (args.length == 6 && args[0].equals("jms-benchmark")) {
            benchmarkJms(args[1], args[2], Integer.parseInt(args[3]), Integer.parseInt(args[4]), Integer.parseInt(args[5]));
        } else {
            throw new IllegalArgumentException("downstream <http-port> <jms-port> | load <http-url> <jms-url> <seconds> <rate> <bytes>"
                    + " | jms-benchmark <jms-url> <mock|forward> <seconds> <concurrency> <bytes>");
        }
    }

    private static void downstream(int httpPort, int jmsPort) throws Exception {
        var broker = ActiveMQServers.newActiveMQServer(new ConfigurationImpl().setPersistenceEnabled(false)
                .setSecurityEnabled(false).setJMXManagementEnabled(false)
                .addAcceptorConfiguration("memory-observation", "tcp://127.0.0.1:" + jmsPort));
        broker.start();
        var http = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), httpPort), 0);
        var count = new AtomicLong();
        var failure = new AtomicReference<Exception>();
        http.createContext("/state", exchange -> {
            byte[] body = JSON.writeValueAsBytes(Map.of("queues", broker.getActiveMQServerControl().getQueueNames(),
                    "connections", broker.getActiveMQServerControl().getConnectionCount(), "received", count.get(),
                    "failed", failure.get() != null));
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        http.createContext("/observe/forward", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        http.start();
        try (var factory = new ActiveMQConnectionFactory("tcp://127.0.0.1:" + jmsPort);
             var connection = factory.createConnection()) {
            for (int i = 0; i < 2; i++) {
                var session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
                var consumer = session.createConsumer(session.createQueue("OBS.TARGET"));
                var producer = session.createProducer(null);
                consumer.setMessageListener(message -> {
                    try {
                        var reply = session.createTextMessage(((TextMessage) message).getText());
                        reply.setJMSCorrelationID(message.getJMSMessageID());
                        producer.send(message.getJMSReplyTo(), reply);
                        count.incrementAndGet();
                    } catch (Exception error) { failure.compareAndSet(null, error); }
                });
            }
            connection.start();
            System.out.println("OBS_DOWNSTREAM_READY");
            new CountDownLatch(1).await();
        } finally {
            http.stop(0);
            broker.stop();
        }
    }

    private static void load(String base, String jmsUrl, int seconds, int rate, int bytes) throws Exception {
        if (seconds < 1 || seconds > 86400 || rate < 4 || rate > 10000 || bytes < 1 || bytes > 1024 * 1024) {
            throw new IllegalArgumentException("Invalid bounded workload");
        }
        var executor = Executors.newFixedThreadPool(4);
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        long start = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        int requestsPerWorker = Math.max(1, seconds * rate / 4);
        String padding = "x".repeat(bytes);
        Map<String, Histogram> latencies = new LinkedHashMap<>();
        for (String scenario : List.of("httpFixed", "httpTemplate", "httpForward", "httpDelay", "jmsMock", "jmsForward")) {
            latencies.put(scenario, new ConcurrentHistogram(TimeUnit.SECONDS.toNanos(60), 3));
        }
        List<Future<Long>> jobs = new ArrayList<>();
        try {
            for (int worker = 0; worker < 4; worker++) {
                final int id = worker;
                jobs.add(executor.submit(() -> {
                    if (id < 2) {
                        for (int i = 0; i < requestsPerWorker; i++) {
                            pace(start + (long) i * TimeUnit.SECONDS.toNanos(seconds) / requestsPerWorker);
                            String token = id + "-" + i;
                            int mode = i % 4;
                            String path = switch (mode) {
                                case 0 -> "/fixed";
                                case 1 -> "/template?id=" + token;
                                case 2 -> "/forward";
                                default -> "/delay";
                            };
                            var request = HttpRequest.newBuilder(URI.create(base + "/mock/observe" + path))
                                    .timeout(Duration.ofSeconds(10));
                            String expected;
                            if (mode == 2) {
                                expected = token + padding;
                                request.POST(HttpRequest.BodyPublishers.ofString(expected));
                            } else {
                                request.GET();
                                expected = mode == 1 ? token : "ok";
                            }
                            long before = System.nanoTime();
                            var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                            long elapsed = System.nanoTime() - before;
                            if (response.statusCode() != 200 || !response.body().equals(expected)) {
                                throw new AssertionError("HTTP response mismatch: " + path + " status=" + response.statusCode());
                            }
                            latencies.get(HTTP_SCENARIOS.get(mode)).recordValue(elapsed);
                        }
                    } else {
                        try (var factory = new ActiveMQConnectionFactory(jmsUrl);
                             var connection = factory.createConnection("admin", "admin");
                             var session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
                            connection.start();
                            var replyQueue = session.createTemporaryQueue();
                            try (var consumer = session.createConsumer(replyQueue);
                                 var producer = session.createProducer(session.createQueue("ECHO.REQUEST"))) {
                                for (int i = 0; i < requestsPerWorker; i++) {
                                    pace(start + (long) i * TimeUnit.SECONDS.toNanos(seconds) / requestsPerWorker);
                                    String operation = i % 2 == 0 ? "mock" : "forward";
                                    String body = "<Request><ServiceName>" + operation + "</ServiceName><Id>" + id + "-" + i
                                            + "</Id><Data>" + padding + "</Data></Request>";
                                    var request = session.createTextMessage(body);
                                    request.setJMSReplyTo(replyQueue);
                                    long before = System.nanoTime();
                                    producer.send(request);
                                    var reply = consumer.receive(10000);
                                    long elapsed = System.nanoTime() - before;
                                    String expected = operation.equals("mock") ? "ok" : body;
                                    if (!(reply instanceof TextMessage text) || !text.getText().equals(expected)
                                            || !request.getJMSMessageID().equals(reply.getJMSCorrelationID())) {
                                        throw new AssertionError("JMS response/correlation mismatch at " + i);
                                    }
                                    latencies.get(operation.equals("mock") ? "jmsMock" : "jmsForward").recordValue(elapsed);
                                }
                            } finally { replyQueue.delete(); }
                        }
                    }
                    return (long) requestsPerWorker;
                }));
            }
            long completed = 0;
            for (var job : jobs) { completed += job.get(seconds + 60L, TimeUnit.SECONDS); }
            if (latencies.values().stream().mapToLong(Histogram::getTotalCount).sum() != completed) {
                throw new AssertionError("Missing per-scenario latency samples");
            }
            Map<String, Object> timing = new LinkedHashMap<>();
            latencies.forEach((scenario, histogram) -> timing.put(scenario, latencySummary(histogram)));
            System.out.println("OBS_RESULT " + JSON.writeValueAsString(Map.of("completed", completed,
                    "httpCompleted", requestsPerWorker * 2L, "jmsCompleted", requestsPerWorker * 2L,
                    "seconds", seconds, "elapsedSeconds", (System.nanoTime() - start) / 1e9, "latencyMs", timing)));
        } finally { executor.shutdownNow(); }
    }

    /** Closed-loop request/reply throughput; independent sessions and reply queues per caller. */
    private static void benchmarkJms(String url, String operation, int seconds, int concurrency, int bytes) throws Exception {
        if (!List.of("mock", "forward").contains(operation) || seconds < 1 || seconds > 300
                || concurrency < 1 || concurrency > 64 || bytes < 1 || bytes > 1024 * 1024) {
            throw new IllegalArgumentException("Invalid bounded JMS benchmark");
        }
        var histogram = new ConcurrentHistogram(TimeUnit.SECONDS.toNanos(60), 3);
        var executor = Executors.newFixedThreadPool(concurrency);
        var ready = new CountDownLatch(concurrency);
        var start = new CountDownLatch(1);
        var origin = new AtomicLong();
        List<Future<Long>> jobs = new ArrayList<>();
        try (var factory = new ActiveMQConnectionFactory(url);
             var connection = factory.createConnection("admin", "admin")) {
            connection.start();
            boolean terminated;
            try {
                for (int worker = 0; worker < concurrency; worker++) {
                    final int id = worker;
                    jobs.add(executor.submit(() -> {
                        try (var session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
                             var producer = session.createProducer(session.createQueue("ECHO.REQUEST"))) {
                            var replyQueue = session.createTemporaryQueue();
                            try (var consumer = session.createConsumer(replyQueue)) {
                                ready.countDown();
                                if (!start.await(20, TimeUnit.SECONDS)) {
                                    throw new AssertionError("JMS benchmark start timed out");
                                }
                                long warmEnd = origin.get() + TimeUnit.SECONDS.toNanos(5);
                                long end = warmEnd + TimeUnit.SECONDS.toNanos(seconds);
                                int sequence = 0;
                                String prefix = "<Request><ServiceName>" + operation + "</ServiceName><Data>" + "x".repeat(bytes);
                                while (System.nanoTime() < end) {
                                    String body = prefix + "</Data><Id>" + id + "-" + sequence++ + "</Id></Request>";
                                    var request = session.createTextMessage(body);
                                    request.setJMSReplyTo(replyQueue);
                                    long before = System.nanoTime();
                                    producer.send(request);
                                    var reply = consumer.receive(10000);
                                    long elapsed = System.nanoTime() - before;
                                    if (!(reply instanceof TextMessage text)
                                            || !text.getText().equals(operation.equals("mock") ? "ok" : body)
                                            || !request.getJMSMessageID().equals(reply.getJMSCorrelationID())) {
                                        throw new AssertionError("JMS benchmark reply/correlation mismatch");
                                    }
                                    if (before >= warmEnd) {
                                        histogram.recordValue(elapsed);
                                    }
                                }
                                return System.nanoTime();
                            } finally {
                                replyQueue.delete();
                            }
                        }
                    }));
                }
                if (!ready.await(20, TimeUnit.SECONDS)) {
                    throw new AssertionError("JMS benchmark workers did not become ready");
                }
                origin.set(System.nanoTime());
                start.countDown();
                long finished = 0;
                for (var job : jobs) {
                    finished = Math.max(finished, job.get(seconds + 40L, TimeUnit.SECONDS));
                }
                double elapsed = (finished - origin.get() - TimeUnit.SECONDS.toNanos(5)) / 1e9;
                System.out.println("PERF_RESULT " + JSON.writeValueAsString(Map.of("operation", operation,
                        "concurrency", concurrency, "completed", histogram.getTotalCount(),
                        "elapsedSeconds", elapsed, "requestsPerSecond", histogram.getTotalCount() / elapsed,
                        "latencyMs", latencySummary(histogram))));
            } finally {
                start.countDown();
                executor.shutdownNow();
                terminated = executor.awaitTermination(15, TimeUnit.SECONDS);
            }
            if (!terminated) {
                throw new AssertionError("JMS benchmark workers did not stop");
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static Map<String, Object> latencySummary(Histogram histogram) {
        if (histogram.getTotalCount() == 0) {
            return Map.of("count", 0);
        }
        return Map.of("count", histogram.getTotalCount(), "mean", histogram.getMean() / 1e6,
                "p50", histogram.getValueAtPercentile(50) / 1e6,
                "p95", histogram.getValueAtPercentile(95) / 1e6,
                "p99", histogram.getValueAtPercentile(99) / 1e6,
                "max", histogram.getMaxValue() / 1e6, "significantDigits", 3);
    }

    private static void pace(long deadline) throws InterruptedException {
        long remaining;
        while ((remaining = deadline - System.nanoTime()) > 0) {
            if (Thread.interrupted()) { throw new InterruptedException(); }
            LockSupport.parkNanos(remaining);
        }
    }
}
