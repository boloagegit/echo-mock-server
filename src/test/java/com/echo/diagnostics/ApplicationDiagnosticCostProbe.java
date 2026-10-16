package com.echo.diagnostics;

import com.echo.EchoApplication;
import com.echo.entity.HttpRule;
import com.echo.entity.Response;
import com.echo.repository.ResponseRepository;
import com.echo.service.HttpRuleService;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;

import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Opt-in short actual HTTP + SQLite + durable spool A/B; no production/external targets. */
public final class ApplicationDiagnosticCostProbe {
    private ApplicationDiagnosticCostProbe() { }
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(output);
        int count = args.length > 1 ? Integer.parseInt(args[1]) : 1000;
        List<String> rows = new ArrayList<>();
        rows.add("round,enabled,requests,rps,p50_ms,p95_ms,p99_ms,cpu_ms,diagnostic_bytes,dropped,sink_failures");
        var cpu = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        for (int round = 0; round < 4; round++) {
            boolean enabled = round == 1 || round == 2; // ABBA, independent data for each AP
            Path root = Files.createTempDirectory("echo-ap-diagnostic-cost-");
            String url = "jdbc:sqlite:" + root.resolve("main.sqlite")
                    + "?journal_mode=WAL&busy_timeout=10000&synchronous=NORMAL&foreign_keys=ON";
            try (var context = new SpringApplicationBuilder(EchoApplication.class)
                    .profiles("dev", "sqlite").run(
                            "--server.address=127.0.0.1", "--server.port=0", "--echo.jms.enabled=false",
                            "--spring.datasource.url=" + url,
                            "--echo.request-log.durable.spool-path=" + root.resolve("spool.sqlite"),
                            "--logging.file.path=" + root.resolve("logs"),
                            "--echo.backup.enabled=false", "--echo.cleanup.enabled=false",
                            "--echo.diagnostics.enabled=" + enabled,
                            "--logging.level.com.echo=WARN", "--logging.level.org.springframework=WARN",
                            "--logging.level.org.hibernate=WARN")) {
                long responseId = context.getBean(ResponseRepository.class).save(
                        Response.builder().description("Cost probe").body("ok").build()).getId();
                context.getBean(HttpRuleService.class).saveHttpRule(HttpRule.builder()
                        .targetHost("cost.local").matchKey("/diagnostic-cost").method("GET")
                        .responseId(responseId).enabled(true).priority(100).build());
                int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
                HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(5)).build();
                HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mock/diagnostic-cost"))
                        .header("X-Original-Host", "cost.local").timeout(Duration.ofSeconds(10)).build();
                for (int n = 0; n < 2500; n++) verify(client.send(request, HttpResponse.BodyHandlers.ofString()));
                long[] times = new long[count];
                long beforeCpu = cpu.getProcessCpuTime();
                long started = System.nanoTime();
                for (int n = 0; n < count; n++) {
                    long before = System.nanoTime();
                    verify(client.send(request, HttpResponse.BodyHandlers.ofString()));
                    times[n] = System.nanoTime() - before;
                }
                long elapsed = System.nanoTime() - started;
                long processCpu = cpu.getProcessCpuTime() - beforeCpu;
                Arrays.sort(times);
                var snapshot = context.getBean(TransactionDiagnostics.class).snapshot();
                long bytes = (Long) snapshot.getOrDefault("diagnosticTodayBytes", 0L);
                if (bytes != 0 || ((Long) snapshot.get("diagnosticAccepted")) != 0)
                    throw new AssertionError("Healthy requests produced diagnostic output: " + snapshot);
                String row = String.format(Locale.ROOT, "%d,%s,%d,%.2f,%.3f,%.3f,%.3f,%.2f,%d,%s,%s",
                        round + 1, enabled, count, count * 1e9 / elapsed,
                        percentile(times, 50), percentile(times, 95), percentile(times, 99),
                        processCpu / 1e6, bytes, snapshot.get("diagnosticDropped"), snapshot.get("diagnosticSinkFailures"));
                rows.add(row);
                System.out.println("AP_COST " + row);
            }
        }
        Files.write(output.resolve("application-results.csv"), rows);
    }
    private static void verify(HttpResponse<String> response) {
        if (response.statusCode() != 200 || !"ok".equals(response.body()))
            throw new AssertionError("HTTP result changed: " + response.statusCode());
    }
    private static double percentile(long[] values, int percentage) {
        return values[Math.min(values.length - 1, (int) Math.ceil(values.length * percentage / 100.0) - 1)] / 1e6;
    }
}
