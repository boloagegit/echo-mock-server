package com.echo.repository;

import com.echo.entity.Protocol;
import com.echo.entity.RequestLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(RequestLogSummaryQuery.class)
class RequestLogSummaryQueryIntegrationTest {

    @Autowired RequestLogRepository repository;
    @Autowired RequestLogSummaryQuery query;

    @BeforeEach
    void seedLogs() {
        repository.deleteAllInBatch();
        LocalDateTime base = LocalDateTime.of(2026, 1, 1, 0, 0);
        List<RequestLog> logs = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            logs.add(RequestLog.builder()
                    .ruleId(i % 3 == 0 ? "rule-orders" : "rule-other")
                    .protocol(i % 2 == 0 ? Protocol.HTTP : Protocol.JMS)
                    .method(i % 2 == 0 ? "GET" : null)
                    .endpoint(i % 3 == 0 ? "/api/orders/" + i : "QUEUE." + i)
                    .targetHost(i % 3 == 0 ? "orders.internal" : null)
                    .matched(i % 5 != 0)
                    .responseTimeMs(i)
                    .requestTime(base.plusSeconds(i))
                    .requestBody(i % 5 == 0 ? "request" : null)
                    .responseBody(i % 6 == 0 ? "response" : null)
                    .matchChain(i % 7 == 0 ? "[]" : null)
                    .faultType(i == 24 ? "EMPTY_RESPONSE" : null)
                    .scenarioName(i == 24 ? "order-flow" : null)
                    .scenarioFromState(i == 24 ? "Started" : null)
                    .scenarioToState(i == 24 ? "Paid" : null)
                    .build());
        }
        repository.saveAllAndFlush(logs);
    }

    @Test
    void appliesOnlyActiveFiltersAndReturnsStablePageMetadata() {
        var result = query.query(
                new RequestLogSummaryQuery.Filter(
                        null, Protocol.HTTP, true, "orders", null),
                0, 3, "requestTime", false);

        assertThat(result.totalElements()).isEqualTo(4);
        assertThat(result.totalPages()).isEqualTo(2);
        assertThat(result.rows()).hasSize(3);
        assertThat(result.rows()).extracting(RequestLogSummaryQuery.SummaryRow::endpoint)
                .containsExactly("/api/orders/24", "/api/orders/18", "/api/orders/12");
        assertThat(result.rows().get(0).faultType()).isEqualTo("EMPTY_RESPONSE");
        assertThat(result.rows().get(0).scenarioName()).isEqualTo("order-flow");
        assertThat(result.rows().get(0).scenarioFromState()).isEqualTo("Started");
        assertThat(result.rows().get(0).scenarioToState()).isEqualTo("Paid");
    }

    @Test
    void afterIdKeepsIncrementalRefreshSemantics() {
        long boundary = repository.findAll().stream()
                .filter(log -> "/api/orders/18".equals(log.getEndpoint()))
                .findFirst().orElseThrow().getId();

        var result = query.query(
                new RequestLogSummaryQuery.Filter(
                        null, null, null, null, boundary),
                0, 100, "requestTime", false);

        assertThat(result.rows()).isNotEmpty();
        assertThat(result.rows()).allSatisfy(row ->
                assertThat(row.id()).isGreaterThan(boundary));
    }
}
