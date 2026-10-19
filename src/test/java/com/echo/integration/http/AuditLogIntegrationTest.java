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
 * Audit log integration test
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
    @DisplayName("11.2 Creating a rule produces a CREATE audit record")
    void createRule_generatesCreateAudit() {
        RuleDto rule = createHttpRule("/api/audit-create", "GET", "{\"ok\":true}");

        List<Map<String, Object>> audits = getRuleAuditLogs(rule.getId());
        assertThat(audits).isNotEmpty();
        assertThat(audits.get(0).get("action")).isEqualTo("CREATE");
        assertThat(audits.get(0).get("ruleId")).isEqualTo(rule.getId());
    }

    @Test
    @DisplayName("11.3 Updating a rule produces an UPDATE audit record with before/after snapshots")
    void updateRule_generatesUpdateAudit() {
        RuleDto rule = createHttpRule("/api/audit-update", "GET", "{\"ok\":true}");

        // Update description (use exchange + JSON Content-Type)
        rule.setDescription("已更新的描述");
        ResponseEntity<RuleDto> updateResp = updateRule(rule.getId(), rule);
        assertThat(updateResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<Map<String, Object>> audits = getRuleAuditLogs(rule.getId());
        // There should be CREATE + UPDATE
        assertThat(audits.size()).isGreaterThanOrEqualTo(2);
        Map<String, Object> updateAudit = audits.stream()
                .filter(a -> "UPDATE".equals(a.get("action")))
                .findFirst().orElse(null);
        assertThat(updateAudit).isNotNull();
        assertThat(updateAudit.get("operator")).isNotNull();
    }

    @Test
    @DisplayName("11.4 Deleting a rule produces a DELETE audit record")
    void deleteRule_generatesDeleteAudit() {
        RuleDto rule = createHttpRule("/api/audit-delete", "GET", "{\"ok\":true}");
        String ruleId = rule.getId();

        // Use exchange to ensure DELETE with authentication
        ResponseEntity<Void> delResp = adminClient().exchange(
                "/api/admin/rules/" + ruleId, HttpMethod.DELETE, null, Void.class);
        assertThat(delResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // Use global audit query after deletion
        List<Map<String, Object>> allAudits = getAllAuditLogs(null);
        Map<String, Object> deleteAudit = allAudits.stream()
                .filter(a -> ruleId.equals(a.get("ruleId")) && "DELETE".equals(a.get("action")))
                .findFirst().orElse(null);
        assertThat(deleteAudit).isNotNull();
        assertThat(deleteAudit.get("operator")).isNotNull();
    }

    @Test
    @DisplayName("11.5 Queries all audit logs and supports the limit parameter")
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
    @DisplayName("11.5.1 Paginated revision summaries omit JSON while individual details retain the complete contents")
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
    @DisplayName("11.5.2 Revision lists include names and changes, support path search, and filter by type")
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
    @DisplayName("11.5.3 An SSE-body-only update does not report a content-type change; lists include today's server date")
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
    @DisplayName("11.6 Clears all audit logs")
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
