package com.echo.core.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 從已排序的候選規則挑出一條，取代原本分散在 HTTP／JMS pipeline 與規則服務中的四份迴圈。
 * <p>
 * 規則依序檢查：停用的略過；未通過前置檢查（例如 Scenario 狀態）的記下並略過；
 * 有條件且條件成立的立即選中；無條件的依 {@link FallbackPolicy} 記為備選。
 * 都沒有條件成立時回傳備選規則。
 */
public final class RuleSelector {

    private RuleSelector() {
    }

    /** 沒有條件成立時，如何從無條件規則中挑選備選。 */
    public enum FallbackPolicy {
        /** 第一條無條件規則（HTTP）。 */
        FIRST_UNCONDITIONAL,
        /** 優先第一條無條件的非萬用規則，其次第一條無條件的萬用規則（JMS）。 */
        EXACT_BEFORE_WILDCARD
    }

    /** 選擇時需要的規則特性。 */
    public interface Traits<R> {

        boolean enabled(R rule);

        /** 沒有任何條件（任何請求都符合）。 */
        boolean unconditional(R rule);

        /** 萬用規則（例如 JMS queue {@code *}）；只影響 {@link FallbackPolicy#EXACT_BEFORE_WILDCARD}。 */
        default boolean wildcard(R rule) {
            return false;
        }
    }

    /**
     * 選擇結果。
     *
     * @param selected         選中的規則；沒有任何規則符合時為 null
     * @param conditionMatched 選中的規則是因條件成立（而非備選）
     * @param gateRejected     依序未通過前置檢查的規則（只含選中之前檢查過的）
     */
    public record Selection<R>(R selected, boolean conditionMatched, List<R> gateRejected) {

        public Selection {
            gateRejected = List.copyOf(gateRejected);
        }
    }

    /**
     * 挑選規則。
     *
     * @param ordered          依優先順序排好的候選規則
     * @param gate             前置檢查，回傳 false 的規則不參與選擇（例如 Scenario 狀態不符）
     * @param conditionMatches 有條件的規則其條件是否成立；只會對啟用、通過前置檢查且有條件的規則呼叫
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
