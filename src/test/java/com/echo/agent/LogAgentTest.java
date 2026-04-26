package com.echo.agent;

import com.echo.entity.Protocol;
import com.echo.entity.RequestLog;
import com.echo.repository.RequestLogRepository;
import com.echo.service.ConditionMatcher;
import com.echo.service.RequestLogService;
import com.echo.service.SystemConfigService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * LogAgent 單元測試 — database/memory mode processBatch、analysis enabled/disabled、例外處理。
 * <p>
 * 直接建構 LogAgent（非 Spring），透過 mock 依賴驗證行為。
 * <p>
 * Validates: Requirements 5.2, 5.3, 5.4, 7.1, 7.4
 */
@ExtendWith(MockitoExtension.class)
class LogAgentTest {

    @Mock
    private RequestLogRepository requestLogRepository;

    @Mock
    private SystemConfigService configService;

    @Mock
    private ConditionMatcher conditionMatcher;

    @Mock
    private RequestLogService requestLogService;

    // ==================== Helpers ====================

    private LogAgent createAgent(boolean analysisEnabled) {
        return new LogAgent(
                requestLogRepository,
                configService,
                conditionMatcher,
                requestLogService,
                500,   // queueCapacity
                50,    // batchSize
                5,     // flushIntervalSeconds
                analysisEnabled
        );
    }

    private List<LogTask> createTasks(int count) {
        List<LogTask> tasks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            tasks.add(LogTask.builder()
                    .ruleId("rule-" + i)
                    .protocol(Protocol.HTTP)
                    .method("GET")
                    .endpoint("/api/test/" + i)
                    .matched(i == 0)
                    .responseTimeMs(10 + i)
                    .matchTimeMs(2)
                    .clientIp("192.168.1.1")
                    .requestTime(LocalDateTime.of(2026, 1, 15, 10, 30, i))
                    .matchChain("[{\"ruleId\":\"rule-" + i + "\"}]")
                    .targetHost("localhost")
                    .responseStatus(200)
                    .requestBody("{\"key\":\"value\"}")
                    .responseBody("{\"result\":\"ok\"}")
                    .build());
        }
        return tasks;
    }

    // ==================== Database Mode Tests (Req 5.2, 5.4) ====================

    @Test
    void processBatch_databaseMode_shouldSaveAllEntities() {
        // Arrange
        when(configService.isRequestLogMemoryMode()).thenReturn(false);
        when(configService.getRequestLogMaxRecords()).thenReturn(10000);
        when(requestLogRepository.count()).thenReturn(5L);

        LogAgent agent = createAgent(false);
        List<LogTask> tasks = createTasks(3);

        // Act
        agent.processBatch(tasks);

        // Assert — saveAll called once with 3 entities
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RequestLog>> captor = ArgumentCaptor.forClass(List.class);
        verify(requestLogRepository, times(1)).saveAll(captor.capture());
        List<RequestLog> saved = captor.getValue();
        assertThat(saved).hasSize(3);

        // Verify field mapping
        assertThat(saved.get(0).getRuleId()).isEqualTo("rule-0");
        assertThat(saved.get(0).getProtocol()).isEqualTo(Protocol.HTTP);
        assertThat(saved.get(0).getMethod()).isEqualTo("GET");
        assertThat(saved.get(0).getEndpoint()).isEqualTo("/api/test/0");
        assertThat(saved.get(0).isMatched()).isTrue();
        assertThat(saved.get(0).getResponseTimeMs()).isEqualTo(10);
        assertThat(saved.get(0).getClientIp()).isEqualTo("192.168.1.1");
        assertThat(saved.get(0).getResponseStatus()).isEqualTo(200);
    }

    @Test
    void processBatch_databaseMode_shouldDeleteOldestWhenExceedingMaxRecords() {
        // Arrange — after save, count = 150, maxRecords = 100 → delete 50
        when(configService.isRequestLogMemoryMode()).thenReturn(false);
        when(configService.getRequestLogMaxRecords()).thenReturn(100);
        when(requestLogRepository.count()).thenReturn(150L);

        LogAgent agent = createAgent(false);
        List<LogTask> tasks = createTasks(2);

        // Act
        agent.processBatch(tasks);

        // Assert
        verify(requestLogRepository).saveAll(any());
        verify(requestLogRepository).deleteOldest(50);
    }

    @Test
    void processBatch_databaseMode_shouldNotDeleteWhenUnderMaxRecords() {
        // Arrange — count = 80, maxRecords = 100 → no delete
        when(configService.isRequestLogMemoryMode()).thenReturn(false);
        when(configService.getRequestLogMaxRecords()).thenReturn(100);
        when(requestLogRepository.count()).thenReturn(80L);

        LogAgent agent = createAgent(false);
        List<LogTask> tasks = createTasks(2);

        // Act
        agent.processBatch(tasks);

        // Assert
        verify(requestLogRepository).saveAll(any());
        verify(requestLogRepository, never()).deleteOldest(anyInt());
    }

    @Test
    void processBatch_databaseMode_shouldNotDeleteWhenExactlyAtMaxRecords() {
        // Arrange — count = 100, maxRecords = 100 → no delete
        when(configService.isRequestLogMemoryMode()).thenReturn(false);
        when(configService.getRequestLogMaxRecords()).thenReturn(100);
        when(requestLogRepository.count()).thenReturn(100L);

        LogAgent agent = createAgent(false);
        List<LogTask> tasks = createTasks(1);

        // Act
        agent.processBatch(tasks);

        // Assert
        verify(requestLogRepository, never()).deleteOldest(anyInt());
    }

    // ==================== Memory Mode Tests (Req 5.3) ====================

    @Test
    void processBatch_memoryMode_shouldWriteToBuffer() {
        // Arrange
        ConcurrentLinkedDeque<RequestLogService.LogEntry> buffer = new ConcurrentLinkedDeque<>();
        AtomicInteger bufferSize = new AtomicInteger(0);

        when(configService.isRequestLogMemoryMode()).thenReturn(true);
        when(configService.getRequestLogMaxRecords()).thenReturn(10000);
        when(requestLogService.getMemoryBuffer()).thenReturn(buffer);
        when(requestLogService.getBufferSize()).thenReturn(bufferSize);

        LogAgent agent = createAgent(false);
        List<LogTask> tasks = createTasks(3);

        // Act
        agent.processBatch(tasks);

        // Assert — buffer has 3 entries, saveAll not called
        assertThat(buffer).hasSize(3);
        assertThat(bufferSize.get()).isEqualTo(3);
        verify(requestLogRepository, never()).saveAll(any());
    }

    @Test
    void processBatch_memoryMode_shouldTrimBufferWhenExceedingMaxRecords() {
        // Arrange — maxRecords = 2, submit 3 tasks → buffer trimmed to 2
        ConcurrentLinkedDeque<RequestLogService.LogEntry> buffer = new ConcurrentLinkedDeque<>();
        AtomicInteger bufferSize = new AtomicInteger(0);

        when(configService.isRequestLogMemoryMode()).thenReturn(true);
        when(configService.getRequestLogMaxRecords()).thenReturn(2);
        when(requestLogService.getMemoryBuffer()).thenReturn(buffer);
        when(requestLogService.getBufferSize()).thenReturn(bufferSize);

        LogAgent agent = createAgent(false);
        List<LogTask> tasks = createTasks(3);

        // Act
        agent.processBatch(tasks);

        // Assert — buffer trimmed to maxRecords
        assertThat(buffer).hasSize(2);
        assertThat(bufferSize.get()).isEqualTo(2);
    }

    @Test
    void processBatch_memoryMode_shouldNotCallSaveAll() {
        // Arrange
        ConcurrentLinkedDeque<RequestLogService.LogEntry> buffer = new ConcurrentLinkedDeque<>();
        AtomicInteger bufferSize = new AtomicInteger(0);

        when(configService.isRequestLogMemoryMode()).thenReturn(true);
        when(configService.getRequestLogMaxRecords()).thenReturn(10000);
        when(requestLogService.getMemoryBuffer()).thenReturn(buffer);
        when(requestLogService.getBufferSize()).thenReturn(bufferSize);

        LogAgent agent = createAgent(false);
        List<LogTask> tasks = createTasks(1);

        // Act
        agent.processBatch(tasks);

        // Assert
        verify(requestLogRepository, never()).saveAll(any());
        verify(requestLogRepository, never()).deleteOldest(anyInt());
    }

    @Test
    void processBatch_memoryMode_shouldHandleNullBuffer() {
        // Arrange — buffer is null (edge case)
        when(configService.isRequestLogMemoryMode()).thenReturn(true);
        when(requestLogService.getMemoryBuffer()).thenReturn(null);

        LogAgent agent = createAgent(false);
        List<LogTask> tasks = createTasks(2);

        // Act — should not throw
        agent.processBatch(tasks);

        // Assert — no crash, no saveAll
        verify(requestLogRepository, never()).saveAll(any());
    }

    @Test
    void processBatch_memoryMode_bufferEntriesHaveCorrectFields() {
        // Arrange
        ConcurrentLinkedDeque<RequestLogService.LogEntry> buffer = new ConcurrentLinkedDeque<>();
        AtomicInteger bufferSize = new AtomicInteger(0);

        when(configService.isRequestLogMemoryMode()).thenReturn(true);
        when(configService.getRequestLogMaxRecords()).thenReturn(10000);
        when(requestLogService.getMemoryBuffer()).thenReturn(buffer);
        when(requestLogService.getBufferSize()).thenReturn(bufferSize);

        LogAgent agent = createAgent(false);
        List<LogTask> tasks = createTasks(1);

        // Act
        agent.processBatch(tasks);

        // Assert — verify field mapping in LogEntry
        RequestLogService.LogEntry entry = buffer.getFirst();
        assertThat(entry.getRuleId()).isEqualTo("rule-0");
        assertThat(entry.getProtocol()).isEqualTo(Protocol.HTTP);
        assertThat(entry.getMethod()).isEqualTo("GET");
        assertThat(entry.getEndpoint()).isEqualTo("/api/test/0");
        assertThat(entry.isMatched()).isTrue();
        assertThat(entry.getResponseTimeMs()).isEqualTo(10);
        assertThat(entry.getClientIp()).isEqualTo("192.168.1.1");
        assertThat(entry.getResponseStatus()).isEqualTo(200);
        assertThat(entry.getTargetHost()).isEqualTo("localhost");
    }

    // ==================== Analysis Enabled/Disabled Tests (Req 7.1, 7.4) ====================

    @Test
    void analyzeMatchChain_analysisDisabled_shouldReturnOriginalMatchChain() {
        // Arrange
        LogAgent agent = createAgent(false);
        LogTask task = LogTask.builder()
                .ruleId("rule-1")
                .protocol(Protocol.HTTP)
                .method("GET")
                .endpoint("/api/test")
                .matched(true)
                .responseTimeMs(10)
                .requestTime(LocalDateTime.now())
                .matchChain("[{\"ruleId\":\"rule-1\",\"reason\":\"match\"}]")
                .build();

        // Act
        String result = agent.analyzeMatchChain(task);

        // Assert — returns original matchChain unchanged
        assertThat(result).isEqualTo("[{\"ruleId\":\"rule-1\",\"reason\":\"match\"}]");
    }

    @Test
    void analyzeMatchChain_analysisEnabled_shouldReturnMatchChain() {
        // Arrange — analysis enabled, but current implementation is placeholder (TODO: Task 7.2)
        LogAgent agent = createAgent(true);
        LogTask task = LogTask.builder()
                .ruleId("rule-1")
                .protocol(Protocol.HTTP)
                .method("GET")
                .endpoint("/api/test")
                .matched(true)
                .responseTimeMs(10)
                .requestTime(LocalDateTime.now())
                .matchChain("[{\"ruleId\":\"rule-1\"}]")
                .build();

        // Act
        String result = agent.analyzeMatchChain(task);

        // Assert — placeholder returns original matchChain (will change in Task 7.2)
        assertThat(result).isNotNull();
    }

    @Test
    void processBatch_databaseMode_analysisDisabled_shouldUseOriginalMatchChain() {
        // Arrange
        when(configService.isRequestLogMemoryMode()).thenReturn(false);
        when(configService.getRequestLogMaxRecords()).thenReturn(10000);
        when(requestLogRepository.count()).thenReturn(1L);

        LogAgent agent = createAgent(false);
        String originalChain = "[{\"ruleId\":\"rule-0\"}]";
        List<LogTask> tasks = List.of(LogTask.builder()
                .ruleId("rule-0")
                .protocol(Protocol.HTTP)
                .method("POST")
                .endpoint("/api/data")
                .matched(true)
                .responseTimeMs(5)
                .requestTime(LocalDateTime.now())
                .matchChain(originalChain)
                .build());

        // Act
        agent.processBatch(tasks);

        // Assert — saved entity has original matchChain
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RequestLog>> captor = ArgumentCaptor.forClass(List.class);
        verify(requestLogRepository).saveAll(captor.capture());
        assertThat(captor.getValue().get(0).getMatchChain()).isEqualTo(originalChain);
    }

    @Test
    void processBatch_memoryMode_analysisDisabled_shouldUseOriginalMatchChain() {
        // Arrange
        ConcurrentLinkedDeque<RequestLogService.LogEntry> buffer = new ConcurrentLinkedDeque<>();
        AtomicInteger bufferSize = new AtomicInteger(0);

        when(configService.isRequestLogMemoryMode()).thenReturn(true);
        when(configService.getRequestLogMaxRecords()).thenReturn(10000);
        when(requestLogService.getMemoryBuffer()).thenReturn(buffer);
        when(requestLogService.getBufferSize()).thenReturn(bufferSize);

        LogAgent agent = createAgent(false);
        String originalChain = "[{\"ruleId\":\"rule-0\"}]";
        List<LogTask> tasks = List.of(LogTask.builder()
                .ruleId("rule-0")
                .protocol(Protocol.HTTP)
                .method("GET")
                .endpoint("/api/test")
                .matched(true)
                .responseTimeMs(5)
                .requestTime(LocalDateTime.now())
                .matchChain(originalChain)
                .build());

        // Act
        agent.processBatch(tasks);

        // Assert — buffer entry has original matchChain
        assertThat(buffer.getFirst().getMatchChain()).isEqualTo(originalChain);
    }

    // ==================== Exception Handling Tests (Req 5.2) ====================

    @Test
    void processBatch_databaseMode_saveAllFailure_shouldNotThrow() {
        // Arrange — saveAll throws exception
        when(configService.isRequestLogMemoryMode()).thenReturn(false);
        when(requestLogRepository.saveAll(any())).thenThrow(new RuntimeException("DB connection lost"));

        LogAgent agent = createAgent(false);
        List<LogTask> tasks = createTasks(2);

        // Act — should not propagate exception
        agent.processBatch(tasks);

        // Assert — no crash, saveAll was attempted
        verify(requestLogRepository).saveAll(any());
    }

    @Test
    void processBatch_databaseMode_saveAllFailure_shouldNotCallDeleteOldest() {
        // Arrange — saveAll throws, so deleteOldest should not be reached
        when(configService.isRequestLogMemoryMode()).thenReturn(false);
        when(requestLogRepository.saveAll(any())).thenThrow(new RuntimeException("DB error"));

        LogAgent agent = createAgent(false);
        List<LogTask> tasks = createTasks(1);

        // Act
        agent.processBatch(tasks);

        // Assert — deleteOldest never called because saveAll failed
        verify(requestLogRepository, never()).deleteOldest(anyInt());
    }

    @Test
    void processBatch_databaseMode_subsequentBatchSucceedsAfterFailure() {
        // Arrange — first call fails, second succeeds
        when(configService.isRequestLogMemoryMode()).thenReturn(false);
        when(configService.getRequestLogMaxRecords()).thenReturn(10000);
        when(requestLogRepository.saveAll(any()))
                .thenThrow(new RuntimeException("Transient DB error"))
                .thenReturn(List.of());
        when(requestLogRepository.count()).thenReturn(2L);

        LogAgent agent = createAgent(false);

        // Act — first batch fails
        agent.processBatch(createTasks(1));

        // Act — second batch succeeds
        agent.processBatch(createTasks(2));

        // Assert — saveAll called twice, second succeeded
        verify(requestLogRepository, times(2)).saveAll(any());
    }

    // ==================== getName Test ====================

    @Test
    void getName_shouldReturnLogAgent() {
        LogAgent agent = createAgent(false);
        assertThat(agent.getName()).isEqualTo("log-agent");
    }
}
