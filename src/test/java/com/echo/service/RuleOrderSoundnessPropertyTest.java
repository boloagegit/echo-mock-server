package com.echo.service;

import com.echo.core.engine.RuleSelector;
import com.echo.entity.HttpRule;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 被判定「一定輪不到」的規則，用實際的候選查詢與挑選邏輯（{@link HttpRuleSnapshot}、{@link RuleSelector}）
 * 跑過各種請求，都不會被選中。
 */
class RuleOrderSoundnessPropertyTest {

    private static final List<String> PATHS = List.of("/orders", "/orders/1", "/orders/*", "/items", "*");
    private static final List<String> REQUEST_PATHS = List.of("/orders", "/orders/1", "/orders/2", "/items", "/other");
    private static final List<String> METHODS = List.of("GET", "POST");
    private static final List<String> HOSTS = List.of("a.test", "b.test");
    private static final List<String> BODIES = List.of("{\"a\":1}", "{\"a\":2}", "{\"b\":1}");

    private final ConditionMatcher matcher = new ConditionMatcher();

    @Property(tries = 300)
    void rulesMarkedNeverReachedAreNeverSelected(@ForAll("ruleSets") List<HttpRule> rules) {
        HttpRuleSnapshot snapshot = new HttpRuleSnapshot(rules, HttpRuleService.HTTP_RULE_ORDER, null);
        HttpRuleService httpRuleService = mock(HttpRuleService.class);
        when(httpRuleService.httpRuleSnapshot()).thenReturn(snapshot);
        JmsRuleService jmsRuleService = mock(JmsRuleService.class);
        when(jmsRuleService.findAllJmsRulesCached()).thenReturn(List.of());
        when(jmsRuleService.withValidResponses(List.of())).thenReturn(List.of());
        Set<String> neverReached = new RuleOrderService(httpRuleService, jmsRuleService, Optional.empty())
                .issues().issues().keySet();

        Set<String> selected = new HashSet<>();
        for (String host : HOSTS) {
            for (String method : METHODS) {
                for (String path : REQUEST_PATHS) {
                    List<HttpRule> candidates = snapshot.candidates(host, method).stream()
                            .filter(rule -> matchesPath(rule.getMatchKey(), path)).toList();
                    for (String body : BODIES) {
                        // 情境狀態兩種都試：受情境限制的規則可能通過也可能被略過
                        for (boolean gateOpen : List.of(true, false)) {
                            HttpRule chosen = select(candidates, body, gateOpen);
                            if (chosen != null) {
                                selected.add(chosen.getId());
                            }
                        }
                    }
                }
            }
        }
        selected.retainAll(neverReached);
        assertThat(selected).as("rules marked never reached but selected").isEmpty();
    }

    private HttpRule select(List<HttpRule> candidates, String body, boolean gateOpen) {
        ConditionMatcher.PreparedBody prepared = matcher.prepareBody(body);
        return RuleSelector.select(candidates, RuleSelector.FallbackPolicy.FIRST_UNCONDITIONAL, TRAITS,
                rule -> gateOpen || !RuleOrderAnalyzer.gated(rule),
                rule -> matcher.matchesPrepared(rule.getMatchSpec(), rule.getBodyCondition(), null, null,
                        prepared, null, Map.of())).selected();
    }

    private static boolean matchesPath(String pattern, String path) {
        if ("*".equals(pattern) || pattern.equals(path)) {
            return true;
        }
        return pattern.endsWith("/*") && path.startsWith(pattern.substring(0, pattern.length() - 1));
    }

    private static final RuleSelector.Traits<HttpRule> TRAITS = new RuleSelector.Traits<>() {
        @Override
        public boolean enabled(HttpRule rule) {
            return !Boolean.FALSE.equals(rule.getEnabled());
        }

        @Override
        public boolean unconditional(HttpRule rule) {
            return rule.getMatchSpec() == null && (rule.getBodyCondition() == null || rule.getBodyCondition().isBlank());
        }
    };

    @Provide
    Arbitrary<List<HttpRule>> ruleSets() {
        Arbitrary<HttpRule> rule = Combinators.combine(
                Arbitraries.of(PATHS),
                Arbitraries.of("GET", "POST", "") ,
                Arbitraries.of("", "a.test"),
                Arbitraries.integers().between(0, 2),
                Arbitraries.of("a=1", "a=2", "b=1", "", "", "a=1;b=1"),
                Arbitraries.of(false, false, false, true),
                Arbitraries.integers().between(0, 3)
        ).as((path, method, host, priority, condition, gated, minutes) -> {
            HttpRule built = HttpRule.builder().matchKey(path).method(method.isEmpty() ? null : method)
                    .targetHost(host).priority(priority).bodyCondition(condition.isEmpty() ? null : condition)
                    .enabled(true).createdAt(LocalDateTime.of(2026, 1, 1, 0, minutes)).build();
            if (gated) {
                built.setScenarioName("s");
                built.setRequiredScenarioState("Started");
            }
            return built;
        });
        return rule.list().ofMinSize(2).ofMaxSize(8).map(list -> {
            List<HttpRule> withIds = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                HttpRule r = list.get(i);
                r.setId(String.format("r%02d", i));
                r.setDescription("rule " + i);
                withIds.add(r);
            }
            return withIds;
        });
    }
}
