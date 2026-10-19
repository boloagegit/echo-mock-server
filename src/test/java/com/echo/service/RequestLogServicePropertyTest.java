package com.echo.service;

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

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * RequestLogService attribute test (Property 9, 10).
 * <p>
 * Feature: agent-framework-log-agent
 * <p>
 * Verify that record() is delegated to LogAgent, and no synchronous writes occur when LogAgent is unavailable.
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
     * When calling record(), if LogAgent is available and the status is RUNNING,
     * LogAgent.submit() is called, and the LogTask field value is correct.
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

        RequestLogService service = createService(
                repo, configService, protocolHandlerRegistry, providerOf(logAgent));

        // Act
        service.record(ruleId, protocol, null, endpoint, matched, responseTimeMs,
                "127.0.0.1", null, null, null, null, null, null, null, null);

        // Assert — lazy task factory is delegated once and contains the correct fields.
        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<Supplier<? extends LogTask>> captor =
                (ArgumentCaptor) ArgumentCaptor.forClass(Supplier.class);
        verify(logAgent, times(1)).submitDurably(captor.capture());

        LogTask task = captor.getValue().get();
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
     * When calling record(), if LogAgent is available and the status is RUNNING,
     * memory buffer or database should not be written directly.
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

        RequestLogService service = createService(
                repo, configService, protocolHandlerRegistry, providerOf(logAgent));

        // Act
        service.record("rule-1", protocol, null, "/api/test", true, responseTimeMs,
                "127.0.0.1", null, null, null, null, null, null, null, null);

        // Assert — memory buffer should be empty (LogAgent handles writing)
        assertThat(service.count()).isEqualTo(0);
        // database should not be written directly
        verify(repo, never()).save(any(RequestLog.class));
    }

    // ==================== Property 10 ====================

    /**
     * Feature: agent-framework-log-agent, Property 10: Drop when agent unavailable
     * <p>
     * When LogAgent is null (provider returns null), record() must not be written to memory or database simultaneously.
     * <p>
     * **Validates: Requirements 6.4**
     */
    @Property(tries = 20)
    void dropWhenAgentNull(
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

        // Act: write recordCount records.
        for (int i = 0; i < recordCount; i++) {
            int index = i;
            assertThatThrownBy(() -> service.record("rule-" + index, Protocol.HTTP, null,
                    "/api/" + index, true, 10, "127.0.0.1", null, null, null,
                    null, null, null, null, null))
                    .isInstanceOf(RequestLogUnavailableException.class);
        }

        assertThat(service.count()).isZero();
        verify(repo, never()).save(any());
    }

    /**
     * Feature: agent-framework-log-agent, Property 10: Drop when agent rejects
     * <p>
     * When LogAgent rejects the task, record() must not write memory buffer simultaneously.
     * <p>
     * **Validates: Requirements 6.4**
     */
    @Property(tries = 20)
    void dropWhenAgentRejects(
            @ForAll @IntRange(min = 1, max = 10) int recordCount) {

        // Arrange
        RequestLogRepository repo = mock(RequestLogRepository.class);
        SystemConfigService configService = mock(SystemConfigService.class);
        ProtocolHandlerRegistry protocolHandlerRegistry = mock(ProtocolHandlerRegistry.class);
        LogAgent logAgent = mock(LogAgent.class);

        when(configService.isRequestLogMemoryMode()).thenReturn(true);
        when(configService.getRequestLogMaxRecords()).thenReturn(100);
        when(configService.isRequestLogIncludeBody()).thenReturn(false);
        doThrow(new RequestLogUnavailableException("rejected"))
                .when(logAgent).submitDurably(any());

        RequestLogService service = createService(
                repo, configService, protocolHandlerRegistry, providerOf(logAgent));

        // Act
        for (int i = 0; i < recordCount; i++) {
            int index = i;
            assertThatThrownBy(() -> service.record("rule-" + index, Protocol.HTTP, null,
                    "/api/" + index, true, 10, "127.0.0.1", null, null, null,
                    null, null, null, null, null))
                    .isInstanceOf(RequestLogUnavailableException.class);
        }

        verify(logAgent, times(recordCount)).submitDurably(any());
        assertThat(service.count()).isZero();
    }

    /**
     * Feature: agent-framework-log-agent, Property 10: Database fallback is forbidden
     * <p>
     * When LogAgent is unavailable, the database mode must not be directly written to DB by the requesting thread.
     * <p>
     * **Validates: Requirements 6.4**
     */
    @Property(tries = 20)
    void dropWithoutDatabaseWriteWhenAgentUnavailable(
            @ForAll @IntRange(min = 1, max = 10) int recordCount) {

        // Arrange
        RequestLogRepository repo = mock(RequestLogRepository.class);
        SystemConfigService configService = mock(SystemConfigService.class);
        ProtocolHandlerRegistry protocolHandlerRegistry = mock(ProtocolHandlerRegistry.class);

        when(configService.isRequestLogMemoryMode()).thenReturn(false);
        when(configService.getRequestLogMaxRecords()).thenReturn(10000);
        when(configService.isRequestLogIncludeBody()).thenReturn(false);

        RequestLogService service = createService(
                repo, configService, protocolHandlerRegistry, emptyProvider());

        // Act
        for (int i = 0; i < recordCount; i++) {
            int index = i;
            assertThatThrownBy(() -> service.record("rule-" + index, Protocol.HTTP, null,
                    "/api/" + index, true, 10, "127.0.0.1", null, null, null,
                    null, null, null, null, null))
                    .isInstanceOf(RequestLogUnavailableException.class);
        }

        verify(repo, never()).save(any(RequestLog.class));
        verify(repo, never()).count();
    }

    // ==================== Providers ====================

    @Provide
    Arbitrary<Protocol> protocols() {
        return Arbitraries.of(Protocol.values());
    }

}
