package com.echo.integration.http;

import com.echo.dto.RuleDto;
import com.echo.integration.base.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Request log integration test
 */
class RequestLogIntegrationTest extends BaseIntegrationTest {

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> queryLogs(String queryParams) {
        // LogAgent asynchronous write, need to wait for flush to complete
        for (int attempt = 0; attempt < 10; attempt++) {
            ResponseEntity<Map> resp = adminClient().getForEntity(
                    "/api/admin/logs" + (queryParams != null ? "?" + queryParams : ""), Map.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            List<Map<String, Object>> results = (List<Map<String, Object>>) resp.getBody().get("results");
            if (results != null && !results.isEmpty()) {
                return results;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // Last query, let assertThat be processed on the calling side
        ResponseEntity<Map> resp = adminClient().getForEntity(
                "/api/admin/logs" + (queryParams != null ? "?" + queryParams : ""), Map.class);
        return (List<Map<String, Object>>) resp.getBody().get("results");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getLogEntry(Map<String, Object> result) {
        return (Map<String, Object>) result.get("log");
    }

    @Test
    @DisplayName("Traffic-overview requests match method and URL exactly and agree with aggregate counts")
    void methodAndPathFilterMatchesTheTrafficCount() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Original-Host", "exact.test");
        restTemplate.exchange("/mock/api/exact-miss", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        restTemplate.exchange("/mock/api/exact-miss", HttpMethod.POST, new HttpEntity<>("{}", headers), String.class);
        restTemplate.exchange("/mock/api/exact-miss/more", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        // Please write all three entries before checking to avoid checking only part of them.
        assertThat(queryLogs("protocol=HTTP&endpoint=/api/exact-miss")).hasSizeGreaterThanOrEqualTo(1);
        for (int attempt = 0; attempt < 20 && queryLogs("protocol=HTTP&endpoint=/api/exact-miss").size() < 3; attempt++) {
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        List<Map<String, Object>> logs = queryLogs("protocol=HTTP&matched=false&method=GET&path=/api/exact-miss");

        assertThat(logs).singleElement().satisfies(result -> {
            Map<String, Object> log = getLogEntry(result);
            assertThat(log).containsEntry("method", "GET").containsEntry("endpoint", "/api/exact-miss");
        });
    }

    @Test
    @DisplayName("10.2a A successful HTTP mock request records matched=true")
    void httpMockMatched_logsMatchedTrue() {
        createHttpRule("/api/log-test", "GET", "{\"ok\":true}", "log.test");

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Original-Host", "log.test");
        restTemplate.exchange("/mock/api/log-test", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        List<Map<String, Object>> logs = queryLogs("protocol=HTTP&endpoint=/api/log-test");
        assertThat(logs).isNotEmpty();
        Map<String, Object> log = getLogEntry(logs.get(0));
        assertThat(log.get("matched")).isEqualTo(true);
        assertThat(log.get("protocol")).isEqualTo("HTTP");
        assertThat((String) log.get("diagnosticId")).matches("[a-f0-9]{8}-[a-z0-9]+");
    }

    @Test
    @DisplayName("Records and returns flow-diagram data, including candidate counts and simulated delay")
    @SuppressWarnings("unchecked")
    void flowDetailsAreStoredAndReturnedWithTheDetail() {
        createHttpRule("/api/log-flow", "GET", "{\"ok\":true}", "flow.test");

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Original-Host", "flow.test");
        restTemplate.exchange("/mock/api/log-flow", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        List<Map<String, Object>> logs = queryLogs("protocol=HTTP&endpoint=/api/log-flow");
        assertThat(logs).isNotEmpty();
        Object id = getLogEntry(logs.get(0)).get("id");
        ResponseEntity<Map> detail = adminClient().getForEntity("/api/admin/logs/" + id + "/detail", Map.class);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = detail.getBody();
        assertThat(body.get("candidateCount")).isEqualTo(1);
        assertThat(body.get("matchedByFallback")).isEqualTo(true);
        assertThat(body.get("delayMs")).isEqualTo(0);
        assertThat(body.get("sseEventCount")).isNull();
        assertThat(body.get("scenarioSkipped")).isNull();
    }

    @Test
    @DisplayName("10.2b An unmatched HTTP mock request records matched=false")
    void httpMockUnmatched_logsMatchedFalse() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Original-Host", "nomatch.test");
        restTemplate.exchange("/mock/api/no-match-endpoint", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        List<Map<String, Object>> logs = queryLogs("protocol=HTTP&endpoint=/api/no-match-endpoint");
        assertThat(logs).isNotEmpty();
        Map<String, Object> log = getLogEntry(logs.get(0));
        assertThat(log.get("matched")).isEqualTo(false);
    }

    @Test
    @DisplayName("10.3 Log filters: protocol, matched, and endpoint")
    void logFiltering() {
        createHttpRule("/api/filter-a", "GET", "{\"a\":true}", "filter.test");
        createHttpRule("/api/filter-b", "GET", "{\"b\":true}", "filter.test");

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Original-Host", "filter.test");
        restTemplate.exchange("/mock/api/filter-a", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        restTemplate.exchange("/mock/api/filter-b", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        // No matching request
        restTemplate.exchange("/mock/api/filter-none", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        // Filter by protocol
        List<Map<String, Object>> httpLogs = queryLogs("protocol=HTTP");
        assertThat(httpLogs).isNotEmpty();
        httpLogs.forEach(r -> assertThat(getLogEntry(r).get("protocol")).isEqualTo("HTTP"));

        // Filter by matched=true
        List<Map<String, Object>> matchedLogs = queryLogs("matched=true&endpoint=filter-");
        assertThat(matchedLogs).isNotEmpty();
        matchedLogs.forEach(r -> assertThat(getLogEntry(r).get("matched")).isEqualTo(true));

        // Filter by endpoint
        List<Map<String, Object>> endpointLogs = queryLogs("endpoint=filter-a");
        assertThat(endpointLogs).isNotEmpty();
        endpointLogs.forEach(r ->
                assertThat((String) getLogEntry(r).get("endpoint")).contains("filter-a"));
    }

    @Test
    @DisplayName("10.4 Log summary: totalRequests, matchedRequests, and matchRate")
    void logSummary() {
        createHttpRule("/api/summary-test", "GET", "{\"ok\":true}", "summary.test");

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Original-Host", "summary.test");
        restTemplate.exchange("/mock/api/summary-test", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        // LogAgent asynchronous write, waiting for flush to complete
        Map<String, Object> summary = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            ResponseEntity<Map> resp = adminClient().getForEntity("/api/admin/logs/summary", Map.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            summary = resp.getBody();
            if (summary != null && ((Number) summary.get("totalRequests")).longValue() >= 1) {
                break;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertThat(summary).containsKeys("totalRequests", "matchedRequests", "matchRate");
        assertThat(((Number) summary.get("totalRequests")).longValue()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("10.5 Log queries support endpoint filtering")
    void logEndpointFilter() {
        createHttpRule("/api/limit-test", "GET", "{\"ok\":true}", "limit.test");

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Original-Host", "limit.test");
        for (int i = 0; i < 5; i++) {
            restTemplate.exchange("/mock/api/limit-test", HttpMethod.GET,
                    new HttpEntity<>(headers), String.class);
        }

        List<Map<String, Object>> logs = queryLogs("endpoint=limit-test");
        assertThat(logs).isNotEmpty();
    }

    @Test
    @DisplayName("10.6 Log queries support server pagination, sorting, and incremental afterId queries")
    @SuppressWarnings("unchecked")
    void logServerPaginationAndIncrementalQuery() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Original-Host", "paged.test");
        for (String suffix : List.of("c", "a", "b")) {
            createHttpRule("/api/paged-" + suffix, "GET", "{\"suffix\":\"" + suffix + "\"}", "paged.test");
            restTemplate.exchange("/mock/api/paged-" + suffix, HttpMethod.GET,
                    new HttpEntity<>(headers), String.class);
        }

        Map<String, Object> page = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            page = adminClient().getForEntity(
                    "/api/admin/logs?endpoint=paged-&page=0&size=2&sort=endpoint&direction=asc", Map.class)
                    .getBody();
            if (page != null && ((Number) page.get("totalElements")).intValue() >= 3) {
                break;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        assertThat(page).isNotNull();
        assertThat(((Number) page.get("totalElements")).intValue()).isEqualTo(3);
        assertThat(((Number) page.get("totalPages")).intValue()).isEqualTo(2);
        List<Map<String, Object>> results = (List<Map<String, Object>>) page.get("results");
        assertThat(results).hasSize(2);
        assertThat(getLogEntry(results.get(0)).get("endpoint")).isEqualTo("/api/paged-a");
        assertThat(getLogEntry(results.get(1)).get("endpoint")).isEqualTo("/api/paged-b");

        Map<String, Object> newestPage = adminClient().getForEntity(
                "/api/admin/logs?endpoint=paged-&page=0&size=1&sort=requestTime&direction=desc", Map.class)
                .getBody();
        long cursor = ((Number) newestPage.get("newestId")).longValue();
        createHttpRule("/api/paged-new", "GET", "{\"new\":true}", "paged.test");
        restTemplate.exchange("/mock/api/paged-new", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        List<Map<String, Object>> incremental = List.of();
        for (int attempt = 0; attempt < 10; attempt++) {
            Map<String, Object> body = adminClient().getForEntity(
                    "/api/admin/logs?endpoint=paged-&page=0&size=20&afterId=" + cursor, Map.class)
                    .getBody();
            incremental = (List<Map<String, Object>>) body.get("results");
            if (!incremental.isEmpty()) {
                break;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        assertThat(incremental).singleElement().satisfies(item -> {
            Map<String, Object> log = getLogEntry(item);
            assertThat(log.get("endpoint")).isEqualTo("/api/paged-new");
            assertThat(((Number) log.get("id")).longValue()).isGreaterThan(cursor);
        });
    }
}
