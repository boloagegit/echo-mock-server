package com.echo.service;

import com.echo.service.ConditionMatcher.ConditionDetail;
import com.echo.service.ConditionMatcher.ConditionResult;
import com.echo.service.ConditionMatcher.PreparedBody;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * matchesPreparedWithDetail 單元測試。
 * <p>
 * 測試各種條件組合（body only, query only, header only, 混合）、
 * detail 格式正確性、空條件與 null 條件的處理。
 * <p>
 * Validates: Requirements 7.2, 7.3
 */
class ConditionMatcherDetailTest {

    private final ConditionMatcher matcher = new ConditionMatcher();

    // ==================== Null / Empty Conditions ====================

    @Test
    void allNullConditions_shouldReturnEmptyResultsAndOverallTrue() {
        PreparedBody prepared = matcher.prepareBody("{\"a\":\"1\"}");

        ConditionDetail detail = matcher.matchesPreparedWithDetail(
                null, null, null, prepared, null, null);

        assertThat(detail.isOverallMatch()).isTrue();
        assertThat(detail.getResults()).isEmpty();
        assertThat(detail.passedCount()).isZero();
        assertThat(detail.totalCount()).isZero();
        assertThat(detail.score()).isEqualTo("0/0");
    }

    @Test
    void allBlankConditions_shouldReturnEmptyResultsAndOverallTrue() {
        PreparedBody prepared = matcher.prepareBody("{\"a\":\"1\"}");

        ConditionDetail detail = matcher.matchesPreparedWithDetail(
                "  ", "  ", "  ", prepared, null, null);

        assertThat(detail.isOverallMatch()).isTrue();
        assertThat(detail.getResults()).isEmpty();
    }

    @Test
    void emptyStringConditions_shouldReturnEmptyResultsAndOverallTrue() {
        PreparedBody prepared = matcher.prepareBody("{\"a\":\"1\"}");

        ConditionDetail detail = matcher.matchesPreparedWithDetail(
                "", "", "", prepared, null, null);

        assertThat(detail.isOverallMatch()).isTrue();
        assertThat(detail.getResults()).isEmpty();
    }

    // ==================== Body Only ====================

    @Test
    void bodyOnly_allPass_shouldReturnOverallTrue() {
        String json = "{\"user\":{\"type\":\"vip\"},\"status\":\"active\"}";
        PreparedBody prepared = matcher.prepareBody(json);

        ConditionDetail detail = matcher.matchesPreparedWithDetail(
                "user.type=vip;status=active", null, null,
                prepared, null, null);

        assertThat(detail.isOverallMatch()).isTrue();
        assertThat(detail.getResults()).hasSize(2);
        assertThat(detail.passedCount()).isEqualTo(2);
        assertThat(detail.score()).isEqualTo("2/2");
        assertThat(detail.getResults()).allSatisfy(r -> {
            assertThat(r.isPassed()).isTrue();
            assertThat(r.getType()).isEqualTo("bodyCondition");
            assertThat(r.getDetail()).contains("PASS");
        });
    }

    @Test
    void bodyOnly_oneFail_shouldReturnOverallFalse() {
        String json = "{\"user\":{\"type\":\"normal\"},\"status\":\"active\"}";
        PreparedBody prepared = matcher.prepareBody(json);

        ConditionDetail detail = matcher.matchesPreparedWithDetail(
                "user.type=vip;status=active", null, null,
                prepared, null, null);

        assertThat(detail.isOverallMatch()).isFalse();
        assertThat(detail.passedCount()).isEqualTo(1);
        assertThat(detail.score()).isEqualTo("1/2");

        ConditionResult failResult = detail.getResults().stream()
                .filter(r -> !r.isPassed()).findFirst().orElseThrow();
        assertThat(failResult.getCondition()).isEqualTo("user.type=vip");
        assertThat(failResult.getDetail()).contains("FAIL");
    }

    @Test
    void bodyOnly_allFail_shouldReturnOverallFalse() {
        String json = "{\"x\":\"1\"}";
        PreparedBody prepared = matcher.prepareBody(json);

        ConditionDetail detail = matcher.matchesPreparedWithDetail(
                "a=1;b=2", null, null, prepared, null, null);

        assertThat(detail.isOverallMatch()).isFalse();
        assertThat(detail.passedCount()).isZero();
        assertThat(detail.score()).isEqualTo("0/2");
        assertThat(detail.getResults()).allSatisfy(r -> {
            assertThat(r.isPassed()).isFalse();
            assertThat(r.getDetail()).contains("FAIL");
        });
    }

    // ==================== Query Only ====================

    @Test
    void queryOnly_pass_shouldReturnOverallTrue() {
        PreparedBody prepared = matcher.prepareBody(null);

        ConditionDetail detail = matcher.matchesPreparedWithDetail(
                null, "status=active", null,
                prepared, "status=active&page=1", null);

        assertThat(detail.isOverallMatch()).isTrue();
        assertThat(detail.getResults()).hasSize(1);

        ConditionResult r = detail.getResults().get(0);
        assertThat(r.getType()).isEqualTo("queryCondition");
        assertThat(r.isPassed()).isTrue();
        assertThat(r.getDetail()).contains("PASS");
        assertThat(r.getCondition()).isEqualTo("status=active");
    }

    @Test
    void queryOnly_fail_shouldReturnOverallFalse() {
        PreparedBody prepared = matcher.prepareBody(null);

        ConditionDetail detail = matcher.matchesPreparedWithDetail(
                null, "env=prod", null,
                prepared, "env=staging", null);

        assertThat(detail.isOverallMatch()).isFalse();
        ConditionResult r = detail.getResults().get(0);
        assertThat(r.getType()).isEqualTo("queryCondition");
        assertThat(r.isPassed()).isFalse();
        assertThat(r.getDetail()).contains("FAIL");
    }

    @Test
    void queryOnly_multipleConditions() {
        PreparedBody prepared = matcher.prepareBody(null);

        ConditionDetail detail = matcher.matchesPreparedWithDetail(
                null, "status=active;page=1", null,
                prepared, "status=active&page=2", null);

        assertThat(detail.isOverallMatch()).isFalse();
        assertThat(detail.getResults()).hasSize(2);
        assertThat(detail.passedCount()).isEqualTo(1);
    }

    // ==================== Header Only ====================

    @Test
    void headerOnly_pass_shouldReturnOverallTrue() {
        PreparedBody prepared = matcher.prepareBody(null);
        Map<String, String> headers = Map.of("X-Env", "prod", "Accept", "application/json");

        ConditionDetail detail = matcher.matchesPreparedWithDetail(
                null, null, "X-Env=prod",
                prepared, null, headers);

        assertThat(detail.isOverallMatch()).isTrue();
        assertThat(detail.getResults()).hasSize(1);

        ConditionResult r = detail.getResults().get(0);
        assertThat(r.getType()).isEqualTo("headerCondition");
        assertThat(r.isPassed()).isTrue();
        assertThat(r.getDetail()).contains("PASS");
    }

    @Test
    void headerOnly_fail_shouldReturnOverallFalse() {
        PreparedBody prepared = matcher.prepareBody(null);
        Map<String, String> headers = Map.of("X-Env", "staging");

        ConditionDetail detail = matcher.matchesPreparedWithDetail(
                null, null, "X-Env=prod",
                prepared, null, headers);

        assertThat(detail.isOverallMatch()).isFalse();
        ConditionResult r = detail.getResults().get(0);
        assertThat(r.getType()).isEqualTo("headerCondition");
        assertThat(r.isPassed()).isFalse();
        assertThat(r.getDetail()).contains("FAIL");
    }

    @Test
    void headerOnly_nullHeaders_shouldFail() {
        PreparedBody prepared = matcher.prepareBody(null);

        ConditionDetail detail = matcher.matchesPreparedWithDetail(
                null, null, "X-Env=prod",
                prepared, null, null);

        assertThat(detail.isOverallMatch()).isFalse();
        assertThat(detail.getResults()).hasSize(1);
        assertThat(detail.getResults().get(0).isPassed()).isFalse();
    }

    // ==================== Mixed Conditions ====================

    @Test
    void mixed_allPass_shouldReturnOverallTrue() {
        String json = "{\"type\":\"ORDER\"}";
        PreparedBody prepared = matcher.prepareBody(json);
        Map<String, String> headers = Map.of("X-Tenant", "abc");

        ConditionDetail detail = matcher.matchesPreparedWithDetail(
                "type=ORDER", "status=active", "X-Tenant=abc",
                prepared, "status=active", headers);

        assertThat(detail.isOverallMatch()).isTrue();
        assertThat(detail.getResults()).hasSize(3);
        assertThat(detail.passedCount()).isEqualTo(3);
        assertThat(detail.score()).isEqualTo("3/3");

        // Verify types are correct
        assertThat(detail.getResults().stream().map(ConditionResult::getType))
                .containsExactly("bodyCondition", "queryCondition", "headerCondition");
    }

    @Test
    void mixed_partialPass_shouldReturnOverallFalse() {
        String json = "{\"type\":\"ORDER\"}";
        PreparedBody prepared = matcher.prepareBody(json);
        Map<String, String> headers = Map.of("X-Tenant", "xyz");

        ConditionDetail detail = matcher.matchesPreparedWithDetail(
                "type=ORDER", "status=active", "X-Tenant=abc",
                prepared, "status=active", headers);

        assertThat(detail.isOverallMatch()).isFalse();
        assertThat(detail.passedCount()).isEqualTo(2);
        assertThat(detail.score()).isEqualTo("2/3");
    }

    @Test
    void mixed_bodyAndQueryOnly_noHeader() {
        String json = "{\"name\":\"test\"}";
        PreparedBody prepared = matcher.prepareBody(json);

        ConditionDetail detail = matcher.matchesPreparedWithDetail(
                "name=test", "page=1", null,
                prepared, "page=1", null);

        assertThat(detail.isOverallMatch()).isTrue();
        assertThat(detail.getResults()).hasSize(2);
        assertThat(detail.getResults().stream().map(ConditionResult::getType))
                .containsExactly("bodyCondition", "queryCondition");
    }

    // ==================== Detail Format Correctness ====================

    @Test
    void detailFormat_passedCondition_shouldContainPassAndCondition() {
        String json = "{\"user\":{\"type\":\"vip\"}}";
        PreparedBody prepared = matcher.prepareBody(json);

        ConditionDetail detail = matcher.matchesPreparedWithDetail(
                "user.type=vip", null, null, prepared, null, null);

        ConditionResult r = detail.getResults().get(0);
        assertThat(r.getDetail()).isEqualTo("bodyCondition PASS (user.type=vip)");
    }

    @Test
    void detailFormat_failedBodyCondition_shouldContainExpectedAndActual() {
        String json = "{\"user\":{\"type\":\"normal\"}}";
        PreparedBody prepared = matcher.prepareBody(json);

        ConditionDetail detail = matcher.matchesPreparedWithDetail(
                "user.type=vip", null, null, prepared, null, null);

        ConditionResult r = detail.getResults().get(0);
        assertThat(r.getDetail()).contains("bodyCondition FAIL");
        assertThat(r.getDetail()).contains("expected");
        assertThat(r.getDetail()).contains("actual");
    }

    @Test
    void detailFormat_failedQueryCondition_shouldContainExpectedAndActual() {
        PreparedBody prepared = matcher.prepareBody(null);

        ConditionDetail detail = matcher.matchesPreparedWithDetail(
                null, "env=prod", null,
                prepared, "env=staging", null);

        ConditionResult r = detail.getResults().get(0);
        assertThat(r.getDetail()).contains("queryCondition FAIL");
        assertThat(r.getDetail()).contains("expected");
        assertThat(r.getDetail()).contains("actual");
    }

    @Test
    void detailFormat_failedHeaderCondition_shouldContainExpectedAndActual() {
        PreparedBody prepared = matcher.prepareBody(null);
        Map<String, String> headers = Map.of("X-Env", "staging");

        ConditionDetail detail = matcher.matchesPreparedWithDetail(
                null, null, "X-Env=prod",
                prepared, null, headers);

        ConditionResult r = detail.getResults().get(0);
        assertThat(r.getDetail()).contains("headerCondition FAIL");
        assertThat(r.getDetail()).contains("expected");
        assertThat(r.getDetail()).contains("actual");
    }

    @Test
    void detailFormat_passedQueryCondition() {
        PreparedBody prepared = matcher.prepareBody(null);

        ConditionDetail detail = matcher.matchesPreparedWithDetail(
                null, "page=1", null,
                prepared, "page=1&size=10", null);

        ConditionResult r = detail.getResults().get(0);
        assertThat(r.getDetail()).isEqualTo("queryCondition PASS (page=1)");
    }

    @Test
    void detailFormat_passedHeaderCondition() {
        PreparedBody prepared = matcher.prepareBody(null);
        Map<String, String> headers = Map.of("Accept", "application/json");

        ConditionDetail detail = matcher.matchesPreparedWithDetail(
                null, null, "Accept=application/json",
                prepared, null, headers);

        ConditionResult r = detail.getResults().get(0);
        assertThat(r.getDetail()).isEqualTo("headerCondition PASS (Accept=application/json)");
    }

    // ==================== Consistency with matchesPrepared ====================

    @Test
    void overallMatch_shouldBeConsistentWithMatchesPrepared() {
        String json = "{\"type\":\"ORDER\",\"status\":\"active\"}";
        PreparedBody prepared = matcher.prepareBody(json);
        Map<String, String> headers = Map.of("X-Env", "prod");
        String queryString = "page=1";

        String bodyCondition = "type=ORDER;status=active";
        String queryCondition = "page=1";
        String headerCondition = "X-Env=prod";

        boolean boolResult = matcher.matchesPrepared(
                bodyCondition, queryCondition, headerCondition,
                prepared, queryString, headers);
        ConditionDetail detail = matcher.matchesPreparedWithDetail(
                bodyCondition, queryCondition, headerCondition,
                prepared, queryString, headers);

        assertThat(detail.isOverallMatch()).isEqualTo(boolResult);
    }

    @Test
    void overallMatch_shouldBeConsistentWhenFailing() {
        String json = "{\"type\":\"RETURN\"}";
        PreparedBody prepared = matcher.prepareBody(json);

        String bodyCondition = "type=ORDER";

        boolean boolResult = matcher.matchesPrepared(
                bodyCondition, null, null, prepared, null, null);
        ConditionDetail detail = matcher.matchesPreparedWithDetail(
                bodyCondition, null, null, prepared, null, null);

        assertThat(detail.isOverallMatch()).isEqualTo(boolResult);
        assertThat(detail.isOverallMatch()).isFalse();
    }

    // ==================== Edge Cases ====================

    @Test
    void emptyBody_withBodyCondition_shouldFail() {
        PreparedBody prepared = matcher.prepareBody(null);

        ConditionDetail detail = matcher.matchesPreparedWithDetail(
                "field=value", null, null, prepared, null, null);

        assertThat(detail.isOverallMatch()).isFalse();
        assertThat(detail.getResults()).hasSize(1);
        assertThat(detail.getResults().get(0).isPassed()).isFalse();
    }

    @Test
    void nullQueryString_withQueryCondition_shouldFail() {
        PreparedBody prepared = matcher.prepareBody(null);

        ConditionDetail detail = matcher.matchesPreparedWithDetail(
                null, "key=value", null, prepared, null, null);

        assertThat(detail.isOverallMatch()).isFalse();
        assertThat(detail.getResults()).hasSize(1);
        assertThat(detail.getResults().get(0).isPassed()).isFalse();
    }

    @Test
    void semicolonOnlyCondition_shouldProduceNoResults() {
        PreparedBody prepared = matcher.prepareBody("{\"a\":\"1\"}");

        ConditionDetail detail = matcher.matchesPreparedWithDetail(
                ";;;", null, null, prepared, null, null);

        assertThat(detail.isOverallMatch()).isTrue();
        assertThat(detail.getResults()).isEmpty();
    }

    @Test
    void xmlBody_withXPathCondition_shouldWork() {
        String xml = "<root><name>test</name><status>active</status></root>";
        PreparedBody prepared = matcher.prepareBody(xml);

        ConditionDetail detail = matcher.matchesPreparedWithDetail(
                "//name=test;//status=active", null, null,
                prepared, null, null);

        assertThat(detail.isOverallMatch()).isTrue();
        assertThat(detail.getResults()).hasSize(2);
        assertThat(detail.passedCount()).isEqualTo(2);
    }

    @Test
    void xmlBody_withFailingXPathCondition() {
        String xml = "<root><name>test</name></root>";
        PreparedBody prepared = matcher.prepareBody(xml);

        ConditionDetail detail = matcher.matchesPreparedWithDetail(
                "//name=wrong", null, null, prepared, null, null);

        assertThat(detail.isOverallMatch()).isFalse();
        assertThat(detail.getResults().get(0).getDetail()).contains("FAIL");
    }

    @Test
    void multipleHeaderConditions_mixedResults() {
        PreparedBody prepared = matcher.prepareBody(null);
        Map<String, String> headers = Map.of("X-Env", "prod", "X-Tenant", "abc");

        ConditionDetail detail = matcher.matchesPreparedWithDetail(
                null, null, "X-Env=prod;X-Tenant=xyz",
                prepared, null, headers);

        assertThat(detail.isOverallMatch()).isFalse();
        assertThat(detail.passedCount()).isEqualTo(1);
        assertThat(detail.score()).isEqualTo("1/2");
    }
}
