package com.echo.agent;

import com.echo.entity.Protocol;
import com.echo.repository.RequestLogRepository;
import com.echo.service.ConditionMatcher;
import com.echo.service.RequestLogService;
import com.echo.service.RequestLogUnavailableException;
import com.echo.service.SystemConfigService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class RequestLogSpoolTest {

    @TempDir
    Path tempDir;

    private RequestLogSpool spool;

    @AfterEach
    void tearDown() {
        if (spool != null) {
            spool.stop();
        }
    }

    @Test
    void committedTaskSurvivesRestartAndRetainsAllFields() {
        Path path = tempDir.resolve("request-log-spool.sqlite");
        spool = createSpool(path, 16 * 1024 * 1024);
        spool.start();
        String firstSpoolId = spool.getSpoolId();

        spool.append(task("rule-1", "/orders/1"));
        assertThat(spool.pendingBytes()).isPositive();
        assertThat(spool.pendingItems()).isEqualTo(1);
        spool.stop();

        spool = createSpool(path, 16 * 1024 * 1024);
        spool.start();
        assertThat(spool.getSpoolId()).isEqualTo(firstSpoolId);

        List<RequestLogSpool.SpoolEntry> entries = spool.readAfter(0, 10);
        assertThat(entries).hasSize(1);
        LogTask restored = entries.get(0).task();
        assertThat(restored.getRuleId()).isEqualTo("rule-1");
        assertThat(restored.getEndpoint()).isEqualTo("/orders/1");
        assertThat(restored.getAnalysisBody()).isEqualTo("{\"id\":1}");
        assertThat(restored.getHeaders()).containsEntry("X-Test", "value");
        assertThat(restored.getMatchOutcomes()).containsEntry("body:id=1", true);
        assertThat(restored.getCandidates()).singleElement()
                .extracting(CandidateSnapshot::getBodyCondition).isEqualTo("id=1");

        spool.deleteThrough(entries.get(0).sequence());
        assertThat(spool.readAfter(0, 10)).isEmpty();
        assertThat(spool.pendingBytes()).isZero();
        assertThat(spool.pendingItems()).isZero();
    }

    @Test
    void concurrentAppendsAreAllDurableAndOrdered() throws Exception {
        spool = createSpool(tempDir.resolve("concurrent.sqlite"), 64 * 1024 * 1024);
        spool.start();
        ExecutorService executor = Executors.newFixedThreadPool(12);
        try {
            List<Future<?>> writes = new ArrayList<>();
            for (int i = 0; i < 120; i++) {
                int index = i;
                writes.add(executor.submit(() -> spool.append(
                        task("rule-" + index, "/items/" + index))));
            }
            for (Future<?> write : writes) {
                write.get();
            }
        } finally {
            executor.shutdownNow();
        }

        List<RequestLogSpool.SpoolEntry> entries = spool.readAfter(0, 200);
        assertThat(entries).hasSize(120);
        assertThat(spool.pendingItems()).isEqualTo(120);
        assertThat(entries).extracting(RequestLogSpool.SpoolEntry::sequence).isSorted();
        assertThat(entries).extracting(entry -> entry.task().getEndpoint()).doesNotHaveDuplicates();
    }

    @Test
    void repeatedCandidateSnapshotsAreStoredOnceAndRemainAvailableUntilLastReferenceIsDeleted()
            throws Exception {
        Path path = tempDir.resolve("deduplicated.sqlite");
        spool = createSpool(path, 64 * 1024 * 1024);
        spool.start();

        spool.append(task("rule-shared", "/orders"));
        long firstBytes = spool.pendingBytes();
        spool.append(task("rule-shared", "/orders"));
        long secondIncrement = spool.pendingBytes() - firstBytes;

        assertThat(queryLong(path, "SELECT COUNT(*) FROM candidate_snapshot_sets")).isEqualTo(1);
        assertThat(secondIncrement).isPositive().isLessThan(firstBytes);
        List<RequestLogSpool.SpoolEntry> entries = spool.readAfter(0, 10);
        assertThat(entries).hasSize(2);
        assertThat(entries).allSatisfy(entry -> assertThat(entry.task().getCandidates())
                .singleElement()
                .extracting(CandidateSnapshot::getBodyCondition).isEqualTo("id=1"));

        spool.deleteThrough(entries.get(0).sequence());
        assertThat(queryLong(path, "SELECT COUNT(*) FROM candidate_snapshot_sets")).isEqualTo(1);
        assertThat(spool.readAfter(entries.get(0).sequence(), 10)).hasSize(1);

        spool.deleteThrough(entries.get(1).sequence());
        assertThat(queryLong(path, "SELECT COUNT(*) FROM candidate_snapshot_sets")).isZero();
        assertThat(spool.pendingBytes()).isZero();
        assertThat(spool.pendingItems()).isZero();
    }

    @Test
    void legacyEmbeddedCandidateRowsAreMigratedWithoutLosingMatchAnalysisData() throws Exception {
        Path path = tempDir.resolve("legacy.sqlite");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path);
             Statement schema = connection.createStatement()) {
            schema.execute("""
                    CREATE TABLE request_log_spool (
                        sequence_id INTEGER PRIMARY KEY AUTOINCREMENT,
                        payload BLOB NOT NULL,
                        created_at INTEGER NOT NULL
                    )
                    """);
            String legacyPayload = """
                    {
                      "ruleId":"legacy-rule","protocol":"HTTP","method":"POST",
                      "endpoint":"/legacy","matched":true,"responseTimeMs":12,"matchTimeMs":2,
                      "clientIp":"127.0.0.1","requestTime":"2026-01-15T10:30:00",
                      "matchChain":"[]","responseStatus":200,
                      "requestBody":"{\\\"id\\\":1}","responseBody":"{\\\"ok\\\":true}",
                      "candidates":[{"ruleId":"near-miss-rule","endpoint":"/legacy",
                        "description":"historical candidate","enabled":true,
                        "bodyCondition":"id=2","priority":10}],
                      "analysisBody":"{\\\"id\\\":1}","queryString":"trace=1",
                      "headers":{"X-Test":"legacy"},"matchOutcomes":{"body:id=2":false}
                    }
                    """;
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO request_log_spool(payload, created_at) VALUES (?, ?)")) {
                insert.setBytes(1, legacyPayload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                insert.setLong(2, System.currentTimeMillis());
                insert.executeUpdate();
            }
        }

        spool = createSpool(path, 16 * 1024 * 1024);
        spool.start();

        List<RequestLogSpool.SpoolEntry> entries = spool.readAfter(0, 10);
        assertThat(entries).singleElement().satisfies(entry -> {
            assertThat(entry.task().getRuleId()).isEqualTo("legacy-rule");
            assertThat(entry.task().getAnalysisBody()).isEqualTo("{\"id\":1}");
            assertThat(entry.task().getCandidates()).singleElement().satisfies(candidate -> {
                assertThat(candidate.getRuleId()).isEqualTo("near-miss-rule");
                assertThat(candidate.getBodyCondition()).isEqualTo("id=2");
            });
            assertThat(entry.task().getMatchOutcomes()).containsEntry("body:id=2", false);
        });
    }

    @Test
    void deduplicatedSnapshotAndSharedBodyPreserveNearMissAfterRestart() throws Exception {
        Path path = tempDir.resolve("near-miss.sqlite");
        spool = createSpool(path, 16 * 1024 * 1024);
        spool.start();
        String body = "{\"type\":\"ORDER\",\"env\":\"prod\"}";
        CandidateSnapshot matched = CandidateSnapshot.builder()
                .ruleId("rule-match").endpoint("/orders").enabled(true)
                .bodyCondition("type=ORDER;env=prod").priority(10).build();
        CandidateSnapshot nearMiss = CandidateSnapshot.builder()
                .ruleId("rule-near-miss").endpoint("/orders").enabled(true)
                .bodyCondition("type=ORDER;env=staging").priority(9).build();
        LogTask original = LogTask.builder()
                .ruleId("rule-match").protocol(Protocol.HTTP).method("POST")
                .endpoint("/orders").matched(true).responseTimeMs(8)
                .requestTime(LocalDateTime.now()).matchChain("[]").responseStatus(200)
                .requestBody(body).responseBody("{\"ok\":true}")
                .candidates(List.of(matched, nearMiss)).analysisBody(body)
                .headers(Map.of()).matchOutcomes(Map.of()).build();

        spool.append(original);
        spool.stop();
        spool = createSpool(path, 16 * 1024 * 1024);
        spool.start();
        LogTask restored = spool.readAfter(0, 10).get(0).task();
        LogAgent analyzer = new LogAgent(
                mock(RequestLogRepository.class), mock(SystemConfigService.class),
                new ConditionMatcher(), mock(RequestLogService.class),
                32, 8, 1, true);

        List<Map<String, Object>> chain = new ObjectMapper().readValue(
                analyzer.analyzeMatchChain(restored), new TypeReference<>() {});

        assertThat(restored.getAnalysisBody()).isEqualTo(body);
        assertThat(restored.getCandidates()).hasSize(2);
        assertThat(chain).extracting(entry -> entry.get("reason"))
                .containsExactly("match", "near-miss");
        assertThat(chain.get(1)).containsEntry("score", "1/2")
                .containsEntry("nearMiss", true);
    }

    @Test
    void concurrentCleanupAndReuseCannotLeaveCandidateReferencesDangling() throws Exception {
        Path path = tempDir.resolve("cleanup-race.sqlite");
        spool = createSpool(path, 64 * 1024 * 1024);
        spool.start();
        spool.append(task("shared-rule", "/shared"));
        long firstSequence = spool.readAfter(0, 1).get(0).sequence();

        ExecutorService executor = Executors.newFixedThreadPool(12);
        try {
            List<Future<?>> writes = new ArrayList<>();
            for (int i = 0; i < 60; i++) {
                writes.add(executor.submit(() -> spool.append(task("shared-rule", "/shared"))));
            }
            spool.deleteThrough(firstSequence);
            for (Future<?> write : writes) {
                write.get();
            }
        } finally {
            executor.shutdownNow();
        }

        List<RequestLogSpool.SpoolEntry> remaining = spool.readAfter(firstSequence, 100);
        assertThat(remaining).hasSize(60);
        assertThat(remaining).allSatisfy(entry -> assertThat(entry.task().getCandidates())
                .singleElement()
                .extracting(CandidateSnapshot::getRuleId).isEqualTo("shared-rule"));
        assertThat(queryLong(path, "SELECT COUNT(*) FROM candidate_snapshot_sets")).isEqualTo(1);
    }

    @Test
    void missingCandidateSnapshotFailsInsteadOfSilentlyDroppingNearMissContext() throws Exception {
        Path path = tempDir.resolve("corrupt.sqlite");
        spool = createSpool(path, 16 * 1024 * 1024);
        spool.start();
        spool.append(task("rule-1", "/orders"));
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM candidate_snapshot_sets");
        }

        assertThatThrownBy(() -> spool.readAfter(0, 10))
                .isInstanceOf(RequestLogUnavailableException.class)
                .hasMessageContaining("Cannot read request-log spool");
    }

    @Test
    void byteLimitFailsFastInsteadOfAcceptingAnUnloggedRequest() {
        spool = createSpool(tempDir.resolve("limited.sqlite"), 8);
        spool.start();

        assertThatThrownBy(() -> spool.append(task("rule-1", "/too-large")))
                .isInstanceOf(RequestLogUnavailableException.class)
                .hasMessageContaining("byte limit");
        assertThat(spool.readAfter(0, 10)).isEmpty();
    }

    private RequestLogSpool createSpool(Path path, long maxPendingBytes) {
        return new RequestLogSpool(new ObjectMapper().findAndRegisterModules(),
                path.toString(), 256, 32, 2, 2_000, 25, maxPendingBytes);
    }

    private long queryLong(Path path, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }

    private LogTask task(String ruleId, String endpoint) {
        CandidateSnapshot candidate = CandidateSnapshot.builder()
                .ruleId(ruleId)
                .endpoint(endpoint)
                .description("candidate")
                .enabled(true)
                .bodyCondition("id=1")
                .priority(10)
                .build();
        return LogTask.builder()
                .ruleId(ruleId)
                .protocol(Protocol.HTTP)
                .method("POST")
                .endpoint(endpoint)
                .matched(true)
                .responseTimeMs(12)
                .matchTimeMs(2)
                .clientIp("127.0.0.1")
                .requestTime(LocalDateTime.now())
                .matchChain("[]")
                .targetHost("api.internal")
                .responseStatus(200)
                .requestBody("{\"id\":1}")
                .responseBody("{\"ok\":true}")
                .candidates(List.of(candidate))
                .analysisBody("{\"id\":1}")
                .queryString("trace=1")
                .headers(Map.of("X-Test", "value"))
                .matchOutcomes(Map.of("body:id=1", true))
                .build();
    }
}
