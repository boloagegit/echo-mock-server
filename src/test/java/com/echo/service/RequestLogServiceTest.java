package com.echo.service;

import com.echo.agent.AgentStatus;
import com.echo.agent.LogAgent;
import com.echo.entity.HttpRule;
import com.echo.entity.Protocol;
import com.echo.entity.RequestLog;
import com.echo.protocol.ProtocolHandlerRegistry;
import com.echo.repository.RequestLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageImpl;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RequestLogServiceTest {

    @Mock
    private RequestLogRepository requestLogRepository;
    @Mock
    private SystemConfigService configService;
    @Mock
    private ProtocolHandlerRegistry protocolHandlerRegistry;
    @Mock
    private LogAgent logAgent;

    private RequestLogService service;

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

    @Nested
    class MemoryModeTests {
        @BeforeEach
        void setUp() {
            when(configService.isRequestLogMemoryMode()).thenReturn(true);
            when(configService.getRequestLogMaxRecords()).thenReturn(100);
            // LogAgent not available — fallback to synchronous memory write
            service = new RequestLogService(requestLogRepository, configService, protocolHandlerRegistry, emptyProvider());
            service.init();
        }

        @Test
        void record_shouldStoreInMemory() {
            service.record("uuid-1", Protocol.HTTP, "GET", "/api", true, 10, "127.0.0.1", null, null, null, null, null, null);
            
            var result = service.query(RequestLogService.QueryFilter.builder().build());
            assertThat(result.getResults()).hasSize(1);
            assertThat(result.getResults().get(0).getLog().getMethod()).isEqualTo("GET");
        }

        @Test
        void querySummary_shouldReturnSummaryWithoutBody() {
            when(configService.isRequestLogIncludeBody()).thenReturn(true);
            when(configService.getRequestLogMaxBodySize()).thenReturn(10000);
            service.record("uuid-1", Protocol.HTTP, "GET", "/api", true, 10, "127.0.0.1",
                    null, null, null, null, null, null, "req-body", "res-body");

            var result = service.querySummary(RequestLogService.QueryFilter.builder().build());
            assertThat(result.getResults()).hasSize(1);
            var summary = result.getResults().get(0).getLog();
            assertThat(summary.getMethod()).isEqualTo("GET");
            assertThat(summary.isHasRequestBody()).isTrue();
            assertThat(summary.isHasResponseBody()).isTrue();
        }

        @Test
        void findById_shouldReturnFullDetail() {
            service.record("uuid-1", Protocol.HTTP, "GET", "/api", true, 10, "127.0.0.1",
                    null, null, null, null, null, null);

            var summaryResult = service.querySummary(RequestLogService.QueryFilter.builder().build());
            Long id = summaryResult.getResults().get(0).getLog().getId();

            var detail = service.findById(id);
            assertThat(detail).isPresent();
            assertThat(detail.get().getEndpoint()).isEqualTo("/api");
        }

        @Test
        void record_shouldRespectMaxRecords() {
            for (int i = 0; i < 150; i++) {
                service.record("uuid-1", Protocol.HTTP, "GET", "/api/" + i, true, 10, "127.0.0.1", null, null, null, null, null, null);
            }
            
            var result = service.query(RequestLogService.QueryFilter.builder().build());
            assertThat(result.getResults()).hasSize(100);
        }

        @Test
        void query_shouldFilterByRuleId() {
            service.record("uuid-1", Protocol.HTTP, "GET", "/api1", true, 10, "127.0.0.1", null, null, null, null, null, null);
            service.record("uuid-2", Protocol.HTTP, "POST", "/api2", true, 10, "127.0.0.1", null, null, null, null, null, null);
            
            var result = service.query(RequestLogService.QueryFilter.builder().ruleId("uuid-1").build());
            assertThat(result.getResults()).hasSize(1);
        }

        @Test
        void query_shouldFilterByProtocol() {
            service.record("uuid-1", Protocol.HTTP, "GET", "/api", true, 10, "127.0.0.1", null, null, null, null, null, null);
            service.record("uuid-1", Protocol.JMS, null, "QUEUE", true, 10, "127.0.0.1", null, null, null, null, null, null);
            
            var result = service.query(RequestLogService.QueryFilter.builder().protocol(Protocol.HTTP).build());
            assertThat(result.getResults()).hasSize(1);
        }

        @Test
        void query_shouldFilterByMatched() {
            service.record("uuid-1", Protocol.HTTP, "GET", "/api", true, 10, "127.0.0.1", null, null, null, null, null, null);
            service.record(null, Protocol.HTTP, "POST", "/unknown", false, 10, "127.0.0.1", null, null, null, null, null, null);
            
            var result = service.query(RequestLogService.QueryFilter.builder().matched(false).build());
            assertThat(result.getResults()).hasSize(1);
            assertThat(result.getResults().get(0).getLog().getEndpoint()).isEqualTo("/unknown");
        }

        @Test
        void query_shouldFilterByEndpoint() {
            service.record("uuid-1", Protocol.HTTP, "GET", "/users", true, 10, "127.0.0.1", null, null, null, null, null, null);
            service.record("uuid-1", Protocol.HTTP, "GET", "/orders", true, 10, "127.0.0.1", null, null, null, null, null, null);
            
            var result = service.query(RequestLogService.QueryFilter.builder().endpoint("user").build());
            assertThat(result.getResults()).hasSize(1);
        }

        @Test
        void query_shouldIncludeRuleInfo() {
            HttpRule rule = new HttpRule();
            rule.setId("uuid-1");
            rule.setMatchKey("/api");
            rule.setMethod("GET");
            rule.setDescription("Test");
            when(protocolHandlerRegistry.findAllByIds(List.of("uuid-1"))).thenReturn(List.of(rule));
            
            service.record("uuid-1", Protocol.HTTP, "GET", "/api", true, 10, "127.0.0.1", null, null, null, null, null, null);
            
            var result = service.query(RequestLogService.QueryFilter.builder().build());
            assertThat(result.getResults().get(0).getRule()).isNotNull();
            assertThat(result.getResults().get(0).getRule().getMatchKey()).isEqualTo("/api");
        }

        @Test
        void getSummary_shouldReturnStats() {
            service.record("uuid-1", Protocol.HTTP, "GET", "/api", true, 10, "127.0.0.1", null, null, null, null, null, null);
            service.record(null, Protocol.HTTP, "POST", "/unknown", false, 10, "127.0.0.1", null, null, null, null, null, null);
            
            var summary = service.getSummary();
            assertThat(summary.getTotalRequests()).isEqualTo(2);
            assertThat(summary.getMatchedRequests()).isEqualTo(1);
            assertThat(summary.getMatchRate()).isEqualTo(50.0);
            assertThat(summary.getMaxRecords()).isEqualTo(100);
        }

        @Test
        void query_shouldHandleMultipleFilters() {
            service.record("uuid-1", Protocol.HTTP, "GET", "/users", true, 10, "127.0.0.1", null, null, null, null, null, null);
            service.record("uuid-2", Protocol.HTTP, "POST", "/orders", true, 10, "127.0.0.1", null, null, null, null, null, null);
            service.record("uuid-3", Protocol.HTTP, "GET", "/users", true, 10, "127.0.0.1", null, null, null, null, null, null);
            
            var result = service.query(RequestLogService.QueryFilter.builder()
                    .ruleId("uuid-1").endpoint("user").build());
            assertThat(result.getResults()).hasSize(1);
        }

        @Test
        void query_shouldIgnoreBlankEndpoint() {
            service.record("uuid-1", Protocol.HTTP, "GET", "/api", true, 10, "127.0.0.1", null, null, null, null, null, null);
            
            var result = service.query(RequestLogService.QueryFilter.builder().endpoint("  ").build());
            assertThat(result.getResults()).hasSize(1);
        }

        @Test
        void query_shouldHandleMissingRule() {
            when(protocolHandlerRegistry.findAllByIds(List.of("uuid-missing"))).thenReturn(List.of());
            service.record("uuid-missing", Protocol.HTTP, "GET", "/api", true, 10, "127.0.0.1", null, null, null, null, null, null);
            
            var result = service.query(RequestLogService.QueryFilter.builder().build());
            assertThat(result.getResults().get(0).getRule()).isNull();
        }

        @Test
        void count_shouldReturnBufferSize() {
            service.record("uuid-1", Protocol.HTTP, "GET", "/api", true, 10, "127.0.0.1", null, null, null, null, null, null);
            service.record("uuid-2", Protocol.HTTP, "POST", "/api2", true, 10, "127.0.0.1", null, null, null, null, null, null);
            assertThat(service.count()).isEqualTo(2);
        }

        @Test
        void deleteAll_shouldClearMemoryBuffer() {
            service.record("uuid-1", Protocol.HTTP, "GET", "/api", true, 10, "127.0.0.1", null, null, null, null, null, null);
            service.record("uuid-2", Protocol.HTTP, "POST", "/api2", true, 10, "127.0.0.1", null, null, null, null, null, null);

            long deleted = service.deleteAll();

            assertThat(deleted).isEqualTo(2);
            assertThat(service.count()).isEqualTo(0);
        }

        @Test
        void record_shouldStoreMatchTimeMs() {
            service.record("uuid-1", Protocol.HTTP, "GET", "/api", true, 30, "127.0.0.1", null, null, null, null, null, 5);

            var result = service.query(RequestLogService.QueryFilter.builder().build());
            assertThat(result.getResults()).hasSize(1);
            assertThat(result.getResults().get(0).getLog().getMatchTimeMs()).isEqualTo(5);
            assertThat(result.getResults().get(0).getLog().getResponseTimeMs()).isEqualTo(30);
        }

        @Test
        void record_shouldAllowNullMatchTimeMs() {
            service.record("uuid-1", Protocol.HTTP, "GET", "/api", true, 10, "127.0.0.1", null, null, null, null, null, null);

            var result = service.query(RequestLogService.QueryFilter.builder().build());
            assertThat(result.getResults().get(0).getLog().getMatchTimeMs()).isNull();
        }
    }

    @Nested
    class MemoryModeWithLogAgentTests {
        @BeforeEach
        void setUp() {
            when(configService.isRequestLogMemoryMode()).thenReturn(true);
            when(configService.getRequestLogMaxRecords()).thenReturn(100);
            when(logAgent.getStatus()).thenReturn(AgentStatus.RUNNING);
            service = new RequestLogService(requestLogRepository, configService, protocolHandlerRegistry, providerOf(logAgent));
            service.init();
        }

        @Test
        void record_shouldDelegateToLogAgent() {
            service.record("uuid-1", Protocol.HTTP, "GET", "/api", true, 10, "127.0.0.1", null, null, null, null, null, null);

            verify(logAgent).submit(any());
            // Memory buffer should be empty — LogAgent handles writing
            assertThat(service.count()).isEqualTo(0);
        }
    }

    @Nested
    class DatabaseModeTests {
        @BeforeEach
        void setUp() {
            when(configService.isRequestLogMemoryMode()).thenReturn(false);
            when(configService.getRequestLogMaxRecords()).thenReturn(10000);
            // LogAgent not available — fallback to synchronous DB write
            service = new RequestLogService(requestLogRepository, configService, protocolHandlerRegistry, emptyProvider());
            service.init();
        }

        @Test
        void record_shouldWriteToDbSynchronously() {
            service.record("uuid-1", Protocol.HTTP, "GET", "/api", true, 10, "127.0.0.1", null, null, null, null, null, null);
            
            verify(requestLogRepository).save(any(RequestLog.class));
        }

        @Test
        void query_shouldQueryDb() {
            RequestLog log = RequestLog.builder()
                    .ruleId("uuid-1").protocol(Protocol.HTTP).method("GET").endpoint("/api")
                    .matched(true).responseTimeMs(10).clientIp("127.0.0.1")
                    .requestTime(LocalDateTime.now()).build();
            when(requestLogRepository.findAllByOrderByRequestTimeDesc(any()))
                    .thenReturn(new PageImpl<>(List.of(log)));
            
            var result = service.query(RequestLogService.QueryFilter.builder().build());
            assertThat(result.getResults()).hasSize(1);
        }

        @Test
        void record_shouldDeleteOldRecordsWhenExceedingMax() {
            when(requestLogRepository.count()).thenReturn(15000L);
            
            service.record("uuid-1", Protocol.HTTP, "GET", "/api", true, 10, "127.0.0.1", null, null, null, null, null, null);
            
            verify(requestLogRepository).deleteOldest(5000);
        }

        @Test
        void getSummary_shouldReturnStats() {
            when(requestLogRepository.count()).thenReturn(100L);
            when(requestLogRepository.countByMatched(true)).thenReturn(80L);
            
            var summary = service.getSummary();
            assertThat(summary.getTotalRequests()).isEqualTo(100);
            assertThat(summary.getMatchedRequests()).isEqualTo(80);
            assertThat(summary.getMatchRate()).isEqualTo(80.0);
        }

        @Test
        void count_shouldReturnDbCount() {
            when(requestLogRepository.count()).thenReturn(42L);
            assertThat(service.count()).isEqualTo(42);
        }

        @Test
        void deleteAll_shouldClearDb() {
            when(requestLogRepository.count()).thenReturn(5L);

            long deleted = service.deleteAll();

            assertThat(deleted).isEqualTo(5);
            verify(requestLogRepository).deleteAllInBatch();
        }
    }

    @Nested
    class DatabaseModeWithLogAgentTests {
        @BeforeEach
        void setUp() {
            when(configService.isRequestLogMemoryMode()).thenReturn(false);
            when(configService.getRequestLogMaxRecords()).thenReturn(10000);
            when(logAgent.getStatus()).thenReturn(AgentStatus.RUNNING);
            service = new RequestLogService(requestLogRepository, configService, protocolHandlerRegistry, providerOf(logAgent));
            service.init();
        }

        @Test
        void record_shouldDelegateToLogAgent() {
            service.record("uuid-1", Protocol.HTTP, "GET", "/api", true, 10, "127.0.0.1", null, null, null, null, null, null);

            verify(logAgent).submit(any());
            verify(requestLogRepository, never()).save(any());
        }
    }

    @Nested
    class FallbackTests {
        @BeforeEach
        void setUp() {
            when(configService.isRequestLogMemoryMode()).thenReturn(true);
            when(configService.getRequestLogMaxRecords()).thenReturn(100);
        }

        @Test
        void record_shouldFallbackWhenLogAgentNotRunning() {
            when(logAgent.getStatus()).thenReturn(AgentStatus.STOPPED);
            service = new RequestLogService(requestLogRepository, configService, protocolHandlerRegistry, providerOf(logAgent));
            service.init();

            service.record("uuid-1", Protocol.HTTP, "GET", "/api", true, 10, "127.0.0.1", null, null, null, null, null, null);

            verify(logAgent, never()).submit(any());
            assertThat(service.count()).isEqualTo(1);
        }

        @Test
        void record_shouldFallbackWhenLogAgentEmpty() {
            service = new RequestLogService(requestLogRepository, configService, protocolHandlerRegistry, emptyProvider());
            service.init();

            service.record("uuid-1", Protocol.HTTP, "GET", "/api", true, 10, "127.0.0.1", null, null, null, null, null, null);

            assertThat(service.count()).isEqualTo(1);
        }
    }
}
