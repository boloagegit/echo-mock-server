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
 * 找出「一定輪不到」的規則，並列出一條規則的判斷順序。規則挑選見 {@code RuleSelector}：
 * 先依順序檢查有條件的規則，第一條成立的就用它；都不成立時才用第一條沒有條件的規則。
 * <p>
 * 只判斷確定的情況：較早的規則 E 適用於規則 R 的所有請求（{@link Route#covers}），E 不受情境狀態限制，而且
 * <ul>
 *   <li>R 與 E 都有條件且條件完全相同：E 一定先命中（{@link #DUPLICATE_CONDITIONS}）；</li>
 *   <li>R 與 E 都沒有條件：E 一定先被當成預設規則（{@link #EARLIER_DEFAULT}）。</li>
 * </ul>
 * 條件寫法不同但意思重疊的情況不判斷。
 * <p>
 * 規則多時的效能：每條規則的條件只轉成一次比對用的文字（{@link Route#conditionKey}），規則依路徑（或 queue）與條件分組，
 * 判斷一條規則時只看可能涵蓋它、而且條件相同（或同樣沒有條件）的那一組，並在遇到排在它之後的規則時停止。
 */
final class RuleOrderAnalyzer {

    static final String DUPLICATE_CONDITIONS = "duplicateConditions";
    static final String EARLIER_DEFAULT = "earlierDefault";

    /** 沒有條件的規則共用的分組（條件文字不會是這個值）。 */
    private static final String NO_CONDITIONS = "\u0000none";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RuleOrderAnalyzer() {
    }

    /** 協定特有的部分：排序、適用範圍與條件。 */
    interface Route<R extends BaseRule> {

        /** 與實際比對相同的候選順序（不含排序條件相同時的先後，見 {@link Index}）。 */
        Comparator<R> order();

        /** {@code broader} 是否一定是 {@code narrower} 所有請求的候選規則。 */
        boolean covers(R broader, R narrower);

        /**
         * {@code other} 是否至少是 {@code rule} 一部分請求的候選規則，而且範圍不比它窄（路徑或 queue 涵蓋它）；
         * 例如只限某個 host 或 method 的較寬路徑規則。判斷順序列出這些規則，但不用它們判定輪不到。
         */
        boolean overlaps(R other, R rule);

        boolean conditional(R rule);

        /** 條件的比對用文字：兩條規則的條件完全相同（不判斷寫法不同但意思相同）時文字相同。只對有條件的規則呼叫。 */
        String conditionKey(R rule);

        /** 規則所在的分組（HTTP 路徑、JMS queue）。 */
        String group(R rule);

        /** 可能涵蓋 {@code rule} 的規則所在的分組，包含它自己的分組。 */
        List<String> coveringGroups(R rule);
    }

    /** 規則被判定輪不到的原因與擋住它的規則。 */
    record Issue<R>(String type, R by) {
    }

    /** 一組啟用中規則的索引：實際檢查的先後、依分組與條件分好的規則。建立一次，可重複查詢。 */
    static final class Index<R extends BaseRule> {

        private final Route<R> route;
        private final Map<String, Integer> position = new HashMap<>();
        /** 分組 → 條件文字 → 規則（依檢查先後）。 */
        private final Map<String, Map<String, List<R>>> byGroupAndCondition = new HashMap<>();
        /** 分組 → 規則（依檢查先後）。 */
        private final Map<String, List<R>> byGroup = new HashMap<>();
        private final Comparator<R> ranked;

        Index(List<R> active, Route<R> route) {
            this.route = route;
            List<R> sorted = new ArrayList<>(active);
            // 與實際比對相同：穩定排序，排序條件相同時維持規則來源的順序
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

        /** {@code rule} 輪不到時回傳原因（擋住它的是最前面的那條），否則回傳 null。 */
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

        /** 判斷 {@code rule} 時會一起考慮的規則（含它自己與只適用部分請求的規則），依實際檢查順序排列。 */
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

        /** 不在索引中的規則（例如已停用）排在排序條件相同的規則之後。 */
        private int positionOf(R rule) {
            return position.getOrDefault(rule.getId(), Integer.MAX_VALUE);
        }

        private String keyOf(R rule) {
            return route.conditional(rule) ? route.conditionKey(rule) : NO_CONDITIONS;
        }
    }

    /** 需要情境狀態符合才會被檢查的規則（狀態不符時直接略過，所以不能確定它會擋住後面的規則）。 */
    static boolean gated(BaseRule rule) {
        return notBlank(rule.getScenarioName()) && notBlank(rule.getRequiredScenarioState());
    }

    /** 結構化條件的比對用文字：JSON 物件的欄位依名稱排序（欄位順序不同視為相同）；不是合法 JSON 時用原文。 */
    static String matchSpecKey(String matchSpec) {
        try {
            return "spec:" + canonical(MAPPER.readTree(matchSpec));
        } catch (Exception e) {
            return "spec-text:" + matchSpec.trim();
        }
    }

    /** 舊格式條件的比對用文字：每個欄位去除前後空白，空白視為沒有條件。 */
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
