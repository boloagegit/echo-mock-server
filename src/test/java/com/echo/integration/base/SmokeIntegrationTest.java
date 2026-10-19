package com.echo.integration.base;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke Test: Confirm that Application Context starts normally and basic API is available
 */
class SmokeIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private Optional<BuildProperties> buildProperties;

    @Test
    @DisplayName("The application context starts and GET /api/admin/status returns 200")
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

        Map<String, Object> build = (Map<String, Object>) body.get("build");
        assertThat(build).containsKeys("commit", "shortCommit", "dirty", "time");
        String commit = buildProperties.map(properties -> properties.get("git.commit")).orElse(null);
        if (commit != null && !commit.isBlank()) {
            assertThat(build.get("commit")).isEqualTo(commit);
            assertThat(build.get("shortCommit")).isEqualTo(commit.substring(0, 12));
            assertThat(build.get("time")).isEqualTo(buildProperties.orElseThrow().getTime().toString());
        } else {
            assertThat(build.get("commit")).isNull();
            assertThat(build.get("shortCommit")).isNull();
        }
    }

    @Test
    @DisplayName("GET /api/admin/status with admin credentials returns isLoggedIn=true")
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
