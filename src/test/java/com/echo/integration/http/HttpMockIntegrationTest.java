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
 * HTTP Mock Endpoint Integration Test
 * <p>
 * Test various matching logic, condition matching, response processing, etc. of the /mock/** endpoint.
 * The Mock endpoint completely bypasses Spring Security and uses restTemplate (no authentication required).
 */
class HttpMockIntegrationTest extends BaseIntegrationTest {

    private static final String ORIGINAL_HOST_HEADER = "X-Original-Host";

    // ========== 3.2 Test basic Mock matching ==========

    @Test
    @DisplayName("Basic mock matching: GET /mock/users with X-Original-Host returns 200 and the expected body")
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

    // ========== 3.3 Test POST method + JSON body ==========

    @Test
    @DisplayName("POST /mock/orders with a JSON body returns 201")
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

    // ========== 3.4 Test Body condition matching (JSON) ==========

    @Test
    @DisplayName("JSON body conditions: type=vip selects the conditional rule; type=normal selects the unconditional fallback")
    void bodyConditionJson_shouldMatchConditionalAndFallback() {
        // Conditional rule: bodyCondition=type=vip
        createHttpRule("/members", "POST", "{\"level\":\"vip\"}", null, 200,
                "type=vip", null, null, null, null, null, "VIP 規則");
        // Unconditional rule (fallback)
        createHttpRule("/members", "POST", "{\"level\":\"normal\"}", null, 200,
                null, null, null, null, null, null, "預設規則");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // VIP body → Match conditional rules
        HttpEntity<String> vipRequest = new HttpEntity<>("{\"type\":\"vip\"}", headers);
        ResponseEntity<String> vipResponse = restTemplate.exchange(
                "/mock/members", HttpMethod.POST, vipRequest, String.class);
        assertThat(vipResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(vipResponse.getBody()).isEqualTo("{\"level\":\"vip\"}");

        // normal body → fallback to unconditional rule
        HttpEntity<String> normalRequest = new HttpEntity<>("{\"type\":\"normal\"}", headers);
        ResponseEntity<String> normalResponse = restTemplate.exchange(
                "/mock/members", HttpMethod.POST, normalRequest, String.class);
        assertThat(normalResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(normalResponse.getBody()).isEqualTo("{\"level\":\"normal\"}");
    }

    // ========== 3.5 Test Body condition matching (XML) ==========

    @Test
    @DisplayName("XML body conditions: //type=ORDER matches the XML body")
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

    // ========== 3.6 Test Query condition matching ==========

    @Test
    @DisplayName("Query conditions: status=active matches and status=inactive does not")
    void queryCondition_shouldMatchActiveAndRejectInactive() {
        createHttpRule("/items", "GET", "{\"filtered\":true}", null, 200,
                null, "status=active", null, null, null, null, "Query 條件規則");

        HttpHeaders headers = new HttpHeaders();

        // status=active → Match successful
        HttpEntity<Void> activeRequest = new HttpEntity<>(headers);
        ResponseEntity<String> activeResponse = restTemplate.exchange(
                "/mock/items?status=active", HttpMethod.GET, activeRequest, String.class);
        assertThat(activeResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(activeResponse.getBody()).isEqualTo("{\"filtered\":true}");

        // status=inactive → No match (404, because there is no fallback rule)
        HttpEntity<Void> inactiveRequest = new HttpEntity<>(headers);
        ResponseEntity<String> inactiveResponse = restTemplate.exchange(
                "/mock/items?status=inactive", HttpMethod.GET, inactiveRequest, String.class);
        assertThat(inactiveResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ========== 3.7 Test Header condition matching ==========

    @Test
    @DisplayName("Header conditions: X-Tenant=abc matches and a missing X-Tenant does not")
    void headerCondition_shouldMatchWithHeaderAndRejectWithout() {
        createHttpRule("/tenant-data", "GET", "{\"tenant\":\"abc\"}", null, 200,
                null, null, "X-Tenant=abc", null, null, null, "Header 條件規則");

        // with X-Tenant: abc → matched successfully
        HttpHeaders headersWithTenant = new HttpHeaders();
        headersWithTenant.set("X-Tenant", "abc");
        HttpEntity<Void> requestWithTenant = new HttpEntity<>(headersWithTenant);
        ResponseEntity<String> matchResponse = restTemplate.exchange(
                "/mock/tenant-data", HttpMethod.GET, requestWithTenant, String.class);
        assertThat(matchResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(matchResponse.getBody()).isEqualTo("{\"tenant\":\"abc\"}");

        // Without X-Tenant → No match (404)
        HttpEntity<Void> requestWithoutTenant = new HttpEntity<>(new HttpHeaders());
        ResponseEntity<String> noMatchResponse = restTemplate.exchange(
                "/mock/tenant-data", HttpMethod.GET, requestWithoutTenant, String.class);
        assertThat(noMatchResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ========== 3.8 Test multiple conditions to match simultaneously (body + query + header) ==========

    @Test
    @DisplayName("Body, query, and header conditions must all match; partial matches fail")
    void multipleConditions_shouldRequireAllToMatch() {
        createHttpRule("/multi", "POST", "{\"all\":\"matched\"}", null, 200,
                "type=order", "status=active", "X-Tenant=abc",
                null, null, null, "多條件規則");

        HttpHeaders fullHeaders = new HttpHeaders();
        fullHeaders.setContentType(MediaType.APPLICATION_JSON);
        fullHeaders.set("X-Tenant", "abc");

        // All three conditions are met → matching successful
        HttpEntity<String> fullRequest = new HttpEntity<>("{\"type\":\"order\"}", fullHeaders);
        ResponseEntity<String> fullResponse = restTemplate.exchange(
                "/mock/multi?status=active", HttpMethod.POST, fullRequest, String.class);
        assertThat(fullResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fullResponse.getBody()).isEqualTo("{\"all\":\"matched\"}");

        // Only body + query is satisfied, header is missing → does not match
        HttpHeaders partialHeaders = new HttpHeaders();
        partialHeaders.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> partialRequest = new HttpEntity<>("{\"type\":\"order\"}", partialHeaders);
        ResponseEntity<String> partialResponse = restTemplate.exchange(
                "/mock/multi?status=active", HttpMethod.POST, partialRequest, String.class);
        assertThat(partialResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ========== 3.9 Test Customization Response Headers ==========

    @Test
    @DisplayName("Custom response headers include X-Custom")
    void customResponseHeaders_shouldBeIncludedInResponse() {
        createHttpRule("/custom-headers", "GET", "{\"ok\":true}", null, 200,
                null, null, null, "{\"X-Custom\":\"hello\"}", null, null, "自訂 Headers 規則");

        ResponseEntity<String> response = restTemplate.getForEntity(
                "/mock/custom-headers", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("X-Custom")).isEqualTo("hello");
    }

    // ========== 3.10 Test Customization HTTP Status Code ==========

    @Test
    @DisplayName("Custom HTTP status codes: 404 and 500")
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

    // ========== 3.11 Test delayed response (delayMs) ==========

    @Test
    @DisplayName("Delayed response: delayMs=300 takes at least 300 ms")
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

    // ========== 3.12 Testing No Matching Rules + None X-Original-Host → 404 ==========

    @Test
    @DisplayName("No matching rule and no X-Original-Host returns 404")
    void noMatchAndNoOriginalHost_shouldReturn404() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/mock/nonexistent", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("No mock rule found");
    }

    // ========== 3.13 Test wildcard matchKey=* as fallback ==========

    @Test
    @DisplayName("Wildcard matchKey=* provides a fallback for any path")
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

    // ========== 3.14 Test rule priority (priority) ==========

    @Test
    @DisplayName("Rule priority: priority=10 precedes priority=1")
    void rulePriority_shouldPreferHigherPriorityValue() {
        // priority=1 (lower priority)
        createHttpRule("/priority-test", "GET", "{\"priority\":1}", null, 200,
                null, null, null, null, null, 1, "低優先規則");
        // priority=10 (higher priority)
        createHttpRule("/priority-test", "GET", "{\"priority\":10}", null, 200,
                null, null, null, null, null, 10, "高優先規則");

        ResponseEntity<String> response = restTemplate.getForEntity(
                "/mock/priority-test", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("{\"priority\":10}");
    }

    // ========== 3.15 Test targetHost Accuracy Sorting ==========

    @Test
    @DisplayName("Host specificity: rules with targetHost precede rules without it")
    void targetHostPrecision_shouldPreferSpecificHost() {
        // No rule for targetHost
        createHttpRule("/host-test", "GET", "{\"host\":\"none\"}", null, 200,
                null, null, null, null, null, null, "無 host 規則");
        // There is a rule for targetHost
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

    // ========== 3.16 Test Response Template Rendering ==========

    @Test
    @DisplayName("Response templates render {{request.path}} and {{request.query.name}} correctly")
    void responseTemplate_shouldRenderPathAndQueryParams() {
        // Test {{request.path}}
        createHttpRule("/template/path", "GET", "path={{request.path}}");

        ResponseEntity<String> pathResponse = restTemplate.getForEntity(
                "/mock/template/path", String.class);
        assertThat(pathResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pathResponse.getBody()).isEqualTo("path=/template/path");

        // Test {{request.query.name}}
        createHttpRule("/template/query", "GET", "name={{request.query.name}}");

        ResponseEntity<String> queryResponse = restTemplate.getForEntity(
                "/mock/template/query?name=John", String.class);
        assertThat(queryResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(queryResponse.getBody()).isEqualTo("name=John");
    }

    // ========== 3.17 Test Content-Type automatic detection ==========

    @Test
    @DisplayName("Content-Type detection: JSON uses application/json, XML uses application/xml, and plain text uses text/plain")
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

        // Plain text body
        createHttpRule("/ct/text", "GET", "plain text content");
        ResponseEntity<String> textResponse = restTemplate.getForEntity(
                "/mock/ct/text", String.class);
        assertThat(textResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(textResponse.getHeaders().getContentType()).isNotNull();
        assertThat(textResponse.getHeaders().getContentType().isCompatibleWith(MediaType.TEXT_PLAIN)).isTrue();
    }
}
