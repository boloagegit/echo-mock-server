package com.echo.core.match;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.Set;

/**
 * 運算子的擴充點。內建運算子見 {@link BuiltinPredicates}。
 */
public interface PredicateFactory {

    /** 條件 JSON 中 {@code op} 欄位的值。 */
    String op();

    /** 支援的選項名稱；條件使用其他選項時編譯失敗。 */
    default Set<String> supportedOptions() {
        return Set.of();
    }

    /** 是否需要 {@code value}；為 false 時條件不得帶 value。 */
    default boolean requiresValue() {
        return true;
    }

    /** {@code onMissing=auto} 且取不到值時的結果。 */
    default boolean passesWhenMissing() {
        return false;
    }

    /** 是否允許條件自訂 {@code onMissing}；本身就在判斷有無值的運算子應回傳 false。 */
    default boolean allowsOnMissing() {
        return true;
    }

    /**
     * 編譯運算子。
     *
     * @param expected 期望值；{@link #requiresValue()} 為 false 時為 null
     * @param options  已過濾為 {@link #supportedOptions()} 內的選項
     * @throws IllegalArgumentException 期望值或選項不合法
     */
    CompiledPredicate compile(JsonNode expected, Map<String, JsonNode> options, MatcherLimits limits);
}
