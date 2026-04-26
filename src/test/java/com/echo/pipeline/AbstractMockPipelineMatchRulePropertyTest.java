package com.echo.pipeline;

import com.echo.entity.HttpRule;
import com.echo.entity.Protocol;
import com.echo.service.ConditionMatcher;
import com.echo.service.MatchChainEntry;
import com.echo.service.MatchResult;
import com.echo.service.RequestLogService;
import com.echo.service.RuleService;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AbstractMockPipeline.matchRule() 屬性測試
 * <p>
 * Feature: protocol-pipeline-refactor, Property 1: matchRule 共用匹配邏輯正確性
 * <p>
 * 使用 jqwik 產生隨機候選規則清單（隨機 enabled/disabled、有條件/無條件），
 * 搭配可控的 ConditionMatcher，驗證 matchRule() 的五個不變式。
 *
 * **Validates: Requirements 1.2, 1.3, 1.4, 1.5, 1.6**
 */
class AbstractMockPipelineMatchRulePropertyTest {

    // ==================== Test Subclass ====================

    /**
     * 具體測試子類別，實作所有抽象方法以便測試 matchRule()
     */
    static class TestPipeline extends AbstractMockPipeline<HttpRule> {

        TestPipeline(Set<String> matchingConditions) {
            super(new StubConditionMatcher(matchingConditions), null, null, null);
        }

        @Override
        protected List<HttpRule> findCandidateRules(MockRequest request) {
            return Collections.emptyList();
        }

        @Override
        protected MockResponse buildResponse(HttpRule rule, MockRequest request, String responseBody) {
            return MockResponse.builder().status(200).body("").matched(true).forwarded(false).build();
        }

        @Override
        protected MockResponse forward(MockRequest request) {
            return MockResponse.builder().status(502).body("").matched(false).forwarded(true).build();
        }

        @Override
        protected boolean shouldForward(MockRequest request) {
            return false;
        }

        @Override
        protected MockResponse handleNoMatch(MockRequest request) {
            return MockResponse.builder().status(404).body("").matched(false).forwarded(false).build();
        }

        @Override
        protected boolean hasCondition(HttpRule rule) {
            return rule.getBodyCondition() != null && !rule.getBodyCondition().isBlank();
        }

        @Override
        protected ConditionSet extractConditions(HttpRule rule) {
            return ConditionSet.builder()
                    .bodyCondition(rule.getBodyCondition())
                    .queryCondition(rule.getQueryCondition())
                    .headerCondition(rule.getHeaderCondition())
                    .build();
        }

        @Override
        protected MatchChainEntry createMatchChainEntry(HttpRule rule, String reason) {
            return MatchChainEntry.fromHttp(rule, reason);
        }
    }

    /**
     * Stub ConditionMatcher：根據 matchingConditions 集合決定是否匹配
     */
    static class StubConditionMatcher extends ConditionMatcher {
        private final Set<String> matchingConditions;

        StubConditionMatcher(Set<String> matchingConditions) {
            this.matchingConditions = matchingConditions;
        }

        @Override
        public boolean matchesPrepared(String bodyCondition, String queryCondition,
                                       String headerCondition,
                                       PreparedBody prepared, String queryString,
                                       Map<String, String> headers) {
            return bodyCondition != null && matchingConditions.contains(bodyCondition);
        }
    }

    // ==================== Generators ====================

    @Provide
    Arbitrary<List<HttpRule>> candidateRules() {
        return ruleArbitrary().list().ofMinSize(0).ofMaxSize(15);
    }

    @Provide
    Arbitrary<HttpRule> singleRule() {
        return ruleArbitrary();
    }

    private Arbitrary<HttpRule> ruleArbitrary() {
        Arbitrary<Boolean> enabled = Arbitraries.of(true, false);
        Arbitrary<String> bodyCondition = Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.just(""),
                Arbitraries.just("  "),
                Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(8)
                        .map(s -> "cond_" + s)
        );

        return Combinators.combine(enabled, bodyCondition).as((en, bc) ->
                HttpRule.builder()
                        .id(UUID.randomUUID().toString())
                        .enabled(en)
                        .bodyCondition(bc)
                        .matchKey("/test")
                        .httpStatus(200)
                        .priority(0)
                        .delayMs(0L)
                        .build()
        );
    }

    // ==================== Helper ====================

    private TestPipeline createPipeline(Set<String> matchingConditions) {
        return new TestPipeline(matchingConditions);
    }

    private boolean hasCondition(HttpRule rule) {
        return rule.getBodyCondition() != null && !rule.getBodyCondition().isBlank();
    }

    // ==================== Property Tests ====================

    /**
     * Feature: protocol-pipeline-refactor, Property 1: matchRule 共用匹配邏輯正確性
     * <p>
     * 不變式 1：回傳的規則永遠不是 enabled=false 的規則
     *
     * **Validates: Requirements 1.2**
     */
    @Property(tries = 200)
    void matchedRuleIsNeverDisabled(@ForAll("candidateRules") List<HttpRule> candidates) {
        // 讓所有條件都匹配，確保 disabled 規則仍不會被選中
        Set<String> allConditions = new HashSet<>();
        for (HttpRule r : candidates) {
            if (r.getBodyCondition() != null && !r.getBodyCondition().isBlank()) {
                allConditions.add(r.getBodyCondition());
            }
        }

        TestPipeline pipeline = createPipeline(allConditions);
        MatchResult<HttpRule> result = pipeline.matchRule(candidates, null, null, null);

        if (result.isMatched()) {
            assertThat(result.getMatchedRule().getEnabled())
                    .as("Matched rule must be enabled")
                    .isNotEqualTo(false);
        }
    }

    /**
     * Feature: protocol-pipeline-refactor, Property 1: matchRule 共用匹配邏輯正確性
     * <p>
     * 不變式 2：條件匹配成功的 enabled 規則優先於無條件 fallback 規則
     *
     * **Validates: Requirements 1.3**
     */
    @Property(tries = 200)
    void conditionMatchedRuleTakesPriorityOverFallback(
            @ForAll("candidateRules") List<HttpRule> candidates) {

        // 收集所有有條件的 enabled 規則的 bodyCondition
        Set<String> allConditions = new HashSet<>();
        for (HttpRule r : candidates) {
            if (Boolean.TRUE.equals(r.getEnabled()) && hasCondition(r)) {
                allConditions.add(r.getBodyCondition());
            }
        }

        TestPipeline pipeline = createPipeline(allConditions);
        MatchResult<HttpRule> result = pipeline.matchRule(candidates, null, null, null);

        if (result.isMatched()) {
            // 檢查是否存在條件匹配成功的 enabled 規則
            boolean hasConditionMatchedEnabled = candidates.stream()
                    .anyMatch(r -> Boolean.TRUE.equals(r.getEnabled())
                            && hasCondition(r)
                            && allConditions.contains(r.getBodyCondition()));

            if (hasConditionMatchedEnabled) {
                // 回傳的規則必須是有條件的（不是 fallback）
                assertThat(hasCondition(result.getMatchedRule()))
                        .as("When condition-matched rules exist, result should be a condition-matched rule, not fallback")
                        .isTrue();
            }
        }
    }

    /**
     * Feature: protocol-pipeline-refactor, Property 1: matchRule 共用匹配邏輯正確性
     * <p>
     * 不變式 3：當無匹配且無 fallback 時，回傳空 MatchResult (matched=false, matchedRule=null)
     *
     * **Validates: Requirements 1.6**
     */
    @Property(tries = 200)
    void noMatchAndNoFallbackReturnsEmptyResult(
            @ForAll("candidateRules") List<HttpRule> candidates) {

        // 不讓任何條件匹配成功
        Set<String> noMatchConditions = Collections.emptySet();
        TestPipeline pipeline = createPipeline(noMatchConditions);

        // 移除所有無條件的 enabled 規則（確保沒有 fallback）
        List<HttpRule> noFallbackCandidates = new ArrayList<>();
        for (HttpRule r : candidates) {
            if (Boolean.TRUE.equals(r.getEnabled()) && !hasCondition(r)) {
                // 跳過無條件 enabled 規則
                continue;
            }
            noFallbackCandidates.add(r);
        }

        MatchResult<HttpRule> result = pipeline.matchRule(noFallbackCandidates, null, null, null);

        assertThat(result.isMatched())
                .as("No condition match + no fallback → matched should be false")
                .isFalse();
        assertThat(result.getMatchedRule())
                .as("No condition match + no fallback → matchedRule should be null")
                .isNull();
    }

    /**
     * Feature: protocol-pipeline-refactor, Property 1: matchRule 共用匹配邏輯正確性
     * <p>
     * 不變式 4：第一個無條件 enabled 規則成為 fallback
     *
     * **Validates: Requirements 1.4, 1.5**
     */
    @Property(tries = 200)
    void firstNoConditionEnabledRuleBecomesFallback(
            @ForAll("candidateRules") List<HttpRule> candidates) {

        // 不讓任何條件匹配成功，這樣只有 fallback 會被選中
        Set<String> noMatchConditions = Collections.emptySet();
        TestPipeline pipeline = createPipeline(noMatchConditions);

        MatchResult<HttpRule> result = pipeline.matchRule(candidates, null, null, null);

        // 找出預期的 fallback：第一個 enabled 且無條件的規則
        HttpRule expectedFallback = null;
        for (HttpRule r : candidates) {
            if (Boolean.TRUE.equals(r.getEnabled()) && !hasCondition(r)) {
                expectedFallback = r;
                break;
            }
        }

        if (expectedFallback != null) {
            assertThat(result.isMatched()).isTrue();
            assertThat(result.getMatchedRule().getId())
                    .as("Fallback should be the first no-condition enabled rule")
                    .isEqualTo(expectedFallback.getId());
        } else {
            // 沒有 fallback 候選，且沒有條件匹配 → 空結果
            assertThat(result.isMatched()).isFalse();
        }
    }

    /**
     * Feature: protocol-pipeline-refactor, Property 1: matchRule 共用匹配邏輯正確性
     * <p>
     * 不變式 5：第一個條件匹配成功的 enabled 規則立即勝出
     *
     * **Validates: Requirements 1.3**
     */
    @Property(tries = 200)
    void firstConditionMatchedEnabledRuleWinsImmediately(
            @ForAll("candidateRules") List<HttpRule> candidates) {

        // 讓所有條件都匹配
        Set<String> allConditions = new HashSet<>();
        for (HttpRule r : candidates) {
            if (r.getBodyCondition() != null && !r.getBodyCondition().isBlank()) {
                allConditions.add(r.getBodyCondition());
            }
        }

        TestPipeline pipeline = createPipeline(allConditions);
        MatchResult<HttpRule> result = pipeline.matchRule(candidates, null, null, null);

        // 找出預期的第一個條件匹配成功的 enabled 規則
        HttpRule expectedFirst = null;
        for (HttpRule r : candidates) {
            if (Boolean.TRUE.equals(r.getEnabled()) && hasCondition(r)) {
                expectedFirst = r;
                break;
            }
        }

        if (expectedFirst != null) {
            assertThat(result.isMatched()).isTrue();
            assertThat(result.getMatchedRule().getId())
                    .as("First condition-matched enabled rule should win")
                    .isEqualTo(expectedFirst.getId());
        }
    }
}
