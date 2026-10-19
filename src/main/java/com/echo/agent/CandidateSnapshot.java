package com.echo.agent;

import com.echo.entity.BaseRule;
import com.echo.entity.HttpRule;
import com.echo.entity.JmsRule;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.util.List;
import java.util.Objects;

/**
 * Immutable snapshot of candidate rules.
 * <p>
 * Create the snapshot on the Pipeline thread, then pass it to the LogAgent background thread.
 * Avoid accessing JPA-managed entities across threads.
 */
@Getter
@Builder
@EqualsAndHashCode
public class CandidateSnapshot {

    private final String ruleId;
    private final String endpoint;
    private final String description;
    private final boolean enabled;
    private final String bodyCondition;
    private final String queryCondition;
    private final String headerCondition;
    /** Structured condition JSON; takes precedence when present. */
    private final String matchSpec;
    private final int priority;

    /**
     * Create a deep-copy snapshot from an HttpRule.
     */
    public static CandidateSnapshot fromHttpRule(HttpRule rule) {
        return CandidateSnapshot.builder()
                .ruleId(rule.getId())
                .endpoint(rule.getMatchKey())
                .description(rule.getDescription())
                .enabled(rule.getEnabled())
                .bodyCondition(rule.getBodyCondition())
                .queryCondition(rule.getQueryCondition())
                .headerCondition(rule.getHeaderCondition())
                .matchSpec(rule.getMatchSpec())
                .priority(rule.getPriority())
                .build();
    }

    /**
     * Create a deep-copy snapshot from a JmsRule.
     * <p>
     * JMS rules have no queryCondition or headerCondition; both are set to null.
     */
    public static CandidateSnapshot fromJmsRule(JmsRule rule) {
        return CandidateSnapshot.builder()
                .ruleId(rule.getId())
                .endpoint(rule.getQueueName())
                .description(rule.getDescription())
                .enabled(rule.getEnabled())
                .bodyCondition(rule.getBodyCondition())
                .queryCondition(null)
                .headerCondition(null)
                .matchSpec(rule.getMatchSpec())
                .priority(rule.getPriority())
                .build();
    }

    /**
     * Deep-copy BaseRule candidates into an immutable list of CandidateSnapshot objects.
     * <p>
     * Call the corresponding factory method according to the actual type of the rule (HttpRule or JmsRule).
     * Unsupported rule types will be filtered out.
     *
     * @param candidates Candidate rule list
     * @param <T> Subtype of BaseRule
     * @return Immutable CandidateSnapshot list
     */
    public static <T extends BaseRule> List<CandidateSnapshot> toCandidateSnapshots(List<T> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        return candidates.stream()
                .map(rule -> {
                    if (rule instanceof HttpRule httpRule) {
                        return CandidateSnapshot.fromHttpRule(httpRule);
                    }
                    if (rule instanceof JmsRule jmsRule) {
                        return CandidateSnapshot.fromJmsRule(jmsRule);
                    }
                    return null;
                })
                .filter(Objects::nonNull)
                .toList();
    }
}
