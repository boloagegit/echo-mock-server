package com.echo.integration.performance;

import com.echo.dto.RuleDto;
import com.echo.entity.Protocol;
import com.echo.integration.base.BaseIntegrationTest;
import com.echo.service.RequestLogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scenario 1 stress test: the most complex HTTP matching path
 * <p>
 * Trigger complete match chain:
 * <ol>
 *   <li>multiple rules sorting (priority + targetHost + matchKey accuracy)</li>
 *   <li>Body condition matching (JSON nested field + JSONPath)</li>
 *   <li>Query condition matches</li>
 *   <li>Header Conditional matching (including operators)</li>
 *   <li>Response Template rendering (Handlebars + jsonPath helper)</li>
 *   <li>Customized Response Headers</li>
 *   <li>matching chain tracking + request logging</li>
 * </ol>
 */
class ComplexScenarioMatchTimeTest extends BaseIntegrationTest {

    private static final String HOST = "stress.api.test";
    private static final String ORIGINAL_HOST_HEADER = "X-Original-Host";

    @Test
    @DisplayName("情境一：多條件匹配 + 模板渲染 — 測量 matchTimeMs")
    void complexScenario_shouldMeasureMatchTime() {
        // === Create multiple rules to create sorting + matching chain complexity ===

        // Rule A: wildcard matchKey (sorted last)
        createHttpRule("*", "POST", "{\"fallback\":\"wildcard\"}", HOST, 200,
                null, null, null, null, null, 0, "萬用 fallback");

        // Rule B: Exact path, unconditional (fallback candidate)
        createHttpRule("/api/v1/orders", "POST", "{\"fallback\":\"no-condition\"}", HOST, 200,
                null, null, null, null, null, 0, "無條件 fallback");

        // Rule C: exact path, condition does not match (will be skipped, increase matching chain length)
        createHttpRule("/api/v1/orders", "POST", "{\"wrong\":true}", HOST, 200,
                "type=REFUND", "status=cancelled", "X-Channel=internal",
                null, null, 0, "不匹配的條件規則");

        // Rule D: exact path, partial condition match (body matches but header does not match)
        createHttpRule("/api/v1/orders", "POST", "{\"partial\":true}", HOST, 200,
                "type=ORDER", "status=active", "X-Channel=internal",
                null, null, 0, "部分匹配規則");

        // Rule E (target rule): exact path, full condition matching + template rendering
        // bodyCondition: JSON Nest + JSONPath
        // queryCondition: status=active
        // headerCondition: Content-Type includes json + customized header
        String templateBody = "{"
                + "\"orderId\":\"{{jsonPath request.body '$.orderId'}}\","
                + "\"customer\":\"{{jsonPath request.body '$.customer.name'}}\","
                + "\"itemCount\":\"{{size (jsonPath request.body '$.items')}}\","
                + "\"path\":\"{{request.path}}\","
                + "\"method\":\"{{request.method}}\","
                + "\"status\":\"{{request.query.status}}\","
                + "\"timestamp\":\"{{now format='yyyy-MM-dd'}}\","
                + "\"traceId\":\"{{randomValue type='UUID'}}\""
                + "}";

        createHttpRule("/api/v1/orders", "POST", templateBody, HOST, 200,
                "type=ORDER;customer.name=John;$.items[0].sku=A1",
                "status=active;type=vip",
                "Content-Type*=json;X-Tenant=abc",
                "{\"X-Trace-Id\":\"echo-trace\",\"X-Matched\":\"true\"}",
                null, 0, "目標規則：全條件 + 模板");

        // Rule F: Same path rule without targetHost (sorted after host)
        createHttpRule("/api/v1/orders", "POST", "{\"no-host\":true}", null, 200,
                null, null, null, null, null, 0, "無 host 規則");

        // === Send request to trigger matching ===

        String requestBody = "{"
                + "\"type\":\"ORDER\","
                + "\"orderId\":\"ORD-20260329-001\","
                + "\"customer\":{\"name\":\"John\",\"level\":\"VIP\"},"
                + "\"items\":["
                + "  {\"sku\":\"A1\",\"qty\":3,\"price\":100},"
                + "  {\"sku\":\"B2\",\"qty\":1,\"price\":250}"
                + "]"
                + "}";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(ORIGINAL_HOST_HEADER, HOST);
        headers.set("X-Tenant", "abc");

        HttpEntity<String> request = new HttpEntity<>(requestBody, headers);

        ResponseEntity<String> response = restTemplate.exchange(
                "/mock/api/v1/orders?status=active&type=vip",
                HttpMethod.POST, request, String.class);

        // === Verification response ===

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        String body = response.getBody();
        assertThat(body).isNotNull();
        // Template rendering verification
        assertThat(body).contains("\"orderId\":\"ORD-20260329-001\"");
        assertThat(body).contains("\"customer\":\"John\"");
        assertThat(body).contains("\"itemCount\":\"2\"");
        assertThat(body).contains("\"path\":\"/api/v1/orders\"");
        assertThat(body).contains("\"method\":\"POST\"");
        assertThat(body).contains("\"status\":\"active\"");
        // Customized response headers
        assertThat(response.getHeaders().getFirst("X-Trace-Id")).isEqualTo("echo-trace");
        assertThat(response.getHeaders().getFirst("X-Matched")).isEqualTo("true");

        // === Query the request log and obtain matchTimeMs (need to wait for LogAgent asynchronous flush) ===

        RequestLogService.SummaryQueryResult logResult = null;
        for (int attempt = 0; attempt < 15; attempt++) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for the asynchronous request log", e);
            }
            ResponseEntity<RequestLogService.SummaryQueryResult> logsResponse = adminClient().getForEntity(
                    "/api/admin/logs?matched=true&protocol=HTTP&endpoint=/api/v1/orders",
                    RequestLogService.SummaryQueryResult.class);
            if (logsResponse.getBody() != null && logsResponse.getBody().getResults() != null
                    && !logsResponse.getBody().getResults().isEmpty()) {
                logResult = logsResponse.getBody();
                break;
            }
        }

        assertThat(logResult).isNotNull();
        assertThat(logResult.getResults()).isNotEmpty();

        var latestLog = logResult.getResults().get(0).getLog();
        assertThat(latestLog.getEndpoint()).isEqualTo("/api/v1/orders");
        assertThat(latestLog.isMatched()).isTrue();

        // Output matching time
        System.out.println("========================================");
        System.out.println("情境一壓力測試結果");
        System.out.println("========================================");
        System.out.println("匹配規則 ID:    " + latestLog.getRuleId());
        System.out.println("匹配時間 (ms):  " + latestLog.getMatchTimeMs());
        System.out.println("回應時間 (ms):  " + latestLog.getResponseTimeMs());
        System.out.println("========================================");

        // matchTimeMs should have a value
        assertThat(latestLog.getMatchTimeMs()).isNotNull();
        assertThat(latestLog.getMatchTimeMs()).isGreaterThanOrEqualTo(0);
    }
}
