package com.echo.core.match;

import java.util.List;

/**
 * Tree description of matching results for matching chain analysis and "test rule" display.
 *
 * @param node     {@code all}, {@code any}, {@code not}, {@code leaf}, or older fragments
 *                 {@code legacy.body}／{@code legacy.query}／{@code legacy.header}
 * @param passed   Does this node comply with
 * @param expected Condition description of leaf node; combined node is null
 * @param actual   Summary of the actual value obtained by the leaf node; the combined node is null
 * @param children Child node description; leaf node is empty
 * @param leaf     The leaf node of the structured condition (for the screen to display in the original field); other nodes are null
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

    /** The number of matches in direct child nodes; leaf nodes are treated as one condition. */
    public int passedCount() {
        if (children.isEmpty()) {
            return isCondition() && passed ? 1 : 0;
        }
        return (int) children.stream().filter(MatchExplanation::passed).count();
    }

    /** Number of direct child nodes; leaf nodes are treated as a condition. */
    public int totalCount() {
        if (children.isEmpty()) {
            return isCondition() ? 1 : 0;
        }
        return children.size();
    }

    /** Whether it is a single condition (leaf node or legacy fragment), not a combined node. */
    public boolean isCondition() {
        return !"all".equals(node) && !"any".equals(node) && !"not".equals(node);
    }

    /** Same {@code passed/total} format as legacy match chain. */
    public String score() {
        return passedCount() + "/" + totalCount();
    }
}
