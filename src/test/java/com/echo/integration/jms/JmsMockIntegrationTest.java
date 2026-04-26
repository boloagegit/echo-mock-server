package com.echo.integration.jms;

import com.echo.dto.RuleDto;
import com.echo.integration.base.BaseIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JMS 訊息匹配整合測試
 */
class JmsMockIntegrationTest extends BaseIntegrationTest {

    @BeforeEach
    void ensureCleanState() {
        // 使用 exchange 確保 DELETE 請求帶認證且成功
        adminClient().exchange("/api/admin/rules/all",
                org.springframework.http.HttpMethod.DELETE, null, Map.class);
        // 清除日誌避免 limit=100 累積問題
        adminClient().exchange("/api/admin/logs/all",
                org.springframework.http.HttpMethod.DELETE, null, Void.class);
        cacheManager.getCacheNames().forEach(name -> {
            var cache = cacheManager.getCache(name);
            if (cache != null) {
                cache.clear();
            }
        });
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> sendJms(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        HttpEntity<String> req = new HttpEntity<>(body, headers);
        return adminClient().postForEntity(
                "/api/admin/jms/test", req, Map.class);
    }

    /**
     * 取得目前 JMS 日誌數量
     */
    @SuppressWarnings("unchecked")
    private int currentJmsLogCount() {
        ResponseEntity<Map> resp = adminClient().getForEntity(
                "/api/admin/logs?protocol=JMS&limit=100", Map.class);
        if (resp.getBody() != null && resp.getBody().get("results") != null) {
            return ((List<?>) resp.getBody().get("results")).size();
        }
        return 0;
    }

    /**
     * 等待新的 JMS 日誌出現（基於發送前的日誌數量）
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> pollNewLogs(int prevCount, int maxMs)
            throws InterruptedException {
        int waited = 0;
        List<Map<String, Object>> results = List.of();
        while (waited < maxMs) {
            Thread.sleep(200);
            waited += 200;
            ResponseEntity<Map> resp = adminClient().getForEntity(
                    "/api/admin/logs?protocol=JMS&limit=100", Map.class);
            if (resp.getBody() != null) {
                Object r = resp.getBody().get("results");
                if (r != null) {
                    results = (List<Map<String, Object>>) r;
                    if (results.size() > prevCount) {
                        return results;
                    }
                }
            }
        }
        return results;
    }

    @SuppressWarnings("unchecked")
    private String logRuleId(Map<String, Object> entry) {
        Map<String, Object> log = (Map<String, Object>) entry.get("log");
        return log != null ? (String) log.get("ruleId") : null;
    }

    @SuppressWarnings("unchecked")
    private boolean logMatched(Map<String, Object> entry) {
        Map<String, Object> log = (Map<String, Object>) entry.get("log");
        return log != null && Boolean.TRUE.equals(log.get("matched"));
    }

    @Test
    @DisplayName("JMS 訊息發送與規則匹配: 建立規則 -> POST /api/admin/jms/test -> 200")
    void sendMessage_matchesRule() throws Exception {
        RuleDto rule = createJmsRule("ECHO.REQUEST", "{\"ok\":true}");
        int before = currentJmsLogCount();

        ResponseEntity<Map> resp = sendJms("{\"hello\":\"world\"}");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().get("sent")).isEqualTo(true);
        assertThat(resp.getBody().get("queue")).isEqualTo("ECHO.REQUEST");

        List<Map<String, Object>> logs = pollNewLogs(before, 3000);
        assertThat(logs.size()).isGreaterThan(before);
        assertThat(logMatched(logs.get(0))).isTrue();
        assertThat(logRuleId(logs.get(0))).isEqualTo(rule.getId());
    }

    @Test
    @DisplayName("JMS Body 條件匹配: bodyCondition=type=ORDER -> 匹配有條件的規則")
    void bodyCondition_matchesConditionalRule() throws Exception {
        RuleDto conditional = createJmsRule("ECHO.REQUEST",
                "{\"matched\":\"conditional\"}",
                "type=ORDER", null, "有條件規則");
        createJmsRule("ECHO.REQUEST",
                "{\"matched\":\"fallback\"}",
                null, null, "無條件 fallback");
        int before = currentJmsLogCount();

        ResponseEntity<Map> resp = sendJms("{\"type\":\"ORDER\"}");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<Map<String, Object>> logs = pollNewLogs(before, 3000);
        assertThat(logs.size()).isGreaterThan(before);
        assertThat(logMatched(logs.get(0))).isTrue();
        assertThat(logRuleId(logs.get(0))).isEqualTo(conditional.getId());
    }

    @Test
    @DisplayName("JMS 萬用 queueName=* 作為 fallback")
    void wildcardQueue_fallback() throws Exception {
        RuleDto wildcard = createJmsRule("*",
                "{\"matched\":\"wildcard\"}",
                null, null, "萬用 fallback");
        int before = currentJmsLogCount();

        ResponseEntity<Map> resp = sendJms("{\"data\":\"test\"}");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<Map<String, Object>> logs = pollNewLogs(before, 3000);
        assertThat(logs.size()).isGreaterThan(before);
        assertThat(logMatched(logs.get(0))).isTrue();
        assertThat(logRuleId(logs.get(0))).isEqualTo(wildcard.getId());
    }

    @Test
    @DisplayName("JMS 規則優先順序: priority=10 優先於 priority=1")
    void priority_higherPriorityMatches() throws Exception {
        createJmsRule("ECHO.REQUEST",
                "{\"matched\":\"low\"}",
                null, 1, "低優先順序");
        RuleDto highPri = createJmsRule("ECHO.REQUEST",
                "{\"matched\":\"high\"}",
                null, 10, "高優先順序");
        int before = currentJmsLogCount();

        ResponseEntity<Map> resp = sendJms("{\"data\":\"priority\"}");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<Map<String, Object>> logs = pollNewLogs(before, 3000);
        assertThat(logs.size()).isGreaterThan(before);
        assertThat(logMatched(logs.get(0))).isTrue();
        assertThat(logRuleId(logs.get(0))).isEqualTo(highPri.getId());
    }

    @Test
    @DisplayName("JMS XML Body 條件匹配: //type=INVOICE -> 匹配 XML 訊息")
    void xmlBodyCondition_matches() throws Exception {
        RuleDto xmlRule = createJmsRule("ECHO.REQUEST",
                "<response>matched</response>",
                "//type=INVOICE", null, "XML 條件規則");
        int before = currentJmsLogCount();

        ResponseEntity<Map> resp = sendJms(
                "<root><type>INVOICE</type></root>");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<Map<String, Object>> logs = pollNewLogs(before, 3000);
        assertThat(logs.size()).isGreaterThan(before);
        assertThat(logMatched(logs.get(0))).isTrue();
        assertThat(logRuleId(logs.get(0))).isEqualTo(xmlRule.getId());
    }
}
