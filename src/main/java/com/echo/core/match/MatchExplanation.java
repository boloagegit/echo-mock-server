package com.echo.core.match;

import java.util.List;

/**
 * 匹配結果的樹狀說明，供匹配鏈分析與「測試規則」顯示。
 *
 * @param node     {@code all}、{@code any}、{@code not} 或 {@code leaf}
 * @param passed   此節點是否符合
 * @param expected 葉節點的條件描述；組合節點為 null
 * @param actual   葉節點實際取到的值摘要；組合節點為 null
 * @param children 子節點說明；葉節點為空
 */
public record MatchExplanation(String node, boolean passed, String expected, String actual,
                               List<MatchExplanation> children) {

    public MatchExplanation {
        children = List.copyOf(children);
    }

    /** 直接子節點中符合的數量；葉節點視為一個條件。 */
    public int passedCount() {
        if (children.isEmpty()) {
            return "leaf".equals(node) && passed ? 1 : 0;
        }
        return (int) children.stream().filter(MatchExplanation::passed).count();
    }

    /** 直接子節點數量；葉節點視為一個條件。 */
    public int totalCount() {
        if (children.isEmpty()) {
            return "leaf".equals(node) ? 1 : 0;
        }
        return children.size();
    }

    /** 與舊版匹配鏈相同的 {@code passed/total} 格式。 */
    public String score() {
        return passedCount() + "/" + totalCount();
    }
}
