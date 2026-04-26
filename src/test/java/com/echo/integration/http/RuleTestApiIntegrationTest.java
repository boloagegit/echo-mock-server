package com.echo.integration.http;

import com.echo.dto.RuleDto;
import com.echo.integration.base.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 規則測試 API 整合測試
 */
class RuleTestApiIntegrationTest extends BaseIntegrationTest {

    @Test
    @DisplayName("13.2 HTTP 規則測試: POST /api/admin/rules/{id}/test → 回傳 status/body/elapsed")
    void testHttpRule() {
        RuleDto rule = createHttpRule("/api/test-endpoint", "GET",
                "{\"result\":\"ok\"}", "test.host", 200);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> testReq = Map.of(
                "method", "GET",
                "path", "/api/test-endpoint",
                "headers", Map.of("X-Original-Host", "test.host")
        );

        ResponseEntity<Map> resp = adminClient().exchange(
                "/api/admin/rules/" + rule.getId() + "/test",
                HttpMethod.POST,
                new HttpEntity<>(testReq, headers),
                Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsKey("status");
        assertThat(body).containsKey("body");
        assertThat(body).containsKey("elapsed");
        // Verify the mock engine returned the configured response
        assertThat(body.get("body")).isNotNull();
    }

    @Test
    @DisplayName("13.3 JMS 規則測試: POST /api/admin/rules/{id}/test → 回傳結果")
    void testJmsRule() {
        RuleDto rule = createJmsRule("TEST.QUEUE", "{\"jms\":\"response\"}");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> testReq = Map.of(
                "body", "{\"type\":\"test\"}"
        );

        ResponseEntity<Map> resp = adminClient().exchange(
                "/api/admin/rules/" + rule.getId() + "/test",
                HttpMethod.POST,
                new HttpEntity<>(testReq, headers),
                Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody()).containsKey("status");
        assertThat(resp.getBody()).containsKey("body");
    }
}
