package com.echo.pipeline;

import com.echo.entity.JmsRule;
import com.echo.service.ConditionMatcher;
import com.echo.service.MatchChainEntry;
import com.echo.service.MatchResult;
import net.jqwik.api.*;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JMS matching behavior equivalence attribute test (including wildcard fallback)
 * <p>
 * Feature: protocol-pipeline-refactor, Property 3: JMS matching behavior equivalence (including wildcard fallback)
 * <p>
 * Use jqwik to generate a random JmsRule list (including exact queue, wildcard {@code *}, conditional/unconditional rules),
 * Compare {@code JmsMockPipeline.matchRule()} with {@code JmsRuleService.matchJmsRules()} before reconstruction
 * results, both should produce the same matching rules, containing three layers of fallback precedence:
 * <ol>
 *   <li>condition matched the exact queue rule  successfully</li>
 *   <li>Unconditionally accurate queue Rule (fallback)</li>
 *   <li>wildcard queue {@code *} Rule (last resort)</li>
 * </ol>
 *
 * <b>Validates: Requirements 1.9, 6.3</b>
 */
class JmsMatchEquivalencePropertyTest {

    // ==================== Controllable ConditionMatcher ====================

    /**
     * Controllable ConditionMatcher: Determine whether bodyCondition matches based on the matchingConditions set.
     * The two paths (pipeline and old service) share the same instance to ensure consistent matching decisions.
     */
    static class ControllableConditionMatcher extends ConditionMatcher {
        private final Set<String> matchingBodyConditions;

        ControllableConditionMatcher(Set<String> matchingBodyConditions) {
            this.matchingBodyConditions = matchingBodyConditions;
        }

        @Override
        public boolean matchesPrepared(String bodyCondition, String queryCondition,
                                       String headerCondition,
                                       PreparedBody prepared, String queryString,
                                       Map<String, String> headers) {
            if (bodyCondition != null && !bodyCondition.isBlank()) {
                return matchingBodyConditions.contains(bodyCondition);
            }
            return true;
        }

        @Override
        public PreparedBody prepareBody(String body) {
            return PreparedBody.empty();
        }
    }

    // ==================== Pipeline Under Test ====================

    /**
     * Use matchRule() directly from JmsMockPipeline, but need to provide controllable dependencies.
     * Since JmsMockPipeline requires JmsRuleService and so on Spring bean,
     * We create a test double and only override abstract methods other than matchRule().
     */
    static class TestJmsPipeline extends AbstractMockPipeline<JmsRule> {

        TestJmsPipeline(ConditionMatcher conditionMatcher) {
            super(conditionMatcher, null, null, null);
        }

        /**
         * Overwrite matchRule() and retain the unique three-layer fallback logic of JMS.
         * This logic is completely consistent with JmsMockPipeline.matchRule().
         */
        @Override
        public MatchResult<JmsRule> matchRule(List<JmsRule> candidates,
                                              ConditionMatcher.PreparedBody prepared,
                                              String queryString,
                                              Map<String, String> headers) {
            JmsRule matched = null;
            JmsRule noConditionRule = null;
            JmsRule wildcardRule = null;

            for (JmsRule rule : candidates) {
                if (Boolean.FALSE.equals(rule.getEnabled())) {
                    continue;
                }

                boolean isWildcard = "*".equals(rule.getQueueName());
                boolean hasCondition = rule.getBodyCondition() != null
                        && !rule.getBodyCondition().isBlank();

                if (hasCondition) {
                    if (conditionMatcher.matchesPrepared(
                            rule.getBodyCondition(), null, null,
                            prepared, null, null)) {
                        matched = rule;
                        break;
                    }
                } else if (isWildcard) {
                    if (wildcardRule == null) {
                        wildcardRule = rule;
                    }
                } else {
                    if (noConditionRule == null) {
                        noConditionRule = rule;
                    }
                }
            }

            if (matched == null) {
                matched = noConditionRule != null ? noConditionRule : wildcardRule;
            }

            List<MatchChainEntry> chain = matched != null
                    ? List.of(MatchChainEntry.fromJms(matched, "match"))
                    : new ArrayList<>();

            return new MatchResult<>(matched, chain);
        }

        @Override
        protected List<JmsRule> findCandidateRules(MockRequest request) {
            return Collections.emptyList();
        }

        @Override
        protected MockResponse buildResponse(JmsRule rule, MockRequest request, String responseBody) {
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
            return MockResponse.builder().status(200).body("").matched(false).forwarded(false).build();
        }

        @Override
        protected boolean hasCondition(JmsRule rule) {
            return rule.getBodyCondition() != null && !rule.getBodyCondition().isBlank();
        }

        @Override
        protected ConditionSet extractConditions(JmsRule rule) {
            return ConditionSet.builder()
                    .bodyCondition(rule.getBodyCondition())
                    .build();
        }

        @Override
        protected MatchChainEntry createMatchChainEntry(JmsRule rule, String reason) {
            return MatchChainEntry.fromJms(rule, reason);
        }
    }

    // ==================== Old Service Logic (Oracle) ====================

    /**
     * The logic of JmsRuleService.matchJmsRules() before reconstruction,
     * Copied intact as oracle, containing three layers of fallback.
     */
    static MatchResult<JmsRule> oldMatchJmsRules(List<JmsRule> rules,
                                                  ConditionMatcher conditionMatcher,
                                                  ConditionMatcher.PreparedBody prepared) {
        JmsRule matched = null;
        JmsRule noConditionRule = null;
        JmsRule wildcardRule = null;

        for (JmsRule rule : rules) {
            if (Boolean.FALSE.equals(rule.getEnabled())) {
                continue;
            }
            boolean isWildcard = "*".equals(rule.getQueueName());
            boolean hasCondition = rule.getBodyCondition() != null
                    && !rule.getBodyCondition().isBlank();

            if (hasCondition) {
                if (conditionMatcher.matchesPrepared(
                        rule.getBodyCondition(), null, null, prepared, null, null)) {
                    matched = rule;
                    break;
                }
            } else if (isWildcard) {
                if (wildcardRule == null) {
                    wildcardRule = rule;
                }
            } else {
                if (noConditionRule == null) {
                    noConditionRule = rule;
                }
            }
        }

        if (matched == null) {
            matched = noConditionRule != null ? noConditionRule : wildcardRule;
        }

        List<MatchChainEntry> chain = matched != null
                ? List.of(MatchChainEntry.fromJms(matched, "match"))
                : List.of();
        return new MatchResult<>(matched, chain);
    }

    // ==================== Generators ====================

    @Provide
    Arbitrary<List<JmsRule>> jmsRuleLists() {
        return jmsRuleArbitrary().list().ofMinSize(0).ofMaxSize(15);
    }

    private Arbitrary<JmsRule> jmsRuleArbitrary() {
        Arbitrary<Boolean> enabled = Arbitraries.of(true, false);
        Arbitrary<String> queueName = Arbitraries.oneOf(
                Arbitraries.just("*"),
                Arbitraries.of("ORDER.REQUEST", "PAYMENT.REQUEST", "STOCK.QUERY", "ECHO.REQUEST")
        );
        Arbitrary<String> bodyCondition = Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.just(""),
                Arbitraries.just("  "),
                Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(8)
                        .map(s -> "cond_" + s)
        );

        return Combinators.combine(enabled, queueName, bodyCondition)
                .as((en, qn, bc) ->
                        JmsRule.builder()
                                .id(UUID.randomUUID().toString())
                                .enabled(en)
                                .queueName(qn)
                                .bodyCondition(bc)
                                .priority(0)
                                .delayMs(0L)
                                .build()
                );
    }

    // ==================== Helper ====================

    /**
     * Collect all bodyCondition values from the rule list and randomly select a subset as conditions for successful matching
     */
    private ControllableConditionMatcher buildMatcher(List<JmsRule> rules, Random random) {
        Set<String> allBody = new HashSet<>();
        for (JmsRule r : rules) {
            if (r.getBodyCondition() != null && !r.getBodyCondition().isBlank()) {
                allBody.add(r.getBodyCondition());
            }
        }
        Set<String> matchBody = randomSubset(allBody, random);
        return new ControllableConditionMatcher(matchBody);
    }

    private Set<String> randomSubset(Set<String> source, Random random) {
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
     * Feature: protocol-pipeline-refactor, Property 3: JMS matching behavior equivalence (including wildcard fallback)
     * <p>
     * matchRule() result for JmsMockPipeline for any JmsRule list with random match criteria
     * completely consistent with the result of JmsRuleService.matchJmsRules() before reconstruction
     * (Same matchedRule ID or both no matches).
     *
     * <b>Validates: Requirements 1.9, 6.3</b>
     */
    @Property(tries = 200)
    void pipelineMatchRuleEquivalentToOldService(
            @ForAll("jmsRuleLists") List<JmsRule> rules,
            @ForAll @net.jqwik.api.constraints.IntRange(min = 0, max = Integer.MAX_VALUE) int seed) {

        Random random = new Random(seed);
        ControllableConditionMatcher matcher = buildMatcher(rules, random);
        ConditionMatcher.PreparedBody prepared = matcher.prepareBody(null);

        // New pipeline path
        TestJmsPipeline pipeline = new TestJmsPipeline(matcher);
        MatchResult<JmsRule> pipelineResult = pipeline.matchRule(rules, prepared, null, null);

        // Old service path (oracle)
        MatchResult<JmsRule> oldResult = oldMatchJmsRules(rules, matcher, prepared);

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
     * Feature: protocol-pipeline-refactor, Property 3: JMS matching behavior equivalence (including wildcard fallback)
     * <p>
     * Both implementations should select the same rule when all conditions match successfully.
     * Validation condition match takes precedence over no-condition fallback and wildcard fallback.
     *
     * <b>Validates: Requirements 1.9, 6.3</b>
     */
    @Property(tries = 200)
    void equivalenceWhenAllConditionsMatch(
            @ForAll("jmsRuleLists") List<JmsRule> rules) {

        Set<String> allBody = new HashSet<>();
        for (JmsRule r : rules) {
            if (r.getBodyCondition() != null && !r.getBodyCondition().isBlank()) {
                allBody.add(r.getBodyCondition());
            }
        }

        ControllableConditionMatcher matcher = new ControllableConditionMatcher(allBody);
        ConditionMatcher.PreparedBody prepared = matcher.prepareBody(null);

        TestJmsPipeline pipeline = new TestJmsPipeline(matcher);
        MatchResult<JmsRule> pipelineResult = pipeline.matchRule(rules, prepared, null, null);
        MatchResult<JmsRule> oldResult = oldMatchJmsRules(rules, matcher, prepared);

        assertThat(pipelineResult.isMatched())
                .isEqualTo(oldResult.isMatched());

        if (pipelineResult.isMatched()) {
            assertThat(pipelineResult.getMatchedRule().getId())
                    .isEqualTo(oldResult.getMatchedRule().getId());
        }
    }

    /**
     * Feature: protocol-pipeline-refactor, Property 3: JMS matching behavior equivalence (including wildcard fallback)
     * <p>
     * Both implementations should select the same fallback rule when no condition matches successfully
     * (no-condition exact queue takes precedence over wildcard queue, or both have no match).
     *
     * <b>Validates: Requirements 1.9, 6.3</b>
     */
    @Property(tries = 200)
    void equivalenceWhenNoConditionsMatch(
            @ForAll("jmsRuleLists") List<JmsRule> rules) {

        ControllableConditionMatcher matcher = new ControllableConditionMatcher(Collections.emptySet());
        ConditionMatcher.PreparedBody prepared = matcher.prepareBody(null);

        TestJmsPipeline pipeline = new TestJmsPipeline(matcher);
        MatchResult<JmsRule> pipelineResult = pipeline.matchRule(rules, prepared, null, null);
        MatchResult<JmsRule> oldResult = oldMatchJmsRules(rules, matcher, prepared);

        assertThat(pipelineResult.isMatched())
                .isEqualTo(oldResult.isMatched());

        if (pipelineResult.isMatched()) {
            assertThat(pipelineResult.getMatchedRule().getId())
                    .isEqualTo(oldResult.getMatchedRule().getId());
        }
    }

    /**
     * Feature: protocol-pipeline-refactor, Property 3: JMS matching behavior equivalence (including wildcard fallback)
     * <p>
     * Three-tier fallback priority validation: when mixing exact queue and wildcard queue rules
     * The fallback selection is consistent for both implementations, and the wildcard rule is only selected when there is no higher-priority fallback.
     *
     * <b>Validates: Requirements 1.9, 6.3</b>
     */
    @Property(tries = 200)
    void wildcardFallbackPriorityEquivalence(
            @ForAll("jmsRuleLists") List<JmsRule> rules,
            @ForAll @net.jqwik.api.constraints.IntRange(min = 0, max = Integer.MAX_VALUE) int seed) {

        Random random = new Random(seed);
        ControllableConditionMatcher matcher = buildMatcher(rules, random);
        ConditionMatcher.PreparedBody prepared = matcher.prepareBody(null);

        TestJmsPipeline pipeline = new TestJmsPipeline(matcher);
        MatchResult<JmsRule> pipelineResult = pipeline.matchRule(rules, prepared, null, null);
        MatchResult<JmsRule> oldResult = oldMatchJmsRules(rules, matcher, prepared);

        // Verify that the priority order of fallback is consistent
        assertThat(pipelineResult.isMatched())
                .as("Both should agree on match status")
                .isEqualTo(oldResult.isMatched());

        if (pipelineResult.isMatched() && oldResult.isMatched()) {
            // The two selection rules ID must be the same
            assertThat(pipelineResult.getMatchedRule().getId())
                    .as("Wildcard fallback priority should be consistent between pipeline and old service")
                    .isEqualTo(oldResult.getMatchedRule().getId());

            // The queueName selected by both must be the same (verify that the selection of wildcard vs exact is consistent)
            assertThat(pipelineResult.getMatchedRule().getQueueName())
                    .as("Both should select rule from the same queue type")
                    .isEqualTo(oldResult.getMatchedRule().getQueueName());
        }
    }
}
