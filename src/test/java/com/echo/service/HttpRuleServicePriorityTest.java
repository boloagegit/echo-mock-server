package com.echo.service;

import com.echo.entity.HttpRule;
import com.echo.protocol.http.HttpProtocolHandler;
import com.echo.repository.HttpRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HttpRuleServicePriorityTest {

    @Mock
    private HttpProtocolHandler httpHandler;
    @Mock
    private RuleService ruleService;
    @Mock
    private HttpRuleRepository httpRuleRepository;
    @Mock
    private ConditionMatcher conditionMatcher;

    private HttpRuleService service;

    @BeforeEach
    void setUp() {
        service = new HttpRuleService(httpHandler, ruleService, httpRuleRepository, conditionMatcher, Optional.empty(), null);
    }

    private HttpRule rule(String id, String targetHost, String matchKey, int priority) {
        return HttpRule.builder().id(id).targetHost(targetHost).matchKey(matchKey)
                .method("GET").priority(priority).enabled(true).build();
    }

    private void mockRules(List<HttpRule> rules) {
        when(httpHandler.findWithFallback(any(), any(), any())).thenReturn(rules);
    }

    private Optional<HttpRule> findMatchingRule(String host, String path, String method, String body, String queryString) {
        MatchResult<HttpRule> result = service.findMatchingHttpRuleWithCandidates(host, path, method, body, queryString, null);
        return result.isMatched() ? Optional.of(result.getMatchedRule()) : Optional.empty();
    }

    @Test
    void priority_higherNumberWins() {
        HttpRule high = rule("high", "api.com", "/users", 10);
        HttpRule low = rule("low", "api.com", "/users", 0);
        mockRules(List.of(low, high));

        Optional<HttpRule> result = findMatchingRule("api.com", "/users", "GET", null, null);

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo("high");
    }

    @Test
    void exactMatchKey_winsOverWildcard_samePriority() {
        HttpRule exact = rule("exact", "api.com", "/users", 0);
        HttpRule wildcard = rule("wildcard", "api.com", "*", 0);
        mockRules(List.of(wildcard, exact));

        Optional<HttpRule> result = findMatchingRule("api.com", "/users", "GET", null, null);

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo("exact");
    }

    @Test
    void specificTargetHost_winsOverEmpty_samePriority() {
        HttpRule specific = rule("specific", "api.com", "/users", 0);
        HttpRule empty = rule("empty", "", "/users", 0);
        HttpRule nullHost = rule("null", null, "/users", 0);
        mockRules(List.of(empty, nullHost, specific));

        Optional<HttpRule> result = findMatchingRule("api.com", "/users", "GET", null, null);

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo("specific");
    }

    @Test
    void priority_doesNotOverrideWildcard() {
        HttpRule wildcardHighPriority = rule("wildcard", "api.com", "*", 10);
        HttpRule exactLowPriority = rule("exact", "api.com", "/users", 0);
        mockRules(List.of(exactLowPriority, wildcardHighPriority));

        Optional<HttpRule> result = findMatchingRule("api.com", "/users", "GET", null, null);

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo("exact");
    }

    @Test
    void combinedPriority_exactMatchKey_specificHost() {
        HttpRule a = rule("A", "api.com", "/users", 10);
        HttpRule b = rule("B", "", "/users", 10);
        HttpRule c = rule("C", "api.com", "*", 10);
        HttpRule d = rule("D", "", "*", 10);
        HttpRule e = rule("E", "api.com", "/users", 1);
        mockRules(List.of(d, c, b, a, e));

        Optional<HttpRule> result = findMatchingRule("api.com", "/users", "GET", null, null);

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo("A");
    }

    @Test
    void conditionMatch_winsOverNoCondition() {
        HttpRule withCondition = rule("cond", "api.com", "/users", 0);
        withCondition.setBodyCondition("type=vip");
        HttpRule noCondition = rule("noCond", "api.com", "/users", 0);
        mockRules(List.of(noCondition, withCondition));
        when(conditionMatcher.matchesPrepared(any(), any(), any(), any(), any(), any()))
                .thenReturn(true);

        Optional<HttpRule> result = findMatchingRule("api.com", "/users", "GET", "{\"type\":\"vip\"}", null);

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo("cond");
    }

    @Test
    void conditionNotMatch_fallsBackToNoCondition() {
        HttpRule withCondition = rule("cond", "api.com", "/users", 0);
        withCondition.setBodyCondition("type=vip");
        HttpRule noCondition = rule("noCond", "api.com", "/users", 0);
        mockRules(List.of(noCondition, withCondition));
        when(conditionMatcher.matchesPrepared(any(), any(), any(), any(), any(), any()))
                .thenReturn(false);

        Optional<HttpRule> result = findMatchingRule("api.com", "/users", "GET", "{\"type\":\"normal\"}", null);

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo("noCond");
    }

    @Test
    void newerCreatedAt_winsOverOlder_samePriorityAndMatchKey() {
        HttpRule older = rule("older", "api.com", "/users", 0);
        older.setCreatedAt(java.time.LocalDateTime.of(2026, 1, 1, 10, 0));
        HttpRule newer = rule("newer", "api.com", "/users", 0);
        newer.setCreatedAt(java.time.LocalDateTime.of(2026, 1, 2, 10, 0));
        mockRules(List.of(older, newer));

        Optional<HttpRule> result = findMatchingRule("api.com", "/users", "GET", null, null);

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo("newer");
    }

    @Test
    void wildcardWithMatchingCondition_winsOverExactNoCondition() {
        HttpRule wildcardCond = rule("wc-cond", "api.com", "*", 0);
        wildcardCond.setBodyCondition("type=ORDER");
        HttpRule exactNoCond = rule("exact-nc", "api.com", "/orders", 0);
        mockRules(List.of(exactNoCond, wildcardCond));
        when(conditionMatcher.matchesPrepared(any(), any(), any(), any(), any(), any()))
                .thenReturn(true);

        Optional<HttpRule> result = findMatchingRule("api.com", "/orders", "POST", "{\"type\":\"ORDER\"}", null);

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo("wc-cond");
    }

    @Test
    void wildcardWithNonMatchingCondition_fallsBackToExactNoCondition() {
        HttpRule wildcardCond = rule("wc-cond", "api.com", "*", 0);
        wildcardCond.setBodyCondition("type=ORDER");
        HttpRule exactNoCond = rule("exact-nc", "api.com", "/orders", 0);
        mockRules(List.of(exactNoCond, wildcardCond));
        when(conditionMatcher.matchesPrepared(any(), any(), any(), any(), any(), any()))
                .thenReturn(false);

        Optional<HttpRule> result = findMatchingRule("api.com", "/orders", "POST", "{\"type\":\"OTHER\"}", null);

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo("exact-nc");
    }
}