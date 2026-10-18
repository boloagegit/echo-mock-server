package com.echo.agent;

import com.echo.entity.Protocol;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.management.ThreadMXBean;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Opt-in real SQLite spool read cost. Not an HTTP/JMS throughput benchmark or a test gate. */
public final class RequestLogSpoolReadCostProbe {
    private static final int BATCH_SIZE = 50;
    private static volatile long blackhole;

    private RequestLogSpoolReadCostProbe() { }

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(output);
        int warmupSeconds = Integer.parseInt(args[1]);
        int measureSeconds = Integer.parseInt(args[2]);
        if (warmupSeconds < 1 || measureSeconds < 1) throw new IllegalArgumentException("Positive durations required");
        // Keep SQLite on the chosen output filesystem, not the OS's possibly RAM-backed temp directory.
        Path work = Files.createTempDirectory(output, "Echo Spool Cost ");
        var mapper = new ObjectMapper().findAndRegisterModules();
        var results = new ArrayList<Map<String, Object>>();
        try {
            for (String shape : List.of("small-ascii", "ascii", "escaped", "unicode")) {
                String pattern = switch (shape) {
                    case "escaped" -> "{\"id\":\"abc\\def\",\"ok\":true}\n";
                    case "unicode" -> "金融測試中文😀\n";
                    default -> "x";
                };
                int length = shape.equals("small-ascii") ? 1_024 : 65_550;
                String body = pattern.repeat(length / pattern.length() + 1).substring(0, length);
                if (Character.isHighSurrogate(body.charAt(body.length() - 1))) body += "\uDE00";
                var spool = new RequestLogSpool(mapper, work.resolve(shape + ".sqlite").toString(),
                        256, 100, 2, 10_000, 25, 256L * 1024 * 1024, false);
                spool.start();
                try {
                    var tasks = new ArrayList<LogTask>();
                    for (int i = 0; i < BATCH_SIZE; i++) {
                        tasks.add(LogTask.builder().protocol(Protocol.JMS).method("JMS")
                                .endpoint("queue-" + i).matched(true).responseStatus(200).responseTimeMs(3)
                                .requestTime(LocalDateTime.of(2026, 10, 18, 0, 0).plusSeconds(i))
                                .requestBody(body).responseBody(body).analysisBody(body)
                                .headers(Map.of("X-Trace", "spool-probe")).build());
                    }
                    if (!spool.appendAll(tasks).equals(new RequestLogSpool.AppendResult(BATCH_SIZE, 0))) {
                        throw new AssertionError("Seed not committed");
                    }
                    var read = spool.readAfter(0, BATCH_SIZE, 16L * 1024 * 1024);
                    for (int i = 0; i < tasks.size(); i++) {
                        LogTask actual = read.get(i).task();
                        if (!body.equals(actual.getRequestBody()) || !body.equals(actual.getResponseBody())
                                || !body.equals(actual.getAnalysisBody())
                                || !tasks.get(i).getEndpoint().equals(actual.getEndpoint())
                                || !tasks.get(i).getRequestTime().equals(actual.getRequestTime())
                                || !tasks.get(i).getHeaders().equals(actual.getHeaders())) {
                            throw new AssertionError("Round-trip changed logged content");
                        }
                    }
                    measure(spool, warmupSeconds);
                    var row = new LinkedHashMap<String, Object>(measure(spool, measureSeconds));
                    row.put("shape", shape);
                    row.put("bodyCharactersPerColumn", body.length());
                    row.put("batchSize", BATCH_SIZE);
                    row.put("passed", true);
                    results.add(row);
                    System.out.println("SPOOL_READ_COST " + mapper.writeValueAsString(row));
                } finally {
                    spool.stop();
                }
            }
            mapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve("result.json").toFile(), results);
        } finally {
            // All SQLite handles are closed before cleanup, including on Windows.
            try (var paths = Files.walk(work)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static Map<String, Object> measure(RequestLogSpool spool, int seconds) {
        var threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        var process = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        long thread = Thread.currentThread().getId();
        long allocation = threads.getThreadAllocatedBytes(thread);
        long cpu = process.getProcessCpuTime();
        long start = System.nanoTime();
        long rows = 0;
        do {
            var batch = spool.readAfter(0, BATCH_SIZE, 16L * 1024 * 1024);
            if (batch.size() != BATCH_SIZE) throw new AssertionError("Missing spool rows");
            blackhole = batch.get(BATCH_SIZE - 1).sequence();
            rows += batch.size();
        } while (System.nanoTime() - start < TimeUnit.SECONDS.toNanos(seconds));
        long elapsed = System.nanoTime() - start;
        if (blackhole != BATCH_SIZE) throw new AssertionError("Sequence changed during read-only measurement");
        return Map.of("rows", rows, "rowsPerSecond", rows * 1e9 / elapsed,
                "wallMicrosPerRow", elapsed / 1e3 / rows,
                "processCpuMicrosPerRow", (process.getProcessCpuTime() - cpu) / 1e3 / rows,
                "readerAllocatedBytesPerRow", (double) (threads.getThreadAllocatedBytes(thread) - allocation) / rows);
    }
}
