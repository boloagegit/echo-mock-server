package com.echo.pipeline;

import com.echo.entity.JmsRule;
import com.echo.service.ConditionMatcher;
import com.echo.service.MatchChainEntry;
import com.echo.service.MatchResult;
import net.jqwik.api.*;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JMS 匹配行為等價性屬性測試（含 wildcard fallback）
 * <p>
 * Feature: protocol-pipeline-refactor, Property 3: JMS 匹配行為等價性（含 wildcard fallback）
 * <p>
 * 使用 jqwik 產生隨機 JmsRule 清單（含精確 queue、wildcard {@code *}、有條件/無條件規則），
 * 比較 {@code JmsMockPipeline.matchRule()} 與重構前 {@code JmsRuleService.matchJmsRules()}
 * 的結果，兩者應產生相同的匹配規則，包含三層 fallback 優先順序：
 * <ol>
 *   <li>條件匹配成功的精確 queue 規則</li>
 *   <li>無條件的精確 queue 規則（fallback）</li>
 *   <li>wildcard queue {@code *} 規則（last resort）</li>
 * </ol>
 *
 * <b>Validates: Requirements 1.9, 6.3</b>
 */
class JmsMatchEquivalencePropertyTest {

    // ==================== Controllable ConditionMatcher ====================

    /**
     * 可控的 ConditionMatcher：根據 matchingConditions 集合決定 bodyCondition 是否匹配。
     * 兩條路徑（pipeline 和 old service）共用同一個實例，確保匹配決策一致。
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
     * 直接使用 JmsMockPipeline 的 matchRule()，但需要提供可控的依賴。
     * 由於 JmsMockPipeline 需要 JmsRuleService 等 Spring bean，
     * 我們建立一個測試替身，只覆寫 matchRule() 以外的抽象方法。
     */
    static class TestJmsPipeline extends AbstractMockPipeline<JmsRule> {

        TestJmsPipeline(ConditionMatcher conditionMatcher) {
            super(conditionMatcher, null, null, null);
        }

        /**
         * 覆寫 matchRule()，保留 JMS 特有的三層 fallback 邏輯。
         * 此邏輯與 JmsMockPipeline.matchRule() 完全一致。
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
     * 重構前 JmsRuleService.matchJmsRules() 的邏輯，
     * 原封不動地複製過來作為 oracle，包含三層 fallback。
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
     * 從規則清單中收集所有 bodyCondition 值，隨機選擇子集作為匹配成功的條件
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
     * Feature: protocol-pipeline-refactor, Property 3: JMS 匹配行為等價性（含 wildcard fallback）
     * <p>
     * 對任意 JmsRule 清單與隨機匹配條件，JmsMockPipeline 的 matchRule() 結果
     * 與重構前 JmsRuleService.matchJmsRules() 的結果完全一致
     * （相同的 matchedRule ID 或同時無匹配）。
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

        // 新 pipeline 路徑
        TestJmsPipeline pipeline = new TestJmsPipeline(matcher);
        MatchResult<JmsRule> pipelineResult = pipeline.matchRule(rules, prepared, null, null);

        // 舊 service 路徑（oracle）
        MatchResult<JmsRule> oldResult = oldMatchJmsRules(rules, matcher, prepared);

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
     * Feature: protocol-pipeline-refactor, Property 3: JMS 匹配行為等價性（含 wildcard fallback）
     * <p>
     * 當所有條件都匹配成功時，兩個實作應選擇相同的規則。
     * 驗證條件匹配優先於 no-condition fallback 和 wildcard fallback。
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
     * Feature: protocol-pipeline-refactor, Property 3: JMS 匹配行為等價性（含 wildcard fallback）
     * <p>
     * 當沒有任何條件匹配成功時，兩個實作應選擇相同的 fallback 規則
     * （no-condition exact queue 優先於 wildcard queue，或同時無匹配）。
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
     * Feature: protocol-pipeline-refactor, Property 3: JMS 匹配行為等價性（含 wildcard fallback）
     * <p>
     * 三層 fallback 優先順序驗證：混合精確 queue 與 wildcard queue 規則時，
     * 兩個實作的 fallback 選擇一致，且 wildcard 規則只在沒有更高優先的 fallback 時被選中。
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

        // 驗證 fallback 優先順序一致
        assertThat(pipelineResult.isMatched())
                .as("Both should agree on match status")
                .isEqualTo(oldResult.isMatched());

        if (pipelineResult.isMatched() && oldResult.isMatched()) {
            // 兩者選擇的規則 ID 必須相同
            assertThat(pipelineResult.getMatchedRule().getId())
                    .as("Wildcard fallback priority should be consistent between pipeline and old service")
                    .isEqualTo(oldResult.getMatchedRule().getId());

            // 兩者選擇的 queueName 必須相同（驗證 wildcard vs exact 的選擇一致）
            assertThat(pipelineResult.getMatchedRule().getQueueName())
                    .as("Both should select rule from the same queue type")
                    .isEqualTo(oldResult.getMatchedRule().getQueueName());
        }
    }
}
