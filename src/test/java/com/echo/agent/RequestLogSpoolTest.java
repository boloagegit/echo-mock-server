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
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

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
        assertThat(restored.getFaultType()).isEqualTo("EMPTY_RESPONSE");
        assertThat(restored.getScenarioName()).isEqualTo("order-flow");
        assertThat(restored.getScenarioFromState()).isEqualTo("Started");
        assertThat(restored.getScenarioToState()).isEqualTo("Paid");
        assertThat(restored.isForwarded()).isTrue();
        assertThat(restored.getForwardTarget())
                .isEqualTo("Primary | https://downstream.example");
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
    void deletedCandidateSnapshotsAreReleasedEvenWhileNewerLogsRemainPending() throws Exception {
        Path path = tempDir.resolve("candidate-churn.sqlite");
        spool = createSpool(path, 64 * 1024 * 1024);
        spool.start();

        spool.append(task("retired-rule", "/old"));
        spool.append(task("active-rule", "/new"));
        List<RequestLogSpool.SpoolEntry> entries = spool.readAfter(0, 10);
        assertThat(queryLong(path, "SELECT COUNT(*) FROM candidate_snapshot_sets")).isEqualTo(2);

        spool.deleteThrough(entries.get(0).sequence());

        assertThat(spool.pendingItems()).isEqualTo(1);
        assertThat(queryLong(path, "SELECT COUNT(*) FROM candidate_snapshot_sets")).isEqualTo(1);
        assertThat(spool.readAfter(entries.get(0).sequence(), 10))
                .singleElement()
                .satisfies(entry -> assertThat(entry.task().getRuleId()).isEqualTo("active-rule"));
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
            assertThat(entry.task().isForwarded()).isFalse();
            assertThat(entry.task().getForwardTarget()).isNull();
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

    @Test
    @Timeout(15)
    void pendingByteBudgetWaitsAndRecoversAfterDurableRowsAreDeleted() throws Exception {
        spool = createSpoolWithMemory(tempDir.resolve("backpressure.sqlite"),
                32 * 1024, 64 * 1024 * 1024);
        spool.start();
        List<RequestLogSpool.SpoolEntry> initialRows = fillNearCapacity();
        assertThat(initialRows).isNotEmpty();
        assertThat(spool.statusSnapshot().pendingBytes())
                .isGreaterThan(spool.statusSnapshot().pendingByteLimit() / 2);

        ExecutorService executor = Executors.newFixedThreadPool(16);
        try {
            List<Future<?>> writes = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                int index = i;
                writes.add(executor.submit(() -> spool.append(
                        task("waiting-" + index, "/waiting/" + index))));
            }

            awaitSpoolWaiting(spool);
            assertThat(spool.statusSnapshot().backpressureActive()).isTrue();

            spool.deleteThrough(initialRows.get(initialRows.size() - 1).sequence());
            for (Future<?> write : writes) {
                write.get(5, TimeUnit.SECONDS);
            }
            assertThat(spool.statusSnapshot().waitingProducers()).isZero();

            List<RequestLogSpool.SpoolEntry> remaining = spool.readAfter(0, 1_000);
            assertThat(remaining).hasSize(32);
            spool.deleteThrough(remaining.get(remaining.size() - 1).sequence());
            assertThat(spool.statusSnapshot().pendingBytes()).isZero();
            assertThat(spool.statusSnapshot().backpressureActive()).isFalse();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @Timeout(15)
    void interruptingPendingAppendReleasesOnlyItsReservations() throws Exception {
        spool = createSpoolWithMemory(tempDir.resolve("interrupt.sqlite"),
                32 * 1024, 64 * 1024 * 1024);
        spool.start();
        List<RequestLogSpool.SpoolEntry> initialRows = fillNearCapacity();
        CountDownLatch finished = new CountDownLatch(32);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> waiters = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            Thread waiter = new Thread(() -> {
                try {
                    spool.append(task("interrupted", "/interrupted"));
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                } finally {
                    finished.countDown();
                }
            });
            waiters.add(waiter);
            waiter.start();
        }

        awaitSpoolWaiting(spool);
        waiters.forEach(Thread::interrupt);
        assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();
        for (Thread waiter : waiters) {
            waiter.join(1_000);
        }
        assertThat(failure.get()).isInstanceOf(RequestLogUnavailableException.class)
                .hasMessageContaining("Interrupted");
        assertThat(spool.statusSnapshot().waitingProducers()).isZero();
        assertThat(spool.statusSnapshot().pendingItems()).isGreaterThanOrEqualTo(initialRows.size());
        assertThat(spool.statusSnapshot().inFlightBytes()).isZero();
    }

    @Test
    @Timeout(15)
    void shutdownWakesPendingAppendWithoutAcknowledgingIt() throws Exception {
        spool = createSpoolWithMemory(tempDir.resolve("shutdown.sqlite"),
                32 * 1024, 64 * 1024 * 1024);
        spool.start();
        fillNearCapacity();
        CountDownLatch finished = new CountDownLatch(32);
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        List<Thread> waiters = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            Thread waiter = new Thread(() -> {
                try {
                    spool.append(task("shutdown", "/shutdown"));
                } catch (Throwable e) {
                    failures.add(e);
                } finally {
                    finished.countDown();
                }
            });
            waiters.add(waiter);
            waiter.start();
        }

        awaitSpoolWaiting(spool);
        spool.stop();
        assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();
        for (Thread waiter : waiters) {
            waiter.join(1_000);
        }
        // The setup intentionally leaves some headroom, so a few appends may
        // commit before stop() is invoked. Every append that was still pending
        // must wake with the explicit unavailable result.
        assertThat(failures).isNotEmpty()
                .allMatch(RequestLogUnavailableException.class::isInstance);
        assertThat(spool.statusSnapshot().waitingProducers()).isZero();
    }

    @Test
    @Timeout(15)
    void storageFailureFailsQueuedAppendsWhileOnlyWriterBatchWaitsForRecovery() throws Exception {
        Path path = tempDir.resolve("unavailable.sqlite");
        spool = new RequestLogSpool(new ObjectMapper().findAndRegisterModules(),
                path.toString(), 64, 1, 2, 2_000, 25,
                64 * 1024 * 1024, 64 * 1024 * 1024);
        spool.start();

        // The spool is initialized but its writer has not opened a connection
        // yet. Replacing this test-only database path with a directory makes
        // the first writer batch fail deterministically.
        Files.delete(path);
        Files.createDirectory(path);

        ExecutorService executor = Executors.newFixedThreadPool(16);
        List<Future<?>> appends = new ArrayList<>();
        try {
            for (int i = 0; i < 16; i++) {
                int index = i;
                appends.add(executor.submit(() -> spool.append(
                        task("unavailable-" + index, "/unavailable/" + index))));
            }

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline
                    && appends.stream().filter(Future::isDone).count() < 15) {
                TimeUnit.MILLISECONDS.sleep(25);
            }

            List<Future<?>> completed = appends.stream().filter(Future::isDone).toList();
            assertThat(completed).hasSizeGreaterThanOrEqualTo(15);
            for (Future<?> append : completed) {
                assertThatThrownBy(append::get)
                        .isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(RequestLogUnavailableException.class);
            }
        } finally {
            spool.stop();
            executor.shutdownNow();
        }
    }

    @Test
    void readBatchByteBudgetStopsBeforeMaterializingFollowingRows() {
        spool = createSpoolWithMemory(tempDir.resolve("read-budget.sqlite"),
                64 * 1024, 64 * 1024 * 1024);
        spool.start();
        spool.append(minimalTask("one"));
        spool.append(minimalTask("two"));
        spool.append(minimalTask("three"));

        List<RequestLogSpool.SpoolEntry> all = spool.readAfter(0, 10);
        assertThat(all).hasSize(3);
        List<RequestLogSpool.SpoolEntry> bounded = spool.readAfter(
                0, 10, all.get(0).payloadBytes() + 1L);
        assertThat(bounded).hasSize(1);
        assertThat(bounded.get(0).sequence()).isEqualTo(all.get(0).sequence());

        assertThatThrownBy(() -> spool.readAfter(0, 10, all.get(0).payloadBytes() - 1L))
                .isInstanceOfSatisfying(RequestLogSpool.UnrecoverableSpoolEntryException.class,
                        failure -> {
                            assertThat(failure.kind()).isEqualTo(
                                    RequestLogSpool.UnrecoverableSpoolEntryException.Kind.OVERSIZED);
                            assertThat(failure.sequence()).isEqualTo(all.get(0).sequence());
                        });
        assertThat(spool.readAfter(0, 10)).hasSize(3);
    }

    @Test
    @Timeout(20)
    void concurrentLargeCandidateSerializationWaitsBehindHeapGate() throws Exception {
        ObjectMapper mapper = spy(new ObjectMapper().findAndRegisterModules());
        CountDownLatch firstSerializationStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstSerialization = new CountDownLatch(1);
        AtomicBoolean blockFirstSerialization = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (blockFirstSerialization.compareAndSet(true, false)) {
                firstSerializationStarted.countDown();
                assertThat(releaseFirstSerialization.await(2, TimeUnit.SECONDS)).isTrue();
            }
            return invocation.callRealMethod();
        }).when(mapper).writeValueAsBytes(any());

        spool = new RequestLogSpool(mapper,
                tempDir.resolve("large-concurrent.sqlite").toString(),
                16, 16, 2, 2_000, 25,
                64 * 1024 * 1024, 10 * 1024 * 1024);
        spool.start();
        String largeCondition = "x".repeat(1024 * 1024);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> appends = new ArrayList<>();
            appends.add(executor.submit(() -> spool.append(
                    taskWithCandidateCondition("large-1", largeCondition))));
            assertThat(firstSerializationStarted.await(2, TimeUnit.SECONDS)).isTrue();

            for (int i = 0; i < 7; i++) {
                appends.add(executor.submit(() -> spool.append(
                        taskWithCandidateCondition("large-1", largeCondition))));
            }
            awaitSpoolWaiting(spool);
            assertThat(spool.statusSnapshot().inFlightBytes())
                    .isLessThanOrEqualTo(spool.statusSnapshot().inFlightByteLimit());

            releaseFirstSerialization.countDown();
            for (Future<?> append : appends) {
                append.get(10, TimeUnit.SECONDS);
            }
            assertThat(spool.candidateCacheEntries()).isEqualTo(1);
        } finally {
            releaseFirstSerialization.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @Timeout(30)
    void oversizedCandidateSnapshotIsPersistedButNotRetainedInHotCache() {
        spool = createSpoolWithMemory(tempDir.resolve("large-cache.sqlite"),
                64 * 1024 * 1024, 256 * 1024 * 1024);
        spool.start();
        String oversizedDescription = "d".repeat((int) RequestLogSpool.CANDIDATE_CACHE_MAX_BYTES + 1);
        CandidateSnapshot candidate = CandidateSnapshot.builder()
                .ruleId("oversized")
                .endpoint("/oversized")
                .description(oversizedDescription)
                .enabled(true)
                .priority(1)
                .build();
        LogTask task = minimalTask("oversized");
        task = LogTask.builder()
                .ruleId(task.getRuleId()).protocol(task.getProtocol()).endpoint(task.getEndpoint())
                .requestTime(task.getRequestTime()).candidates(List.of(candidate)).build();

        spool.append(task);

        assertThat(spool.candidateCacheEntries()).isZero();
        assertThat(spool.pendingBytes()).isGreaterThan(RequestLogSpool.CANDIDATE_CACHE_MAX_BYTES);
    }

    private RequestLogSpool createSpool(Path path, long maxPendingBytes) {
        return new RequestLogSpool(new ObjectMapper().findAndRegisterModules(),
                path.toString(), 256, 32, 2, 2_000, 25, maxPendingBytes);
    }

    private RequestLogSpool createSpoolWithMemory(
            Path path, long maxPendingBytes, long maxInMemoryBytes) {
        return new RequestLogSpool(new ObjectMapper().findAndRegisterModules(),
                path.toString(), 256, 32, 2, 2_000, 25,
                maxPendingBytes, maxInMemoryBytes);
    }

    private List<RequestLogSpool.SpoolEntry> fillNearCapacity() throws Exception {
        int attempts = 0;
        long target = spool.statusSnapshot().pendingByteLimit() * 3 / 4;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            while (spool.pendingBytes() < target && attempts++ < 100) {
                int index = attempts;
                Future<?> append = executor.submit(() -> spool.append(
                        task("initial-" + index, "/initial/" + index)));
                try {
                    append.get(2, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    // The last payload can be larger than the remaining byte
                    // allowance even when the target watermark was not reached.
                    // Cancel that setup append and retain the already committed rows.
                    append.cancel(true);
                    break;
                }
            }
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(2, TimeUnit.SECONDS);
        }
        return spool.readAfter(0, 1_000);
    }

    private static void awaitSpoolWaiting(RequestLogSpool spool) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (spool.statusSnapshot().waitingProducers() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        RequestLogSpool.StatusSnapshot status = spool.statusSnapshot();
        assertThat(status.waitingProducers())
                .withFailMessage("expected an append waiter, status=%s", status)
                .isPositive();
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
                .forwarded(true)
                .forwardTarget("Primary | https://downstream.example")
                .responseStatus(200)
                .faultType("EMPTY_RESPONSE")
                .scenarioName("order-flow")
                .scenarioFromState("Started")
                .scenarioToState("Paid")
                .requestBody("{\"id\":1}")
                .responseBody("{\"ok\":true}")
                .candidates(List.of(candidate))
                .analysisBody("{\"id\":1}")
                .queryString("trace=1")
                .headers(Map.of("X-Test", "value"))
                .matchOutcomes(Map.of("body:id=1", true))
                .build();
    }

    private LogTask taskWithCandidateCondition(String ruleId, String condition) {
        CandidateSnapshot candidate = CandidateSnapshot.builder()
                .ruleId(ruleId)
                .endpoint("/large")
                .description("candidate")
                .enabled(true)
                .bodyCondition(condition)
                .priority(10)
                .build();
        return LogTask.builder()
                .ruleId(ruleId)
                .protocol(Protocol.HTTP)
                .method("POST")
                .endpoint("/large")
                .matched(true)
                .responseTimeMs(12)
                .requestTime(LocalDateTime.now())
                .candidates(List.of(candidate))
                .build();
    }

    private LogTask minimalTask(String ruleId) {
        return LogTask.builder()
                .ruleId(ruleId)
                .protocol(Protocol.HTTP)
                .endpoint("/minimal")
                .requestTime(LocalDateTime.now())
                .build();
    }
}
