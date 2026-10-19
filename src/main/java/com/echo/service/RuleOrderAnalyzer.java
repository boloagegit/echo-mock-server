package com.echo.service;

import com.echo.entity.BaseRule;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Find definitely unreachable rules and list a rule's evaluation order. {@code RuleSelector} selects rules as follows:
 * Evaluate conditional rules in order and use the first that matches; only if none match, use the first unconditional rule.
 * <p>
 * Report only certain cases: an earlier rule E covers every request of rule R ({@link Route#covers}), E has no scenario-state restriction, and
 * <ul>
 *   <li>R and E both have identical conditions, so E always matches first ({@link #DUPLICATE_CONDITIONS}); or</li>
 *   <li>R and E are both unconditional, so E is always the first default ({@link #EARLIER_DEFAULT}).</li>
 * </ul>
 * Do not infer semantic overlap between differently written conditions.
 * <p>
 * For large rule sets, compute each rule's {@link Route#conditionKey} once, then group rules by path or queue and condition key.
 * Check only groups that may cover the rule and have identical conditions, or are both unconditional; stop on reaching a later rule.
 */
final class RuleOrderAnalyzer {

    static final String DUPLICATE_CONDITIONS = "duplicateConditions";
    static final String EARLIER_DEFAULT = "earlierDefault";

    /** Shared group key for unconditional rules; condition text cannot equal this value. */
    private static final String NO_CONDITIONS = "\u0000none";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RuleOrderAnalyzer() {
    }

    /** Protocol-specific ordering, coverage, and conditions. */
    interface Route<R extends BaseRule> {

        /** Candidate order matching actual evaluation, except tie-breaking handled by {@link Index}. */
        Comparator<R> order();

        /** Whether {@code broader} is a candidate for every request covered by {@code narrower}. */
        boolean covers(R broader, R narrower);

        /**
         * Whether {@code other} is a candidate for some requests covered by {@code rule} and its path or queue covers the rule's scope;
         * for example, a broader path limited to one host or method. Show these rules in evaluation order, but do not use them to prove unreachability.
         */
        boolean overlaps(R other, R rule);

        boolean conditional(R rule);

        /** Comparison key equal only for identically written conditions, without inferring semantic equivalence; called only for conditional rules. */
        String conditionKey(R rule);

        /** The group the rule belongs to (HTTP path, JMS queue). */
        String group(R rule);

        /** Groups containing rules that may cover {@code rule}, including its own group. */
        List<String> coveringGroups(R rule);
    }

    /** Reason a rule is unreachable, including the earlier rule that shadows it. */
    record Issue<R>(String type, R by) {
    }

    /** Reusable index of enabled rules, retaining evaluation order and grouping by route and condition. */
    static final class Index<R extends BaseRule> {

        private final Route<R> route;
        private final Map<String, Integer> position = new HashMap<>();
        /** Group → Conditional text → Rules (in order of checking). */
        private final Map<String, Map<String, List<R>>> byGroupAndCondition = new HashMap<>();
        /** Group → Rules (in order of inspection). */
        private final Map<String, List<R>> byGroup = new HashMap<>();
        private final Comparator<R> ranked;

        Index(List<R> active, Route<R> route) {
            this.route = route;
            List<R> sorted = new ArrayList<>(active);
            // Same as the actual comparison: stable sorting, maintaining the order of the rule source when the sorting conditions are the same
            sorted.sort(route.order());
            for (int i = 0; i < sorted.size(); i++) {
                R rule = sorted.get(i);
                position.putIfAbsent(rule.getId(), i);
                String group = route.group(rule);
                byGroup.computeIfAbsent(group, key -> new ArrayList<>()).add(rule);
                byGroupAndCondition.computeIfAbsent(group, key -> new HashMap<>())
                        .computeIfAbsent(keyOf(rule), key -> new ArrayList<>()).add(rule);
            }
            this.ranked = route.order().thenComparingInt(this::positionOf);
        }

        /** Return why {@code rule} is unreachable, identifying the earliest shadowing rule; return null otherwise. */
        Issue<R> issueOf(R rule) {
            int rulePosition = positionOf(rule);
            String key = keyOf(rule);
            R earliest = null;
            for (String group : route.coveringGroups(rule)) {
                List<R> sameConditions = byGroupAndCondition.getOrDefault(group, Map.of()).get(key);
                if (sameConditions == null) {
                    continue;
                }
                for (R earlier : sameConditions) {
                    int earlierPosition = positionOf(earlier);
                    if (earlierPosition >= rulePosition) {
                        break;
                    }
                    if (!gated(earlier) && route.covers(earlier, rule)) {
                        if (earliest == null || earlierPosition < positionOf(earliest)) {
                            earliest = earlier;
                        }
                        break;
                    }
                }
            }
            if (earliest == null) {
                return null;
            }
            return new Issue<>(route.conditional(rule) ? DUPLICATE_CONDITIONS : EARLIER_DEFAULT, earliest);
        }

        /** Rules considered alongside {@code rule}, including itself and partially overlapping rules, in actual evaluation order. */
        List<R> orderFor(R rule) {
            List<R> ordered = new ArrayList<>();
            ordered.add(rule);
            for (String group : route.coveringGroups(rule)) {
                for (R other : byGroup.getOrDefault(group, List.of())) {
                    if (!Objects.equals(other.getId(), rule.getId()) && route.overlaps(other, rule)) {
                        ordered.add(other);
                    }
                }
            }
            ordered.sort(ranked);
            return ordered;
        }

        /** Rules that are not in the index (for example, are disabled) are ranked after rules with the same sort criteria. */
        private int positionOf(R rule) {
            return position.getOrDefault(rule.getId(), Integer.MAX_VALUE);
        }

        private String keyOf(R rule) {
            return route.conditional(rule) ? route.conditionKey(rule) : NO_CONDITIONS;
        }
    }

    /** Scenario-dependent rules are skipped when their required state differs, so they cannot be assumed to shadow later rules. */
    static boolean gated(BaseRule rule) {
        return notBlank(rule.getScenarioName()) && notBlank(rule.getRequiredScenarioState());
    }

    /** Structured-condition comparison key: sort JSON object fields by name so field order does not matter; use the original text for invalid JSON. */
    static String matchSpecKey(String matchSpec) {
        try {
            return "spec:" + canonical(MAPPER.readTree(matchSpec));
        } catch (Exception e) {
            return "spec-text:" + matchSpec.trim();
        }
    }

    /** Legacy-condition comparison key: trim each field and treat blank fields as absent conditions. */
    static String legacyKey(String... conditions) {
        StringBuilder key = new StringBuilder("legacy");
        for (String condition : conditions) {
            key.append('\u0001').append(condition == null || condition.isBlank() ? "" : condition.trim());
        }
        return key.toString();
    }

    private static String canonical(JsonNode node) {
        if (node.isObject()) {
            Map<String, String> fields = new TreeMap<>();
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                fields.put(field.getKey(), canonical(field.getValue()));
            }
            StringBuilder text = new StringBuilder("{");
            fields.forEach((name, value) -> text.append(MAPPER.getNodeFactory().textNode(name)).append(':')
                    .append(value).append(','));
            return text.append('}').toString();
        }
        if (node.isArray()) {
            StringBuilder text = new StringBuilder("[");
            node.forEach(item -> text.append(canonical(item)).append(','));
            return text.append(']').toString();
        }
        return node.toString();
    }

    static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
