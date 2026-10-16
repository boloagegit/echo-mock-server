package com.echo.diagnostics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.server.ActiveMQServers;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;

import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
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
    private MemoryObservationFixture() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 3 && args[0].equals("downstream")) {
            downstream(Integer.parseInt(args[1]), Integer.parseInt(args[2]));
        } else if (args.length == 6 && args[0].equals("load")) {
            load(args[1], args[2], Integer.parseInt(args[3]), Integer.parseInt(args[4]), Integer.parseInt(args[5]));
        } else {
            throw new IllegalArgumentException("downstream <http-port> <jms-port> | load <http-url> <jms-url> <seconds> <rate> <bytes>");
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
                            var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                            if (response.statusCode() != 200 || !response.body().equals(expected)) {
                                throw new AssertionError("HTTP response mismatch: " + path + " status=" + response.statusCode());
                            }
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
                                    producer.send(request);
                                    var reply = consumer.receive(10000);
                                    String expected = operation.equals("mock") ? "ok" : body;
                                    if (!(reply instanceof TextMessage text) || !text.getText().equals(expected)
                                            || !request.getJMSMessageID().equals(reply.getJMSCorrelationID())) {
                                        throw new AssertionError("JMS response/correlation mismatch at " + i);
                                    }
                                }
                            } finally { replyQueue.delete(); }
                        }
                    }
                    return (long) requestsPerWorker;
                }));
            }
            long completed = 0;
            for (var job : jobs) { completed += job.get(seconds + 60L, TimeUnit.SECONDS); }
            System.out.println("OBS_RESULT " + JSON.writeValueAsString(Map.of("completed", completed,
                    "httpCompleted", requestsPerWorker * 2L, "jmsCompleted", requestsPerWorker * 2L,
                    "seconds", seconds, "elapsedSeconds", (System.nanoTime() - start) / 1e9)));
        } finally { executor.shutdownNow(); }
    }

    private static void pace(long deadline) throws InterruptedException {
        long remaining;
        while ((remaining = deadline - System.nanoTime()) > 0) {
            if (Thread.interrupted()) { throw new InterruptedException(); }
            LockSupport.parkNanos(remaining);
        }
    }
}
