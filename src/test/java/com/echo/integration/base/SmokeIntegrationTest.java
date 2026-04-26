package com.echo.integration.base;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke Test：確認 Application Context 正常啟動、基本 API 可用
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

        // 驗證必要欄位存在
        assertThat(body).containsKey("serverPort");
        assertThat(body).containsKey("jmsEnabled");
        assertThat(body).containsKey("isLoggedIn");
        assertThat(body).containsKey("version");
        assertThat(body).containsKey("envLabel");

        // 驗證 JMS 在 test profile 下啟用
        assertThat(body.get("jmsEnabled")).isEqualTo(true);

        // 驗證 envLabel 為 TEST（application-test.yml 設定）
        assertThat(body.get("envLabel")).isEqualTo("TEST");

        // 未認證時 isLoggedIn 應為 false
        assertThat(body.get("isLoggedIn")).isEqualTo(false);

        // version 應存在（dev 或實際版本號）
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
