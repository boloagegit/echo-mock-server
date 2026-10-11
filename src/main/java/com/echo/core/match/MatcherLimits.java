package com.echo.core.match;

/**
 * 匹配時的資源上限，預設值與舊版 {@code ConditionMatcher} 相同。
 *
 * @param maxBodyChars           body 超過此長度時不解析，所有 body 條件視為無法判斷
 * @param maxFieldValueLength    單一取值超過此長度時略過
 * @param maxRegexPatternLength  正則表達式長度上限（編譯時檢查）
 * @param maxRegexInputLength    正則比對的輸入長度上限
 * @param regexTimeoutMs         單次正則比對的時間上限
 */
public record MatcherLimits(int maxBodyChars, int maxFieldValueLength,
                            int maxRegexPatternLength, int maxRegexInputLength,
                            long regexTimeoutMs) {

    public static final MatcherLimits DEFAULT =
            new MatcherLimits(10 * 1024 * 1024, 2 * 1024 * 1024, 200, 10_000, 1000);

    public MatcherLimits {
        if (maxBodyChars <= 0 || maxFieldValueLength <= 0 || maxRegexPatternLength <= 0
                || maxRegexInputLength <= 0 || regexTimeoutMs <= 0) {
            throw new IllegalArgumentException("Matcher limits must be positive");
        }
    }

    public MatcherLimits withMaxFieldValueLength(int length) {
        return new MatcherLimits(maxBodyChars, length, maxRegexPatternLength,
                maxRegexInputLength, regexTimeoutMs);
    }
}
