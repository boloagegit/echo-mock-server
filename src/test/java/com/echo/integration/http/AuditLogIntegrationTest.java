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
        assertThat(updateAudit.get("operator")).isNotNull();
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
        assertThat(deleteAudit.get("operator")).isNotNull();
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
    @DisplayName("11.5.1 修訂摘要分頁不含 JSON，單筆明細保留完整內容")
    void pagedAuditSummary_andLazyDetail() {
        RuleDto rule = createHttpRule("/api/audit-detail", "GET", "{\"ok\":true}");
        rule.setDescription("lazy-detail-description");
        updateRule(rule.getId(), rule);

        ResponseEntity<Map> pageResponse = adminClient().getForEntity(
                "/api/admin/audit?page=0&size=1&action=UPDATE&keyword=" + rule.getId(), Map.class);

        assertThat(pageResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pageResponse.getBody()).isNotNull();
        assertThat(pageResponse.getBody().get("totalElements")).isEqualTo(1);
        List<Map<String, Object>> results = (List<Map<String, Object>>) pageResponse.getBody().get("results");
        assertThat(results).singleElement().satisfies(summary -> {
            assertThat(summary).containsEntry("ruleId", rule.getId());
            assertThat(summary).containsEntry("hasBeforeJson", true);
            assertThat(summary).doesNotContainKeys("beforeJson", "afterJson");
        });

        Number auditId = (Number) results.get(0).get("id");
        ResponseEntity<Map> detailResponse = adminClient().getForEntity(
                "/api/admin/audit?detailId=" + auditId.longValue(), Map.class);
        assertThat(detailResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(detailResponse.getBody().get("beforeJson").toString()).contains("/api/audit-detail");
        assertThat(detailResponse.getBody().get("afterJson").toString()).contains("lazy-detail-description");
    }

    @Test
    @DisplayName("11.5.2 修訂列表直接附上名稱與改了什麼，並可用路徑搜尋、依類型篩選")
    @SuppressWarnings("unchecked")
    void pagedAuditSummary_carriesNameAndChangesForSearch() {
        RuleDto rule = createHttpRule("/api/audit-summary-path", "GET", "{\"ok\":true}");
        rule.setPriority(7);
        updateRule(rule.getId(), rule);

        ResponseEntity<Map> byPath = adminClient().getForEntity(
                "/api/admin/audit?page=0&size=5&action=UPDATE&type=RULE&keyword=audit-summary-PATH", Map.class);

        assertThat(byPath.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> results = (List<Map<String, Object>>) byPath.getBody().get("results");
        assertThat(results).singleElement().satisfies(summary -> {
            assertThat(summary).containsEntry("targetProtocol", "HTTP")
                    .containsEntry("targetName", "/api/audit-summary-path")
                    .doesNotContainKeys("beforeJson", "afterJson");
            Map<String, Object> listSummary = (Map<String, Object>) summary.get("summary");
            assertThat(listSummary).containsEntry("method", "GET").containsEntry("changeCount", 1);
            assertThat((List<Map<String, Object>>) listSummary.get("changes")).singleElement()
                    .satisfies(change -> assertThat(change).containsEntry("f", "priority").containsEntry("a", 7));
        });

        ResponseEntity<Map> responsesOnly = adminClient().getForEntity(
                "/api/admin/audit?page=0&size=5&type=RESPONSE&keyword=audit-summary-path", Map.class);
        assertThat(responsesOnly.getBody().get("totalElements")).isEqualTo(0);

        ResponseEntity<Map> invalidType = adminClient().getForEntity(
                "/api/admin/audit?page=0&size=5&type=ISSUE", Map.class);
        assertThat(invalidType.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("11.5.3 只改 SSE 回應內容時，摘要不會多出內容類型的變更；列表附上伺服器今天日期")
    @SuppressWarnings("unchecked")
    void sseResponseBodyEdit_listsOnlyTheBody() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> created = adminClient().exchange("/api/admin/responses", HttpMethod.POST,
                new HttpEntity<>(Map.of("description", "audit-sse-only-body", "contentType", "SSE",
                        "body", "[{\"type\":\"normal\",\"data\":\"a\"}]"), headers), Map.class);
        assertThat(created.getStatusCode().is2xxSuccessful()).as(created.getStatusCode() + " " + created.getBody()).isTrue();
        Number id = (Number) created.getBody().get("id");
        adminClient().exchange("/api/admin/responses/" + id.longValue(), HttpMethod.PUT,
                new HttpEntity<>(Map.of("body", "[{\"type\":\"normal\",\"data\":\"b\"}]"), headers), Map.class);

        ResponseEntity<Map> page = adminClient().getForEntity(
                "/api/admin/audit?page=0&size=5&action=UPDATE&type=RESPONSE&keyword=audit-sse-only-body", Map.class);

        assertThat(page.getBody().get("today")).isEqualTo(java.time.LocalDate.now().toString());
        List<Map<String, Object>> results = (List<Map<String, Object>>) page.getBody().get("results");
        assertThat(results).singleElement().satisfies(summary -> {
            Map<String, Object> listSummary = (Map<String, Object>) summary.get("summary");
            assertThat(listSummary).containsEntry("changeCount", 1);
            assertThat((List<Map<String, Object>>) listSummary.get("changes")).singleElement()
                    .satisfies(change -> assertThat(change).containsEntry("f", "body"));
        });
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
