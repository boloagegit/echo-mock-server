package com.echo.service;

import com.echo.entity.BaseRule;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
 */
final class RuleOrderAnalyzer {

    static final String DUPLICATE_CONDITIONS = "duplicateConditions";
    static final String EARLIER_DEFAULT = "earlierDefault";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RuleOrderAnalyzer() {
    }

    /** 協定特有的部分：排序、適用範圍與條件。 */
    interface Route<R extends BaseRule> {

        /** 與實際比對相同的候選順序。 */
        Comparator<R> order();

        /** {@code broader} 是否一定是 {@code narrower} 所有請求的候選規則。 */
        boolean covers(R broader, R narrower);

        /**
         * {@code other} 是否至少是 {@code rule} 一部分請求的候選規則，而且範圍不比它窄（路徑或 queue 涵蓋它）；
         * 例如只限某個 host 或 method 的較寬路徑規則。判斷順序列出這些規則，但不用它們判定輪不到。
         */
        boolean overlaps(R other, R rule);

        boolean conditional(R rule);

        /** 兩條規則的條件文字是否完全相同（不判斷寫法不同但意思相同）。 */
        boolean sameConditions(R a, R b);

        /** 可能適用於 {@code rule} 請求的規則（用來縮小比較範圍，必須包含所有 covers 的規則）。 */
        Collection<R> candidatesFor(R rule);
    }

    /** 規則被判定輪不到的原因與擋住它的規則。 */
    record Issue<R>(String type, R by) {
    }

    /** {@code rule} 輪不到時回傳原因（擋住它的是最前面的那條），否則回傳 null。 */
    static <R extends BaseRule> Issue<R> issueOf(R rule, Route<R> route) {
        boolean conditional = route.conditional(rule);
        Issue<R> found = null;
        for (R earlier : route.candidatesFor(rule)) {
            if (Objects.equals(earlier.getId(), rule.getId()) || gated(earlier)
                    || route.order().compare(earlier, rule) >= 0 || !route.covers(earlier, rule)) {
                continue;
            }
            String type = null;
            if (conditional && route.conditional(earlier) && route.sameConditions(earlier, rule)) {
                type = DUPLICATE_CONDITIONS;
            } else if (!conditional && !route.conditional(earlier)) {
                type = EARLIER_DEFAULT;
            }
            if (type != null && (found == null || route.order().compare(earlier, found.by()) < 0)) {
                found = new Issue<>(type, earlier);
            }
        }
        return found;
    }

    /** 判斷 {@code rule} 時會一起考慮的規則（含它自己與只適用部分請求的規則），依實際檢查順序排列。 */
    static <R extends BaseRule> List<R> orderFor(R rule, Collection<R> active, Route<R> route) {
        List<R> ordered = new ArrayList<>();
        ordered.add(rule);
        for (R other : route.candidatesFor(rule)) {
            if (!Objects.equals(other.getId(), rule.getId()) && route.overlaps(other, rule)) {
                ordered.add(other);
            }
        }
        ordered.sort(route.order());
        return ordered;
    }

    /** 需要情境狀態符合才會被檢查的規則（狀態不符時直接略過，所以不能確定它會擋住後面的規則）。 */
    static boolean gated(BaseRule rule) {
        return notBlank(rule.getScenarioName()) && notBlank(rule.getRequiredScenarioState());
    }

    /** 結構化條件以 JSON 內容比較（欄位順序不同視為相同），舊格式條件以去除前後空白的文字比較。 */
    static boolean sameMatchSpec(String a, String b) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        try {
            JsonNode left = MAPPER.readTree(a);
            JsonNode right = MAPPER.readTree(b);
            return left.equals(right);
        } catch (Exception e) {
            return a.trim().equals(b.trim());
        }
    }

    static boolean sameText(String a, String b) {
        return Objects.equals(normalize(a), normalize(b));
    }

    /**
     * 實際比對以穩定排序排列候選規則，排序條件相同時維持規則來源的順序；這裡以同樣方式決定先後，
     * 不在 {@code active} 中的規則（例如已停用）排在相同條件的規則之後。
     */
    static <R extends BaseRule> Comparator<R> ranked(List<R> active, Comparator<R> order) {
        Map<String, Integer> rank = new HashMap<>(active.size() * 2);
        for (int i = 0; i < active.size(); i++) {
            rank.putIfAbsent(active.get(i).getId(), i);
        }
        return order.thenComparingInt(rule -> rank.getOrDefault(rule.getId(), Integer.MAX_VALUE));
    }

    static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
