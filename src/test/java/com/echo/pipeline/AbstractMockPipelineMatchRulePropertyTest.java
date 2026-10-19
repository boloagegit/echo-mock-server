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
 * AbstractMockPipeline.matchRule() attribute test
 * <p>
 * Feature: protocol-pipeline-refactor, Property 1: matchRule Common matching logic correctness
 * <p>
 * Use jqwik to generate a random candidate rule list (random enabled/disabled, conditional/unconditional),
 * Verify the five invariants of matchRule() with controllable ConditionMatcher.
 *
 * **Validates: Requirements 1.2, 1.3, 1.4, 1.5, 1.6**
 */
class AbstractMockPipelineMatchRulePropertyTest {

    // ==================== Test Subclass ====================

    /**
     * Concrete test subclass implementing all abstract methods to exercise matchRule().
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
     * Stub ConditionMatcher: Determine whether to match based on the matchingConditions set
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
     * Feature: protocol-pipeline-refactor, Property 1: matchRule Common matching logic correctness
     * <p>
     * Invariant 1: The returned rule is never the rule of enabled=false
     *
     * **Validates: Requirements 1.2**
     */
    @Property(tries = 200)
    void matchedRuleIsNeverDisabled(@ForAll("candidateRules") List<HttpRule> candidates) {
        // Make sure all conditions match to ensure the disabled rule is still not selected
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
     * Feature: protocol-pipeline-refactor, Property 1: matchRule Common matching logic correctness
     * <p>
     * Invariant 2: enabled rules with successful conditional matching take precedence over unconditional fallback rules
     *
     * **Validates: Requirements 1.3**
     */
    @Property(tries = 200)
    void conditionMatchedRuleTakesPriorityOverFallback(
            @ForAll("candidateRules") List<HttpRule> candidates) {

        // Collecting bodyCondition for all conditional enabled rules
        Set<String> allConditions = new HashSet<>();
        for (HttpRule r : candidates) {
            if (Boolean.TRUE.equals(r.getEnabled()) && hasCondition(r)) {
                allConditions.add(r.getBodyCondition());
            }
        }

        TestPipeline pipeline = createPipeline(allConditions);
        MatchResult<HttpRule> result = pipeline.matchRule(candidates, null, null, null);

        if (result.isMatched()) {
            // Check whether there is a enabled rule whose condition matches successfully
            boolean hasConditionMatchedEnabled = candidates.stream()
                    .anyMatch(r -> Boolean.TRUE.equals(r.getEnabled())
                            && hasCondition(r)
                            && allConditions.contains(r.getBodyCondition()));

            if (hasConditionMatchedEnabled) {
                // The returned rule must be conditional (not fallback)
                assertThat(hasCondition(result.getMatchedRule()))
                        .as("When condition-matched rules exist, result should be a condition-matched rule, not fallback")
                        .isTrue();
            }
        }
    }

    /**
     * Feature: protocol-pipeline-refactor, Property 1: matchRule Common matching logic correctness
     * <p>
     * Invariant 3: When there is no match and no fallback, return empty MatchResult (matched=false, matchedRule=null)
     *
     * **Validates: Requirements 1.6**
     */
    @Property(tries = 200)
    void noMatchAndNoFallbackReturnsEmptyResult(
            @ForAll("candidateRules") List<HttpRule> candidates) {

        // Do not allow any condition to match successfully
        Set<String> noMatchConditions = Collections.emptySet();
        TestPipeline pipeline = createPipeline(noMatchConditions);

        // Remove all unconditional enabled rules (make sure there are no fallback)
        List<HttpRule> noFallbackCandidates = new ArrayList<>();
        for (HttpRule r : candidates) {
            if (Boolean.TRUE.equals(r.getEnabled()) && !hasCondition(r)) {
                // Skipping unconditional enabled rule
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
     * Feature: protocol-pipeline-refactor, Property 1: matchRule Common matching logic correctness
     * <p>
     * Invariant 4: The first unconditional enabled rule becomes fallback
     *
     * **Validates: Requirements 1.4, 1.5**
     */
    @Property(tries = 200)
    void firstNoConditionEnabledRuleBecomesFallback(
            @ForAll("candidateRules") List<HttpRule> candidates) {

        // Do not allow any condition to match successfully, so that only fallback will be selected
        Set<String> noMatchConditions = Collections.emptySet();
        TestPipeline pipeline = createPipeline(noMatchConditions);

        MatchResult<HttpRule> result = pipeline.matchRule(candidates, null, null, null);

        // Find expected fallback: first enabled and unconditional rule
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
            // No fallback candidate and no condition matching → empty result
            assertThat(result.isMatched()).isFalse();
        }
    }

    /**
     * Feature: protocol-pipeline-refactor, Property 1: matchRule Common matching logic correctness
     * <p>
     * Invariant 5: the first enabled rule whose conditions match wins immediately.
     *
     * **Validates: Requirements 1.3**
     */
    @Property(tries = 200)
    void firstConditionMatchedEnabledRuleWinsImmediately(
            @ForAll("candidateRules") List<HttpRule> candidates) {

        // Make all conditions match
        Set<String> allConditions = new HashSet<>();
        for (HttpRule r : candidates) {
            if (r.getBodyCondition() != null && !r.getBodyCondition().isBlank()) {
                allConditions.add(r.getBodyCondition());
            }
        }

        TestPipeline pipeline = createPipeline(allConditions);
        MatchResult<HttpRule> result = pipeline.matchRule(candidates, null, null, null);

        // Find the enabled rule where the expected first condition matches successfully
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
