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
 * 規則啟用/停用、保護與展延整合測試
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
    @DisplayName("12.2 停用規則後不參與 Mock 匹配")
    void disableRule_notMatchedByMock() {
        // 不設 targetHost，避免 proxy forwarding 導致 502
        RuleDto rule = createHttpRule("/api/lifecycle-dis", "GET", "{\"ok\":true}");

        // 停用（使用 exchange）
        ResponseEntity<Map> disResp = putJson(
                "/api/admin/rules/" + rule.getId() + "/disable", null);
        assertThat(disResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        clearCaches();

        // Mock 請求應不匹配（無 X-Original-Host → 回傳 404 而非 proxy）
        ResponseEntity<String> mockResp = restTemplate.getForEntity(
                "/mock/api/lifecycle-dis", String.class);
        assertThat(mockResp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("12.3 重新啟用規則後恢復匹配")
    void enableRule_resumesMatching() {
        RuleDto rule = createHttpRule("/api/lifecycle-en", "GET", "{\"ok\":true}");

        // 停用 → 啟用
        putJson("/api/admin/rules/" + rule.getId() + "/disable", null);
        putJson("/api/admin/rules/" + rule.getId() + "/enable", null);
        clearCaches();

        ResponseEntity<String> mockResp = restTemplate.getForEntity(
                "/mock/api/lifecycle-en", String.class);
        assertThat(mockResp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("12.4 批次啟用/停用")
    void batchEnableDisable() {
        RuleDto r1 = createHttpRule("/api/batch-a", "GET", "{\"a\":true}");
        RuleDto r2 = createHttpRule("/api/batch-b", "GET", "{\"b\":true}");
        List<String> ids = List.of(r1.getId(), r2.getId());

        // 批次停用
        ResponseEntity<Map> disResp = putJson("/api/admin/rules/batch/disable", ids);
        assertThat(disResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) disResp.getBody().get("updated")).intValue()).isEqualTo(2);

        assertThat(getRule(r1.getId()).getEnabled()).isFalse();
        assertThat(getRule(r2.getId()).getEnabled()).isFalse();

        // 批次啟用
        ResponseEntity<Map> enResp = putJson("/api/admin/rules/batch/enable", ids);
        assertThat(enResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) enResp.getBody().get("updated")).intValue()).isEqualTo(2);

        assertThat(getRule(r1.getId()).getEnabled()).isTrue();
        assertThat(getRule(r2.getId()).getEnabled()).isTrue();
    }

    @Test
    @DisplayName("12.5 規則保護與取消保護")
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
    @DisplayName("12.6 批次保護/取消保護")
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
    @DisplayName("12.7 規則展延 → extendedAt 被更新")
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
    @DisplayName("12.8 批次展延 → 多個規則 extendedAt 被更新")
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
    @DisplayName("12.9 按標籤啟用/停用")
    void enableDisableByTag() {
        // 建立規則，然後更新 tags
        RuleDto rule = createHttpRule("/api/tag-test", "GET", "{\"ok\":true}");
        rule.setTags("{\"env\":\"sit\"}");
        HttpHeaders jsonHeaders = new HttpHeaders();
        jsonHeaders.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<RuleDto> updateResp = adminClient().exchange(
                "/api/admin/rules/" + rule.getId(), HttpMethod.PUT,
                new HttpEntity<>(rule, jsonHeaders), RuleDto.class);
        assertThat(updateResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 按標籤停用
        ResponseEntity<Map> disResp = putJson(
                "/api/admin/rules/tag/env/sit/disable", null);
        assertThat(disResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        RuleDto disabled = getRule(rule.getId());
        assertThat(disabled.getEnabled()).isFalse();

        // 按標籤啟用
        ResponseEntity<Map> enResp = putJson(
                "/api/admin/rules/tag/env/sit/enable", null);
        assertThat(enResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        RuleDto enabled = getRule(rule.getId());
        assertThat(enabled.getEnabled()).isTrue();
    }
}
