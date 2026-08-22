package com.echo.service;

import com.echo.agent.LogAgent;
import com.echo.agent.LogTask;
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
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    private void persistMemoryTask(LogTask task) {
        RequestLogService.LogEntry entry = RequestLogService.LogEntry.builder()
                .id(RequestLogService.nextMemoryId())
                .ruleId(task.getRuleId())
                .protocol(task.getProtocol())
                .method(task.getMethod())
                .endpoint(task.getEndpoint())
                .matched(task.isMatched())
                .responseTimeMs(task.getResponseTimeMs())
                .matchTimeMs(task.getMatchTimeMs())
                .clientIp(task.getClientIp())
                .requestTime(task.getRequestTime())
                .matchChain(task.getMatchChain())
                .targetHost(task.getTargetHost())
                .forwarded(task.isForwarded())
                .forwardTarget(task.getForwardTarget())
                .proxyStatus(task.getProxyStatus())
                .proxyError(task.getProxyError())
                .responseStatus(task.getResponseStatus())
                .requestBody(task.getRequestBody())
                .responseBody(task.getResponseBody())
                .build();
        service.getMemoryBuffer().addFirst(entry);
        int size = service.getBufferSize().incrementAndGet();
        int maxRecords = 100;
        while (size > maxRecords && service.getMemoryBuffer().pollLast() != null) {
            size = service.getBufferSize().decrementAndGet();
        }
    }

    @Nested
    class MemoryModeTests {
        @BeforeEach
        void setUp() {
            when(configService.isRequestLogMemoryMode()).thenReturn(true);
            when(configService.getRequestLogMaxRecords()).thenReturn(100);
            service = new RequestLogService(requestLogRepository, configService, protocolHandlerRegistry, providerOf(logAgent));
            service.init();
            lenient().doAnswer(invocation -> {
                Supplier<LogTask> supplier = invocation.getArgument(0);
                persistMemoryTask(supplier.get());
                return null;
            }).when(logAgent).submitDurably(any());
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
        void record_shouldLimitForwardTargetToDatabaseColumnSize() {
            String oversizedTarget = "x".repeat(RequestLog.MAX_FORWARD_TARGET_LENGTH + 200);

            service.record("uuid-1", Protocol.HTTP, "GET", "/api", false, 10, "127.0.0.1",
                    null, null, true, oversizedTarget, null, null, 502, 1,
                    null, null, List.of(), null, null, java.util.Map.of(),
                    null, null, null, null);

            var result = service.querySummary(RequestLogService.QueryFilter.builder().build());
            assertThat(result.getResults()).singleElement().satisfies(item ->
                    assertThat(item.getLog().getForwardTarget())
                            .hasSize(RequestLog.MAX_FORWARD_TARGET_LENGTH));
        }

        @Test
        void querySummary_shouldPaginateAndSortInMemory() {
            service.record("uuid-1", Protocol.HTTP, "GET", "/50", true, 50, "127.0.0.1", null, null, null, null, null, null);
            service.record("uuid-1", Protocol.HTTP, "GET", "/10", true, 10, "127.0.0.1", null, null, null, null, null, null);
            service.record("uuid-1", Protocol.HTTP, "GET", "/30", true, 30, "127.0.0.1", null, null, null, null, null, null);
            service.record("uuid-1", Protocol.HTTP, "GET", "/20", true, 20, "127.0.0.1", null, null, null, null, null, null);
            service.record("uuid-1", Protocol.HTTP, "GET", "/40", true, 40, "127.0.0.1", null, null, null, null, null, null);

            var result = service.querySummary(RequestLogService.QueryFilter.builder()
                    .page(1).size(2).sortField("responseTimeMs").sortDirection("asc").build());

            assertThat(result.getResults()).extracting(item -> item.getLog().getResponseTimeMs())
                    .containsExactly(30, 40);
            assertThat(result.getPage()).isEqualTo(1);
            assertThat(result.getSize()).isEqualTo(2);
            assertThat(result.getTotalElements()).isEqualTo(5);
            assertThat(result.getTotalPages()).isEqualTo(3);

            var outOfRange = service.querySummary(RequestLogService.QueryFilter.builder()
                    .page(Integer.MAX_VALUE).size(100).build());
            assertThat(outOfRange.getResults()).isEmpty();
            assertThat(outOfRange.getTotalElements()).isEqualTo(5);
        }

        @Test
        void querySummary_shouldReturnOnlyRecordsAfterCursor() {
            service.record("uuid-1", Protocol.HTTP, "GET", "/old-1", true, 10, "127.0.0.1", null, null, null, null, null, null);
            service.record("uuid-1", Protocol.HTTP, "GET", "/old-2", true, 10, "127.0.0.1", null, null, null, null, null, null);
            Long cursor = service.querySummary(RequestLogService.QueryFilter.builder().build()).getNewestId();
            service.record("uuid-1", Protocol.HTTP, "GET", "/new", true, 10, "127.0.0.1", null, null, null, null, null, null);

            var result = service.querySummary(RequestLogService.QueryFilter.builder()
                    .afterId(cursor).page(0).size(20).build());

            assertThat(result.getResults()).singleElement()
                    .extracting(item -> item.getLog().getEndpoint()).isEqualTo("/new");
            assertThat(result.getTotalElements()).isEqualTo(1);
            assertThat(result.getNewestId()).isGreaterThan(cursor);
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
            service = new RequestLogService(requestLogRepository, configService, protocolHandlerRegistry, providerOf(logAgent));
            service.init();
        }

        @Test
        void record_shouldDelegateToLogAgent() {
            service.record("uuid-1", Protocol.HTTP, "GET", "/api", true, 10, "127.0.0.1", null, null, null, null, null, null);

            verify(logAgent).submitDurably(any());
            // Memory buffer should be empty — LogAgent handles writing
            assertThat(service.count()).isEqualTo(0);
        }

        @Test
        void record_shouldNotBuildBodySnapshotBeforeAgentInvokesSupplier() {

            service.record("uuid-1", Protocol.HTTP, "POST", "/api", true, 10, "127.0.0.1",
                    null, null, null, null, null, null, "request", "response");

            verify(configService, never()).isRequestLogIncludeBody();
            verify(requestLogRepository, never()).save(any());
        }
    }

    @Nested
    class DatabaseModeTests {
        @BeforeEach
        void setUp() {
            when(configService.isRequestLogMemoryMode()).thenReturn(false);
            when(configService.getRequestLogMaxRecords()).thenReturn(10000);
            // LogAgent 不可用時 request log 必須丟棄，不得同步寫 DB。
            service = new RequestLogService(requestLogRepository, configService, protocolHandlerRegistry, emptyProvider());
            service.init();
        }

        @Test
        void record_shouldNotWriteToDbSynchronously() {
            assertThatThrownBy(() -> service.record("uuid-1", Protocol.HTTP, "GET", "/api",
                    true, 10, "127.0.0.1", null, null, null, null, null, null))
                    .isInstanceOf(RequestLogUnavailableException.class);

            verify(requestLogRepository, never()).save(any(RequestLog.class));
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
        void querySummary_shouldDelegateFilteringPaginationAndSortingToDb() {
            Object[] row = { 15L, "uuid-1", Protocol.HTTP, "GET", "/api", true,
                    12, 3, "127.0.0.1", LocalDateTime.now(), "host", true,
                    "Primary | https://downstream.example", null, null, 200,
                    "EMPTY_RESPONSE", "order-flow", "Started", "Paid",
                    false, false, false };
            when(requestLogRepository.findSummaryPage(isNull(), eq(Protocol.HTTP), eq(true), eq("/api"),
                    eq(10L), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(Collections.singletonList(row),
                            org.springframework.data.domain.PageRequest.of(1, 2), 5));

            var result = service.querySummary(RequestLogService.QueryFilter.builder()
                    .protocol(Protocol.HTTP).matched(true).endpoint(" /api ").afterId(10L)
                    .page(1).size(2).sortField("responseTimeMs").sortDirection("asc").build());

            assertThat(result.getResults()).singleElement()
                    .satisfies(item -> {
                        assertThat(item.getLog().getId()).isEqualTo(15L);
                        assertThat(item.getLog().isForwarded()).isTrue();
                        assertThat(item.getLog().getForwardTarget())
                                .isEqualTo("Primary | https://downstream.example");
                        assertThat(item.getLog().getFaultType()).isEqualTo("EMPTY_RESPONSE");
                        assertThat(item.getLog().getScenarioName()).isEqualTo("order-flow");
                        assertThat(item.getLog().getScenarioFromState()).isEqualTo("Started");
                        assertThat(item.getLog().getScenarioToState()).isEqualTo("Paid");
                    });
            assertThat(result.getTotalElements()).isEqualTo(5);
            assertThat(result.getTotalPages()).isEqualTo(3);
            verify(requestLogRepository).findSummaryPage(isNull(), eq(Protocol.HTTP), eq(true), eq("/api"),
                    eq(10L), argThat(pageable -> pageable.getPageNumber() == 1
                            && pageable.getPageSize() == 2
                            && pageable.getSort().getOrderFor("responseTimeMs").getDirection() == Sort.Direction.ASC));
            verify(requestLogRepository, never()).findSummaryProjections(any());
        }

        @Test
        void record_shouldNotRunRetentionSynchronously() {
            assertThatThrownBy(() -> service.record("uuid-1", Protocol.HTTP, "GET", "/api",
                    true, 10, "127.0.0.1", null, null, null, null, null, null))
                    .isInstanceOf(RequestLogUnavailableException.class);

            verify(requestLogRepository, never()).count();
            verify(requestLogRepository, never()).deleteOldest(anyInt());
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
            service = new RequestLogService(requestLogRepository, configService, protocolHandlerRegistry, providerOf(logAgent));
            service.init();
        }

        @Test
        void record_shouldDelegateToLogAgent() {
            service.record("uuid-1", Protocol.HTTP, "GET", "/api", true, 10, "127.0.0.1", null, null, null, null, null, null);

            verify(logAgent).submitDurably(any());
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
        void record_shouldDropWhenLogAgentNotRunning() {
            service = new RequestLogService(requestLogRepository, configService, protocolHandlerRegistry, providerOf(logAgent));
            service.init();

            service.record("uuid-1", Protocol.HTTP, "GET", "/api", true, 10, "127.0.0.1", null, null, null, null, null, null);

            verify(logAgent).submitDurably(any());
            assertThat(service.count()).isZero();
        }

        @Test
        void record_shouldDropWhenLogAgentEmpty() {
            service = new RequestLogService(requestLogRepository, configService, protocolHandlerRegistry, emptyProvider());
            service.init();

            assertThatThrownBy(() -> service.record("uuid-1", Protocol.HTTP, "GET", "/api",
                    true, 10, "127.0.0.1", null, null, null, null, null, null))
                    .isInstanceOf(RequestLogUnavailableException.class);

            assertThat(service.count()).isZero();
            verify(requestLogRepository, never()).save(any());
        }
    }
}
