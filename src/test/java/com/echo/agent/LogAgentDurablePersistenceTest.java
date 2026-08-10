package com.echo.agent;

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
                .extracting(RequestLog::getEndpoint).isEqualTo("/must-deliver");
        awaitSpoolEmpty();
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
                .responseTimeMs(5)
                .requestTime(LocalDateTime.now())
                .responseStatus(200)
                .build();
    }
}
