package com.echo.integration.http;

import com.echo.dto.RuleDto;
import com.echo.integration.base.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rule enablement/deactivation, protection and extended integration testing
 */
class RuleLifecycleIntegrationTest extends BaseIntegrationTest {

    private RuleDto getRule(String id) {
        ResponseEntity<RuleDto> resp = adminClient().getForEntity(
                "/api/admin/rules/" + id, RuleDto.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody();
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> putJson(String url, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return adminClient().exchange(url, HttpMethod.PUT,
                new HttpEntity<>(body, headers), Map.class);
    }

    private void clearCaches() {
        cacheManager.getCacheNames().forEach(n -> {
            var c = cacheManager.getCache(n);
            if (c != null) {
                c.clear();
            }
        });
    }

    @Test
    @DisplayName("12.2 Disabled rules do not participate in mock matching")
    void disableRule_notMatchedByMock() {
        // Do not set targetHost to avoid proxy forwarding causing 502
        RuleDto rule = createHttpRule("/api/lifecycle-dis", "GET", "{\"ok\":true}");

        // Disabled (using exchange)
        ResponseEntity<Map> disResp = putJson(
                "/api/admin/rules/" + rule.getId() + "/disable", null);
        assertThat(disResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        clearCaches();

        // Mock request should not match (no X-Original-Host → return 404 instead of proxy)
        ResponseEntity<String> mockResp = restTemplate.getForEntity(
                "/mock/api/lifecycle-dis", String.class);
        assertThat(mockResp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("12.3 Re-enabling a rule restores matching")
    void enableRule_resumesMatching() {
        RuleDto rule = createHttpRule("/api/lifecycle-en", "GET", "{\"ok\":true}");

        // Disable → Enable
        putJson("/api/admin/rules/" + rule.getId() + "/disable", null);
        putJson("/api/admin/rules/" + rule.getId() + "/enable", null);
        clearCaches();

        ResponseEntity<String> mockResp = restTemplate.getForEntity(
                "/mock/api/lifecycle-en", String.class);
        assertThat(mockResp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("12.4 Batch enable and disable")
    void batchEnableDisable() {
        RuleDto r1 = createHttpRule("/api/batch-a", "GET", "{\"a\":true}");
        RuleDto r2 = createHttpRule("/api/batch-b", "GET", "{\"b\":true}");
        List<String> ids = List.of(r1.getId(), r2.getId());

        // Batch deactivation
        ResponseEntity<Map> disResp = putJson("/api/admin/rules/batch/disable", ids);
        assertThat(disResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) disResp.getBody().get("updated")).intValue()).isEqualTo(2);

        assertThat(getRule(r1.getId()).getEnabled()).isFalse();
        assertThat(getRule(r2.getId()).getEnabled()).isFalse();

        // Batch enable
        ResponseEntity<Map> enResp = putJson("/api/admin/rules/batch/enable", ids);
        assertThat(enResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) enResp.getBody().get("updated")).intValue()).isEqualTo(2);

        assertThat(getRule(r1.getId()).getEnabled()).isTrue();
        assertThat(getRule(r2.getId()).getEnabled()).isTrue();
    }

    @Test
    @DisplayName("12.5 Protect and unprotect rules")
    void protectAndUnprotect() {
        RuleDto rule = createHttpRule("/api/protect-test", "GET", "{\"ok\":true}");

        ResponseEntity<Map> protResp = putJson(
                "/api/admin/rules/" + rule.getId() + "/protect", null);
        assertThat(protResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getRule(rule.getId()).getIsProtected()).isTrue();

        ResponseEntity<Map> unprotResp = putJson(
                "/api/admin/rules/" + rule.getId() + "/unprotect", null);
        assertThat(unprotResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getRule(rule.getId()).getIsProtected()).isFalse();
    }

    @Test
    @DisplayName("12.6 Batch protect and unprotect")
    void batchProtectUnprotect() {
        RuleDto r1 = createHttpRule("/api/bp-a", "GET", "{\"a\":true}");
        RuleDto r2 = createHttpRule("/api/bp-b", "GET", "{\"b\":true}");
        List<String> ids = List.of(r1.getId(), r2.getId());

        ResponseEntity<Map> protResp = putJson("/api/admin/rules/batch/protect", ids);
        assertThat(protResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) protResp.getBody().get("updated")).intValue()).isEqualTo(2);
        assertThat(getRule(r1.getId()).getIsProtected()).isTrue();

        ResponseEntity<Map> unprotResp = putJson("/api/admin/rules/batch/unprotect", ids);
        assertThat(unprotResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getRule(r1.getId()).getIsProtected()).isFalse();
    }

    @Test
    @DisplayName("12.7 Extending a rule updates extendedAt")
    void extendRule_updatesExtendedAt() {
        RuleDto rule = createHttpRule("/api/extend-test", "GET", "{\"ok\":true}");
        assertThat(rule.getExtendedAt()).isNull();

        ResponseEntity<Map> resp = putJson(
                "/api/admin/rules/" + rule.getId() + "/extend", null);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        RuleDto updated = getRule(rule.getId());
        assertThat(updated.getExtendedAt()).isNotNull();
    }

    @Test
    @DisplayName("12.8 Batch extension updates extendedAt for multiple rules")
    void batchExtend() {
        RuleDto r1 = createHttpRule("/api/bext-a", "GET", "{\"a\":true}");
        RuleDto r2 = createHttpRule("/api/bext-b", "GET", "{\"b\":true}");
        List<String> ids = List.of(r1.getId(), r2.getId());

        ResponseEntity<Map> resp = putJson("/api/admin/rules/batch/extend", ids);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) resp.getBody().get("updated")).intValue()).isEqualTo(2);

        assertThat(getRule(r1.getId()).getExtendedAt()).isNotNull();
        assertThat(getRule(r2.getId()).getExtendedAt()).isNotNull();
    }

    @Test
    @DisplayName("12.9 Enable and disable rules by tag")
    void enableDisableByTag() {
        // Create rules and then update tags
        RuleDto rule = createHttpRule("/api/tag-test", "GET", "{\"ok\":true}");
        rule.setTags("{\"env\":\"sit\"}");
        HttpHeaders jsonHeaders = new HttpHeaders();
        jsonHeaders.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<RuleDto> updateResp = adminClient().exchange(
                "/api/admin/rules/" + rule.getId(), HttpMethod.PUT,
                new HttpEntity<>(rule, jsonHeaders), RuleDto.class);
        assertThat(updateResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Deactivate by label
        ResponseEntity<Map> disResp = putJson(
                "/api/admin/rules/tag/env/sit/disable", null);
        assertThat(disResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        RuleDto disabled = getRule(rule.getId());
        assertThat(disabled.getEnabled()).isFalse();

        // Enable by tag
        ResponseEntity<Map> enResp = putJson(
                "/api/admin/rules/tag/env/sit/enable", null);
        assertThat(enResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        RuleDto enabled = getRule(rule.getId());
        assertThat(enabled.getEnabled()).isTrue();
    }
}
