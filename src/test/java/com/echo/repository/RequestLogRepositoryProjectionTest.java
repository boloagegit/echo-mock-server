package com.echo.repository;

import com.echo.entity.Protocol;
import com.echo.entity.RequestLog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class RequestLogRepositoryProjectionTest {

    @Autowired
    private RequestLogRepository repository;

    @Test
    void findSummaryProjections_shouldNotReturnLobContents() {
        String largeBody = "x".repeat(512 * 1024);
        repository.saveAll(List.of(
                log("/null", null, null, null, LocalDateTime.now().minusSeconds(2)),
                log("/empty", "", "", "", LocalDateTime.now().minusSeconds(1)),
                log("/large", largeBody, largeBody, largeBody, LocalDateTime.now())
        ));
        repository.flush();

        List<Object[]> rows = repository.findSummaryProjections(PageRequest.of(0, 10));
        Map<String, Object[]> byEndpoint = rows.stream()
                .collect(Collectors.toMap(row -> (String) row[4], row -> row));

        assertThat(rows).hasSize(3);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row).hasSize(23);
            assertThat(Arrays.asList(row)).doesNotContain(largeBody);
        });
        assertThat(byEndpoint.get("/large")[16]).isEqualTo("EMPTY_RESPONSE");
        assertThat(byEndpoint.get("/large")[17]).isEqualTo("order-flow");
        assertThat(byEndpoint.get("/large")[18]).isEqualTo("Started");
        assertThat(byEndpoint.get("/large")[19]).isEqualTo("Paid");
        assertThat(byEndpoint.get("/null")[20]).isEqualTo(false);
        assertThat(byEndpoint.get("/null")[21]).isEqualTo(false);
        assertThat(byEndpoint.get("/null")[22]).isEqualTo(false);
        assertThat(byEndpoint.get("/empty")[20]).isEqualTo(true);
        assertThat(byEndpoint.get("/empty")[21]).isEqualTo(true);
        assertThat(byEndpoint.get("/empty")[22]).isEqualTo(true);
        assertThat(byEndpoint.get("/large")[20]).isEqualTo(true);
        assertThat(byEndpoint.get("/large")[21]).isEqualTo(true);
        assertThat(byEndpoint.get("/large")[22]).isEqualTo(true);
    }

    @Test
    void findSummaryPage_shouldFilterSortPaginateAndQueryAfterId() {
        RequestLog first = repository.save(log("/orders/first", null, null, null,
                LocalDateTime.now().minusSeconds(3)));
        first.setRuleId("rule-orders");
        first.setTargetHost("PAYMENT.EXAMPLE");
        first.setForwarded(true);
        first.setForwardTarget("Primary downstream");
        first.setResponseTimeMs(30);
        RequestLog second = repository.save(log("/orders/second", null, null, null,
                LocalDateTime.now().minusSeconds(2)));
        second.setRuleId("rule-other");
        second.setResponseTimeMs(10);
        RequestLog third = repository.save(log("/customers", null, null, null,
                LocalDateTime.now().minusSeconds(1)));
        third.setRuleId("rule-customer");
        third.setResponseTimeMs(20);
        repository.flush();

        Page<Object[]> firstPage = repository.findSummaryPage(null, Protocol.HTTP, true, null, null,
                PageRequest.of(0, 2, Sort.by(Sort.Direction.ASC, "responseTimeMs")));

        assertThat(firstPage.getTotalElements()).isEqualTo(3);
        assertThat(firstPage.getTotalPages()).isEqualTo(2);
        assertThat(firstPage.getContent()).extracting(row -> ((Number) row[6]).intValue())
                .containsExactly(10, 20);

        Page<Object[]> targetHostSearch = repository.findSummaryPage(null, null, null, "payment", null,
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "requestTime")));
        assertThat(targetHostSearch.getContent()).extracting(row -> row[4])
                .containsExactly("/orders/first");

        Page<Object[]> forwardTargetSearch = repository.findSummaryPage(
                null, null, null, "downstream", null,
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "requestTime")));
        assertThat(forwardTargetSearch.getContent()).extracting(row -> row[4])
                .containsExactly("/orders/first");

        Page<Object[]> incremental = repository.findSummaryPage(null, null, null, null, second.getId(),
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "requestTime")));
        assertThat(incremental.getContent()).extracting(row -> ((Number) row[0]).longValue())
                .containsExactly(third.getId());
    }

    private RequestLog log(String endpoint, String requestBody, String responseBody,
                           String matchChain, LocalDateTime requestTime) {
        return RequestLog.builder()
                .protocol(Protocol.HTTP)
                .method("GET")
                .endpoint(endpoint)
                .matched(true)
                .responseTimeMs(1)
                .requestTime(requestTime)
                .requestBody(requestBody)
                .responseBody(responseBody)
                .matchChain(matchChain)
                .faultType("/large".equals(endpoint) ? "EMPTY_RESPONSE" : null)
                .scenarioName("/large".equals(endpoint) ? "order-flow" : null)
                .scenarioFromState("/large".equals(endpoint) ? "Started" : null)
                .scenarioToState("/large".equals(endpoint) ? "Paid" : null)
                .build();
    }
}
