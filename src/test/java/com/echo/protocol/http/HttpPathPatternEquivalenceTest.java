package com.echo.protocol.http;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HttpPathPatternEquivalenceTest {
    @Test
    void matchingPreservesTheOriginalContractAcrossBoundariesAndUnicode() {
        String[] values = {null, "", "*", "/", "/*", "/a", "/a/", "/a/*", "/a/b", "/a/b/*",
                "/ab", "/ab/*", "/a*", "/a/**", "**", "a/*", "a", "/A/*", "/a//", "/a/?x=1",
                "/銀行/*", "/銀行/交易", "/😀/*", "/😀/x", " /a/*", "/a/* ", "/a/\u0000", "/a/😀", "/a/銀行"};
        for (String pattern : values) {
            for (String path : values) {
                assertThat(HttpPathPattern.matches(pattern, path)).as("pattern=%s path=%s", pattern, path)
                        .isEqualTo(originalMatches(pattern, path));
            }
        }
    }

    private static boolean originalMatches(String pattern, String path) {
        if ("*".equals(pattern)) return true;
        if (pattern == null || path == null) return false;
        if (pattern.equals(path)) return true;
        return pattern.endsWith("/*") && path.startsWith(pattern.substring(0, pattern.length() - 1));
    }
}
