package com.echo.agent;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import com.echo.entity.Protocol;
import com.echo.entity.RequestLog;
import com.echo.repository.RequestLogRepository;
import com.echo.service.ConditionMatcher;
import com.echo.service.RequestLogBatchWriter;
import com.echo.service.RequestLogService;
import com.echo.service.SystemConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LogAgentDurablePersistenceTest {

    @TempDir
    Path tempDir;

    private RequestLogSpool spool;
    private LogAgent agent;
    private RequestLogBatchWriter batchWriter;
    private SystemConfigService configService;

    @BeforeEach
    void setUp() {
        spool = new RequestLogSpool(new ObjectMapper().findAndRegisterModules(),
                tempDir.resolve("spool.sqlite").toString(), 128, 16, 1,
                2_000, 25, 64 * 1024 * 1024);
        spool.start();
        batchWriter = mock(RequestLogBatchWriter.class);
        configService = mock(SystemConfigService.class);
        when(configService.isRequestLogMemoryMode()).thenReturn(false);
        when(configService.getRequestLogMaxRecords()).thenReturn(1_000);
    }

    @AfterEach
    void tearDown() {
        if (agent != null) {
            agent.destroy();
        }
        if (spool != null) {
            spool.stop();
        }
    }

    @Test
    void corruptHeadStopsConsumerAndReportsHealthWithoutDeletingRetainedRows() throws Exception {
        when(batchWriter.findCheckpoint(anyString())).thenReturn(0L);
        spool.append(task("one", "/one"));
        spool.append(task("two", "/two"));
        long firstSequence = spool.readAfter(0, 1).get(0).sequence();
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("spool.sqlite"));
             var update = connection.prepareStatement("UPDATE request_log_spool SET payload = ? WHERE sequence_id = ?")) {
            update.setBytes(1, new byte[]{'{'});
            update.setLong(2, firstSequence);
            update.executeUpdate();
        }
        startAgent();
        org.awaitility.Awaitility.await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(agent.isDurableConsumerRunning()).isFalse();
            assertThat(agent.getStats().getStoppedAtSequence()).isEqualTo(firstSequence);
        });
        var factory = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        factory.addBean("logAgent", agent);
        var indicator = new com.echo.service.RequestLogHealthIndicator(configService,
                factory.getBeanProvider(LogAgent.class));
        assertThat(indicator.health().getStatus()).isEqualTo(org.springframework.boot.actuate.health.Status.DOWN);
        assertThat(spool.countBetween(0, Long.MAX_VALUE)).isEqualTo(2);
        org.mockito.Mockito.verify(batchWriter, org.mockito.Mockito.never())
                .persist(anyString(), anyLong(), anyList(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void byteLimitedBacklogShedsAnalysisForThatBatchAndResumesAtTheTail() throws Exception {
        when(batchWriter.findCheckpoint(anyString())).thenReturn(0L);
        var matcher = org.mockito.Mockito.spy(new ConditionMatcher());
        var candidates = List.of(CandidateSnapshot.builder().ruleId("rule").endpoint("/body")
                .enabled(true).bodyCondition("n=1").build());
        String body = "{\"n\":1,\"padding\":\"" + "x".repeat(2048) + "\"}";
        for (int i = 0; i < 60; i++) {
            spool.append(LogTask.builder().ruleId("rule").endpoint("/" + i)
                    .protocol(i % 2 == 0 ? Protocol.HTTP : Protocol.JMS)
                    .requestTime(LocalDateTime.now()).responseStatus(200).matched(true)
                    .requestBody(body).responseBody("ok").analysisBody(body)
                    .candidates(candidates).matchChain("[]").build());
        }
        long first31Bytes;
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("spool.sqlite"));
             var query = connection.createStatement();
             var rows = query.executeQuery("SELECT sum(bytes) FROM (SELECT length(s.payload) + length(c.payload) AS bytes "
                     + "FROM request_log_spool s JOIN candidate_snapshot_sets c ON c.candidate_set_id=s.candidate_set_id "
                     + "ORDER BY s.sequence_id LIMIT 31)")) {
            assertThat(rows.next()).isTrue();
            first31Bytes = rows.getLong(1);
        }
        org.springframework.test.util.ReflectionTestUtils.setField(spool, "maxInMemoryBytes", first31Bytes);
        List<RequestLog> persisted = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<Integer> batches = new java.util.concurrent.CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        doAnswer(invocation -> {
            List<RequestLog> logs = invocation.getArgument(2);
            persisted.addAll(logs);
            batches.add(logs.size());
            if (persisted.size() == 60) done.countDown();
            return null;
        }).when(batchWriter).persist(anyString(), anyLong(), anyList(), eq(1_000));
        agent = new LogAgent(mock(RequestLogRepository.class), configService, matcher,
                mock(RequestLogService.class), spool, batchWriter, 128, 50, 1, true, 10_000);
        agent.init();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(batches).containsExactly(31, 29);
        verify(matcher, times(29)).prepareBody(anyString(), org.mockito.ArgumentMatchers.anyMap());
        assertThat(persisted).hasSize(60).allSatisfy(row -> {
            assertThat(row.getRequestBody()).isEqualTo(body);
            assertThat(row.getResponseBody()).isEqualTo("ok");
            assertThat(row.getResponseStatus()).isEqualTo(200);
        });
        assertThat(persisted.subList(0, 31)).allSatisfy(row -> assertThat(row.getMatchChain()).isEqualTo("[]"));
        assertThat(persisted).extracting(RequestLog::getEndpoint).doesNotHaveDuplicates();
        awaitSpoolEmpty();
    }

    @Test
    void mainDatabaseFailureRetriesWithoutDeletingSpoolRow() throws Exception {
        when(batchWriter.findCheckpoint(anyString())).thenReturn(0L);
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch delivered = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("main database unavailable");
            }
            delivered.countDown();
            return null;
        }).when(batchWriter).persist(anyString(), anyLong(), anyList(), eq(1_000));
        startAgent();

        agent.submitDurably(() -> task("rule-1", "/retry"));

        assertThat(delivered.await(5, TimeUnit.SECONDS)).isTrue();
        verify(batchWriter, times(2))
                .persist(anyString(), anyLong(), anyList(), eq(1_000));
        awaitSpoolEmpty();
        assertThat(agent.getStats().getProcessedCount()).isEqualTo(1);
    }

    @Test
    void persistenceWorkerSurvivesAnErrorAndRetries() throws Exception {
        when(batchWriter.findCheckpoint(anyString())).thenReturn(0L);
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch delivered = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (attempts.incrementAndGet() == 1) {
                throw new OutOfMemoryError("simulated");
            }
            delivered.countDown();
            return null;
        }).when(batchWriter).persist(anyString(), anyLong(), anyList(), eq(1_000));
        startAgent();

        agent.submitDurably(() -> task("rule-1", "/after-error"));

        // Occurred when writing to the main database for the first time Error: The work thread did not end, the data was left in the temporary file and the retry was successful.
        assertThat(delivered.await(5, TimeUnit.SECONDS)).isTrue();
        awaitSpoolEmpty();
        assertThat(agent.getStats().getProcessedCount()).isEqualTo(1);
    }

    @Test
    void committedCheckpointSkipsAlreadyDeliveredRowAfterRestart() throws Exception {
        spool.append(task("rule-1", "/already-committed"));
        spool.append(task("rule-2", "/must-deliver"));
        List<RequestLogSpool.SpoolEntry> stored = spool.readAfter(0, 10);
        long committedSequence = stored.get(0).sequence();
        when(batchWriter.findCheckpoint(spool.getSpoolId())).thenReturn(committedSequence);
        CountDownLatch delivered = new CountDownLatch(1);
        doAnswer(invocation -> {
            delivered.countDown();
            return null;
        }).when(batchWriter).persist(anyString(), anyLong(), anyList(), eq(1_000));
        startAgent();

        assertThat(delivered.await(5, TimeUnit.SECONDS)).isTrue();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RequestLog>> logs = ArgumentCaptor.forClass(List.class);
        verify(batchWriter).persist(eq(spool.getSpoolId()),
                eq(stored.get(1).sequence()), logs.capture(), eq(1_000));

        assertThat(logs.getValue()).singleElement()
                .satisfies(log -> {
                    assertThat(log.getEndpoint()).isEqualTo("/must-deliver");
                    assertThat(log.isForwarded()).isTrue();
                    assertThat(log.getForwardTarget())
                            .isEqualTo("Primary HTTP | https://downstream.example");
                });
        awaitSpoolEmpty();
    }

    @Test
    void rowRejectedByTheDatabaseIsSkippedWithoutBlockingTheOthers() throws Exception {
        when(batchWriter.findCheckpoint(anyString())).thenReturn(0L);
        List<String> persisted = new java.util.concurrent.CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        doAnswer(invocation -> {
            List<RequestLog> logs = invocation.getArgument(2);
            if (logs.stream().anyMatch(log -> "/rejected".equals(log.getEndpoint()))) {
                throw new org.springframework.dao.DataIntegrityViolationException("value too long");
            }
            logs.forEach(log -> persisted.add(log.getEndpoint()));
            if (persisted.contains("/after")) {
                done.countDown();
            }
            return null;
        }).when(batchWriter).persist(anyString(), anyLong(), anyList(), eq(1_000));
        spool.append(task("rule-1", "/before"));
        spool.append(task("rule-2", "/rejected"));
        spool.append(task("rule-3", "/after"));
        startAgent();

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(persisted).containsExactly("/before", "/after");
        awaitSpoolEmpty();
        assertThat(agent.getStats().getDroppedCount()).isEqualTo(1);
        assertThat(agent.getStats().getOtherDroppedCount()).isEqualTo(1);
        assertThat(agent.getStats().getRetentionSkippedCount()).isZero();
    }

    @Test
    void unavailableDatabaseNeverSkipsRows() throws Exception {
        when(batchWriter.findCheckpoint(anyString())).thenReturn(0L);
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch delivered = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (attempts.incrementAndGet() <= 2) {
                throw new org.springframework.dao.DataAccessResourceFailureException("database is locked");
            }
            List<RequestLog> logs = invocation.getArgument(2);
            if (logs.size() == 1) {
                delivered.countDown();
            }
            return null;
        }).when(batchWriter).persist(anyString(), anyLong(), anyList(), eq(1_000));
        startAgent();

        agent.submitDurably(() -> task("rule-1", "/kept"));

        assertThat(delivered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(agent.getStats().getDroppedCount()).isZero();
        assertThat(agent.getStats().getStorageRetryCount()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void backlogBeyondTheRecordLimitSkipsOnlyEntriesRetentionWouldDelete() throws Exception {
        when(configService.getRequestLogMaxRecords()).thenReturn(5);
        when(batchWriter.findCheckpoint(anyString())).thenReturn(0L);
        List<String> persisted = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<Long> emptyCheckpoints = new java.util.concurrent.CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        doAnswer(invocation -> {
            List<RequestLog> logs = invocation.getArgument(2);
            if (logs.isEmpty()) {
                emptyCheckpoints.add(invocation.getArgument(1));
            }
            logs.forEach(log -> persisted.add(log.getEndpoint()));
            if (persisted.contains("/12")) {
                done.countDown();
            }
            return null;
        }).when(batchWriter).persist(anyString(), anyLong(), anyList(), eq(5));
        for (int i = 1; i <= 12; i++) {
            spool.append(task("rule-" + i, "/" + i));
        }
        long seventh = spool.readAfter(0, 100).get(6).sequence();
        startAgent();

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        // Only the latest 5 transactions are kept in the main database: the older 7 transactions are skipped directly, and the progress is advanced in the same transaction.
        assertThat(persisted).containsExactly("/8", "/9", "/10", "/11", "/12");
        assertThat(emptyCheckpoints).containsExactly(seventh);
        awaitSpoolEmpty();
        assertThat(agent.getStats().getDroppedCount()).isEqualTo(7);
        assertThat(agent.getStats().getRetentionSkippedCount()).isEqualTo(7);
        assertThat(agent.getStats().getBufferOverflowDroppedCount()).isZero();
        assertThat(agent.getStats().getOtherDroppedCount()).isZero();
    }

    @Test
    void skipWarningsAddUpToEverySkippedEntryIncludingTheLastBurst() throws Exception {
        when(configService.getRequestLogMaxRecords()).thenReturn(5);
        when(batchWriter.findCheckpoint(anyString())).thenReturn(0L);
        List<String> persisted = new java.util.concurrent.CopyOnWriteArrayList<>();
        CountDownLatch firstBatchStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstBatch = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        doAnswer(invocation -> {
            List<RequestLog> logs = invocation.getArgument(2);
            if (!logs.isEmpty() && firstBatchStarted.getCount() > 0) {
                // The first batch of writes is stuck, allowing the second wave of records to be skipped again in the same 30 second interval
                firstBatchStarted.countDown();
                assertThat(releaseFirstBatch.await(5, TimeUnit.SECONDS)).isTrue();
            }
            logs.forEach(log -> persisted.add(log.getEndpoint()));
            if (persisted.contains("/21")) {
                done.countDown();
            }
            return null;
        }).when(batchWriter).persist(anyString(), anyLong(), anyList(), eq(5));
        Logger logger = (Logger) LoggerFactory.getLogger(LogAgent.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            for (int i = 1; i <= 12; i++) {
                spool.append(task("rule-" + i, "/" + i));
            }
            startAgent();
            assertThat(firstBatchStarted.await(5, TimeUnit.SECONDS)).isTrue();
            for (int i = 13; i <= 21; i++) {
                spool.append(task("rule-" + i, "/" + i));
            }
            Thread.sleep(1_200); // Next write pending check (once per second)
            releaseFirstBatch.countDown();

            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            // The first wave skips 7 records; the second retains only the newest 5 from /13 through /21, skipping 4.
            assertThat(persisted).containsExactly("/8", "/9", "/10", "/11", "/12", "/17", "/18", "/19", "/20", "/21");
            assertThat(agent.getStats().getDroppedCount()).isEqualTo(11);
            agent.destroy();
            agent = null;

            // The second wave occurs within 30 seconds and emits no new warning; flush its count on shutdown so warning totals equal the actual skipped count.
            List<ILoggingEvent> warnings = appender.list.stream()
                    .filter(event -> event.getFormattedMessage().startsWith("Request logs arrive faster"))
                    .toList();
            assertThat(warnings).extracting(event -> event.getArgumentArray()[0]).containsExactly(7L, 4L);
            assertThat(warnings).extracting(event -> event.getArgumentArray()[1]).containsOnly(5);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void backlogWithinTheRecordLimitSkipsNothing() throws Exception {
        when(batchWriter.findCheckpoint(anyString())).thenReturn(0L);
        List<String> persisted = new java.util.concurrent.CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        doAnswer(invocation -> {
            List<RequestLog> logs = invocation.getArgument(2);
            logs.forEach(log -> persisted.add(log.getEndpoint()));
            if (persisted.size() == 12) {
                done.countDown();
            }
            return null;
        }).when(batchWriter).persist(anyString(), anyLong(), anyList(), eq(1_000));
        for (int i = 1; i <= 12; i++) {
            spool.append(task("rule-" + i, "/" + i));
        }
        startAgent();

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(persisted).hasSize(12).startsWith("/1", "/2").endsWith("/12");
        assertThat(agent.getStats().getDroppedCount()).isZero();
    }

    @Test
    void failedSkipFallsBackToNormalWritingWithoutLosingEntries() throws Exception {
        when(configService.getRequestLogMaxRecords()).thenReturn(3);
        when(batchWriter.findCheckpoint(anyString())).thenReturn(0L);
        AtomicInteger skipAttempts = new AtomicInteger();
        List<String> persisted = new java.util.concurrent.CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        doAnswer(invocation -> {
            List<RequestLog> logs = invocation.getArgument(2);
            if (logs.isEmpty() && skipAttempts.incrementAndGet() == 1) {
                throw new org.springframework.dao.DataAccessResourceFailureException("database is locked");
            }
            logs.forEach(log -> persisted.add(log.getEndpoint()));
            if (persisted.contains("/6")) {
                done.countDown();
            }
            return null;
        }).when(batchWriter).persist(anyString(), anyLong(), anyList(), eq(3));
        for (int i = 1; i <= 6; i++) {
            spool.append(task("rule-" + i, "/" + i));
        }
        startAgent();

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        awaitSpoolEmpty();
        // If checkpoint advancement fails, delete nothing and write normally: every record is either written or counted as skipped, without loss or duplication.
        assertThat(skipAttempts.get()).isGreaterThanOrEqualTo(1);
        assertThat(persisted).doesNotHaveDuplicates().endsWith("/4", "/5", "/6");
        assertThat(persisted.size() + agent.getStats().getDroppedCount()).isEqualTo(6);
    }

    @Test
    void withoutWaitingForWritesEverySubmittedLogStillReachesTheDatabaseInOrder() throws Exception {
        when(batchWriter.findCheckpoint(anyString())).thenReturn(0L);
        List<String> persisted = new java.util.concurrent.CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        doAnswer(invocation -> {
            List<RequestLog> logs = invocation.getArgument(2);
            logs.forEach(log -> persisted.add(log.getEndpoint()));
            if (persisted.size() == 200) {
                done.countDown();
            }
            return null;
        }).when(batchWriter).persist(anyString(), anyLong(), anyList(), eq(1_000));
        agent = new LogAgent(mock(RequestLogRepository.class), configService,
                mock(ConditionMatcher.class), mock(RequestLogService.class),
                spool, batchWriter, 32, 8, 1, false, 10_000,
                new LogAgent.BufferLimits(1_000, 64L * 1024 * 1024));
        agent.init();

        for (int i = 1; i <= 200; i++) {
            int index = i;
            agent.submitDurably(() -> task("rule-" + index, "/" + index));
        }

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(persisted).hasSize(200).startsWith("/1", "/2").endsWith("/200").doesNotHaveDuplicates();
        assertThat(agent.getStats().getDroppedCount()).isZero();
        // The memory limit of the temporary storage area is included in the monitored memory limit.
        assertThat(agent.getStats().getInFlightByteLimit())
                .isEqualTo(spool.statusSnapshot().inFlightByteLimit() + 64L * 1024 * 1024);
    }

    @Test
    void withoutWaitingForWritesAFailingLogNeverFailsTheRequest() {
        agent = new LogAgent(mock(RequestLogRepository.class), configService,
                mock(ConditionMatcher.class), mock(RequestLogService.class),
                spool, batchWriter, 32, 8, 1, false, 10_000,
                new LogAgent.BufferLimits(1_000, 64L * 1024 * 1024));
        agent.init();

        agent.submitDurably(() -> {
            throw new IllegalStateException("cannot build the log entry");
        });
        agent.destroy();
        // Records sent after stopping will only be counted as discarded, and the request will not fail.
        agent.submitDurably(() -> task("rule-late", "/late"));

        assertThat(agent.getStats().getDroppedCount()).isEqualTo(2);
        agent = null;
    }

    private void startAgent() {
        agent = new LogAgent(mock(RequestLogRepository.class), configService,
                mock(ConditionMatcher.class), mock(RequestLogService.class),
                spool, batchWriter, 32, 8, 1, false, 10_000);
        agent.init();
    }

    private void awaitSpoolEmpty() {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline && !spool.readAfter(0, 1).isEmpty()) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while awaiting spool cleanup", e);
            }
        }
        assertThat(spool.readAfter(0, 1)).isEmpty();
    }

    private LogTask task(String ruleId, String endpoint) {
        return LogTask.builder()
                .ruleId(ruleId)
                .protocol(Protocol.HTTP)
                .method("GET")
                .endpoint(endpoint)
                .matched(true)
                .forwarded(true)
                .forwardTarget("Primary HTTP | https://downstream.example")
                .responseTimeMs(5)
                .requestTime(LocalDateTime.now())
                .responseStatus(200)
                .build();
    }
}
