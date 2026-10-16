package com.echo.core.match;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Arrays;
import java.util.Map;

/**
 * {@code equalToJson} 使用的 JSON 結構比較。
 * <p>
 * 數值以數學值比較（{@code 1} 等於 {@code 1.0}）；{@code ignoreExtraElements} 只放寬物件欄位，
 * 陣列長度仍需相同；不計順序比較時，陣列最多 {@value #MAX_UNORDERED_ELEMENTS} 個元素。
 */
final class JsonComparator {

    /** 不計順序比較的陣列元素上限；超過時視為不符合，避免大型 body 造成 O(n²) 以上的成本。 */
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
     * 以二分圖最大匹配（Kuhn 演算法）配對元素。寬鬆比較下「符合」不是等價關係，貪婪配對會誤判，
     * 回溯則可能指數爆炸；最大匹配在多項式時間內給出正確答案。
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
