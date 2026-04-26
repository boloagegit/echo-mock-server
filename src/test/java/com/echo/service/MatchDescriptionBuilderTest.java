package com.echo.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MatchDescriptionBuilder 單元測試。
 *
 * <p>驗證 toMatchChainJson、escapeJson。
 */
class MatchDescriptionBuilderTest {

    @Nested
    @DisplayName("toMatchChainJson 精簡匹配鏈")
    class MatchChainJsonTests {

        @Test
        @DisplayName("匹配成功 → 只保留 match/fallback 條目")
        void matchedOnlyKeepsMatchedEntries() {
            List<MatchChainEntry> chain = List.of(
                    new MatchChainEntry("r1", "condition_not_match", "/api", "規則A", "body: x=1"),
                    new MatchChainEntry("r2", "match", "/api", "規則B", "body: x=2"),
                    new MatchChainEntry("r3", "condition_not_match", "/api", "規則C", null));

            String json = MatchDescriptionBuilder.toMatchChainJson(chain, true);

            assertThat(json).isNotNull();
            assertThat(json).contains("\"ruleId\":\"r2\"");
            assertThat(json).contains("\"reason\":\"match\"");
            assertThat(json).doesNotContain("\"ruleId\":\"r1\"");
            assertThat(json).doesNotContain("\"ruleId\":\"r3\"");
        }

        @Test
        @DisplayName("未匹配 → 回傳 null")
        void unmatchedReturnsNull() {
            List<MatchChainEntry> chain = List.of(
                    new MatchChainEntry("r1", "condition_not_match", "/api", "規則A", null),
                    new MatchChainEntry("r2", "condition_not_match", "/api", "規則B", null));

            String json = MatchDescriptionBuilder.toMatchChainJson(chain, false);

            assertThat(json).isNull();
        }

        @Test
        @DisplayName("fallback 匹配 → 保留 fallback 條目")
        void fallbackKeptWhenMatched() {
            List<MatchChainEntry> chain = List.of(
                    new MatchChainEntry("r1", "condition_not_match", "/api", "規則A", null),
                    new MatchChainEntry("r2", "fallback", "/api", "預設規則", null));

            String json = MatchDescriptionBuilder.toMatchChainJson(chain, true);

            assertThat(json).isNotNull();
            assertThat(json).contains("\"ruleId\":\"r2\"");
            assertThat(json).contains("\"reason\":\"fallback\"");
            assertThat(json).doesNotContain("\"ruleId\":\"r1\"");
        }

        @Test
        @DisplayName("空 chain → 回傳 null")
        void emptyChainReturnsNull() {
            assertThat(MatchDescriptionBuilder.toMatchChainJson(List.of(), true)).isNull();
            assertThat(MatchDescriptionBuilder.toMatchChainJson(List.of(), false)).isNull();
            assertThat(MatchDescriptionBuilder.toMatchChainJson(null, true)).isNull();
        }
    }
}
