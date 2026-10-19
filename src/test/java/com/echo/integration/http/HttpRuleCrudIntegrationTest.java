package com.echo.integration.http;

import com.echo.dto.RuleDto;
import com.echo.entity.Protocol;
import com.echo.config.CacheConfig;
import com.echo.integration.base.BaseIntegrationTest;
import com.echo.service.HttpRuleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP General Rules CRUD Integration Test
 */
class HttpRuleCrudIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private HttpRuleService httpRuleService;

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
    @DisplayName("Creating an HTTP rule returns 201 and a DTO with id, protocol, matchKey, and responseId")
    void createHttpRule_shouldReturn201WithCorrectFields() {
        RuleDto created = createHttpRule("/api/test", "GET", "{\"ok\":true}");

        assertThat(created.getId()).isNotBlank();
        assertThat(created.getProtocol()).isEqualTo(Protocol.HTTP);
        assertThat(created.getMatchKey()).isEqualTo("/api/test");
        assertThat(created.getResponseId()).isNotNull();
        assertThat(created.getCreatedBy()).isEqualTo("admin");
        assertThat(created.getUpdatedBy()).isEqualTo("admin");
    }

    @Test
    @DisplayName("Reading an HTTP rule returns 200 and correct fields, including responseBody")
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
    @DisplayName("Updating an HTTP rule description returns 200 and a subsequent GET confirms the change")
    void updateHttpRule_shouldUpdateDescription() {
        RuleDto created = createHttpRule("/api/update", "POST", "{\"updated\":false}");

        // GET first to obtain complete dto (including version)
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

        // Confirm again GET
        ResponseEntity<RuleDto> verifyResponse = adminClient()
                .getForEntity("/api/admin/rules/" + created.getId(), RuleDto.class);
        assertThat(verifyResponse.getBody()).isNotNull();
        assertThat(verifyResponse.getBody().getDescription()).isEqualTo("已更新的描述");
        assertThat(verifyResponse.getBody().getCreatedBy()).isEqualTo("admin");
        assertThat(verifyResponse.getBody().getUpdatedBy()).isEqualTo("admin");
    }

    @Test
    @DisplayName("Deleting an HTTP rule returns 204 and a subsequent GET returns 404")
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
    @DisplayName("Creating 3 rules makes GET /api/admin/rules return 3 entries")
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
    @DisplayName("Dynamic URLs share the host+method rule cache while preserving exact and wildcard matching")
    void dynamicPaths_shouldSharePreparedRuleSetCache() {
        createHttpRule("/users/*", "GET", "{\"kind\":\"wildcard\"}");
        RuleDto exact = createHttpRule("/users/42", "GET", "{\"kind\":\"exact\"}");

        List<com.echo.entity.HttpRule> first =
                httpRuleService.findPreparedHttpRules("default", "/users/1", "GET");
        List<com.echo.entity.HttpRule> second =
                httpRuleService.findPreparedHttpRules("default", "/users/42", "GET");

        assertThat(first).extracting(com.echo.entity.HttpRule::getMatchKey)
                .containsExactly("/users/*");
        assertThat(second).extracting(com.echo.entity.HttpRule::getId)
                .contains(exact.getId());
        assertThat(second).extracting(com.echo.entity.HttpRule::getMatchKey)
                .contains("/users/42", "/users/*");

        var springCache = cacheManager.getCache(CacheConfig.HTTP_RULES_CACHE);
        assertThat(springCache).isNotNull();
        var nativeCache = (com.github.benmanes.caffeine.cache.Cache<?, ?>)
                springCache.getNativeCache();
        // Different dynamic URL do not occupy cache items respectively; the shared rule set is placed in the rule snapshot (RuleSnapshots)
        assertThat(nativeCache.estimatedSize()).isZero();
    }

    @Test
    @DisplayName("Large response bodies load only in individual rule details while lists retain all metadata")
    void listRules_shouldExcludeLargeResponseBodyWithoutBreakingDetail() {
        String largeBody = "x".repeat(512 * 1024);
        RuleDto created = createHttpRule("/api/large-response", "GET", largeBody);

        ResponseEntity<RuleDto[]> listResponse = adminClient()
                .getForEntity("/api/admin/rules", RuleDto[].class);

        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResponse.getBody()).isNotNull();
        assertThat(listResponse.getBody()).hasSize(1);
        RuleDto summary = listResponse.getBody()[0];
        assertThat(summary.getId()).isEqualTo(created.getId());
        assertThat(summary.getResponseId()).isEqualTo(created.getResponseId());
        assertThat(summary.getMatchKey()).isEqualTo("/api/large-response");
        assertThat(summary.getResponseBody()).isNull();

        ResponseEntity<RuleDto> detailResponse = restTemplate
                .getForEntity("/api/admin/rules/" + created.getId(), RuleDto.class);

        assertThat(detailResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(detailResponse.getBody()).isNotNull();
        assertThat(detailResponse.getBody().getResponseBody()).isEqualTo(largeBody);
    }

    @Test
    @DisplayName("Unauthenticated POST /api/admin/rules is rejected with 401 or a 302 login redirect")
    void createRuleWithoutAuth_shouldBeRejected() {
        RuleDto dto = RuleDto.builder()
                .protocol(Protocol.HTTP)
                .matchKey("/api/noauth")
                .method("GET")
                .responseBody("{\"fail\":true}")
                .build();

        ResponseEntity<String> response = restTemplate
                .postForEntity("/api/admin/rules", dto, String.class);

        // Customized noPopupEntryPoint returns 401, not redirect
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Paginated lists and status allow anonymous reads; retrieving all rules or responses requires login")
    @SuppressWarnings("unchecked")
    void readOnlyEndpoints_shouldNotRequireAuth() {
        // The paging list used in the screen does not require authentication.
        ResponseEntity<Map> pageResponse = restTemplate
                .getForEntity("/api/admin/rules/page", Map.class);
        assertThat(pageResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Retrieving all rules/all response contents at one time requires logging in
        assertThat(restTemplate.getForEntity("/api/admin/rules", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(restTemplate.getForEntity("/api/admin/responses", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<List> rulesResponse = adminClient()
                .getForEntity("/api/admin/rules", List.class);
        assertThat(rulesResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // GET /api/admin/status does not require certification
        ResponseEntity<String> statusResponse = restTemplate
                .getForEntity("/api/admin/status", String.class);
        assertThat(statusResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
