package com.echo.core.match;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 舊版三個條件字串（bodyCondition、queryCondition、headerCondition）與 {@link MatchNode} 之間的轉換。
 * <p>
 * 轉換只發生在記憶體中；資料庫裡的字串維持不變。
 */
public final class LegacyConditions {

    /** {@link MatchExplanation#node()} 中舊版片段的前綴，例如 {@code legacy.body}。 */
    static final String LEGACY_NODE_PREFIX = "legacy.";

    private LegacyConditions() {
    }

    /**
     * 舊版三個條件字串，任一欄位沒有條件時為 null。
     *
     * @param bodyCondition   以 {@code ;} 串接的 body 條件
     * @param queryCondition  以 {@code ;} 串接的 query 條件
     * @param headerCondition 以 {@code ;} 串接的 header 條件
     */
    public record Strings(String bodyCondition, String queryCondition, String headerCondition) {
    }

    /**
     * 單一舊版條件的欄位、運算子與期望值（皆已去除前後空白）。
     *
     * @param operator {@code =}、{@code !=}、{@code *=} 或 {@code ~=}
     */
    public record Parts(String field, String operator, String value) {
    }

    /**
     * 以比對時相同的規則拆解單一條件：看<strong>第一個</strong> {@code =}，它前一個字元是 {@code ~}、{@code *}、
     * {@code !} 時分別是 {@code ~=}、{@code *=}、{@code !=}，否則是 {@code =}。例如 {@code a=x!=y} 是欄位
     * {@code a}、運算子 {@code =}、值 {@code x!=y}。前端條件列與 v1 文件檢查都依這個規則（見 {@code match-tree.js}）。
     *
     * @return 沒有 {@code =}，或 {@code =} 在第一個字元時回傳 empty（比對時整段被當成欄位）
     */
    public static Optional<Parts> parts(String condition) {
        int eqIdx = condition.indexOf('=');
        if (eqIdx <= 0) {
            return Optional.empty();
        }
        String value = condition.substring(eqIdx + 1).trim();
        char previous = condition.charAt(eqIdx - 1);
        if (previous == '~' || previous == '*' || previous == '!') {
            return Optional.of(new Parts(condition.substring(0, eqIdx - 1).trim(), previous + "=", value));
        }
        return Optional.of(new Parts(condition.substring(0, eqIdx).trim(), "=", value));
    }

    /**
     * 把舊版條件字串轉成 {@code all[legacy...]}，片段順序與舊版評估順序相同（body → query → header）。
     * 三個欄位都沒有條件時回傳空的 {@code all}，也就是無條件（fallback）規則。
     */
    public static MatchNode.All toMatchNode(String bodyCondition, String queryCondition, String headerCondition) {
        List<MatchNode> fragments = new ArrayList<>();
        addFragments(fragments, MatchNode.LegacySource.BODY, bodyCondition);
        addFragments(fragments, MatchNode.LegacySource.QUERY, queryCondition);
        addFragments(fragments, MatchNode.LegacySource.HEADER, headerCondition);
        return new MatchNode.All(fragments);
    }

    /**
     * 若條件樹可以無損表示成舊版字串（空的 {@code all}，或只含舊版片段的 {@code all}），回傳對應字串。
     * 用到 {@code any}、{@code not} 或新運算子的條件樹回傳 empty，呼叫端不可把它寫回舊欄位。
     */
    public static Optional<Strings> toStrings(MatchNode node) {
        if (!(node instanceof MatchNode.All all)) {
            return Optional.empty();
        }
        List<String> body = new ArrayList<>();
        List<String> query = new ArrayList<>();
        List<String> header = new ArrayList<>();
        for (MatchNode child : all.children()) {
            if (!(child instanceof MatchNode.Legacy legacy)) {
                return Optional.empty();
            }
            switch (legacy.source()) {
                case BODY -> body.add(legacy.condition());
                case QUERY -> query.add(legacy.condition());
                case HEADER -> header.add(legacy.condition());
            }
        }
        return Optional.of(new Strings(join(body), join(query), join(header)));
    }

    /**
     * 產生與舊版匹配鏈完全相同的條件說明，例如
     * {@code bodyCondition FAIL (expected user.type=vip, actual user.type=normal)}。
     *
     * @return 非舊版片段的說明時回傳 empty
     */
    public static Optional<String> legacyDetail(MatchExplanation explanation) {
        return sourceOf(explanation).map(source -> detail(source.fieldName(), explanation));
    }

    /** 舊版片段說明來自哪個舊欄位；不是舊版片段時回傳 empty。 */
    public static Optional<MatchNode.LegacySource> sourceOf(MatchExplanation explanation) {
        if (!explanation.node().startsWith(LEGACY_NODE_PREFIX)) {
            return Optional.empty();
        }
        return Optional.ofNullable(
                MatchNode.LegacySource.fromJsonName(explanation.node().substring(LEGACY_NODE_PREFIX.length())));
    }

    private static String detail(String type, MatchExplanation explanation) {
        return explanation.passed()
                ? type + " PASS (" + explanation.expected() + ")"
                : type + " FAIL (expected " + explanation.expected() + ", actual " + explanation.actual() + ")";
    }

    private static void addFragments(List<MatchNode> fragments, MatchNode.LegacySource source, String condition) {
        if (condition == null || condition.isBlank()) {
            return;
        }
        for (String fragment : condition.split(";")) {
            String trimmed = fragment.trim();
            if (!trimmed.isEmpty()) {
                fragments.add(new MatchNode.Legacy(source, trimmed));
            }
        }
    }

    private static String join(List<String> fragments) {
        return fragments.isEmpty() ? null : String.join(";", fragments);
    }
}
