package com.echo.jms;

import com.echo.EchoApplication;
import com.echo.entity.JmsRule;
import com.echo.entity.JmsRuleAction;
import com.echo.entity.Response;
import com.echo.repository.ResponseRepository;
import com.echo.service.JmsRuleService;
import jakarta.jms.*;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.server.ActiveMQServers;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Actual source TCP -> persistent broker -> application -> downstream/reply, with isolated SQLite data. */
public final class JmsApplicationCostProbe {
    private JmsApplicationCostProbe() { }

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(output);
        String downstreamUrl = "tcp://127.0.0.1:" + freePort();
        Path downstreamData = output.resolve("downstream");
        var config = new ConfigurationImpl().setPersistenceEnabled(false).setSecurityEnabled(false)
                .setJMXManagementEnabled(false).setJournalDirectory(downstreamData.resolve("journal").toString())
                .setBindingsDirectory(downstreamData.resolve("bindings").toString())
                .setPagingDirectory(downstreamData.resolve("paging").toString())
                .setLargeMessagesDirectory(downstreamData.resolve("large").toString())
                .addAcceptorConfiguration("probe", downstreamUrl);
        var broker = ActiveMQServers.newActiveMQServer(config);
        broker.start();
        var failure = new AtomicReference<Throwable>();
        var rows = new ArrayList<String>();
        rows.add("round,mode,case,concurrency,requests,rps,p50_ms,p95_ms,p99_ms,forward_failures_including_warmup");
        try (var factory = new ActiveMQConnectionFactory(downstreamUrl); var responder = factory.createConnection()) {
            for (int worker = 0; worker < 8; worker++) {
                var session = responder.createSession(false, Session.AUTO_ACKNOWLEDGE);
                var consumer = session.createConsumer(session.createQueue("DOWN.REQUEST"));
                var producer = session.createProducer(null);
                consumer.setMessageListener(message -> {
                    try { producer.send(message.getJMSReplyTo(), session.createTextMessage(((TextMessage) message).getText())); }
                    catch (Throwable error) { failure.compareAndSet(null, error); }
                });
            }
            responder.start();
            // Same-version configuration comparison; admission stays disabled in both variants.
            String[] modes = {"baseline", "cached", "cached", "baseline"};
            for (int round = 0; round < modes.length; round++) {
                String mode = modes[round];
                boolean baseline = mode.equals("baseline");
                int port = freePort();
                Path root = output.resolve("run-" + (round + 1));
                Files.createDirectories(root);
                String database = "jdbc:sqlite:" + root.resolve("main.sqlite")
                        + "?journal_mode=WAL&busy_timeout=10000&synchronous=NORMAL&foreign_keys=ON";
                try (var context = new SpringApplicationBuilder(EchoApplication.class).profiles("dev", "sqlite").run(
                        "--server.address=127.0.0.1", "--server.port=0", "--echo.jms.enabled=true",
                        "--echo.jms.source.type=embedded", "--echo.jms.persistent=true", "--echo.jms.port=" + port,
                        "--echo.jms.data-directory=" + root.resolve("source"),
                        "--echo.jms.username=bench", "--echo.jms.password=bench", "--echo.jms.queue=ECHO.REQUEST",
                        "--echo.jms.concurrency=10-30",
                        "--echo.jms.forward.session-cache-size=" + (baseline ? 0 : 8),
                        "--echo.jms.target.enabled=true", "--echo.jms.target.type=artemis",
                        "--echo.jms.target.server-url=" + downstreamUrl, "--echo.jms.target.queue=DOWN.REQUEST",
                        "--echo.jms.target.username=", "--echo.jms.target.password=", "--echo.jms.target.timeout-seconds=10",
                        "--spring.datasource.url=" + database, "--echo.request-log.enabled=false",
                        "--echo.request-log.durable.spool-path=" + root.resolve("spool.sqlite"),
                        "--logging.file.path=" + root.resolve("logs"), "--echo.backup.enabled=false",
                        "--echo.cleanup.enabled=false", "--logging.level.root=ERROR", "--logging.level.com.echo=ERROR",
                        "--logging.level.org.apache.activemq.artemis=ERROR")) {
                    long responseId = context.getBean(ResponseRepository.class).save(
                            Response.builder().description("JMS cost probe").body("mock-ok").build()).getId();
                    var ruleService = context.getBean(JmsRuleService.class);
                    for (String operation : List.of("mock", "forward", "delay")) {
                        ruleService.saveJmsRule(JmsRule.builder().queueName("ECHO.REQUEST").enabled(true).priority(100)
                                .bodyCondition("//ServiceName=" + operation).responseId(responseId)
                                .action(operation.equals("forward") ? JmsRuleAction.FORWARD : JmsRuleAction.MOCK)
                                .delayMs(operation.equals("delay") ? 50L : 0L).build());
                    }
                    var metrics = context.getBean(JmsRuntimeMetrics.class);
                    try (var clients = new ActiveMQConnectionFactory("tcp://127.0.0.1:" + port);
                         var connection = clients.createConnection("bench", "bench")) {
                        connection.start();
                        for (String operation : List.of("mock", "forward", "delay")) {
                            for (int concurrency : operation.equals("delay") ? new int[]{30} : new int[]{8, 30}) {
                                long failuresBefore = ((Number) metrics.snapshot().get("forwardFailures")).longValue();
                                var result = measure(connection, operation, concurrency);
                                long forwardFailures = ((Number) metrics.snapshot().get("forwardFailures")).longValue() - failuresBefore;
                                if (failure.get() != null) throw new AssertionError("downstream failed", failure.get());
                                if (!Arrays.equals(broker.getActiveMQServerControl().getQueueNames(), new String[]{"DOWN.REQUEST"}))
                                    throw new AssertionError("Temporary downstream queue leaked");
                                String row = String.format(Locale.ROOT, "%d,%s,%s,%d,%d,%.2f,%.3f,%.3f,%.3f,%d",
                                        round + 1, mode, operation, concurrency, result.times.length,
                                        result.times.length * 1e9 / result.elapsed,
                                        percentile(result.times, .5), percentile(result.times, .95), percentile(result.times, .99), forwardFailures);
                                rows.add(row);
                                Files.write(output.resolve("results.csv"), rows);
                                System.out.println("JMS_AP " + row);
                            }
                        }
                    }
                }
            }
        } finally { broker.stop(); }
    }

    @SuppressWarnings("ArrayRecordComponent")
    private record Measurement(long elapsed, long[] times) {}
    @SuppressWarnings("ArrayRecordComponent")
    private record WorkerResult(long finished, long[] times) {}

    private static Measurement measure(Connection connection, String operation, int concurrency) throws Exception {
        var executor = Executors.newFixedThreadPool(concurrency);
        var ready = new CountDownLatch(concurrency);
        var start = new CountDownLatch(1);
        var origin = new AtomicLong();
        var futures = new ArrayList<java.util.concurrent.Future<WorkerResult>>();
        try {
            for (int worker = 0; worker < concurrency; worker++) {
                int id = worker;
                futures.add(executor.submit(() -> {
                    try (var session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
                         var producer = session.createProducer(session.createQueue("ECHO.REQUEST"))) {
                        TemporaryQueue replyQueue = session.createTemporaryQueue();
                        try (var replies = session.createConsumer(replyQueue)) {
                            ready.countDown();
                            if (!start.await(20, TimeUnit.SECONDS)) throw new AssertionError("Worker start timeout");
                            long warmEnd = origin.get() + TimeUnit.SECONDS.toNanos(5);
                            long end = warmEnd + TimeUnit.SECONDS.toNanos(10);
                            long[] times = new long[50000];
                            int count = 0;
                            int number = 0;
                            String prefix = "<Request><ServiceName>" + operation + "</ServiceName><Payload>" + "x".repeat(900);
                            while (System.nanoTime() < end) {
                                String body = prefix + "</Payload><Id>" + id + ":" + number++ + "</Id></Request>";
                                TextMessage request = session.createTextMessage(body);
                                request.setJMSReplyTo(replyQueue);
                                long before = System.nanoTime();
                                producer.send(request, DeliveryMode.PERSISTENT, 4, 0);
                                Message response = replies.receive(20000);
                                if (response == null) throw new AssertionError("Reply timeout for " + operation + ":" + id + ":" + number);
                                if (!(response instanceof TextMessage text)
                                        || !request.getJMSMessageID().equals(response.getJMSCorrelationID())
                                        || !(operation.equals("forward") ? body : "mock-ok").equals(text.getText()))
                                    throw new AssertionError("Reply body/correlation mismatch for " + operation + ":" + id + ":" + number);
                                if (before >= warmEnd) {
                                    if (count == times.length) throw new AssertionError("Increase probe sample bound");
                                    times[count++] = System.nanoTime() - before;
                                }
                            }
                            return new WorkerResult(System.nanoTime(), Arrays.copyOf(times, count));
                        } finally { replyQueue.delete(); }
                    }
                }));
            }
            if (!ready.await(20, TimeUnit.SECONDS)) throw new AssertionError("Workers not ready");
            origin.set(System.nanoTime());
            start.countDown();
            var results = new ArrayList<WorkerResult>();
            for (var future : futures) results.add(future.get(60, TimeUnit.SECONDS));
            long[] times = results.stream().flatMapToLong(r -> Arrays.stream(r.times)).sorted().toArray();
            long finished = results.stream().mapToLong(WorkerResult::finished).max().orElseThrow();
            return new Measurement(finished - origin.get() - TimeUnit.SECONDS.toNanos(5), times);
        } finally {
            start.countDown();
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    private static double percentile(long[] values, double quantile) {
        if (values.length == 0) throw new AssertionError("No measured replies");
        return values[Math.min(values.length - 1, (int) Math.ceil(values.length * quantile) - 1)] / 1e6;
    }

    private static int freePort() throws java.io.IOException {
        try (var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { return socket.getLocalPort(); }
    }
}
