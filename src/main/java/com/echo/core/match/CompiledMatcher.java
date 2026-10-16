package com.echo.core.match;

/**
 * 編譯後的不可變匹配樹，可在多執行緒間共用。由 {@link MatcherCompiler} 建立。
 */
public interface CompiledMatcher {

    /** 快速判斷，可提前結束；用於規則選擇的熱路徑。 */
    boolean matches(EvalContext context);

    /** 評估所有節點並回傳說明；用於匹配鏈分析與規則測試。 */
    MatchExplanation explain(EvalContext context);

    /** 規範化的條件 JSON，可作為同一請求內的結果暫存 key。 */
    String cacheKey();

    /**
     * 此條件對 body 的需求，用來決定 body 要怎麼準備（例如 XML 能否只串流掃描、不建立 DOM）。
     * 預設視為需要完整解析，自行實作的條件不會因此得到錯誤結果。
     */
    default BodyRequirements bodyRequirements() {
        return BodyRequirements.FULL;
    }

    /** 沒有任何條件（空的 {@code all}），規則選擇時視為 fallback。 */
    default boolean isUnconditional() {
        return false;
    }
}
