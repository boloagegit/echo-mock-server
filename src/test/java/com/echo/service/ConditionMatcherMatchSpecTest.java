package com.echo.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 結構化條件在兩種引擎模式下都由新引擎評估，並能產生匹配鏈說明。 */
class ConditionMatcherMatchSpecTest {

    private static final String SPEC = """
            {"all": [
              {"source": "header", "name": "X-Tenant", "op": "equals", "value": "acme",
               "options": {"caseInsensitive": true}},
              {"any": [
                {"source": "body", "selector": {"jsonPath": "$.amount"}, "op": "gte", "value": 1000},
                {"source": "query", "name": "vip", "op": "exists"}
              ]},
              {"not": {"source": "body", "selector": {"jsonPath": "$.status"}, "op": "equals", "value": "CANCELLED"}}
            ]}""";

    private static boolean matches(ConditionMatcher matcher, String body, String query, Map<String, String> headers) {
        return matcher.matchesPrepared(SPEC, null, null, null, matcher.prepareBody(body), query, headers);
    }

    @ParameterizedTest
    @EnumSource(ConditionMatcher.EngineMode.class)
    void evaluatesAnyAllNotAndNewOperatorsInBothEngineModes(ConditionMatcher.EngineMode mode) {
        ConditionMatcher matcher = new ConditionMatcher(2 * 1024 * 1024, mode);
        Map<String, String> acme = Map.of("X-Tenant", "ACME");

        assertThat(matches(matcher, "{\"amount\":1500,\"status\":\"NEW\"}", null, acme)).isTrue();
        assertThat(matches(matcher, "{\"amount\":10,\"status\":\"NEW\"}", "vip=1", acme)).isTrue();
        assertThat(matches(matcher, "{\"amount\":10,\"status\":\"NEW\"}", null, acme)).isFalse();
        assertThat(matches(matcher, "{\"amount\":5000,\"status\":\"CANCELLED\"}", null, acme)).isFalse();
        assertThat(matches(matcher, "{\"amount\":5000}", null, Map.of("X-Tenant", "other"))).isFalse();
    }

    @Test
    void legacyFieldsAreIgnoredWhenMatchSpecIsPresent() {
        ConditionMatcher matcher = new ConditionMatcher(2 * 1024 * 1024, ConditionMatcher.EngineMode.LEGACY);
        // 舊欄位寫的條件不成立，但以 matchSpec 為準
        assertThat(matcher.matchesPrepared(SPEC, "amount=1", null, null,
                matcher.prepareBody("{\"amount\":1500}"), null, Map.of("X-Tenant", "acme"))).isTrue();
    }

    @Test
    void blankMatchSpecFallsBackToLegacyConditions() {
        ConditionMatcher matcher = new ConditionMatcher(2 * 1024 * 1024, ConditionMatcher.EngineMode.LEGACY);
        ConditionMatcher.PreparedBody body = matcher.prepareBody("{\"a\":\"1\"}");
        assertThat(matcher.matchesPrepared(" ", "a=1", null, null, body, null, Map.of())).isTrue();
        assertThat(matcher.matchesPrepared(null, "a=2", null, null, body, null, Map.of())).isFalse();
    }

    @ParameterizedTest
    @EnumSource(ConditionMatcher.EngineMode.class)
    void corruptStoredSpecNeverMatchesInsteadOfFailingRequests(ConditionMatcher.EngineMode mode) {
        ConditionMatcher matcher = new ConditionMatcher(2 * 1024 * 1024, mode);
        ConditionMatcher.PreparedBody body = matcher.prepareBody("{}");

        assertThat(matcher.matchesPrepared("{not json", null, null, null, body, null, Map.of())).isFalse();
        assertThat(matcher.matchesPrepared("{\"source\":\"query\",\"name\":\"a\",\"op\":\"bogus\"}",
                null, null, null, body, "a=1", Map.of())).isFalse();
        assertThat(matcher.matchesPreparedWithDetail("{not json", null, null, null, body, null, Map.of())
                .isOverallMatch()).isFalse();
    }

    @Test
    void detailFlattensConditionsAndReportsNegatedOnesByTheirContribution() {
        ConditionMatcher matcher = new ConditionMatcher(2 * 1024 * 1024, ConditionMatcher.EngineMode.LEGACY);
        ConditionMatcher.ConditionDetail detail = matcher.matchesPreparedWithDetail(SPEC, null, null, null,
                matcher.prepareBody("{\"amount\":10,\"status\":\"CANCELLED\"}"), null, Map.of("X-Tenant", "acme"));

        assertThat(detail.isOverallMatch()).isFalse();
        assertThat(detail.getResults()).hasSize(4);
        assertThat(detail.getResults()).allSatisfy(result ->
                assertThat(result.getType()).isEqualTo(ConditionMatcher.MATCH_SPEC_TYPE));
        // header 通過；amount 與 vip 都不成立；status 等於 CANCELLED，所以 not 這一筆不通過
        assertThat(detail.getResults()).extracting(ConditionMatcher.ConditionResult::isPassed)
                .containsExactly(true, false, false, false);
        assertThat(detail.getResults().get(3).getCondition()).startsWith("not ");
        assertThat(detail.getResults().get(3).getDetail()).startsWith("matchSpec FAIL (expected not ");
        assertThat(detail.score()).isEqualTo("1/4");
    }

    @Test
    void legacyConditionDetailIsUnchanged() {
        ConditionMatcher legacy = new ConditionMatcher(2 * 1024 * 1024, ConditionMatcher.EngineMode.LEGACY);
        ConditionMatcher compiled = new ConditionMatcher(2 * 1024 * 1024, ConditionMatcher.EngineMode.COMPILED);
        String body = "{\"user\":{\"type\":\"normal\"}}";

        ConditionMatcher.ConditionDetail expected = legacy.matchesPreparedWithDetail(
                "user.type=vip", "a=1", null, legacy.prepareBody(body), "a=1", Map.of());
        ConditionMatcher.ConditionDetail actual = compiled.matchesPreparedWithDetail(
                null, "user.type=vip", "a=1", null, compiled.prepareBody(body), "a=1", Map.of());

        assertThat(actual.isOverallMatch()).isEqualTo(expected.isOverallMatch());
        assertThat(actual.getResults()).extracting(ConditionMatcher.ConditionResult::getDetail)
                .containsExactlyElementsOf(expected.getResults().stream()
                        .map(ConditionMatcher.ConditionResult::getDetail).toList());
    }

    @Test
    void conditionsObjectIsSharedForTheSameSpec() {
        ConditionMatcher matcher = new ConditionMatcher();
        assertThat(matcher.conditions(SPEC, null, null, null)).isSameAs(matcher.conditions(SPEC, null, null, null));
        assertThat(matcher.conditions(SPEC, null, null, null)).isNotSameAs(matcher.conditions(null, null, null, null));
    }
}
