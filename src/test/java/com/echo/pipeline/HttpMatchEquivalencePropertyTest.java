package com.echo.pipeline;

import com.echo.entity.HttpRule;
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
 * HTTP 匹配行為等價性屬性測試
 * <p>
 * Feature: protocol-pipeline-refactor, Property 2: HTTP 匹配行為等價性
 * <p>
 * 使用 jqwik 產生隨機 HttpRule 清單與請求參數，比較 {@code HttpMockPipeline} 透過
 * {@code matchRule()} 的結果與重構前 {@code HttpRuleService.findMatchingHttpRuleInternal()}
 * 的結果，兩者應產生相同的匹配規則（或同時無匹配）。
 *
 * <b>Validates: Requirements 1.8, 6.2</b>
 */
class HttpMatchEquivalencePropertyTest {

    // ==================== Controllable ConditionMatcher ====================

    /**
     * 可控的 ConditionMatcher：根據 matchingConditions 集合決定是否匹配。
     * 兩條路徑（pipeline 和 old service）共用同一個實例，確保匹配決策一致。
     */
    static class ControllableConditionMatcher extends ConditionMatcher {
        private final Set<String> matchingBodyConditions;
        private final Set<String> matchingQueryConditions;
        private final Set<String> matchingHeaderConditions;

        ControllableConditionMatcher(Set<String> matchingBodyConditions,
                                     Set<String> matchingQueryConditions,
                                     Set<String> matchingHeaderConditions) {
            this.matchingBodyConditions = matchingBodyConditions;
            this.matchingQueryConditions = matchingQueryConditions;
            this.matchingHeaderConditions = matchingHeaderConditions;
        }

        @Override
        public boolean matchesPrepared(String bodyCondition, String queryCondition,
                                       String headerCondition,
                                       PreparedBody prepared, String queryString,
                                       Map<String, String> headers) {
            // Body condition check
            if (bodyCondition != null && !bodyCondition.isBlank()) {
                if (!matchingBodyConditions.contains(bodyCondition)) {
                    return false;
                }
            }
            // Query condition check
            if (queryCondition != null && !queryCondition.isBlank()) {
                if (!matchingQueryConditions.contains(queryCondition)) {
                    return false;
                }
            }
            // Header condition check
            if (headerCondition != null && !headerCondition.isBlank()) {
                if (!matchingHeaderConditions.contains(headerCondition)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public PreparedBody prepareBody(String body) {
            return PreparedBody.empty();
        }
    }

    // ==================== Pipeline Test Subclass ====================

    /**
     * HttpMockPipeline 的測試替身，使用可控的 ConditionMatcher
     */
    static class TestHttpPipeline extends AbstractMockPipeline<HttpRule> {

        TestHttpPipeline(ConditionMatcher conditionMatcher) {
            super(conditionMatcher, null, null, null);
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
            return (rule.getBodyCondition() != null && !rule.getBodyCondition().isBlank())
                    || (rule.getQueryCondition() != null && !rule.getQueryCondition().isBlank())
                    || (rule.getHeaderCondition() != null && !rule.getHeaderCondition().isBlank());
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

    // ==================== Old Service Logic (extracted) ====================

    /**
     * 重構前 HttpRuleService.findMatchingHttpRuleInternal() 的邏輯，
     * 原封不動地複製過來作為 oracle。
     */
    static MatchResult<HttpRule> oldFindMatchingHttpRuleInternal(
            List<HttpRule> rules, ConditionMatcher conditionMatcher,
            ConditionMatcher.PreparedBody prepared,
            String queryString, Map<String, String> headers) {

        HttpRule matched = null;
        HttpRule fallbackRule = null;

        for (HttpRule rule : rules) {
            if (Boolean.FALSE.equals(rule.getEnabled())) {
                continue;
            }

            boolean hasCondition = (rule.getBodyCondition() != null && !rule.getBodyCondition().isBlank())
                    || (rule.getQueryCondition() != null && !rule.getQueryCondition().isBlank())
                    || (rule.getHeaderCondition() != null && !rule.getHeaderCondition().isBlank());

            if (hasCondition) {
                if (conditionMatcher.matchesPrepared(
                        rule.getBodyCondition(), rule.getQueryCondition(),
                        rule.getHeaderCondition(), prepared, queryString, headers)) {
                    matched = rule;
                    break;
                }
            } else {
                if (fallbackRule == null) {
                    fallbackRule = rule;
                }
            }
        }

        if (matched == null && fallbackRule != null) {
            matched = fallbackRule;
        }

        List<MatchChainEntry> chain = matched != null
                ? List.of(MatchChainEntry.fromHttp(matched, "match"))
                : List.of();
        return new MatchResult<>(matched, chain);
    }

    // ==================== Generators ====================

    @Provide
    Arbitrary<List<HttpRule>> httpRuleLists() {
        return httpRuleArbitrary().list().ofMinSize(0).ofMaxSize(15);
    }

    private Arbitrary<HttpRule> httpRuleArbitrary() {
        Arbitrary<Boolean> enabled = Arbitraries.of(true, false);
        Arbitrary<String> bodyCondition = Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.just(""),
                Arbitraries.just("  "),
                Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(8)
                        .map(s -> "body_" + s)
        );
        Arbitrary<String> queryCondition = Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.just(""),
                Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(8)
                        .map(s -> "query_" + s)
        );
        Arbitrary<String> headerCondition = Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.just(""),
                Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(8)
                        .map(s -> "header_" + s)
        );

        return Combinators.combine(enabled, bodyCondition, queryCondition, headerCondition)
                .as((en, bc, qc, hc) ->
                        HttpRule.builder()
                                .id(UUID.randomUUID().toString())
                                .enabled(en)
                                .bodyCondition(bc)
                                .queryCondition(qc)
                                .headerCondition(hc)
                                .matchKey("/test")
                                .httpStatus(200)
                                .priority(0)
                                .delayMs(0L)
                                .build()
                );
    }

    /**
     * 從規則清單中收集所有非空條件值，隨機選擇一部分作為「匹配成功」的條件。
     * 這樣可以產生各種匹配/不匹配的組合。
     */
    @Provide
    Arbitrary<Set<String>> matchingSubset() {
        return Arbitraries.just(new HashSet<>());
    }

    // ==================== Helper ====================

    /**
     * 從規則清單中收集所有條件值，隨機選擇子集作為匹配成功的條件
     */
    private ControllableConditionMatcher buildMatcher(List<HttpRule> rules, java.util.Random random) {
        Set<String> allBody = new HashSet<>();
        Set<String> allQuery = new HashSet<>();
        Set<String> allHeader = new HashSet<>();

        for (HttpRule r : rules) {
            if (r.getBodyCondition() != null && !r.getBodyCondition().isBlank()) {
                allBody.add(r.getBodyCondition());
            }
            if (r.getQueryCondition() != null && !r.getQueryCondition().isBlank()) {
                allQuery.add(r.getQueryCondition());
            }
            if (r.getHeaderCondition() != null && !r.getHeaderCondition().isBlank()) {
                allHeader.add(r.getHeaderCondition());
            }
        }

        // 隨機選擇子集
        Set<String> matchBody = randomSubset(allBody, random);
        Set<String> matchQuery = randomSubset(allQuery, random);
        Set<String> matchHeader = randomSubset(allHeader, random);

        return new ControllableConditionMatcher(matchBody, matchQuery, matchHeader);
    }

    private Set<String> randomSubset(Set<String> source, java.util.Random random) {
        Set<String> subset = new HashSet<>();
        for (String s : source) {
            if (random.nextBoolean()) {
                subset.add(s);
            }
        }
        return subset;
    }

    // ==================== Property Tests ====================

    /**
     * Feature: protocol-pipeline-refactor, Property 2: HTTP 匹配行為等價性
     * <p>
     * 對任意已排序的 HttpRule 清單，HttpMockPipeline 透過 matchRule() 產生的匹配結果
     * 與重構前 HttpRuleService.findMatchingHttpRuleInternal() 的結果完全一致
     * （相同的 matchedRule ID 或同時無匹配）。
     *
     * <b>Validates: Requirements 1.8, 6.2</b>
     */
    @Property(tries = 200)
    void pipelineMatchRuleEquivalentToOldService(
            @ForAll("httpRuleLists") List<HttpRule> rules,
            @ForAll @IntRange(min = 0, max = Integer.MAX_VALUE) int seed) {

        java.util.Random random = new java.util.Random(seed);
        ControllableConditionMatcher matcher = buildMatcher(rules, random);

        // 準備 preparedBody（兩邊共用同一個）
        ConditionMatcher.PreparedBody prepared = matcher.prepareBody(null);

        // 新 pipeline 路徑
        TestHttpPipeline pipeline = new TestHttpPipeline(matcher);
        MatchResult<HttpRule> pipelineResult = pipeline.matchRule(rules, prepared, null, null);

        // 舊 service 路徑
        MatchResult<HttpRule> oldResult = oldFindMatchingHttpRuleInternal(
                rules, matcher, prepared, null, null);

        // 比較結果
        assertThat(pipelineResult.isMatched())
                .as("Both should agree on whether a match was found")
                .isEqualTo(oldResult.isMatched());

        if (pipelineResult.isMatched()) {
            assertThat(pipelineResult.getMatchedRule().getId())
                    .as("Both should match the same rule ID")
                    .isEqualTo(oldResult.getMatchedRule().getId());
        }
    }

    /**
     * Feature: protocol-pipeline-refactor, Property 2: HTTP 匹配行為等價性
     * <p>
     * 當所有條件都匹配成功時，兩個實作應選擇相同的規則。
     *
     * <b>Validates: Requirements 1.8, 6.2</b>
     */
    @Property(tries = 200)
    void equivalenceWhenAllConditionsMatch(
            @ForAll("httpRuleLists") List<HttpRule> rules) {

        // 收集所有條件，全部設為匹配
        Set<String> allBody = new HashSet<>();
        Set<String> allQuery = new HashSet<>();
        Set<String> allHeader = new HashSet<>();
        for (HttpRule r : rules) {
            if (r.getBodyCondition() != null && !r.getBodyCondition().isBlank()) {
                allBody.add(r.getBodyCondition());
            }
            if (r.getQueryCondition() != null && !r.getQueryCondition().isBlank()) {
                allQuery.add(r.getQueryCondition());
            }
            if (r.getHeaderCondition() != null && !r.getHeaderCondition().isBlank()) {
                allHeader.add(r.getHeaderCondition());
            }
        }

        ControllableConditionMatcher matcher = new ControllableConditionMatcher(allBody, allQuery, allHeader);
        ConditionMatcher.PreparedBody prepared = matcher.prepareBody(null);

        TestHttpPipeline pipeline = new TestHttpPipeline(matcher);
        MatchResult<HttpRule> pipelineResult = pipeline.matchRule(rules, prepared, null, null);
        MatchResult<HttpRule> oldResult = oldFindMatchingHttpRuleInternal(
                rules, matcher, prepared, null, null);

        assertThat(pipelineResult.isMatched())
                .isEqualTo(oldResult.isMatched());

        if (pipelineResult.isMatched()) {
            assertThat(pipelineResult.getMatchedRule().getId())
                    .isEqualTo(oldResult.getMatchedRule().getId());
        }
    }

    /**
     * Feature: protocol-pipeline-refactor, Property 2: HTTP 匹配行為等價性
     * <p>
     * 當沒有任何條件匹配成功時，兩個實作應選擇相同的 fallback 規則（或同時無匹配）。
     *
     * <b>Validates: Requirements 1.8, 6.2</b>
     */
    @Property(tries = 200)
    void equivalenceWhenNoConditionsMatch(
            @ForAll("httpRuleLists") List<HttpRule> rules) {

        // 不讓任何條件匹配
        ControllableConditionMatcher matcher = new ControllableConditionMatcher(
                Collections.emptySet(), Collections.emptySet(), Collections.emptySet());
        ConditionMatcher.PreparedBody prepared = matcher.prepareBody(null);

        TestHttpPipeline pipeline = new TestHttpPipeline(matcher);
        MatchResult<HttpRule> pipelineResult = pipeline.matchRule(rules, prepared, null, null);
        MatchResult<HttpRule> oldResult = oldFindMatchingHttpRuleInternal(
                rules, matcher, prepared, null, null);

        assertThat(pipelineResult.isMatched())
                .isEqualTo(oldResult.isMatched());

        if (pipelineResult.isMatched()) {
            assertThat(pipelineResult.getMatchedRule().getId())
                    .isEqualTo(oldResult.getMatchedRule().getId());
        }
    }
}
