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
 * 請求日誌整合測試
 */
class RequestLogIntegrationTest extends BaseIntegrationTest {

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> queryLogs(String queryParams) {
        // LogAgent 非同步寫入，需等待 flush 完成
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
        // 最後一次查詢，讓 assertThat 在呼叫端處理
        ResponseEntity<Map> resp = adminClient().getForEntity(
                "/api/admin/logs" + (queryParams != null ? "?" + queryParams : ""), Map.class);
        return (List<Map<String, Object>>) resp.getBody().get("results");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getLogEntry(Map<String, Object> result) {
        return (Map<String, Object>) result.get("log");
    }

    @Test
    @DisplayName("10.2a HTTP Mock 請求成功 → 日誌 matched=true")
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
    }

    @Test
    @DisplayName("10.2b HTTP Mock 無匹配 → 日誌 matched=false")
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
    @DisplayName("10.3 日誌篩選: protocol / matched / endpoint")
    void logFiltering() {
        createHttpRule("/api/filter-a", "GET", "{\"a\":true}", "filter.test");
        createHttpRule("/api/filter-b", "GET", "{\"b\":true}", "filter.test");

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Original-Host", "filter.test");
        restTemplate.exchange("/mock/api/filter-a", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        restTemplate.exchange("/mock/api/filter-b", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        // 無匹配請求
        restTemplate.exchange("/mock/api/filter-none", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        // 按 protocol 篩選
        List<Map<String, Object>> httpLogs = queryLogs("protocol=HTTP");
        assertThat(httpLogs).isNotEmpty();
        httpLogs.forEach(r -> assertThat(getLogEntry(r).get("protocol")).isEqualTo("HTTP"));

        // 按 matched=true 篩選
        List<Map<String, Object>> matchedLogs = queryLogs("matched=true&endpoint=filter-");
        assertThat(matchedLogs).isNotEmpty();
        matchedLogs.forEach(r -> assertThat(getLogEntry(r).get("matched")).isEqualTo(true));

        // 按 endpoint 篩選
        List<Map<String, Object>> endpointLogs = queryLogs("endpoint=filter-a");
        assertThat(endpointLogs).isNotEmpty();
        endpointLogs.forEach(r ->
                assertThat((String) getLogEntry(r).get("endpoint")).contains("filter-a"));
    }

    @Test
    @DisplayName("10.4 日誌摘要: totalRequests / matchedRequests / matchRate")
    void logSummary() {
        createHttpRule("/api/summary-test", "GET", "{\"ok\":true}", "summary.test");

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Original-Host", "summary.test");
        restTemplate.exchange("/mock/api/summary-test", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        // LogAgent 非同步寫入，等待 flush 完成
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
    @DisplayName("10.5 日誌查詢: endpoint 篩選")
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
}
