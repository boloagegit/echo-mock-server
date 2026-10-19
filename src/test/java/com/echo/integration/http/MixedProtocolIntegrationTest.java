package com.echo.integration.http;

import com.echo.dto.RuleDto;
import com.echo.entity.HttpRule;
import com.echo.integration.base.BaseIntegrationTest;
import com.echo.repository.HttpRuleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mixed Protocol and Edge Scenario Integration Testing
 */
class MixedProtocolIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private HttpRuleRepository httpRuleRepository;

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listRules() {
        ResponseEntity<List> resp = adminClient().getForEntity(
                "/api/admin/rules", List.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody();
    }

    @Test
    @DisplayName("14.2 HTTP and JMS rules coexist and match independently")
    void mixedProtocols_coexist() {
        createHttpRule("/api/mixed", "GET",
                "{\"type\":\"http\"}", "mixed.test");
        createJmsRule("MIXED.Q", "{\"type\":\"jms\"}");

        // The list contains two
        List<Map<String, Object>> rules = listRules();
        assertThat(rules.size()).isGreaterThanOrEqualTo(2);

        // HTTP matches normally
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Original-Host", "mixed.test");
        ResponseEntity<String> mockResp = restTemplate.exchange(
                "/mock/api/mixed", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertThat(mockResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mockResp.getBody()).contains("http");
    }

    @Test
    @DisplayName("14.3 The rule list includes HTTP and JMS rules")
    void ruleList_containsBothProtocols() {
        createHttpRule("/api/list-mix", "GET", "{\"http\":true}");
        createJmsRule("LIST.MIX.Q", "{\"jms\":true}");

        List<Map<String, Object>> rules = listRules();
        boolean hasHttp = rules.stream()
                .anyMatch(r -> "HTTP".equals(r.get("protocol")));
        boolean hasJms = rules.stream()
                .anyMatch(r -> "JMS".equals(r.get("protocol")));
        assertThat(hasHttp).isTrue();
        assertThat(hasJms).isTrue();
    }

    @Test
    @DisplayName("14.4 Batch deletion supports mixed protocols")
    void batchDelete_mixedProtocols() {
        RuleDto httpRule = createHttpRule("/api/bdel-mix", "GET", "{\"h\":true}");
        RuleDto jmsRule = createJmsRule("BDEL.MIX.Q", "{\"j\":true}");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        List<String> ids = List.of(httpRule.getId(), jmsRule.getId());

        ResponseEntity<Map> resp = adminClient().exchange(
                "/api/admin/rules/batch", HttpMethod.DELETE,
                new HttpEntity<>(ids, headers), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Verification deleted
        ResponseEntity<RuleDto> h = adminClient().getForEntity(
                "/api/admin/rules/" + httpRule.getId(), RuleDto.class);
        assertThat(h.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<RuleDto> j = adminClient().getForEntity(
                "/api/admin/rules/" + jmsRule.getId(), RuleDto.class);
        assertThat(j.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("14.5 Deletes all rules")
    void deleteAll() {
        createHttpRule("/api/delall-a", "GET", "{\"a\":true}");
        createJmsRule("DELALL.Q", "{\"b\":true}");

        assertThat(listRules()).isNotEmpty();

        adminClient().exchange("/api/admin/rules/all",
                HttpMethod.DELETE, null, Map.class);

        assertThat(listRules()).isEmpty();
    }

    @Test
    @DisplayName("14.6 A stale version returns 409 after another edit; consecutive updates using returned versions succeed")
    void sequentialUpdates_bothSucceed() {
        RuleDto rule = createHttpRule("/api/lock-test", "GET", "{\"ok\":true}");

        // Directly modify DB through repository, simulating that another user has updated (the version is therefore advanced)
        HttpRule entity = httpRuleRepository.findById(rule.getId()).orElseThrow();
        entity.setDescription("被另一個使用者修改");
        httpRuleRepository.saveAndFlush(entity);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        rule.setDescription("用舊版本更新");
        ResponseEntity<String> stale = adminClient().exchange(
                "/api/admin/rules/" + rule.getId(), HttpMethod.PUT,
                new HttpEntity<>(rule, headers), String.class);
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // Continuously update after re-reading, each time with a version of the save returned (same as the editing screen)
        rule = adminClient().getForEntity("/api/admin/rules/" + rule.getId(), RuleDto.class).getBody();
        rule.setDescription("第一次更新");
        ResponseEntity<RuleDto> firstUpdate = adminClient().exchange(
                "/api/admin/rules/" + rule.getId(), HttpMethod.PUT,
                new HttpEntity<>(rule, headers), RuleDto.class);
        assertThat(firstUpdate.getStatusCode()).isEqualTo(HttpStatus.OK);

        rule.setVersion(firstUpdate.getBody().getVersion());
        rule.setDescription("第二次更新");
        ResponseEntity<RuleDto> secondUpdate = adminClient().exchange(
                "/api/admin/rules/" + rule.getId(), HttpMethod.PUT,
                new HttpEntity<>(rule, headers), RuleDto.class);
        assertThat(secondUpdate.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Verify that the final description is the second updated value
        RuleDto finalRule = adminClient().getForEntity(
                "/api/admin/rules/" + rule.getId(), RuleDto.class).getBody();
        assertThat(finalRule.getDescription()).isEqualTo("第二次更新");
    }
}
