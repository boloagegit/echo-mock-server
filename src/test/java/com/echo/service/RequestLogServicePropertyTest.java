package com.echo.service;

import com.echo.agent.AgentStatus;
import com.echo.agent.LogAgent;
import com.echo.agent.LogTask;
import com.echo.entity.Protocol;
import com.echo.entity.RequestLog;
import com.echo.protocol.ProtocolHandlerRegistry;
import com.echo.repository.RequestLogRepository;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.StringLength;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * RequestLogService 屬性測試（Property 9, 10）。
 * <p>
 * Feature: agent-framework-log-agent
 * <p>
 * 驗證 record() 委派給 LogAgent 以及 LogAgent 不可用時的降級同步寫入。
 */
class RequestLogServicePropertyTest {

    // ==================== Helpers ====================

    @SuppressWarnings("unchecked")
    private ObjectProvider<LogAgent> emptyProvider() {
        ObjectProvider<LogAgent> provider = mock(ObjectProvider.class);
        lenient().when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<LogAgent> providerOf(LogAgent agent) {
        ObjectProvider<LogAgent> provider = mock(ObjectProvider.class);
        lenient().when(provider.getIfAvailable()).thenReturn(agent);
        return provider;
    }

    private RequestLogService createService(
            RequestLogRepository repo,
            SystemConfigService configService,
            ProtocolHandlerRegistry protocolHandlerRegistry,
            ObjectProvider<LogAgent> logAgentProvider) {
        RequestLogService service = new RequestLogService(
                repo, configService, protocolHandlerRegistry, logAgentProvider);
        service.init();
        return service;
    }

    // ==================== Property 9 ====================

    /**
     * Feature: agent-framework-log-agent, Property 9: record() delegates to LogAgent
     * <p>
     * 呼叫 record() 時，若 LogAgent 可用且狀態為 RUNNING，
     * LogAgent.submit() 被呼叫，且 LogTask 欄位值正確。
     * <p>
     * **Validates: Requirements 6.1**
     */
    @Property(tries = 20)
    void recordDelegatesToLogAgentWithCorrectFields(
            @ForAll @StringLength(min = 1, max = 20) String ruleId,
            @ForAll("protocols") Protocol protocol,
            @ForAll @StringLength(min = 1, max = 50) String endpoint,
            @ForAll boolean matched,
            @ForAll @IntRange(min = 0, max = 5000) int responseTimeMs) {

        // Arrange
        RequestLogRepository repo = mock(RequestLogRepository.class);
        SystemConfigService configService = mock(SystemConfigService.class);
        ProtocolHandlerRegistry protocolHandlerRegistry = mock(ProtocolHandlerRegistry.class);
        LogAgent logAgent = mock(LogAgent.class);

        when(configService.isRequestLogMemoryMode()).thenReturn(true);
        when(configService.getRequestLogMaxRecords()).thenReturn(100);
        when(configService.isRequestLogIncludeBody()).thenReturn(false);
        when(logAgent.getStatus()).thenReturn(AgentStatus.RUNNING);

        RequestLogService service = createService(
                repo, configService, protocolHandlerRegistry, providerOf(logAgent));

        // Act
        service.record(ruleId, protocol, null, endpoint, matched, responseTimeMs,
                "127.0.0.1", null, null, null, null, null, null, null, null);

        // Assert — LogAgent.submit() 被呼叫一次
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(logAgent, times(1)).submit(captor.capture());

        Object submitted = captor.getValue();
        assertThat(submitted).isInstanceOf(LogTask.class);

        LogTask task = (LogTask) submitted;
        assertThat(task.getRuleId()).isEqualTo(ruleId);
        assertThat(task.getProtocol()).isEqualTo(protocol);
        assertThat(task.getEndpoint()).isEqualTo(endpoint);
        assertThat(task.isMatched()).isEqualTo(matched);
        assertThat(task.getResponseTimeMs()).isEqualTo(responseTimeMs);
        assertThat(task.getClientIp()).isEqualTo("127.0.0.1");
        assertThat(task.getRequestTime()).isNotNull();
    }

    /**
     * Feature: agent-framework-log-agent, Property 9: record() delegates to LogAgent
     * <p>
     * 呼叫 record() 時，若 LogAgent 可用且狀態為 RUNNING，
     * 不應直接寫入 memory buffer 或 database。
     * <p>
     * **Validates: Requirements 6.1**
     */
    @Property(tries = 20)
    void recordDoesNotWriteDirectlyWhenAgentRunning(
            @ForAll("protocols") Protocol protocol,
            @ForAll @IntRange(min = 0, max = 5000) int responseTimeMs) {

        // Arrange
        RequestLogRepository repo = mock(RequestLogRepository.class);
        SystemConfigService configService = mock(SystemConfigService.class);
        ProtocolHandlerRegistry protocolHandlerRegistry = mock(ProtocolHandlerRegistry.class);
        LogAgent logAgent = mock(LogAgent.class);

        when(configService.isRequestLogMemoryMode()).thenReturn(true);
        when(configService.getRequestLogMaxRecords()).thenReturn(100);
        when(configService.isRequestLogIncludeBody()).thenReturn(false);
        when(logAgent.getStatus()).thenReturn(AgentStatus.RUNNING);

        RequestLogService service = createService(
                repo, configService, protocolHandlerRegistry, providerOf(logAgent));

        // Act
        service.record("rule-1", protocol, null, "/api/test", true, responseTimeMs,
                "127.0.0.1", null, null, null, null, null, null, null, null);

        // Assert — memory buffer 應為空（LogAgent 處理寫入）
        assertThat(service.count()).isEqualTo(0);
        // database 不應被直接寫入
        verify(repo, never()).save(any(RequestLog.class));
    }

    // ==================== Property 10 ====================

    /**
     * Feature: agent-framework-log-agent, Property 10: Fallback to synchronous write when agent unavailable
     * <p>
     * LogAgent 為 null（provider 回傳 null）時，record() 直接寫入 memory buffer，無資料遺失。
     * <p>
     * **Validates: Requirements 6.4**
     */
    @Property(tries = 20)
    void fallbackToMemoryWriteWhenAgentNull(
            @ForAll @IntRange(min = 1, max = 10) int recordCount) {

        // Arrange
        RequestLogRepository repo = mock(RequestLogRepository.class);
        SystemConfigService configService = mock(SystemConfigService.class);
        ProtocolHandlerRegistry protocolHandlerRegistry = mock(ProtocolHandlerRegistry.class);

        when(configService.isRequestLogMemoryMode()).thenReturn(true);
        when(configService.getRequestLogMaxRecords()).thenReturn(100);
        when(configService.isRequestLogIncludeBody()).thenReturn(false);

        RequestLogService service = createService(
                repo, configService, protocolHandlerRegistry, emptyProvider());

        // Act — 寫入 recordCount 筆
        for (int i = 0; i < recordCount; i++) {
            service.record("rule-" + i, Protocol.HTTP, null, "/api/" + i, true, 10,
                    "127.0.0.1", null, null, null, null, null, null, null, null);
        }

        // Assert — 全部寫入 memory buffer，無資料遺失
        assertThat(service.count()).isEqualTo(recordCount);
    }

    /**
     * Feature: agent-framework-log-agent, Property 10: Fallback to synchronous write when agent unavailable
     * <p>
     * LogAgent 狀態為 STOPPED 時，record() 直接寫入 memory buffer，無資料遺失。
     * <p>
     * **Validates: Requirements 6.4**
     */
    @Property(tries = 20)
    void fallbackToMemoryWriteWhenAgentStopped(
            @ForAll("nonRunningStatuses") AgentStatus status,
            @ForAll @IntRange(min = 1, max = 10) int recordCount) {

        // Arrange
        RequestLogRepository repo = mock(RequestLogRepository.class);
        SystemConfigService configService = mock(SystemConfigService.class);
        ProtocolHandlerRegistry protocolHandlerRegistry = mock(ProtocolHandlerRegistry.class);
        LogAgent logAgent = mock(LogAgent.class);

        when(configService.isRequestLogMemoryMode()).thenReturn(true);
        when(configService.getRequestLogMaxRecords()).thenReturn(100);
        when(configService.isRequestLogIncludeBody()).thenReturn(false);
        when(logAgent.getStatus()).thenReturn(status);

        RequestLogService service = createService(
                repo, configService, protocolHandlerRegistry, providerOf(logAgent));

        // Act
        for (int i = 0; i < recordCount; i++) {
            service.record("rule-" + i, Protocol.HTTP, null, "/api/" + i, true, 10,
                    "127.0.0.1", null, null, null, null, null, null, null, null);
        }

        // Assert — LogAgent.submit() 不應被呼叫
        verify(logAgent, never()).submit(any());
        // 全部寫入 memory buffer
        assertThat(service.count()).isEqualTo(recordCount);
    }

    /**
     * Feature: agent-framework-log-agent, Property 10: Fallback to synchronous write when agent unavailable
     * <p>
     * LogAgent 不可用時，database 模式下 record() 直接寫入 DB，無資料遺失。
     * <p>
     * **Validates: Requirements 6.4**
     */
    @Property(tries = 20)
    void fallbackToDatabaseWriteWhenAgentUnavailable(
            @ForAll @IntRange(min = 1, max = 10) int recordCount) {

        // Arrange
        RequestLogRepository repo = mock(RequestLogRepository.class);
        SystemConfigService configService = mock(SystemConfigService.class);
        ProtocolHandlerRegistry protocolHandlerRegistry = mock(ProtocolHandlerRegistry.class);

        when(configService.isRequestLogMemoryMode()).thenReturn(false);
        when(configService.getRequestLogMaxRecords()).thenReturn(10000);
        when(configService.isRequestLogIncludeBody()).thenReturn(false);
        when(repo.count()).thenReturn(0L);

        RequestLogService service = createService(
                repo, configService, protocolHandlerRegistry, emptyProvider());

        // Act
        for (int i = 0; i < recordCount; i++) {
            service.record("rule-" + i, Protocol.HTTP, null, "/api/" + i, true, 10,
                    "127.0.0.1", null, null, null, null, null, null, null, null);
        }

        // Assert — 每筆都直接寫入 DB
        verify(repo, times(recordCount)).save(any(RequestLog.class));
    }

    // ==================== Providers ====================

    @Provide
    Arbitrary<Protocol> protocols() {
        return Arbitraries.of(Protocol.values());
    }

    @Provide
    Arbitrary<AgentStatus> nonRunningStatuses() {
        return Arbitraries.of(AgentStatus.STARTING, AgentStatus.STOPPING, AgentStatus.STOPPED);
    }
}
