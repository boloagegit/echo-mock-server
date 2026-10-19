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
 * HTTP Match behavior equivalence attribute test
 * <p>
 * Feature: protocol-pipeline-refactor, Property 2: HTTP Match behavior equivalence
 * <p>
 * Use jqwik to generate a random HttpRule list and request parameters, compare {@code HttpMockPipeline} via
 * Result of {@code matchRule()} vs. {@code HttpRuleService.findMatchingHttpRuleInternal()} before reconstruction
 * The results of both should produce the same matching rule (or no match at the same time).
 *
 * <b>Validates: Requirements 1.8, 6.2</b>
 */
class HttpMatchEquivalencePropertyTest {

    // ==================== Controllable ConditionMatcher ====================

    /**
     * Controllable ConditionMatcher: Determine whether to match based on the matchingConditions set.
     * The two paths (pipeline and old service) share the same instance to ensure consistent matching decisions.
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
     * Test double for HttpMockPipeline, using controllable ConditionMatcher
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
     * The logic of HttpRuleService.findMatchingHttpRuleInternal() before reconstruction,
     * Copied unchanged as oracle.
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
     * Collect all non-empty condition values from the rule list, and randomly select a part as the "match success" condition.
     * This can produce various match/mismatch combinations.
     */
    @Provide
    Arbitrary<Set<String>> matchingSubset() {
        return Arbitraries.just(new HashSet<>());
    }

    // ==================== Helper ====================

    /**
     * Collect all condition values from the rule list and randomly select a subset as conditions for successful matching
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

        // Randomly select subset
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
     * Feature: protocol-pipeline-refactor, Property 2: HTTP Match behavior equivalence
     * <p>
     * For any sorted HttpRule list, the matching result of HttpMockPipeline through matchRule()
     * Exactly the same as the result of HttpRuleService.findMatchingHttpRuleInternal() before reconstruction
     * (Same matchedRule ID or both no matches).
     *
     * <b>Validates: Requirements 1.8, 6.2</b>
     */
    @Property(tries = 200)
    void pipelineMatchRuleEquivalentToOldService(
            @ForAll("httpRuleLists") List<HttpRule> rules,
            @ForAll @IntRange(min = 0, max = Integer.MAX_VALUE) int seed) {

        java.util.Random random = new java.util.Random(seed);
        ControllableConditionMatcher matcher = buildMatcher(rules, random);

        // Prepare preparedBody (share the same one on both sides)
        ConditionMatcher.PreparedBody prepared = matcher.prepareBody(null);

        // New pipeline path
        TestHttpPipeline pipeline = new TestHttpPipeline(matcher);
        MatchResult<HttpRule> pipelineResult = pipeline.matchRule(rules, prepared, null, null);

        // Old service path
        MatchResult<HttpRule> oldResult = oldFindMatchingHttpRuleInternal(
                rules, matcher, prepared, null, null);

        // Comparison results
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
     * Feature: protocol-pipeline-refactor, Property 2: HTTP Match behavior equivalence
     * <p>
     * Both implementations should select the same rule when all conditions match successfully.
     *
     * <b>Validates: Requirements 1.8, 6.2</b>
     */
    @Property(tries = 200)
    void equivalenceWhenAllConditionsMatch(
            @ForAll("httpRuleLists") List<HttpRule> rules) {

        // Collect all conditions and set them all to match
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
     * Feature: protocol-pipeline-refactor, Property 2: HTTP Match behavior equivalence
     * <p>
     * Two implementations should select the same fallback rule when no condition matches successfully (or both have no matches).
     *
     * <b>Validates: Requirements 1.8, 6.2</b>
     */
    @Property(tries = 200)
    void equivalenceWhenNoConditionsMatch(
            @ForAll("httpRuleLists") List<HttpRule> rules) {

        // Don't let any conditions match
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
