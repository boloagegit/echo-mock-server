package com.echo.diagnostics;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import com.echo.config.DiagnosticProperties;
import com.echo.config.JmsProperties;
import com.echo.jms.JmsTargetForwarder;
import com.echo.jms.target.ArtemisFactoryProvider;
import jakarta.jms.*;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.server.ActiveMQServers;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/** Short standalone A/B probe, NOT an assertion of production capacity or an endurance test. */
public final class DiagnosticCostProbe {
    private DiagnosticCostProbe() { }

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(output);
        int count = args.length > 1 ? Integer.parseInt(args[1]) : 1500;
        var context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.reset();
        context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).setLevel(Level.ERROR);
        measureProducer(output);
        String url = "vm://9487";
        var config = new ConfigurationImpl().setPersistenceEnabled(false).setSecurityEnabled(false)
                .setJMXManagementEnabled(false)
                .setJournalDirectory(output.resolve("broker/journal").toString())
                .setBindingsDirectory(output.resolve("broker/bindings").toString())
                .setPagingDirectory(output.resolve("broker/paging").toString())
                .setLargeMessagesDirectory(output.resolve("broker/large").toString())
                .addAcceptorConfiguration("cost-probe", url);
        var broker = ActiveMQServers.newActiveMQServer(config);
        broker.start();
        var properties = new JmsProperties();
        properties.getTarget().setEnabled(true);
        properties.getTarget().setType("artemis");
        properties.getTarget().setServerUrl(url);
        properties.getTarget().setQueue("COST.REQUEST");
        properties.getTarget().setTimeoutSeconds(1);
        AtomicReference<Exception> responderFailure = new AtomicReference<>();
        var forwarder = new JmsTargetForwarder(properties, List.of(new ArtemisFactoryProvider()));
        var cpu = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        try (var factory = new ActiveMQConnectionFactory(url); Connection responder = factory.createConnection();
             Session session = responder.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
            var consumer = session.createConsumer(session.createQueue("COST.REQUEST"));
            var producer = session.createProducer(null);
            consumer.setMessageListener(message -> {
                try { producer.send(message.getJMSReplyTo(), session.createTextMessage("reply")); }
                catch (Exception error) { responderFailure.set(error); }
            });
            responder.start();
            var disabled = new DiagnosticProperties();
            disabled.setEnabled(false);
            // Warm the exact measured operation in both modes, not a shorter forwarding-only path.
            try (var warmOff = new TransactionDiagnostics(disabled, output.toString());
                 var warmOn = new TransactionDiagnostics(new DiagnosticProperties(), output.toString())) {
                warmOff.start(); warmOn.start();
                for (int i = 0; i < 12000; i++) {
                    forwardOnce(warmOff, forwarder, i);
                    forwardOnce(warmOn, forwarder, i);
                }
            }
            var rows = new java.util.ArrayList<String>();
            rows.add("round,enabled,requests,rps,p50_ms,p95_ms,p99_ms,cpu_ms,log_bytes,dropped,sink_failures,temporary_queues");
            System.out.println(rows.get(0));
            for (int round = 0; round < 12; round++) {
                // Three ABBA blocks; retain every round, with no forced GC or long-run load.
                boolean enabled = round % 4 == 1 || round % 4 == 2;
                var settings = new DiagnosticProperties();
                settings.setEnabled(enabled);
                long bytesBefore = diagnosticBytes(output);
                long[] latencies = new long[count];
                long cpuBefore = cpu.getProcessCpuTime();
                long started = System.nanoTime();
                java.util.Map<String, Object> snapshot;
                try (var diagnostics = new TransactionDiagnostics(settings, output.toString())) {
                    diagnostics.start();
                    for (int n = 0; n < count; n++) {
                        long requestStarted = System.nanoTime();
                        forwardOnce(diagnostics, forwarder, n);
                        latencies[n] = System.nanoTime() - requestStarted;
                    }
                    long elapsed = System.nanoTime() - started;
                    diagnostics.close(); // include writer drain CPU/bytes, not in request percentiles
                    snapshot = diagnostics.snapshot();
                    Arrays.sort(latencies);
                    long queueCount = Arrays.stream(broker.getActiveMQServerControl().getQueueNames())
                            .filter(name -> !name.equals("COST.REQUEST")).count();
                    String row = String.format(Locale.ROOT, "%d,%s,%d,%.2f,%.3f,%.3f,%.3f,%.2f,%d,%s,%s,%d",
                            round + 1, enabled, count, count * 1e9 / elapsed,
                            percentile(latencies, 50), percentile(latencies, 95), percentile(latencies, 99),
                            (cpu.getProcessCpuTime() - cpuBefore) / 1e6,
                            diagnosticBytes(output) - bytesBefore,
                            snapshot.get("diagnosticDropped"), snapshot.get("diagnosticSinkFailures"), queueCount);
                    rows.add(row);
                    System.out.println(row);
                    if (responderFailure.get() != null) throw new AssertionError("Responder failed", responderFailure.get());
                    if (queueCount != 0) throw new AssertionError("Temporary reply queue retained");
                }
            }
            Files.write(output.resolve("results.csv"), rows);
        } finally {
            forwarder.cleanup();
            broker.stop();
            context.stop();
        }
    }

    private static void forwardOnce(TransactionDiagnostics diagnostics, JmsTargetForwarder forwarder, int number) {
        var trace = diagnostics.begin("JMS");
        trace.event("RECEIVED", "messageId", "ID:probe-" + number, "deliveryCount", 1);
        trace.event("ROUTE", "mode", "FORWARD", "ruleId", "probe-rule", "ruleVersion", 1);
        String response = forwarder.forwardWithMetadata("<ServiceName>probe</ServiceName>", null, trace).body();
        if (!"reply".equals(response)) throw new AssertionError("Forward result changed");
        trace.event("PROCESSING_READY", "forwarded", true);
        trace.event("REPLY_SEND_RETURNED", "messageId", "ID:probe-reply");
        trace.end("PROCESSING_ENDED");
    }

    private static long diagnosticBytes(Path root) throws Exception {
        Path directory = root.resolve("diagnostics");
        if (!Files.isDirectory(directory)) return 0;
        try (var files = Files.list(directory)) {
            long total = 0;
            for (Path file : files.filter(path -> path.getFileName().toString().startsWith("transactions-")).toList()) {
                total += Files.size(file);
            }
            return total;
        }
    }

    private static double percentile(long[] sorted, int percentile) {
        return sorted[Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * percentile / 100.0) - 1)] / 1e6;
    }

    private static void measureProducer(Path output) throws Exception {
        var cpu = ManagementFactory.getThreadMXBean();
        var allocations = (com.sun.management.ThreadMXBean) cpu;
        var rows = new java.util.ArrayList<String>();
        rows.add("round,enabled,requests,producer_cpu_us_per_request,p95_enqueue_us,allocated_bytes_per_request,dropped");
        int count = 2000;
        for (int round = 0; round < 4; round++) {
            boolean enabled = round % 2 == 1;
            var properties = new DiagnosticProperties();
            properties.setEnabled(enabled);
            long[] durations = new long[count];
            try (var diagnostics = new TransactionDiagnostics(properties, output.toString())) {
                diagnostics.start();
                long startCpu = cpu.getCurrentThreadCpuTime();
                long startAllocated = allocations.getThreadAllocatedBytes(Thread.currentThread().getId());
                long started = System.nanoTime();
                for (int n = 0; n < count; n++) {
                    long before = System.nanoTime();
                    var trace = diagnostics.begin("JMS");
                    trace.event("RECEIVED", "messageId", "ID:probe-" + n, "deliveryCount", 1);
                    trace.event("ROUTE", "mode", "FORWARD", "ruleId", "probe-rule", "ruleVersion", 1);
                    trace.event("FORWARD_BEGIN", "connectionRevision", "db:7:1", "source", "DATABASE",
                            "provider", "artemis", "queue", "PROBE.REQUEST", "timeoutMs", 1000);
                    trace.event("OUTBOUND_SEND_RETURNED", "messageId", "ID:outbound-" + n, "replyTo", "temporary-probe");
                    trace.event("REPLY_WAIT_END", "outcome", "TEXT_REPLY", "messageId", "ID:response-" + n, "waitMs", 1);
                    trace.event("PROCESSING_READY", "matched", true, "forwarded", true, "delayMs", 0);
                    trace.event("REPLY_SEND_BEGIN", "replyTo", "caller-probe");
                    trace.event("REPLY_SEND_RETURNED", "messageId", "ID:reply-" + n);
                    trace.end("PROCESSING_ENDED");
                    durations[n] = System.nanoTime() - before;
                    // Pace only the synthetic CPU/allocation probe; production adds no pacing.
                    long remaining = started + (n + 1L) * 1_000_000L - System.nanoTime();
                    if (remaining > 0) java.util.concurrent.locks.LockSupport.parkNanos(remaining);
                }
                long producerCpu = cpu.getCurrentThreadCpuTime() - startCpu;
                long allocated = allocations.getThreadAllocatedBytes(Thread.currentThread().getId()) - startAllocated;
                diagnostics.close();
                Arrays.sort(durations);
                String row = String.format(Locale.ROOT, "%d,%s,%d,%.3f,%.3f,%.1f,%s", round + 1, enabled, count,
                        producerCpu / (count * 1000.0), percentile(durations, 95) * 1000,
                        allocated / (double) count, diagnostics.snapshot().get("diagnosticDropped"));
                rows.add(row);
                System.out.println("PRODUCER_COST " + row);
            }
        }
        Files.write(output.resolve("producer-results.csv"), rows);
    }
}
