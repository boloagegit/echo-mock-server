package com.echo.core.match;

import java.util.List;

/**
 * 匹配結果的樹狀說明，供匹配鏈分析與「測試規則」顯示。
 *
 * @param node     {@code all}、{@code any}、{@code not}、{@code leaf}，或舊版片段的
 *                 {@code legacy.body}／{@code legacy.query}／{@code legacy.header}
 * @param passed   此節點是否符合
 * @param expected 葉節點的條件描述；組合節點為 null
 * @param actual   葉節點實際取到的值摘要；組合節點為 null
 * @param children 子節點說明；葉節點為空
 * @param leaf     結構化條件的葉節點（供畫面以原本的欄位顯示）；其他節點為 null
 */
public record MatchExplanation(String node, boolean passed, String expected, String actual,
                               List<MatchExplanation> children, MatchNode.Leaf leaf) {

    public MatchExplanation {
        children = List.copyOf(children);
    }

    public MatchExplanation(String node, boolean passed, String expected, String actual,
                            List<MatchExplanation> children) {
        this(node, passed, expected, actual, children, null);
    }

    /** 直接子節點中符合的數量；葉節點視為一個條件。 */
    public int passedCount() {
        if (children.isEmpty()) {
            return isCondition() && passed ? 1 : 0;
        }
        return (int) children.stream().filter(MatchExplanation::passed).count();
    }

    /** 直接子節點數量；葉節點視為一個條件。 */
    public int totalCount() {
        if (children.isEmpty()) {
            return isCondition() ? 1 : 0;
        }
        return children.size();
    }

    /** 是否為單一條件（葉節點或舊版片段），而非組合節點。 */
    public boolean isCondition() {
        return !"all".equals(node) && !"any".equals(node) && !"not".equals(node);
    }

    /** 與舊版匹配鏈相同的 {@code passed/total} 格式。 */
    public String score() {
        return passedCount() + "/" + totalCount();
    }
}
