package com.echo.integration.http;

import com.echo.integration.base.BaseIntegrationTest;
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
 * HTTP Mock 端點整合測試
 * <p>
 * 測試 /mock/** 端點的各種匹配邏輯、條件匹配、回應處理等。
 * Mock 端點完全繞過 Spring Security，使用 restTemplate（無需認證）。
 */
class HttpMockIntegrationTest extends BaseIntegrationTest {

    private static final String ORIGINAL_HOST_HEADER = "X-Original-Host";

    // ========== 3.2 測試基本 Mock 匹配 ==========

    @Test
    @DisplayName("基本 Mock 匹配：GET /mock/users + X-Original-Host → 200 + body 正確")
    void basicMockMatch_shouldReturnCorrectResponse() {
        createHttpRule("/users", "GET", "[{\"id\":1}]", "api.test", 200);

        HttpHeaders headers = new HttpHeaders();
        headers.set(ORIGINAL_HOST_HEADER, "api.test");
        HttpEntity<Void> request = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange(
                "/mock/users", HttpMethod.GET, request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("[{\"id\":1}]");
    }

    // ========== 3.3 測試 POST 方法 + JSON body ==========

    @Test
    @DisplayName("POST 方法 + JSON body：POST /mock/orders → 201")
    void postMethodWithJsonBody_shouldReturn201() {
        createHttpRule("/orders", "POST", "{\"created\":true}", null, 201);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>("{\"item\":\"book\"}", headers);

        ResponseEntity<String> response = restTemplate.exchange(
                "/mock/orders", HttpMethod.POST, request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isEqualTo("{\"created\":true}");
    }

    // ========== 3.4 測試 Body 條件匹配（JSON）==========

    @Test
    @DisplayName("Body 條件匹配（JSON）：type=vip 匹配有條件規則，type=normal fallback 到無條件規則")
    void bodyConditionJson_shouldMatchConditionalAndFallback() {
        // 有條件規則：bodyCondition=type=vip
        createHttpRule("/members", "POST", "{\"level\":\"vip\"}", null, 200,
                "type=vip", null, null, null, null, null, "VIP 規則");
        // 無條件規則（fallback）
        createHttpRule("/members", "POST", "{\"level\":\"normal\"}", null, 200,
                null, null, null, null, null, null, "預設規則");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // VIP body → 匹配有條件規則
        HttpEntity<String> vipRequest = new HttpEntity<>("{\"type\":\"vip\"}", headers);
        ResponseEntity<String> vipResponse = restTemplate.exchange(
                "/mock/members", HttpMethod.POST, vipRequest, String.class);
        assertThat(vipResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(vipResponse.getBody()).isEqualTo("{\"level\":\"vip\"}");

        // normal body → fallback 到無條件規則
        HttpEntity<String> normalRequest = new HttpEntity<>("{\"type\":\"normal\"}", headers);
        ResponseEntity<String> normalResponse = restTemplate.exchange(
                "/mock/members", HttpMethod.POST, normalRequest, String.class);
        assertThat(normalResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(normalResponse.getBody()).isEqualTo("{\"level\":\"normal\"}");
    }

    // ========== 3.5 測試 Body 條件匹配（XML）==========

    @Test
    @DisplayName("Body 條件匹配（XML）：//type=ORDER 匹配 XML body")
    void bodyConditionXml_shouldMatchXPathCondition() {
        createHttpRule("/xml-orders", "POST", "<response>matched</response>", null, 200,
                "//type=ORDER", null, null, null, null, null, "XML 條件規則");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_XML);
        HttpEntity<String> request = new HttpEntity<>(
                "<root><type>ORDER</type></root>", headers);

        ResponseEntity<String> response = restTemplate.exchange(
                "/mock/xml-orders", HttpMethod.POST, request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("<response>matched</response>");
    }

    // ========== 3.6 測試 Query 條件匹配 ==========

    @Test
    @DisplayName("Query 條件匹配：status=active 匹配成功，status=inactive 不匹配")
    void queryCondition_shouldMatchActiveAndRejectInactive() {
        createHttpRule("/items", "GET", "{\"filtered\":true}", null, 200,
                null, "status=active", null, null, null, null, "Query 條件規則");

        HttpHeaders headers = new HttpHeaders();

        // status=active → 匹配成功
        HttpEntity<Void> activeRequest = new HttpEntity<>(headers);
        ResponseEntity<String> activeResponse = restTemplate.exchange(
                "/mock/items?status=active", HttpMethod.GET, activeRequest, String.class);
        assertThat(activeResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(activeResponse.getBody()).isEqualTo("{\"filtered\":true}");

        // status=inactive → 不匹配（404，因為沒有 fallback 規則）
        HttpEntity<Void> inactiveRequest = new HttpEntity<>(headers);
        ResponseEntity<String> inactiveResponse = restTemplate.exchange(
                "/mock/items?status=inactive", HttpMethod.GET, inactiveRequest, String.class);
        assertThat(inactiveResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ========== 3.7 測試 Header 條件匹配 ==========

    @Test
    @DisplayName("Header 條件匹配：X-Tenant=abc 匹配成功，不帶 X-Tenant 不匹配")
    void headerCondition_shouldMatchWithHeaderAndRejectWithout() {
        createHttpRule("/tenant-data", "GET", "{\"tenant\":\"abc\"}", null, 200,
                null, null, "X-Tenant=abc", null, null, null, "Header 條件規則");

        // 帶 X-Tenant: abc → 匹配成功
        HttpHeaders headersWithTenant = new HttpHeaders();
        headersWithTenant.set("X-Tenant", "abc");
        HttpEntity<Void> requestWithTenant = new HttpEntity<>(headersWithTenant);
        ResponseEntity<String> matchResponse = restTemplate.exchange(
                "/mock/tenant-data", HttpMethod.GET, requestWithTenant, String.class);
        assertThat(matchResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(matchResponse.getBody()).isEqualTo("{\"tenant\":\"abc\"}");

        // 不帶 X-Tenant → 不匹配（404）
        HttpEntity<Void> requestWithoutTenant = new HttpEntity<>(new HttpHeaders());
        ResponseEntity<String> noMatchResponse = restTemplate.exchange(
                "/mock/tenant-data", HttpMethod.GET, requestWithoutTenant, String.class);
        assertThat(noMatchResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ========== 3.8 測試多條件同時匹配（body + query + header）==========

    @Test
    @DisplayName("多條件同時匹配：body + query + header 全滿足才匹配，部分滿足不匹配")
    void multipleConditions_shouldRequireAllToMatch() {
        createHttpRule("/multi", "POST", "{\"all\":\"matched\"}", null, 200,
                "type=order", "status=active", "X-Tenant=abc",
                null, null, null, "多條件規則");

        HttpHeaders fullHeaders = new HttpHeaders();
        fullHeaders.setContentType(MediaType.APPLICATION_JSON);
        fullHeaders.set("X-Tenant", "abc");

        // 三個條件都滿足 → 匹配成功
        HttpEntity<String> fullRequest = new HttpEntity<>("{\"type\":\"order\"}", fullHeaders);
        ResponseEntity<String> fullResponse = restTemplate.exchange(
                "/mock/multi?status=active", HttpMethod.POST, fullRequest, String.class);
        assertThat(fullResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fullResponse.getBody()).isEqualTo("{\"all\":\"matched\"}");

        // 只滿足 body + query，缺少 header → 不匹配
        HttpHeaders partialHeaders = new HttpHeaders();
        partialHeaders.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> partialRequest = new HttpEntity<>("{\"type\":\"order\"}", partialHeaders);
        ResponseEntity<String> partialResponse = restTemplate.exchange(
                "/mock/multi?status=active", HttpMethod.POST, partialRequest, String.class);
        assertThat(partialResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ========== 3.9 測試自訂 Response Headers ==========

    @Test
    @DisplayName("自訂 Response Headers：回應包含 X-Custom header")
    void customResponseHeaders_shouldBeIncludedInResponse() {
        createHttpRule("/custom-headers", "GET", "{\"ok\":true}", null, 200,
                null, null, null, "{\"X-Custom\":\"hello\"}", null, null, "自訂 Headers 規則");

        ResponseEntity<String> response = restTemplate.getForEntity(
                "/mock/custom-headers", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("X-Custom")).isEqualTo("hello");
    }

    // ========== 3.10 測試自訂 HTTP Status Code ==========

    @Test
    @DisplayName("自訂 HTTP Status Code：404 和 500")
    void customStatusCode_shouldReturnConfiguredStatus() {
        createHttpRule("/not-found", "GET", "{\"error\":\"not found\"}", null, 404,
                null, null, null, null, null, null, "404 規則");
        createHttpRule("/server-error", "GET", "{\"error\":\"internal\"}", null, 500,
                null, null, null, null, null, null, "500 規則");

        ResponseEntity<String> response404 = restTemplate.getForEntity(
                "/mock/not-found", String.class);
        assertThat(response404.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response404.getBody()).isEqualTo("{\"error\":\"not found\"}");

        ResponseEntity<String> response500 = restTemplate.getForEntity(
                "/mock/server-error", String.class);
        assertThat(response500.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response500.getBody()).isEqualTo("{\"error\":\"internal\"}");
    }

    // ========== 3.11 測試延遲回應（delayMs）==========

    @Test
    @DisplayName("延遲回應：delayMs=300 → 回應耗時 ≥ 300ms")
    void delayedResponse_shouldTakeAtLeastConfiguredDelay() {
        createHttpRule("/delayed", "GET", "{\"delayed\":true}", null, 200,
                null, null, null, null, 300L, null, "延遲規則");

        long start = System.currentTimeMillis();
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/mock/delayed", String.class);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("{\"delayed\":true}");
        assertThat(elapsed).isGreaterThanOrEqualTo(300L);
    }

    // ========== 3.12 測試無匹配規則 + 無 X-Original-Host → 404 ==========

    @Test
    @DisplayName("無匹配規則 + 無 X-Original-Host → 404")
    void noMatchAndNoOriginalHost_shouldReturn404() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/mock/nonexistent", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("No mock rule found");
    }

    // ========== 3.13 測試萬用 matchKey=* 作為 fallback ==========

    @Test
    @DisplayName("萬用 matchKey=* 作為 fallback：任意路徑都能匹配")
    void wildcardMatchKey_shouldMatchAnyPath() {
        createHttpRule("*", "GET", "{\"wildcard\":true}");

        ResponseEntity<String> response1 = restTemplate.getForEntity(
                "/mock/any/path/here", String.class);
        assertThat(response1.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response1.getBody()).isEqualTo("{\"wildcard\":true}");

        ResponseEntity<String> response2 = restTemplate.getForEntity(
                "/mock/another", String.class);
        assertThat(response2.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response2.getBody()).isEqualTo("{\"wildcard\":true}");
    }

    // ========== 3.14 測試規則優先順序（priority）==========

    @Test
    @DisplayName("規則優先順序：priority=10 優先於 priority=1")
    void rulePriority_shouldPreferHigherPriorityValue() {
        // priority=1（較低優先）
        createHttpRule("/priority-test", "GET", "{\"priority\":1}", null, 200,
                null, null, null, null, null, 1, "低優先規則");
        // priority=10（較高優先）
        createHttpRule("/priority-test", "GET", "{\"priority\":10}", null, 200,
                null, null, null, null, null, 10, "高優先規則");

        ResponseEntity<String> response = restTemplate.getForEntity(
                "/mock/priority-test", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("{\"priority\":10}");
    }

    // ========== 3.15 測試 targetHost 精確度排序 ==========

    @Test
    @DisplayName("targetHost 精確度排序：有 targetHost 的規則優先於無 targetHost 的規則")
    void targetHostPrecision_shouldPreferSpecificHost() {
        // 無 targetHost 的規則
        createHttpRule("/host-test", "GET", "{\"host\":\"none\"}", null, 200,
                null, null, null, null, null, null, "無 host 規則");
        // 有 targetHost 的規則
        createHttpRule("/host-test", "GET", "{\"host\":\"specific\"}", "api.test", 200,
                null, null, null, null, null, null, "有 host 規則");

        HttpHeaders headers = new HttpHeaders();
        headers.set(ORIGINAL_HOST_HEADER, "api.test");
        HttpEntity<Void> request = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange(
                "/mock/host-test", HttpMethod.GET, request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("{\"host\":\"specific\"}");
    }

    // ========== 3.16 測試 Response Template 渲染 ==========

    @Test
    @DisplayName("Response Template 渲染：{{request.path}} 和 {{request.query.name}} 被正確渲染")
    void responseTemplate_shouldRenderPathAndQueryParams() {
        // 測試 {{request.path}}
        createHttpRule("/template/path", "GET", "path={{request.path}}");

        ResponseEntity<String> pathResponse = restTemplate.getForEntity(
                "/mock/template/path", String.class);
        assertThat(pathResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pathResponse.getBody()).isEqualTo("path=/template/path");

        // 測試 {{request.query.name}}
        createHttpRule("/template/query", "GET", "name={{request.query.name}}");

        ResponseEntity<String> queryResponse = restTemplate.getForEntity(
                "/mock/template/query?name=John", String.class);
        assertThat(queryResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(queryResponse.getBody()).isEqualTo("name=John");
    }

    // ========== 3.17 測試 Content-Type 自動偵測 ==========

    @Test
    @DisplayName("Content-Type 自動偵測：JSON → application/json，XML → application/xml，純文字 → text/plain")
    void contentTypeAutoDetection_shouldDetectCorrectType() {
        // JSON body
        createHttpRule("/ct/json", "GET", "{\"type\":\"json\"}");
        ResponseEntity<String> jsonResponse = restTemplate.getForEntity(
                "/mock/ct/json", String.class);
        assertThat(jsonResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jsonResponse.getHeaders().getContentType()).isNotNull();
        assertThat(jsonResponse.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();

        // XML body
        createHttpRule("/ct/xml", "GET", "<root>xml</root>");
        ResponseEntity<String> xmlResponse = restTemplate.getForEntity(
                "/mock/ct/xml", String.class);
        assertThat(xmlResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(xmlResponse.getHeaders().getContentType()).isNotNull();
        assertThat(xmlResponse.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_XML)).isTrue();

        // 純文字 body
        createHttpRule("/ct/text", "GET", "plain text content");
        ResponseEntity<String> textResponse = restTemplate.getForEntity(
                "/mock/ct/text", String.class);
        assertThat(textResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(textResponse.getHeaders().getContentType()).isNotNull();
        assertThat(textResponse.getHeaders().getContentType().isCompatibleWith(MediaType.TEXT_PLAIN)).isTrue();
    }
}
