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
 * 審計日誌整合測試
 */
class AuditLogIntegrationTest extends BaseIntegrationTest {

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getRuleAuditLogs(String ruleId) {
        ResponseEntity<List> resp = adminClient().getForEntity(
                "/api/admin/rules/" + ruleId + "/audit", List.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getAllAuditLogs(String queryParams) {
        String url = "/api/admin/audit" + (queryParams != null ? "?" + queryParams : "");
        ResponseEntity<List> resp = adminClient().getForEntity(url, List.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody();
    }

    private ResponseEntity<RuleDto> updateRule(String id, RuleDto dto) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return adminClient().exchange("/api/admin/rules/" + id,
                HttpMethod.PUT, new HttpEntity<>(dto, headers), RuleDto.class);
    }

    @Test
    @DisplayName("11.2 規則建立產生 CREATE 審計記錄")
    void createRule_generatesCreateAudit() {
        RuleDto rule = createHttpRule("/api/audit-create", "GET", "{\"ok\":true}");

        List<Map<String, Object>> audits = getRuleAuditLogs(rule.getId());
        assertThat(audits).isNotEmpty();
        assertThat(audits.get(0).get("action")).isEqualTo("CREATE");
        assertThat(audits.get(0).get("ruleId")).isEqualTo(rule.getId());
        assertThat(audits.get(0).get("afterJson")).isNotNull();
    }

    @Test
    @DisplayName("11.3 規則更新產生 UPDATE 審計記錄（含 before/after）")
    void updateRule_generatesUpdateAudit() {
        RuleDto rule = createHttpRule("/api/audit-update", "GET", "{\"ok\":true}");

        // 更新 description（使用 exchange + JSON Content-Type）
        rule.setDescription("已更新的描述");
        ResponseEntity<RuleDto> updateResp = updateRule(rule.getId(), rule);
        assertThat(updateResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<Map<String, Object>> audits = getRuleAuditLogs(rule.getId());
        // 應有 CREATE + UPDATE
        assertThat(audits.size()).isGreaterThanOrEqualTo(2);
        Map<String, Object> updateAudit = audits.stream()
                .filter(a -> "UPDATE".equals(a.get("action")))
                .findFirst().orElse(null);
        assertThat(updateAudit).isNotNull();
        assertThat(updateAudit.get("beforeJson")).isNotNull();
        assertThat(updateAudit.get("afterJson")).isNotNull();
    }

    @Test
    @DisplayName("11.4 規則刪除產生 DELETE 審計記錄")
    void deleteRule_generatesDeleteAudit() {
        RuleDto rule = createHttpRule("/api/audit-delete", "GET", "{\"ok\":true}");
        String ruleId = rule.getId();

        // 使用 exchange 確保 DELETE 帶認證
        ResponseEntity<Void> delResp = adminClient().exchange(
                "/api/admin/rules/" + ruleId, HttpMethod.DELETE, null, Void.class);
        assertThat(delResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // 刪除後用全域審計查詢
        List<Map<String, Object>> allAudits = getAllAuditLogs(null);
        Map<String, Object> deleteAudit = allAudits.stream()
                .filter(a -> ruleId.equals(a.get("ruleId")) && "DELETE".equals(a.get("action")))
                .findFirst().orElse(null);
        assertThat(deleteAudit).isNotNull();
        assertThat(deleteAudit.get("beforeJson")).isNotNull();
    }

    @Test
    @DisplayName("11.5 全部審計日誌查詢 + limit 參數")
    void getAllAuditLogs_withLimit() {
        createHttpRule("/api/audit-a", "GET", "{\"a\":true}");
        createHttpRule("/api/audit-b", "GET", "{\"b\":true}");
        createHttpRule("/api/audit-c", "GET", "{\"c\":true}");

        List<Map<String, Object>> all = getAllAuditLogs(null);
        assertThat(all.size()).isGreaterThanOrEqualTo(3);

        List<Map<String, Object>> limited = getAllAuditLogs("limit=2");
        assertThat(limited.size()).isLessThanOrEqualTo(2);
    }

    @Test
    @DisplayName("11.6 清除全部審計日誌")
    void deleteAllAuditLogs() {
        createHttpRule("/api/audit-clear", "GET", "{\"ok\":true}");

        List<Map<String, Object>> before = getAllAuditLogs(null);
        assertThat(before).isNotEmpty();

        ResponseEntity<Map> resp = adminClient().exchange(
                "/api/admin/audit/all", HttpMethod.DELETE, null, Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsKey("deleted");

        List<Map<String, Object>> after = getAllAuditLogs(null);
        assertThat(after).isEmpty();
    }
}
