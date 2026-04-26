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
 * LogAgent 屬性測試（Property 7, 8）。
 * <p>
 * Feature: agent-framework-log-agent
 * <p>
 * 直接建構 LogAgent（非 Spring），透過 mock 依賴驗證
 * processBatch 在 database / memory 模式下的寫入行為與 max-records 限制。
 */
class LogAgentPropertyTest {

    // ==================== Helpers ====================

    /**
     * 建立 N 筆 LogTask 測試資料。
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
     * 建立 LogAgent 實例（直接 new，不透過 Spring）。
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
                false  // analysisEnabled — 關閉分析以隔離測試
        );
    }

    // ==================== Property 7 (Database Mode) ====================

    /**
     * Feature: agent-framework-log-agent, Property 7: processBatch writes all entries to correct store
     * <p>
     * Database 模式：N 筆 LogTask 呼叫 processBatch 後，saveAll 被呼叫一次且傳入 N 筆 entity。
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

        // Act — 直接呼叫 package-private processBatch
        agent.processBatch(tasks);

        // Assert — saveAll 被呼叫一次，傳入 batchCount 筆 entity
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RequestLog>> captor = ArgumentCaptor.forClass(List.class);
        verify(repo, times(1)).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(batchCount);
    }

    // ==================== Property 7 (Memory Mode) ====================

    /**
     * Feature: agent-framework-log-agent, Property 7: processBatch writes all entries to correct store
     * <p>
     * Memory 模式：N 筆 LogTask 呼叫 processBatch 後，memory buffer 增加 N 筆。
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

        // Assert — buffer 中有 batchCount 筆 entry
        assertThat(buffer).hasSize(batchCount);
        assertThat(bufferSize.get()).isEqualTo(batchCount);

        // saveAll 不應被呼叫（memory 模式）
        verify(repo, never()).saveAll(any());
    }

    // ==================== Property 8 ====================

    /**
     * Feature: agent-framework-log-agent, Property 8: processBatch enforces max-records in database mode
     * <p>
     * 總數超過 max-records 時呼叫 deleteOldest(count - maxRecords)。
     * <p>
     * **Validates: Requirements 5.4**
     */
    @Property(tries = 20)
    void processBatchEnforcesMaxRecordsInDatabaseMode(
            @ForAll @IntRange(min = 1, max = 30) int batchCount,
            @ForAll @IntRange(min = 10, max = 100) int maxRecords,
            @ForAll @IntRange(min = 1, max = 50) int excess) {

        // Arrange
        long totalAfterSave = (long) maxRecords + excess; // 確保超過 maxRecords

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

        // Assert — deleteOldest 被呼叫，刪除數量 = totalAfterSave - maxRecords = excess
        verify(repo, times(1)).deleteOldest(excess);
    }

    /**
     * Feature: agent-framework-log-agent, Property 8: processBatch enforces max-records in database mode
     * <p>
     * 總數未超過 max-records 時不呼叫 deleteOldest。
     * <p>
     * **Validates: Requirements 5.4**
     */
    @Property(tries = 20)
    void processBatchDoesNotDeleteWhenUnderMaxRecords(
            @ForAll @IntRange(min = 1, max = 30) int batchCount,
            @ForAll @IntRange(min = 10, max = 200) int maxRecords) {

        // Arrange — count <= maxRecords
        long totalAfterSave = maxRecords; // 剛好等於上限，不超過

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

        // Assert — deleteOldest 不應被呼叫
        verify(repo, never()).deleteOldest(anyInt());
    }
}
