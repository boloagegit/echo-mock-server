package com.echo.core.engine;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RuleSelector} 必須與原本 HTTP／JMS 兩份選擇迴圈完全一致；
 * 兩份原始迴圈以 {@link #legacyHttp} 與 {@link #legacyJms} 原樣保留作為標準答案。
 */
class RuleSelectorTest {

    record Rule(int id, boolean enabled, boolean conditional, boolean conditionMatches,
                boolean wildcard, boolean gatePasses) {
    }

    private static final RuleSelector.Traits<Rule> TRAITS = new RuleSelector.Traits<>() {
        @Override
        public boolean enabled(Rule rule) {
            return rule.enabled();
        }

        @Override
        public boolean unconditional(Rule rule) {
            return !rule.conditional();
        }

        @Override
        public boolean wildcard(Rule rule) {
            return rule.wildcard();
        }
    };

    @Property(tries = 3000)
    void matchesLegacyHttpLoop(@ForAll("rules") List<Rule> rules) {
        List<Integer> evaluated = new ArrayList<>();
        RuleSelector.Selection<Rule> selection = RuleSelector.select(rules,
                RuleSelector.FallbackPolicy.FIRST_UNCONDITIONAL, TRAITS, Rule::gatePasses,
                rule -> {
                    evaluated.add(rule.id());
                    return rule.conditionMatches();
                });
        Legacy expected = legacyHttp(rules);

        assertThat(selection.selected()).isEqualTo(expected.selected);
        assertThat(selection.gateRejected()).isEqualTo(expected.rejected);
        assertThat(evaluated).as("conditions evaluated, in order").isEqualTo(expected.evaluated);
    }

    @Property(tries = 3000)
    void matchesLegacyJmsLoop(@ForAll("rules") List<Rule> rules) {
        List<Integer> evaluated = new ArrayList<>();
        RuleSelector.Selection<Rule> selection = RuleSelector.select(rules,
                RuleSelector.FallbackPolicy.EXACT_BEFORE_WILDCARD, TRAITS, Rule::gatePasses,
                rule -> {
                    evaluated.add(rule.id());
                    return rule.conditionMatches();
                });
        Legacy expected = legacyJms(rules);

        assertThat(selection.selected()).isEqualTo(expected.selected);
        assertThat(selection.gateRejected()).isEqualTo(expected.rejected);
        assertThat(evaluated).isEqualTo(expected.evaluated);
    }

    @Test
    void reportsWhetherSelectionCameFromCondition() {
        Rule fallback = new Rule(1, true, false, false, false, true);
        Rule conditional = new Rule(2, true, true, true, false, true);

        assertThat(RuleSelector.select(List.of(fallback, conditional), RuleSelector.FallbackPolicy.FIRST_UNCONDITIONAL,
                TRAITS, r -> true, Rule::conditionMatches).conditionMatched()).isTrue();
        assertThat(RuleSelector.select(List.of(fallback), RuleSelector.FallbackPolicy.FIRST_UNCONDITIONAL,
                TRAITS, r -> true, Rule::conditionMatches).conditionMatched()).isFalse();
        assertThat(RuleSelector.select(List.of(), RuleSelector.FallbackPolicy.FIRST_UNCONDITIONAL,
                TRAITS, r -> true, Rule::conditionMatches).selected()).isNull();
    }

    @Provide
    Arbitrary<List<Rule>> rules() {
        Arbitrary<Boolean> mostlyTrue = Arbitraries.frequency(Tuple.of(4, true), Tuple.of(1, false));
        Arbitrary<Boolean> any = Arbitraries.of(true, false);
        return Combinators.combine(mostlyTrue, any, any, any, mostlyTrue)
                .as((enabled, conditional, matches, wildcard, gate) -> new boolean[] {
                        enabled, conditional, matches, wildcard, gate})
                .list().ofMaxSize(8)
                .map(flags -> {
                    List<Rule> rules = new ArrayList<>();
                    for (int i = 0; i < flags.size(); i++) {
                        boolean[] f = flags.get(i);
                        rules.add(new Rule(i, f[0], f[1], f[2], f[3], f[4]));
                    }
                    return rules;
                });
    }

    // ==================== 原始迴圈（標準答案） ====================

    private record Legacy(Rule selected, List<Rule> rejected, List<Integer> evaluated) {
    }

    /** 原 AbstractMockPipeline.matchRule。 */
    private static Legacy legacyHttp(List<Rule> candidates) {
        Rule matched = null;
        Rule fallbackRule = null;
        List<Rule> rejected = new ArrayList<>();
        List<Integer> evaluated = new ArrayList<>();
        for (Rule rule : candidates) {
            if (!rule.enabled()) {
                continue;
            }
            if (!rule.gatePasses()) {
                rejected.add(rule);
                continue;
            }
            if (rule.conditional()) {
                evaluated.add(rule.id());
                if (rule.conditionMatches()) {
                    matched = rule;
                    break;
                }
            } else if (fallbackRule == null) {
                fallbackRule = rule;
            }
        }
        if (matched == null && fallbackRule != null) {
            matched = fallbackRule;
        }
        return new Legacy(matched, rejected, evaluated);
    }

    /** 原 JmsMockPipeline.matchRule（三層 fallback）。 */
    private static Legacy legacyJms(List<Rule> candidates) {
        Rule matched = null;
        Rule noConditionRule = null;
        Rule wildcardRule = null;
        List<Rule> rejected = new ArrayList<>();
        List<Integer> evaluated = new ArrayList<>();
        for (Rule rule : candidates) {
            if (!rule.enabled()) {
                continue;
            }
            if (!rule.gatePasses()) {
                rejected.add(rule);
                continue;
            }
            if (rule.conditional()) {
                evaluated.add(rule.id());
                if (rule.conditionMatches()) {
                    matched = rule;
                    break;
                }
            } else if (rule.wildcard()) {
                if (wildcardRule == null) {
                    wildcardRule = rule;
                }
            } else if (noConditionRule == null) {
                noConditionRule = rule;
            }
        }
        if (matched == null) {
            matched = noConditionRule != null ? noConditionRule : wildcardRule;
        }
        return new Legacy(matched, rejected, evaluated);
    }
}
