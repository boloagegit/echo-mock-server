package com.echo.service;

import com.echo.EchoApplication;
import com.echo.entity.Protocol;
import com.echo.entity.RequestLog;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.management.ThreadMXBean;
import org.HdrHistogram.Histogram;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Isolated main-DB writer cost, not HTTP/JMS capacity. Never part of normal test gates. */
public final class RequestLogWriteCostProbe {
    private static final int RETENTION = 10_000;
    private RequestLogWriteCostProbe() { }

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(output.getParent());
        Files.createDirectory(output);
        boolean jdbcMode = switch (args[1]) {
            case "jpa" -> false;
            case "jdbc" -> true;
            default -> throw new IllegalArgumentException("Expected jpa or jdbc");
        };
        int warmupSeconds = Integer.parseInt(args[2]);
        int measureSeconds = Integer.parseInt(args[3]);
        if (warmupSeconds < 1 || measureSeconds < 1) throw new IllegalArgumentException("Positive durations required");
        Path work = Files.createTempDirectory("Echo Request Log Cost ");
        var results = new ArrayList<Object>();
        try {
            try (var context = new SpringApplicationBuilder(EchoApplication.class).run(
                    "--server.address=127.0.0.1", "--server.port=0", "--echo.jms.enabled=false",
                    "--spring.datasource.url=jdbc:sqlite:" + work.resolve("main.sqlite")
                            + "?journal_mode=WAL&busy_timeout=10000&synchronous=NORMAL&foreign_keys=ON",
                    "--echo.request-log.enabled=false", "--echo.request-log.jdbc-batch-enabled=" + jdbcMode,
                    "--echo.backup.enabled=false", "--echo.backup.on-shutdown=false", "--echo.cleanup.enabled=false",
                    "--echo.cache.sync-enabled=false", "--logging.file.path=" + output.resolve("logs"),
                    "--logging.level.root=ERROR", "--logging.level.com.echo=ERROR", "--spring.main.banner-mode=off")) {
                var writer = context.getBean(RequestLogBatchWriter.class);
                if (context.containsBean("requestLogJdbcBatchInsert") != jdbcMode) {
                    throw new AssertionError("Configured insert strategy was not activated");
                }
                var sql = context.getBean(JdbcTemplate.class);
                for (int bodyBytes : new int[]{0, 16_384}) {
                    for (int batchSize : new int[]{1, 50}) {
                        sql.update("DELETE FROM request_log");
                        String body = "x".repeat(bodyBytes);
                        long sequence = 0;
                        int retained = 0;
                        String spool = "cost-" + bodyBytes + "-" + batchSize;
                        long warmupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(warmupSeconds);
                        while (System.nanoTime() < warmupDeadline || sequence < RETENTION) {
                            writer.persist(spool, sequence + batchSize, batch(sequence, body, batchSize), RETENTION);
                            sequence += batchSize;
                            retained = retainedAfterBatch(retained, batchSize);
                        }
                        long firstMeasured = sequence;
                        var latency = new Histogram(TimeUnit.SECONDS.toNanos(60), 3);
                        var process = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
                        var threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
                        long threadId = Thread.currentThread().getId();
                        long allocationStart = threads.getThreadAllocatedBytes(threadId);
                        long cpuStart = process.getProcessCpuTime();
                        long start = System.nanoTime();
                        long deadline = start + TimeUnit.SECONDS.toNanos(measureSeconds);
                        while (System.nanoTime() < deadline) {
                            long before = System.nanoTime();
                            writer.persist(spool, sequence + batchSize, batch(sequence, body, batchSize), RETENTION);
                            latency.recordValue(System.nanoTime() - before);
                            sequence += batchSize;
                            retained = retainedAfterBatch(retained, batchSize);
                        }
                        long elapsed = System.nanoTime() - start;
                        long cpu = process.getProcessCpuTime() - cpuStart;
                        long allocation = threads.getThreadAllocatedBytes(threadId) - allocationStart;
                        long rows = sequence - firstMeasured;
                        long actualCount = sql.queryForObject("SELECT COUNT(*) FROM request_log", Long.class);
                        long correctRows = sql.queryForObject("SELECT COUNT(*) FROM request_log "
                                        + "WHERE request_body = ? AND response_body = ? AND matched = 1 "
                                        + "AND response_time_ms = 3 AND protocol = 'JMS' "
                                        + "AND CAST(substr(endpoint, 7) AS INTEGER) >= ? "
                                        + "AND CAST(substr(endpoint, 7) AS INTEGER) < ?",
                                Long.class, body, body, sequence - retained, sequence);
                        if (actualCount != retained || correctRows != retained || writer.findCheckpoint(spool) != sequence) {
                            throw new AssertionError("Record content/retention/checkpoint mismatch");
                        }
                        var row = new LinkedHashMap<String, Object>();
                        row.put("mode", args[1]);
                        row.put("bodyBytesPerColumn", bodyBytes);
                        row.put("batchSize", batchSize);
                        row.put("retention", RETENTION);
                        row.put("warmupRows", firstMeasured);
                        row.put("measuredRows", rows);
                        row.put("elapsedSeconds", elapsed / 1e9);
                        row.put("rowsPerSecond", rows * 1e9 / elapsed);
                        row.put("processCpuMicrosPerRow", cpu / 1e3 / rows);
                        row.put("writerThreadAllocatedBytesPerRow", (double) allocation / rows);
                        row.put("batchP50Ms", latency.getValueAtPercentile(50) / 1e6);
                        row.put("batchP99Ms", latency.getValueAtPercentile(99) / 1e6);
                        row.put("retainedRowsVerified", retained);
                        row.put("checkpoint", sequence);
                        row.put("passed", true);
                        results.add(row);
                        System.out.println("REQUEST_LOG_COST " + new ObjectMapper().writeValueAsString(row));
                    }
                }
            }
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.resolve("result.json").toFile(), results);
        } finally {
            // Stop the application before removing its database, including on failure (Windows too).
            try (var paths = Files.walk(work)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static int retainedAfterBatch(int previous, int batchSize) {
        int count = previous + batchSize;
        return count > RETENTION ? RETENTION - 1_000 : count;
    }

    private static List<RequestLog> batch(long first, String body, int batchSize) {
        var rows = new ArrayList<RequestLog>(batchSize);
        for (int index = 0; index < batchSize; index++) {
            long sequence = first + index;
            rows.add(RequestLog.builder().protocol(Protocol.JMS).method("JMS").endpoint(endpoint(sequence))
                    .matched(true).responseTimeMs(3).responseStatus(200).requestBody(body).responseBody(body)
                    .requestTime(LocalDateTime.of(2026, 10, 18, 0, 0).plusNanos(sequence * 1_000_000)).build());
        }
        return rows;
    }

    private static String endpoint(long sequence) { return "queue-" + sequence; }
}
