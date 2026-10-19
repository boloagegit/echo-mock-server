package com.echo.core.match;

/**
 * The resource limit during matching, the default value is the same as the old version {@code ConditionMatcher}.
 *
 * @param maxBodyChars           body will not be parsed if it exceeds this length, and all body conditions will be deemed undeterminable.
 * @param maxFieldValueLength    Skip if a single value exceeds this length
 * @param maxRegexPatternLength  Upper limit on regular expression length (compile-time check)
 * @param maxRegexInputLength    Upper limit of input length for regular comparison
 * @param regexTimeoutMs         The upper limit of time for a single canonical comparison
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
