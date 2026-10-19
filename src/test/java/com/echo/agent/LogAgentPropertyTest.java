package com.echo.agent;

import com.echo.entity.Protocol;
import com.echo.repository.RequestLogRepository;
import com.echo.service.ConditionMatcher;
import com.echo.service.RequestLogService;
import com.echo.service.SystemConfigService;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import org.mockito.ArgumentCaptor;

import com.echo.entity.RequestLog;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * LogAgent attribute test (Property 7, 8).
 * <p>
 * Feature: agent-framework-log-agent
 * <p>
 * Directly construct LogAgent (not Spring) and verify through mock dependency
 * processBatch write behavior in database / memory mode with max-records limitations.
 */
class LogAgentPropertyTest {

    // ==================== Helpers ====================

    /**
     * Create N LogTask records as test data.
     */
    private List<LogTask> createLogTasks(int count) {
        List<LogTask> tasks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            tasks.add(LogTask.builder()
                    .ruleId("rule-" + i)
                    .protocol(Protocol.HTTP)
                    .method("GET")
                    .endpoint("/api/test/" + i)
                    .matched(true)
                    .responseTimeMs(10 + i)
                    .matchTimeMs(1)
                    .clientIp("127.0.0.1")
                    .requestTime(LocalDateTime.now())
                    .matchChain(null)
                    .build());
        }
        return tasks;
    }

    /**
     * Create LogAgent instance (directly new, not through Spring).
     */
    private LogAgent createLogAgent(
            RequestLogRepository repo,
            SystemConfigService configService,
            ConditionMatcher conditionMatcher,
            RequestLogService requestLogService) {
        return new LogAgent(
                repo,
                configService,
                conditionMatcher,
                requestLogService,
                500,   // queueCapacity
                50,    // batchSize
                5,     // flushIntervalSeconds
                false  // analysisEnabled - Turn off analysis to isolate test
        );
    }

    // ==================== Property 7 (Database Mode) ====================

    /**
     * Feature: agent-framework-log-agent, Property 7: processBatch writes all entries to correct store
     * <p>
     * Database mode: processing N LogTask records calls saveAll once with N entities.
     * <p>
     * **Validates: Requirements 5.2, 5.3**
     */
    @Property(tries = 20)
    void processBatchDatabaseModeWritesAllEntries(
            @ForAll @IntRange(min = 1, max = 50) int batchCount) {

        // Arrange
        RequestLogRepository repo = mock(RequestLogRepository.class);
        SystemConfigService configService = mock(SystemConfigService.class);
        ConditionMatcher conditionMatcher = mock(ConditionMatcher.class);
        RequestLogService requestLogService = mock(RequestLogService.class);

        when(configService.isRequestLogMemoryMode()).thenReturn(false);
        when(configService.getRequestLogMaxRecords()).thenReturn(10000);
        when(repo.count()).thenReturn((long) batchCount);

        LogAgent agent = createLogAgent(repo, configService, conditionMatcher, requestLogService);

        List<LogTask> tasks = createLogTasks(batchCount);

        // Act — Direct call package-private processBatch
        agent.processBatch(tasks);

        // Assert: saveAll was called once with batchCount entities.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RequestLog>> captor = ArgumentCaptor.forClass(List.class);
        verify(repo, times(1)).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(batchCount);
    }

    // ==================== Property 7 (Memory Mode) ====================

    /**
     * Feature: agent-framework-log-agent, Property 7: processBatch writes all entries to correct store
     * <p>
     * Memory mode: processing N LogTask records adds N entries to the memory buffer.
     * <p>
     * **Validates: Requirements 5.2, 5.3**
     */
    @Property(tries = 20)
    void processBatchMemoryModeWritesAllEntries(
            @ForAll @IntRange(min = 1, max = 50) int batchCount) {

        // Arrange
        RequestLogRepository repo = mock(RequestLogRepository.class);
        SystemConfigService configService = mock(SystemConfigService.class);
        ConditionMatcher conditionMatcher = mock(ConditionMatcher.class);
        RequestLogService requestLogService = mock(RequestLogService.class);

        ConcurrentLinkedDeque<RequestLogService.LogEntry> buffer = new ConcurrentLinkedDeque<>();
        AtomicInteger bufferSize = new AtomicInteger(0);

        when(configService.isRequestLogMemoryMode()).thenReturn(true);
        when(configService.getRequestLogMaxRecords()).thenReturn(10000);
        when(requestLogService.getMemoryBuffer()).thenReturn(buffer);
        when(requestLogService.getBufferSize()).thenReturn(bufferSize);

        LogAgent agent = createLogAgent(repo, configService, conditionMatcher, requestLogService);

        List<LogTask> tasks = createLogTasks(batchCount);

        // Act
        agent.processBatch(tasks);

        // Assert: the buffer contains batchCount entries.
        assertThat(buffer).hasSize(batchCount);
        assertThat(bufferSize.get()).isEqualTo(batchCount);

        // saveAll should not be called (memory mode)
        verify(repo, never()).saveAll(any());
    }

    // ==================== Property 8 ====================

    /**
     * Feature: agent-framework-log-agent, Property 8: processBatch enforces max-records in database mode
     * <p>
     * Call deleteOldest(count - maxRecords) when the total exceeds max-records.
     * <p>
     * **Validates: Requirements 5.4**
     */
    @Property(tries = 20)
    void processBatchEnforcesMaxRecordsInDatabaseMode(
            @ForAll @IntRange(min = 1, max = 30) int batchCount,
            @ForAll @IntRange(min = 10, max = 100) int maxRecords,
            @ForAll @IntRange(min = 1, max = 50) int excess) {

        // Arrange
        long totalAfterSave = (long) maxRecords + excess; // Ensure more than maxRecords

        RequestLogRepository repo = mock(RequestLogRepository.class);
        SystemConfigService configService = mock(SystemConfigService.class);
        ConditionMatcher conditionMatcher = mock(ConditionMatcher.class);
        RequestLogService requestLogService = mock(RequestLogService.class);

        when(configService.isRequestLogMemoryMode()).thenReturn(false);
        when(configService.getRequestLogMaxRecords()).thenReturn(maxRecords);
        when(repo.count()).thenReturn(totalAfterSave);

        LogAgent agent = createLogAgent(repo, configService, conditionMatcher, requestLogService);

        List<LogTask> tasks = createLogTasks(batchCount);

        // Act
        agent.processBatch(tasks);

        // Assert — deleteOldest is called, delete quantity = totalAfterSave - maxRecords = excess
        verify(repo, times(1)).deleteOldest(excess);
    }

    /**
     * Feature: agent-framework-log-agent, Property 8: processBatch enforces max-records in database mode
     * <p>
     * Do not call deleteOldest when the total number does not exceed max-records.
     * <p>
     * **Validates: Requirements 5.4**
     */
    @Property(tries = 20)
    void processBatchDoesNotDeleteWhenUnderMaxRecords(
            @ForAll @IntRange(min = 1, max = 30) int batchCount,
            @ForAll @IntRange(min = 10, max = 200) int maxRecords) {

        // Arrange — count <= maxRecords
        long totalAfterSave = maxRecords; // Just equal to the upper limit, not exceeding

        RequestLogRepository repo = mock(RequestLogRepository.class);
        SystemConfigService configService = mock(SystemConfigService.class);
        ConditionMatcher conditionMatcher = mock(ConditionMatcher.class);
        RequestLogService requestLogService = mock(RequestLogService.class);

        when(configService.isRequestLogMemoryMode()).thenReturn(false);
        when(configService.getRequestLogMaxRecords()).thenReturn(maxRecords);
        when(repo.count()).thenReturn(totalAfterSave);

        LogAgent agent = createLogAgent(repo, configService, conditionMatcher, requestLogService);

        List<LogTask> tasks = createLogTasks(batchCount);

        // Act
        agent.processBatch(tasks);

        // Assert — deleteOldest should not be called
        verify(repo, never()).deleteOldest(anyInt());
    }
}
