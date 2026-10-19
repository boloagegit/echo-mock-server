package com.echo.core.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Select one from the sorted candidate rules to replace the four loops originally scattered in HTTP/JMS pipeline and the rule service.
 * <p>
 * Rules are checked in order: disabled ones are skipped; those that fail the pre-check (such as Scenario status) are noted and skipped;
 * If there are conditions and the conditions are met, they will be selected immediately; if there are no conditions, they will be recorded as alternatives according to {@link FallbackPolicy}.
 * Return alternative rules when none of the conditions are met.
 */
public final class RuleSelector {

    private RuleSelector() {
    }

    /** How to select alternatives from unconditional rules when no condition is true. */
    public enum FallbackPolicy {
        /** The first unconditional rule (HTTP). */
        FIRST_UNCONDITIONAL,
        /** Prefer the first unconditional exact rule, then the first unconditional wildcard rule for JMS. */
        EXACT_BEFORE_WILDCARD
    }

    /** Rule properties required when selecting. */
    public interface Traits<R> {

        boolean enabled(R rule);

        /** No conditions (any request is met). */
        boolean unconditional(R rule);

        /** Wildcard rule, such as JMS queue {@code *}; relevant only to {@link FallbackPolicy#EXACT_BEFORE_WILDCARD}. */
        default boolean wildcard(R rule) {
            return false;
        }
    }

    /**
     * Select results.
     *
     * @param selected         Selected rule; null when no rule matches
     * @param conditionMatched The selected rule is because the condition is true (not the alternative)
     * @param gateRejected     Rules that failed the pre-check in sequence (only those that have been checked before are selected)
     */
    public record Selection<R>(R selected, boolean conditionMatched, List<R> gateRejected) {

        public Selection {
            gateRejected = List.copyOf(gateRejected);
        }
    }

    /**
     * Picking rules.
     *
     * @param ordered          Candidate rules ranked by priority
     * @param gate             Pre-check, the rule returning false does not participate in the selection (for example, the Scenario status does not match)
     * @param conditionMatches Whether the condition of a conditional rule is true; it will only be called for rules that are enabled, pass pre-check, and are conditional
     */
    public static <R> Selection<R> select(List<R> ordered, FallbackPolicy policy, Traits<R> traits,
                                          Predicate<R> gate, Predicate<R> conditionMatches) {
        R firstUnconditional = null;
        R firstUnconditionalWildcard = null;
        List<R> rejected = new ArrayList<>(0);

        for (R rule : ordered) {
            if (!traits.enabled(rule)) {
                continue;
            }
            if (!gate.test(rule)) {
                rejected.add(rule);
                continue;
            }
            if (!traits.unconditional(rule)) {
                if (conditionMatches.test(rule)) {
                    return new Selection<>(rule, true, rejected);
                }
            } else if (policy == FallbackPolicy.EXACT_BEFORE_WILDCARD && traits.wildcard(rule)) {
                if (firstUnconditionalWildcard == null) {
                    firstUnconditionalWildcard = rule;
                }
            } else if (firstUnconditional == null) {
                firstUnconditional = rule;
            }
        }
        R fallback = firstUnconditional != null ? firstUnconditional : firstUnconditionalWildcard;
        return new Selection<>(fallback, false, rejected);
    }
}
