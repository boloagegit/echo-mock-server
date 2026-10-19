package com.echo.integration.base;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke Test: Confirm that Application Context starts normally and basic API is available
 */
class SmokeIntegrationTest extends BaseIntegrationTest {

    @Test
    @DisplayName("Application Context 正常啟動，GET /api/admin/status 回傳 200")
    @SuppressWarnings("unchecked")
    void statusEndpoint_shouldReturn200WithExpectedFields() {
        ResponseEntity<Map> response = restTemplate.getForEntity("/api/admin/status", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();

        // Verify required fields exist
        assertThat(body).containsKey("serverPort");
        assertThat(body).containsKey("jmsEnabled");
        assertThat(body).containsKey("isLoggedIn");
        assertThat(body).containsKey("version");
        assertThat(body).containsKey("envLabel");

        // Verify JMS is enabled under test profile
        assertThat(body.get("jmsEnabled")).isEqualTo(true);

        // Verify envLabel as TEST (application-test.yml setting)
        assertThat(body.get("envLabel")).isEqualTo("TEST");

        // isLoggedIn should be false when not authenticated
        assertThat(body.get("isLoggedIn")).isEqualTo(false);

        // version should exist (dev or actual version number)
        assertThat(body.get("version")).isNotNull();
    }

    @Test
    @DisplayName("帶 admin 認證的 GET /api/admin/status 回傳 isLoggedIn=true")
    @SuppressWarnings("unchecked")
    void statusEndpoint_withAuth_shouldReturnLoggedIn() {
        ResponseEntity<Map> response = adminClient()
                .getForEntity("/api/admin/status", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("isLoggedIn")).isEqualTo(true);
        assertThat(body.get("isAdmin")).isEqualTo(true);
        assertThat(body.get("username")).isEqualTo("admin");
    }
}
