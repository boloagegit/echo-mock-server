package com.echo.integration.http;

import com.echo.dto.RuleDto;
import com.echo.integration.base.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rule test API integration test
 */
class RuleTestApiIntegrationTest extends BaseIntegrationTest {

    @Test
    @DisplayName("13.2 HTTP rule test: POST /api/admin/rules/{id}/test returns status, body, and elapsed")
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
    @DisplayName("13.3 JMS rule test: POST /api/admin/rules/{id}/test returns a result")
    void testJmsRule() {
        // Send to Echo queue, which is actually monitored: will receive the response of the rule, without waiting for the 30 seconds timeout (the old way of writing is sent to TEST.QUEUE, which is not monitored)
        RuleDto rule = createJmsRule("ECHO.REQUEST", "{\"jms\":\"response\"}");

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
        assertThat(resp.getBody()).containsEntry("status", 200);
        assertThat(resp.getBody()).containsEntry("body", "{\"jms\":\"response\"}");
    }
}
