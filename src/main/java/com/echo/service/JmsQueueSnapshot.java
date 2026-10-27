package com.echo.service;

import com.echo.entity.JmsRule;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Immutable JMS-rule snapshot for a queue, cached in {@code jmsRules}; queue names come from configuration, so the key count is bounded.
 * <p>
 * When bucketing by endpoint field, such as ServiceName, create buckets only for values present in rule conditions.
 * All other message-derived values, which are unbounded, share the bucket for rules without an endpoint constraint, so snapshot size grows only with rule count.
 * The contents of the bucket are the same as the original {@code findBucketedJmsRules}.
 */
final class JmsQueueSnapshot {

    private final RuleCandidates<JmsRule> rules;
    private final String endpointField;
    /** Corresponding to {@link #rules}: the value of the endpoint field in the rule condition; if not mentioned, it is null. */
    private final List<String> endpointValues;
    private final Set<String> mentionedValues;
    private final RuleCandidates<JmsRule> unmentioned;
    private final Map<String, List<JmsRule>> buckets = new ConcurrentHashMap<>();

    /**
     * @param rules         Sorted rules with valid responses.
     * @param endpointField The field name used for bucketing; if blank, no bucketing will be performed.
     * @param conditions    Resolve and retain each rule's condition object; null disables retention.
     */
    JmsQueueSnapshot(List<JmsRule> rules, String endpointField, ConditionMatcher conditions) {
        this(rules, endpointField, conditions, java.util.Map.of());
    }

    JmsQueueSnapshot(
            List<JmsRule> rules,
            String endpointField,
            ConditionMatcher conditions,
            java.util.Map<Long, ResponseRevision> revisions) {
        List<JmsRule> snapshotRules = List.copyOf(rules);
        this.endpointField = endpointField;
        List<String> values = new ArrayList<>(snapshotRules.size());
        Set<String> mentioned = new HashSet<>();
        List<RuleCandidates.Entry<JmsRule>> withoutEndpoint = new ArrayList<>();
        List<RuleCandidates.Entry<JmsRule>> pinned = new ArrayList<>(snapshotRules.size());
        for (JmsRule rule : snapshotRules) {
            String value = bucketingEnabled() ? endpointValueOf(rule.getBodyCondition(), endpointField) : null;
            values.add(value);
            if (value != null) mentioned.add(value);
            ConditionMatcher.RuleConditions ruleConditions =
                    conditions == null
                            ? null
                            : conditions.conditions(
                                    rule.getMatchSpec(), rule.getBodyCondition(), null, null);
            RuleCandidates.Entry<JmsRule> entry =
                    new RuleCandidates.Entry<>(
                            rule,
                            ruleConditions,
                            rule.getResponseId() == null
                                    ? null
                                    : revisions.get(rule.getResponseId()));
            pinned.add(entry);
            if (value == null) withoutEndpoint.add(entry);
        }
        this.endpointValues = values;
        this.mentionedValues = Set.copyOf(mentioned);
        this.unmentioned = RuleCandidates.ofEntries(withoutEndpoint);
        this.rules = RuleCandidates.ofEntries(pinned);
    }

    List<JmsRule> rules() {
        return rules;
    }

    /** Same as the original {@code findBucketedJmsRules}: the endpoint field is not mentioned, or the rules with the same value are mentioned, and the order remains unchanged. */
    List<JmsRule> bucket(String endpointValue) {
        if (endpointValue == null || endpointValue.isBlank() || !bucketingEnabled()) {
            return rules;
        }
        if (!mentionedValues.contains(endpointValue)) {
            return unmentioned;
        }
        return buckets.computeIfAbsent(endpointValue, this::computeBucket);
    }

    /** The number of created buckets (for testing to confirm that it will not grow with any endpoint value). */
    int bucketCount() {
        return buckets.size();
    }

    private boolean bucketingEnabled() {
        return endpointField != null && !endpointField.isBlank();
    }

    private List<JmsRule> computeBucket(String endpointValue) {
        List<RuleCandidates.Entry<JmsRule>> bucket = new ArrayList<>();
        for (int i = 0; i < rules.size(); i++) {
            String value = endpointValues.get(i);
            if (value == null || value.equals(endpointValue)) {
                bucket.add(rules.entry(i));
            }
        }
        return RuleCandidates.ofEntries(bucket);
    }

    /**
     * The value of the fragment that first mentions the endpoint field (case-insensitive) in the condition; returns null if it is not mentioned.
     * The parsing method is the same as the original {@code bodyConditionMatchesEndpoint}.
     */
    static String endpointValueOf(String bodyCondition, String endpointField) {
        if (bodyCondition == null || bodyCondition.isBlank()) {
            return null;
        }
        for (String part : bodyCondition.split(";")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String normalized = trimmed.startsWith("//") ? trimmed.substring(2) : trimmed;
            int eqIdx = normalized.indexOf('=');
            if (eqIdx <= 0) {
                continue;
            }
            char prev = normalized.charAt(eqIdx - 1);
            String field = prev == '!' || prev == '*' || prev == '~'
                    ? normalized.substring(0, eqIdx - 1).trim()
                    : normalized.substring(0, eqIdx).trim();
            if (!field.equalsIgnoreCase(endpointField)) {
                continue;
            }
            String value = normalized.substring(eqIdx + 1).trim();
            // An unmatched double quote used to throw and disable bucketing; treat it as a literal to match the new engine.
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            return value;
        }
        return null;
    }
}
