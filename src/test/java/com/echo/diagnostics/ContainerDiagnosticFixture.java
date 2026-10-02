package com.echo.diagnostics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import jakarta.jms.BytesMessage;
import jakarta.jms.Connection;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import jakarta.jms.TemporaryQueue;
import jakarta.jms.TextMessage;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.core.server.ActiveMQServers;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/** Disposable downstream and caller for the opt-in Docker test, not shipped in Echo. */
public final class ContainerDiagnosticFixture {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TARGET_QUEUE = "DIAGNOSTIC.TARGET";
    private static final String SUCCESS = "<reply>container-ok</reply>";
    private ContainerDiagnosticFixture() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "broker".equals(args[0])) broker();
        else if (args.length == 5 && "caller".equals(args[0])) {
            caller(args[1], args[2], Integer.parseInt(args[3]), Integer.parseInt(args[4]));
        } else if (args.length == 7 && "steady".equals(args[0])) {
            steady(args[1], args[2], args[3], Integer.parseInt(args[4]), Integer.parseInt(args[5]), Integer.parseInt(args[6]));
        } else throw new IllegalArgumentException("broker | caller <url> <NORMAL|NO_REPLY|INVALID_TYPE> <count> <concurrency>"
                + " | steady <jms-url> <http-url> <downstream-name> <seconds> <rate> <bytes>");
    }

    private static void broker() throws Exception {
        ConfigurationImpl config = new ConfigurationImpl();
        config.setPersistenceEnabled(false).setSecurityEnabled(false).setJMXManagementEnabled(false);
        config.addAcceptorConfiguration("fixture", "tcp://0.0.0.0:61616?protocols=CORE");
        ActiveMQServer server = ActiveMQServers.newActiveMQServer(config);
        server.start();
        AtomicInteger received = new AtomicInteger();
        AtomicInteger httpReceived = new AtomicInteger();
        // Only retain the two injected anomalies, never an unbounded per-request map.
        List<Map<String, Object>> anomalies = new CopyOnWriteArrayList<>();
        HttpServer http = HttpServer.create(new InetSocketAddress("0.0.0.0", 8081), 0);
        http.createContext("/state", exchange -> {
            try {
                Map<String, Object> state = new LinkedHashMap<>();
                state.put("received", received.get());
                state.put("httpReceived", httpReceived.get());
                state.put("queues", Arrays.asList(server.getActiveMQServerControl().getQueueNames()));
                state.put("connections", server.getActiveMQServerControl().getConnectionCount());
                state.put("anomalies", anomalies);
                byte[] body = JSON.writeValueAsBytes(state);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
            } catch (Exception error) {
                exchange.close();
                throw new IllegalStateException("fixture state failed", error);
            }
        });
        addHttpReply(http, httpReceived);
        http.start();
        List<HttpServer> extraHttp = new ArrayList<>();
        SSLContext tls = fixtureTls();
        for (int port = 8441; port <= 8444; port++) {
            HttpsServer responder = HttpsServer.create(new InetSocketAddress("0.0.0.0", port), 0);
            responder.setHttpsConfigurator(new HttpsConfigurator(tls));
            addHttpReply(responder, httpReceived);
            responder.start();
            extraHttp.add(responder);
        }
        try (ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory("tcp://127.0.0.1:61616");
             Connection connection = factory.createConnection();
             Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
             MessageConsumer consumer = session.createConsumer(session.createQueue(TARGET_QUEUE));
             MessageProducer producer = session.createProducer(null)) {
            connection.start();
            System.out.println("FIXTURE_READY");
            while (!Thread.currentThread().isInterrupted()) {
                Message request = consumer.receive(1000);
                if (request == null) continue;
                if (!(request instanceof TextMessage text)) throw new AssertionError("Echo did not forward text");
                received.incrementAndGet();
                String body = text.getText();
                String testCase = body.contains("<DiagnosticCase>NO_REPLY</DiagnosticCase>") ? "NO_REPLY"
                        : body.contains("<DiagnosticCase>INVALID_TYPE</DiagnosticCase>") ? "INVALID_TYPE" : "NORMAL";
                if (!"NORMAL".equals(testCase)) {
                    if (anomalies.size() >= 2) throw new AssertionError("Unexpected retry or duplicate forwarding");
                    Map<String, Object> record = new LinkedHashMap<>();
                    record.put("case", testCase);
                    record.put("messageId", request.getJMSMessageID());
                    record.put("replyTo", JmsDiagnosticMetadata.destination(request.getJMSReplyTo()));
                    anomalies.add(record);
                }
                if ("NO_REPLY".equals(testCase)) continue;
                Message reply;
                if ("INVALID_TYPE".equals(testCase)) {
                    BytesMessage bytes = session.createBytesMessage();
                    bytes.writeBytes("non-text-fixture".getBytes(StandardCharsets.UTF_8));
                    reply = bytes;
                } else reply = session.createTextMessage(SUCCESS);
                reply.setJMSCorrelationID(request.getJMSMessageID());
                producer.send(request.getJMSReplyTo(), reply);
            }
        } finally {
            http.stop(0);
            extraHttp.forEach(responder -> responder.stop(0));
            server.stop();
        }
    }

    private static SSLContext fixtureTls() throws Exception {
        // Public disposable fixture identity, generated only inside this owned container.
        Path store = Path.of("/work/data/fixture.p12");
        char[] password = "public-fixture-only".toCharArray();
        if (new ProcessBuilder("keytool", "-genkeypair", "-alias", "fixture", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "2", "-dname", "CN=disposable-fixture", "-storetype", "PKCS12", "-keystore", store.toString(),
                "-storepass", new String(password)).inheritIO().start().waitFor() != 0)
            throw new IllegalStateException("Fixture TLS identity generation failed");
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(store)) { keys.load(input, password); }
        KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keys, password);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(managers.getKeyManagers(), null, null);
        return context;
    }

    private static void addHttpReply(HttpServer server, AtomicInteger received) {
        server.createContext("/resource-soak", exchange -> {
            try (var input = exchange.getRequestBody()) { input.transferTo(java.io.OutputStream.nullOutputStream()); }
            received.incrementAndGet();
            byte[] body = SUCCESS.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
    }

    /** Fixed-rate two-protocol workload; constant-space results and real caller reply verification. */
    private static void steady(String jmsUrl, String httpUrl, String downstream, int seconds, int rate, int bytes) throws Exception {
        if (seconds < 10 || seconds > 600 || rate < 2 || rate > 40 || rate % 2 != 0 || bytes < 1024 || bytes > 131072)
            throw new IllegalArgumentException("steady duration/rate/payload outside finite test bounds");
        var workers = Executors.newFixedThreadPool(2);
        long start = System.nanoTime();
        int count = seconds * rate / 2;
        long spacing = 1_000_000_000L / (rate / 2);
        try {
            var jms = workers.submit(() -> {
                var timing = new Timing();
                try (ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(jmsUrl);
                     Connection connection = factory.createConnection("admin", "admin");
                     Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
                    connection.start();
                    TemporaryQueue replyQueue = session.createTemporaryQueue();
                    try (MessageConsumer consumer = session.createConsumer(replyQueue);
                         MessageProducer producer = session.createProducer(session.createQueue("ECHO.REQUEST"))) {
                        for (int index = 0; index < count; index++) {
                            long scheduled = start + index * spacing;
                            pace(scheduled);
                            String prefix = "<Request><ServiceName>ECHO_DIAG_NORMAL</ServiceName><Sequence>" + index + "</Sequence><Data>";
                            String suffix = "</Data></Request>";
                            TextMessage request = session.createTextMessage(prefix + "x".repeat(bytes - prefix.length() - suffix.length()) + suffix);
                            request.setJMSReplyTo(replyQueue);
                            long before = System.nanoTime();
                            producer.send(request);
                            Message response = consumer.receive(15000);
                            if (!(response instanceof TextMessage text) || !SUCCESS.equals(text.getText())
                                    || !request.getJMSMessageID().equals(response.getJMSCorrelationID()))
                                throw new AssertionError("Steady JMS response/correlation failed at " + index);
                            timing.record(before - scheduled, System.nanoTime() - before);
                        }
                    } finally { replyQueue.delete(); }
                }
                return timing.summary();
            });
            var http = workers.submit(() -> {
                var timing = new Timing();
                HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(5)).build();
                for (int index = 0; index < count; index++) {
                    long scheduled = start + index * spacing;
                    pace(scheduled);
                    // Rotate real origins every 30s, so inactive per-host pools must be reclaimed.
                    int port = 8441 + (int) (((scheduled - start) / 30_000_000_000L) % 4);
                    HttpRequest request = HttpRequest.newBuilder(URI.create(httpUrl + "/mock/resource-soak"))
                            .timeout(Duration.ofSeconds(15)).header("X-Original-Host", downstream + ":" + port)
                            .header("Content-Type", "text/plain").POST(HttpRequest.BodyPublishers.ofString("x".repeat(bytes))).build();
                    long before = System.nanoTime();
                    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() != 200 || !SUCCESS.equals(response.body()))
                        throw new AssertionError("Steady HTTP forwarding failed at " + index + ": " + response.statusCode());
                    timing.record(before - scheduled, System.nanoTime() - before);
                }
                return timing.summary();
            });
            Map<String, Object> jmsResult = jms.get();
            Map<String, Object> httpResult = http.get();
            pace(start + seconds * 1_000_000_000L);
            System.out.println("STEADY_RESULT " + JSON.writeValueAsString(Map.of("durationSeconds", seconds,
                    "elapsedMs", (System.nanoTime() - start) / 1_000_000.0, "rate", rate, "payloadBytes", bytes,
                    "jms", jmsResult, "http", httpResult)));
        } finally { workers.shutdownNow(); }
    }

    private static void pace(long scheduled) throws InterruptedException {
        while (true) {
            if (Thread.interrupted()) throw new InterruptedException("Fixture cancelled");
            long remaining = scheduled - System.nanoTime();
            if (remaining <= 0) return;
            LockSupport.parkNanos(remaining);
        }
    }

    private static final class Timing {
        private long count, lagOver100ms, lagMax, latencyMax, latencyTotal;
        private final long[] buckets = new long[16];
        private static final long[] LIMITS_MS = {1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 15000, 30000};
        void record(long lag, long latency) {
            count++;
            if (lag > 100_000_000L) lagOver100ms++;
            lagMax = Math.max(lagMax, lag);
            latencyMax = Math.max(latencyMax, latency);
            latencyTotal += latency;
            int bucket = 0;
            while (bucket < LIMITS_MS.length && latency > LIMITS_MS[bucket] * 1_000_000L) bucket++;
            buckets[bucket]++;
        }
        Map<String, Object> summary() {
            return Map.of("completed", count, "lagOver100ms", lagOver100ms, "lagMaxMs", lagMax / 1_000_000.0,
                    "latencyMaxMs", latencyMax / 1_000_000.0, "latencyMeanMs", latencyTotal / 1_000_000.0 / Math.max(1, count),
                    "latencyBucketUpperMs", LIMITS_MS, "latencyBuckets", buckets);
        }
    }

    private static void caller(String url, String testCase, int count, int concurrency) throws Exception {
        if (!List.of("NORMAL", "NO_REPLY", "INVALID_TYPE").contains(testCase)
                || count < 1 || count > 2000 || concurrency < 1 || concurrency > 8)
            throw new IllegalArgumentException("Invalid bounded fixture workload");
        var workers = Executors.newFixedThreadPool(concurrency);
        List<Map<String, Object>> results = new ArrayList<>();
        long started = System.nanoTime();
        try {
            List<java.util.concurrent.Future<List<Map<String, Object>>>> futures = new ArrayList<>();
            for (int worker = 0; worker < concurrency; worker++) {
                int offset = worker;
                futures.add(workers.submit(() -> {
                    List<Map<String, Object>> rows = new ArrayList<>();
                    try (ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(url);
                         Connection connection = factory.createConnection("admin", "admin");
                         Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
                        connection.start();
                        TemporaryQueue replyQueue = session.createTemporaryQueue();
                        try (MessageConsumer consumer = session.createConsumer(replyQueue);
                             MessageProducer producer = session.createProducer(session.createQueue("ECHO.REQUEST"))) {
                            for (int index = offset; index < count; index += concurrency) {
                                String xml = "<Request><ServiceName>ECHO_DIAG_" + testCase + "</ServiceName>"
                                        + "<DiagnosticCase>" + testCase + "</DiagnosticCase><Sequence>" + index + "</Sequence></Request>";
                                TextMessage request = session.createTextMessage(xml);
                                request.setJMSReplyTo(replyQueue);
                                long before = System.nanoTime();
                                producer.send(request);
                                Message response = consumer.receive(60000);
                                if (!(response instanceof TextMessage text)) throw new AssertionError("No text reply to caller");
                                String expected = "NORMAL".equals(testCase) ? SUCCESS : "<error>JMS response timeout</error>";
                                if (!expected.equals(text.getText())) throw new AssertionError("Business reply changed: " + text.getText());
                                if (!request.getJMSMessageID().equals(response.getJMSCorrelationID()))
                                    throw new AssertionError("Caller correlation changed");
                                rows.add(Map.of("case", testCase, "messageId", request.getJMSMessageID(),
                                        "elapsedMs", (System.nanoTime() - before) / 1_000_000.0,
                                        "reply", text.getText()));
                            }
                        } finally { replyQueue.delete(); }
                    }
                    return rows;
                }));
            }
            for (var future : futures) results.addAll(future.get());
        } finally { workers.shutdownNow(); }
        System.out.println("FIXTURE_RESULT " + JSON.writeValueAsString(Map.of("case", testCase,
                "requests", results.size(), "elapsedMs", (System.nanoTime() - started) / 1_000_000.0,
                "results", results)));
    }
}
