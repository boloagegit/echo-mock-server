package com.echo.integration.http;

import com.echo.dto.RuleDto;
import com.echo.integration.base.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 規則判斷順序 API：一定輪不到的規則與單一規則的檢查順序（規則異動後立即反映）。 */
class RuleOrderIntegrationTest extends BaseIntegrationTest {

    @Test
    @DisplayName("條件相同的較後規則被標為輪不到，判斷順序依實際排序列出")
    @SuppressWarnings("unchecked")
    void duplicateRuleIsReportedAndOrderIsListed() {
        RuleDto vip = createHttpRule("/api/order-check", "POST", "{\"vip\":true}", null, 200,
                "type=vip", null, null, null, null, 10, "VIP");
        RuleDto copy = createHttpRule("/api/order-check", "POST", "{\"copy\":true}", null, 200,
                "type=vip", null, null, null, null, 1, "VIP copy");
        RuleDto fallback = createHttpRule("/api/order-check", "POST", "{}", null, 200,
                null, null, null, null, null, 0, "default");

        ResponseEntity<Map> issues = restTemplate.getForEntity("/api/admin/rules/order-issues", Map.class);
        assertThat(issues.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> byId = (Map<String, Object>) issues.getBody().get("issues");
        assertThat(byId).containsOnlyKeys(copy.getId());
        assertThat((Map<String, Object>) byId.get(copy.getId()))
                .containsEntry("type", "duplicateConditions").containsEntry("byRuleId", vip.getId())
                .containsEntry("route", "POST /api/order-check");

        ResponseEntity<Map> order = restTemplate.getForEntity(
                "/api/admin/rules/" + fallback.getId() + "/order", Map.class);
        assertThat(order.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> entries = (List<Map<String, Object>>) order.getBody().get("entries");
        assertThat(entries).extracting(entry -> entry.get("id"))
                .containsExactly(vip.getId(), copy.getId(), fallback.getId());
        assertThat(entries.get(2)).containsEntry("self", true).containsEntry("usedAsDefault", true);

        // 刪掉重複的規則後，提示立即消失（快照重建）
        ResponseEntity<Void> deleted = adminClient().exchange("/api/admin/rules/" + copy.getId(),
                org.springframework.http.HttpMethod.DELETE, null, Void.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        Map<String, Object> after = (Map<String, Object>) restTemplate
                .getForEntity("/api/admin/rules/order-issues", Map.class).getBody().get("issues");
        assertThat(after).isEmpty();

        assertThat(restTemplate.getForEntity("/api/admin/rules/missing-rule/order", Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
