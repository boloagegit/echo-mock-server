package com.echo.integration.http;

import com.echo.dto.RuleDto;
import com.echo.entity.Protocol;
import com.echo.integration.base.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP 一般規則 CRUD 整合測試
 */
class HttpRuleCrudIntegrationTest extends BaseIntegrationTest {

    @org.junit.jupiter.api.BeforeEach
    void ensureCleanState() {
        adminClient().exchange("/api/admin/rules/all",
                HttpMethod.DELETE, null, Void.class);
        cacheManager.getCacheNames().forEach(name -> {
            var cache = cacheManager.getCache(name);
            if (cache != null) {
                cache.clear();
            }
        });
    }

    @Test
    @DisplayName("建立 HTTP 規則 → 201，回傳 dto 包含 id、protocol、matchKey、responseId")
    void createHttpRule_shouldReturn201WithCorrectFields() {
        RuleDto created = createHttpRule("/api/test", "GET", "{\"ok\":true}");

        assertThat(created.getId()).isNotBlank();
        assertThat(created.getProtocol()).isEqualTo(Protocol.HTTP);
        assertThat(created.getMatchKey()).isEqualTo("/api/test");
        assertThat(created.getResponseId()).isNotNull();
    }

    @Test
    @DisplayName("讀取 HTTP 規則 → 200，欄位正確（含 responseBody）")
    void getHttpRule_shouldReturn200WithResponseBody() {
        RuleDto created = createHttpRule("/api/read", "GET", "{\"data\":\"hello\"}");

        ResponseEntity<RuleDto> response = adminClient()
                .getForEntity("/api/admin/rules/" + created.getId(), RuleDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        RuleDto fetched = response.getBody();
        assertThat(fetched).isNotNull();
        assertThat(fetched.getId()).isEqualTo(created.getId());
        assertThat(fetched.getProtocol()).isEqualTo(Protocol.HTTP);
        assertThat(fetched.getMatchKey()).isEqualTo("/api/read");
        assertThat(fetched.getMethod()).isEqualTo("GET");
        assertThat(fetched.getResponseBody()).isEqualTo("{\"data\":\"hello\"}");
        assertThat(fetched.getResponseId()).isEqualTo(created.getResponseId());
    }

    @Test
    @DisplayName("更新 HTTP 規則 description → 200，再 GET 確認已更新")
    void updateHttpRule_shouldUpdateDescription() {
        RuleDto created = createHttpRule("/api/update", "POST", "{\"updated\":false}");

        // 先 GET 取得完整 dto（含 version）
        ResponseEntity<RuleDto> getResponse = adminClient()
                .getForEntity("/api/admin/rules/" + created.getId(), RuleDto.class);
        RuleDto toUpdate = getResponse.getBody();
        assertThat(toUpdate).isNotNull();

        toUpdate.setDescription("已更新的描述");

        ResponseEntity<RuleDto> putResponse = adminClient().exchange(
                "/api/admin/rules/" + created.getId(),
                HttpMethod.PUT,
                new HttpEntity<>(toUpdate),
                RuleDto.class);

        assertThat(putResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 再 GET 確認
        ResponseEntity<RuleDto> verifyResponse = adminClient()
                .getForEntity("/api/admin/rules/" + created.getId(), RuleDto.class);
        assertThat(verifyResponse.getBody()).isNotNull();
        assertThat(verifyResponse.getBody().getDescription()).isEqualTo("已更新的描述");
    }

    @Test
    @DisplayName("刪除 HTTP 規則 → 204，再 GET → 404")
    void deleteHttpRule_shouldReturn204ThenGetReturns404() {
        RuleDto created = createHttpRule("/api/delete", "DELETE", "{\"deleted\":true}");

        ResponseEntity<Void> deleteResponse = adminClient().exchange(
                "/api/admin/rules/" + created.getId(),
                HttpMethod.DELETE,
                null,
                Void.class);
        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<RuleDto> getResponse = adminClient()
                .getForEntity("/api/admin/rules/" + created.getId(), RuleDto.class);
        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("建立 3 個規則 → GET /api/admin/rules 回傳 3 筆")
    @SuppressWarnings("unchecked")
    void listRules_shouldReturnAllCreatedRules() {
        createHttpRule("/api/list/1", "GET", "{\"n\":1}");
        createHttpRule("/api/list/2", "GET", "{\"n\":2}");
        createHttpRule("/api/list/3", "GET", "{\"n\":3}");

        ResponseEntity<List> response = adminClient()
                .getForEntity("/api/admin/rules", List.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).hasSize(3);
    }

    @Test
    @DisplayName("未認證的 POST /api/admin/rules → 被拒絕（401 或 302 redirect to login）")
    void createRuleWithoutAuth_shouldBeRejected() {
        RuleDto dto = RuleDto.builder()
                .protocol(Protocol.HTTP)
                .matchKey("/api/noauth")
                .method("GET")
                .responseBody("{\"fail\":true}")
                .build();

        ResponseEntity<String> response = restTemplate
                .postForEntity("/api/admin/rules", dto, String.class);

        // 自訂 noPopupEntryPoint 回傳 401，不會 redirect
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("唯讀 API 不需認證：GET /api/admin/rules → 200，GET /api/admin/status → 200")
    @SuppressWarnings("unchecked")
    void readOnlyEndpoints_shouldNotRequireAuth() {
        // GET /api/admin/rules 不需認證
        ResponseEntity<List> rulesResponse = restTemplate
                .getForEntity("/api/admin/rules", List.class);
        assertThat(rulesResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // GET /api/admin/status 不需認證
        ResponseEntity<String> statusResponse = restTemplate
                .getForEntity("/api/admin/status", String.class);
        assertThat(statusResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
