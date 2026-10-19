package com.echo.core.match;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Arrays;
import java.util.Map;

/**
 * Comparison of the JSON structure used by {@code equalToJson}.
 * <p>
 * Values are compared mathematically ({@code 1} is equal to {@code 1.0}); {@code ignoreExtraElements} only widens the object field,
 * The array lengths still need to be the same; the array can have up to {@value #MAX_UNORDERED_ELEMENTS} elements, not counting sequential comparisons.
 */
final class JsonComparator {

    /** The upper limit of array elements is not included in the sequential comparison; when it is exceeded, it is considered as non-compliance to avoid the cost of O(n²) caused by large body. */
    static final int MAX_UNORDERED_ELEMENTS = 256;

    private JsonComparator() {
    }

    static boolean matches(JsonNode expected, JsonNode actual,
                           boolean ignoreArrayOrder, boolean ignoreExtraElements) {
        if (expected.isNumber() && actual.isNumber()) {
            return expected.decimalValue().compareTo(actual.decimalValue()) == 0;
        }
        if (expected.isObject() && actual.isObject()) {
            if (!ignoreExtraElements && expected.size() != actual.size()) {
                return false;
            }
            for (Map.Entry<String, JsonNode> field : expected.properties()) {
                JsonNode actualValue = actual.get(field.getKey());
                if (actualValue == null
                        || !matches(field.getValue(), actualValue, ignoreArrayOrder, ignoreExtraElements)) {
                    return false;
                }
            }
            return true;
        }
        if (expected.isArray() && actual.isArray()) {
            if (expected.size() != actual.size()) {
                return false;
            }
            return ignoreArrayOrder
                    ? matchesUnordered(expected, actual, ignoreExtraElements)
                    : matchesOrdered(expected, actual, ignoreArrayOrder, ignoreExtraElements);
        }
        return expected.equals(actual);
    }

    private static boolean matchesOrdered(JsonNode expected, JsonNode actual,
                                          boolean ignoreArrayOrder, boolean ignoreExtraElements) {
        for (int i = 0; i < expected.size(); i++) {
            if (!matches(expected.get(i), actual.get(i), ignoreArrayOrder, ignoreExtraElements)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Pair elements using bipartite graph maximum matching (Kuhn algorithm). "Consistent" under loose comparison is not an equivalence relationship, and greedy matching will lead to misjudgment.
     * Backtracking may explode exponentially; maximum matching gives the correct answer in polynomial time.
     */
    private static boolean matchesUnordered(JsonNode expected, JsonNode actual, boolean ignoreExtraElements) {
        int size = expected.size();
        if (size > MAX_UNORDERED_ELEMENTS) {
            return false;
        }
        boolean[][] compatible = new boolean[size][size];
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                compatible[i][j] = matches(expected.get(i), actual.get(j), true, ignoreExtraElements);
            }
        }
        int[] owner = new int[size];
        Arrays.fill(owner, -1);
        for (int i = 0; i < size; i++) {
            if (!augment(i, compatible, owner, new boolean[size])) {
                return false;
            }
        }
        return true;
    }

    private static boolean augment(int expectedIndex, boolean[][] compatible, int[] owner, boolean[] visited) {
        for (int j = 0; j < owner.length; j++) {
            if (compatible[expectedIndex][j] && !visited[j]) {
                visited[j] = true;
                if (owner[j] < 0 || augment(owner[j], compatible, owner, visited)) {
                    owner[j] = expectedIndex;
                    return true;
                }
            }
        }
        return false;
    }
}
