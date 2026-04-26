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
 * 情境一壓力測試：最複雜的 HTTP 匹配路徑
 * <p>
 * 觸發完整匹配鏈：
 * <ol>
 *   <li>多條規則排序（priority + targetHost + matchKey 精確度）</li>
 *   <li>Body 條件匹配（JSON 巢狀欄位 + JSONPath）</li>
 *   <li>Query 條件匹配</li>
 *   <li>Header 條件匹配（包含運算子）</li>
 *   <li>Response Template 渲染（Handlebars + jsonPath helper）</li>
 *   <li>自訂 Response Headers</li>
 *   <li>匹配鏈追蹤 + 請求日誌記錄</li>
 * </ol>
 */
class ComplexScenarioMatchTimeTest extends BaseIntegrationTest {

    private static final String HOST = "stress.api.test";
    private static final String ORIGINAL_HOST_HEADER = "X-Original-Host";

    @Test
    @DisplayName("情境一：多條件匹配 + 模板渲染 — 測量 matchTimeMs")
    void complexScenario_shouldMeasureMatchTime() {
        // === 建立多條規則，製造排序 + 匹配鏈的複雜度 ===

        // 規則 A：萬用 matchKey（排序最後）
        createHttpRule("*", "POST", "{\"fallback\":\"wildcard\"}", HOST, 200,
                null, null, null, null, null, 0, "萬用 fallback");

        // 規則 B：精確路徑，無條件（fallback 候選）
        createHttpRule("/api/v1/orders", "POST", "{\"fallback\":\"no-condition\"}", HOST, 200,
                null, null, null, null, null, 0, "無條件 fallback");

        // 規則 C：精確路徑，條件不匹配（會被跳過，增加匹配鏈長度）
        createHttpRule("/api/v1/orders", "POST", "{\"wrong\":true}", HOST, 200,
                "type=REFUND", "status=cancelled", "X-Channel=internal",
                null, null, 0, "不匹配的條件規則");

        // 規則 D：精確路徑，部分條件匹配（body 匹配但 header 不匹配）
        createHttpRule("/api/v1/orders", "POST", "{\"partial\":true}", HOST, 200,
                "type=ORDER", "status=active", "X-Channel=internal",
                null, null, 0, "部分匹配規則");

        // 規則 E（目標規則）：精確路徑，全條件匹配 + 模板渲染
        // bodyCondition: JSON 巢狀 + JSONPath
        // queryCondition: status=active
        // headerCondition: Content-Type 包含 json + 自訂 header
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

        // 規則 F：無 targetHost 的同路徑規則（排序在有 host 之後）
        createHttpRule("/api/v1/orders", "POST", "{\"no-host\":true}", null, 200,
                null, null, null, null, null, 0, "無 host 規則");

        // === 發送請求觸發匹配 ===

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

        // === 驗證回應 ===

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        String body = response.getBody();
        assertThat(body).isNotNull();
        // 模板渲染驗證
        assertThat(body).contains("\"orderId\":\"ORD-20260329-001\"");
        assertThat(body).contains("\"customer\":\"John\"");
        assertThat(body).contains("\"itemCount\":\"2\"");
        assertThat(body).contains("\"path\":\"/api/v1/orders\"");
        assertThat(body).contains("\"method\":\"POST\"");
        assertThat(body).contains("\"status\":\"active\"");
        // 自訂 response headers
        assertThat(response.getHeaders().getFirst("X-Trace-Id")).isEqualTo("echo-trace");
        assertThat(response.getHeaders().getFirst("X-Matched")).isEqualTo("true");

        // === 查詢請求日誌，取得 matchTimeMs（需等待 LogAgent 非同步 flush）===

        RequestLogService.SummaryQueryResult logResult = null;
        for (int attempt = 0; attempt < 15; attempt++) {
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
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

        // 輸出匹配時間
        System.out.println("========================================");
        System.out.println("情境一壓力測試結果");
        System.out.println("========================================");
        System.out.println("匹配規則 ID:    " + latestLog.getRuleId());
        System.out.println("匹配時間 (ms):  " + latestLog.getMatchTimeMs());
        System.out.println("回應時間 (ms):  " + latestLog.getResponseTimeMs());
        System.out.println("========================================");

        // matchTimeMs 應該有值
        assertThat(latestLog.getMatchTimeMs()).isNotNull();
        assertThat(latestLog.getMatchTimeMs()).isGreaterThanOrEqualTo(0);
    }
}
