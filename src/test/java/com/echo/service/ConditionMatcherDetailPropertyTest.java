package com.echo.service;

import com.echo.service.ConditionMatcher.ConditionDetail;
import com.echo.service.ConditionMatcher.ConditionResult;
import com.echo.service.ConditionMatcher.PreparedBody;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.StringLength;

import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * matchesPreparedWithDetail 屬性測試（Property 11, 12）。
 * <p>
 * Feature: agent-framework-log-agent
 * <p>
 * Property 11: matchesPreparedWithDetail returns structured PASS/FAIL results
 * Property 12: matchesPreparedWithDetail consistency with matchesPrepared
 */
class ConditionMatcherDetailPropertyTest {

    private final ConditionMatcher matcher = new ConditionMatcher();

    // ==================== Generators ====================

    /**
     * Generate a random JSON body with 1-5 fields, each field has a simple alphanumeric value.
     */
    @Provide
    Arbitrary<Map<String, String>> jsonFields() {
        Arbitrary<String> fieldNames = Arbitraries.of("field1", "field2", "field3", "field4", "field5");
        Arbitrary<String> fieldValues = Arbitraries.of("alpha", "beta", "gamma", "delta", "epsilon",
                "vip", "normal", "prod", "staging", "test");
        return Arbitraries.maps(fieldNames, fieldValues)
                .ofMinSize(1).ofMaxSize(5);
    }

    /**
     * Build a JSON string from a field map.
     */
    private String toJsonBody(Map<String, String> fields) {
        String entries = fields.entrySet().stream()
                .map(e -> "\"" + e.getKey() + "\":\"" + e.getValue() + "\"")
                .collect(Collectors.joining(","));
        return "{" + entries + "}";
    }

    /**
     * Generate body conditions that reference fields from the given map.
     * Some conditions match (use actual value), some don't (use a different value).
     */
    private String buildBodyConditions(Map<String, String> fields, boolean allMatch) {
        List<String> conditions = new ArrayList<>();
        List<Map.Entry<String, String>> entries = new ArrayList<>(fields.entrySet());
        for (int i = 0; i < entries.size(); i++) {
            Map.Entry<String, String> entry = entries.get(i);
            if (allMatch || i % 2 == 0) {
                // matching condition
                conditions.add(entry.getKey() + "=" + entry.getValue());
            } else {
                // non-matching condition
                conditions.add(entry.getKey() + "=NOMATCH_" + entry.getValue());
            }
        }
        return String.join(";", conditions);
    }

    @Provide
    Arbitrary<String> queryParams() {
        return Arbitraries.of(
                "status=active", "page=1", "env=prod", "type=vip",
                "status=inactive", "page=2", "env=staging", "type=normal");
    }

    @Provide
    Arbitrary<Map<String, String>> headerMaps() {
        Arbitrary<String> headerNames = Arbitraries.of("X-Env", "X-Tenant", "Accept", "Content-Type");
        Arbitrary<String> headerValues = Arbitraries.of("prod", "staging", "abc", "application/json", "text/html");
        return Arbitraries.maps(headerNames, headerValues)
                .ofMinSize(1).ofMaxSize(3);
    }

    // ==================== Property 11 ====================

    /**
     * Feature: agent-framework-log-agent, Property 11: matchesPreparedWithDetail returns structured PASS/FAIL results
     * <p>
     * 每個非 null 條件產生一個 ConditionResult；每個 result 含 PASS 或 FAIL；
     * overallMatch = 所有 passed 的 AND。
     * <p>
     * **Validates: Requirements 7.2, 7.3**
     */
    @Property(tries = 20)
    void detailResultCountMatchesNonNullConditions(@ForAll("jsonFields") Map<String, String> fields) {
        String jsonBody = toJsonBody(fields);
        String bodyCondition = buildBodyConditions(fields, false);
        PreparedBody prepared = matcher.prepareBody(jsonBody);

        ConditionDetail detail = matcher.matchesPreparedWithDetail(
                bodyCondition, null, null, prepared, null, null);

        // Number of results == number of non-empty semicolon-separated conditions
        long expectedCount = Arrays.stream(bodyCondition.split(";"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .count();

        assertThat(detail.getResults()).hasSize((int) expectedCount);
    }

    /**
     * Feature: agent-framework-log-agent, Property 11: matchesPreparedWithDetail returns structured PASS/FAIL results
     * <p>
     * 每個 ConditionResult 的 detail 包含 "PASS" 或 "FAIL"。
     * <p>
     * **Validates: Requirements 7.2, 7.3**
     */
    @Property(tries = 20)
    void eachResultDetailContainsPassOrFail(@ForAll("jsonFields") Map<String, String> fields) {
        String jsonBody = toJsonBody(fields);
        String bodyCondition = buildBodyConditions(fields, false);
        PreparedBody prepared = matcher.prepareBody(jsonBody);

        ConditionDetail detail = matcher.matchesPreparedWithDetail(
                bodyCondition, null, null, prepared, null, null);

        for (ConditionResult result : detail.getResults()) {
            assertThat(result.getDetail()).matches(".*\\b(PASS|FAIL)\\b.*");
            // passed flag should be consistent with detail text
            if (result.isPassed()) {
                assertThat(result.getDetail()).contains("PASS");
            } else {
                assertThat(result.getDetail()).contains("FAIL");
            }
        }
    }

    /**
     * Feature: agent-framework-log-agent, Property 11: matchesPreparedWithDetail returns structured PASS/FAIL results
     * <p>
     * overallMatch == 所有 results 的 passed 的 AND。
     * <p>
     * **Validates: Requirements 7.2, 7.3**
     */
    @Property(tries = 20)
    void overallMatchEqualsAndOfAllPassed(@ForAll("jsonFields") Map<String, String> fields) {
        String jsonBody = toJsonBody(fields);
        // Mix matching and non-matching conditions
        String bodyCondition = buildBodyConditions(fields, false);
        PreparedBody prepared = matcher.prepareBody(jsonBody);

        ConditionDetail detail = matcher.matchesPreparedWithDetail(
                bodyCondition, null, null, prepared, null, null);

        boolean expectedOverall = detail.getResults().stream().allMatch(ConditionResult::isPassed);
        assertThat(detail.isOverallMatch()).isEqualTo(expectedOverall);
    }

    /**
     * Feature: agent-framework-log-agent, Property 11: matchesPreparedWithDetail returns structured PASS/FAIL results
     * <p>
     * 混合 body + query + header 條件時，結果數量 = 所有非 null 條件的總數。
     * <p>
     * **Validates: Requirements 7.2, 7.3**
     */
    @Property(tries = 20)
    void mixedConditionsProduceCorrectResultCount(
            @ForAll("jsonFields") Map<String, String> fields,
            @ForAll("queryParams") String queryParam,
            @ForAll("headerMaps") Map<String, String> headers) {

        String jsonBody = toJsonBody(fields);
        String bodyCondition = buildBodyConditions(fields, true);
        // Build a query condition from the query param
        String queryCondition = queryParam.split("=")[0] + "=" + queryParam.split("=")[1];
        // Build a header condition from the first header entry
        Map.Entry<String, String> firstHeader = headers.entrySet().iterator().next();
        String headerCondition = firstHeader.getKey() + "=" + firstHeader.getValue();

        PreparedBody prepared = matcher.prepareBody(jsonBody);

        ConditionDetail detail = matcher.matchesPreparedWithDetail(
                bodyCondition, queryCondition, headerCondition,
                prepared, queryParam, headers);

        // Count expected: body conditions + 1 query + 1 header
        long bodyCount = Arrays.stream(bodyCondition.split(";"))
                .map(String::trim).filter(s -> !s.isEmpty()).count();
        long queryCount = Arrays.stream(queryCondition.split(";"))
                .map(String::trim).filter(s -> !s.isEmpty()).count();
        long headerCount = Arrays.stream(headerCondition.split(";"))
                .map(String::trim).filter(s -> !s.isEmpty()).count();

        assertThat(detail.getResults()).hasSize((int) (bodyCount + queryCount + headerCount));

        // overallMatch should still be AND of all
        boolean expectedOverall = detail.getResults().stream().allMatch(ConditionResult::isPassed);
        assertThat(detail.isOverallMatch()).isEqualTo(expectedOverall);
    }

    /**
     * Feature: agent-framework-log-agent, Property 11: matchesPreparedWithDetail returns structured PASS/FAIL results
     * <p>
     * null 條件不產生任何 ConditionResult。
     * <p>
     * **Validates: Requirements 7.2, 7.3**
     */
    @Property(tries = 20)
    void nullConditionsProduceNoResults(@ForAll("jsonFields") Map<String, String> fields) {
        String jsonBody = toJsonBody(fields);
        PreparedBody prepared = matcher.prepareBody(jsonBody);

        ConditionDetail detail = matcher.matchesPreparedWithDetail(
                null, null, null, prepared, null, null);

        assertThat(detail.getResults()).isEmpty();
        // No conditions means vacuously true
        assertThat(detail.isOverallMatch()).isTrue();
    }

    // ==================== Property 12 ====================

    /**
     * Feature: agent-framework-log-agent, Property 12: matchesPreparedWithDetail consistency with matchesPrepared
     * <p>
     * overallMatch 與 matchesPrepared 布林結果一致（body only）。
     * <p>
     * **Validates: Requirements 7.2**
     */
    @Property(tries = 20)
    void consistencyWithMatchesPrepared_bodyOnly(@ForAll("jsonFields") Map<String, String> fields) {
        String jsonBody = toJsonBody(fields);
        String bodyCondition = buildBodyConditions(fields, false);
        PreparedBody prepared = matcher.prepareBody(jsonBody);

        boolean boolResult = matcher.matchesPrepared(
                bodyCondition, null, null, prepared, null, null);
        ConditionDetail detail = matcher.matchesPreparedWithDetail(
                bodyCondition, null, null, prepared, null, null);

        assertThat(detail.isOverallMatch()).isEqualTo(boolResult);
    }

    /**
     * Feature: agent-framework-log-agent, Property 12: matchesPreparedWithDetail consistency with matchesPrepared
     * <p>
     * overallMatch 與 matchesPrepared 布林結果一致（all matching conditions）。
     * <p>
     * **Validates: Requirements 7.2**
     */
    @Property(tries = 20)
    void consistencyWithMatchesPrepared_allMatch(@ForAll("jsonFields") Map<String, String> fields) {
        String jsonBody = toJsonBody(fields);
        String bodyCondition = buildBodyConditions(fields, true);
        PreparedBody prepared = matcher.prepareBody(jsonBody);

        boolean boolResult = matcher.matchesPrepared(
                bodyCondition, null, null, prepared, null, null);
        ConditionDetail detail = matcher.matchesPreparedWithDetail(
                bodyCondition, null, null, prepared, null, null);

        assertThat(detail.isOverallMatch()).isEqualTo(boolResult);
    }

    /**
     * Feature: agent-framework-log-agent, Property 12: matchesPreparedWithDetail consistency with matchesPrepared
     * <p>
     * overallMatch 與 matchesPrepared 布林結果一致（mixed body + query + header）。
     * <p>
     * **Validates: Requirements 7.2**
     */
    @Property(tries = 20)
    void consistencyWithMatchesPrepared_mixed(
            @ForAll("jsonFields") Map<String, String> fields,
            @ForAll("queryParams") String queryParam,
            @ForAll("headerMaps") Map<String, String> headers) {

        String jsonBody = toJsonBody(fields);
        String bodyCondition = buildBodyConditions(fields, false);

        // Build query condition — sometimes matching, sometimes not
        String[] qParts = queryParam.split("=");
        String queryCondition = qParts[0] + "=" + qParts[1];

        // Build header condition from first header
        Map.Entry<String, String> firstHeader = headers.entrySet().iterator().next();
        String headerCondition = firstHeader.getKey() + "=" + firstHeader.getValue();

        PreparedBody prepared = matcher.prepareBody(jsonBody);

        boolean boolResult = matcher.matchesPrepared(
                bodyCondition, queryCondition, headerCondition,
                prepared, queryParam, headers);
        ConditionDetail detail = matcher.matchesPreparedWithDetail(
                bodyCondition, queryCondition, headerCondition,
                prepared, queryParam, headers);

        assertThat(detail.isOverallMatch()).isEqualTo(boolResult);
    }

    /**
     * Feature: agent-framework-log-agent, Property 12: matchesPreparedWithDetail consistency with matchesPrepared
     * <p>
     * overallMatch 與 matchesPrepared 布林結果一致（null conditions）。
     * <p>
     * **Validates: Requirements 7.2**
     */
    @Property(tries = 20)
    void consistencyWithMatchesPrepared_nullConditions(@ForAll("jsonFields") Map<String, String> fields) {
        String jsonBody = toJsonBody(fields);
        PreparedBody prepared = matcher.prepareBody(jsonBody);

        boolean boolResult = matcher.matchesPrepared(
                null, null, null, prepared, null, null);
        ConditionDetail detail = matcher.matchesPreparedWithDetail(
                null, null, null, prepared, null, null);

        assertThat(detail.isOverallMatch()).isEqualTo(boolResult);
    }
}
