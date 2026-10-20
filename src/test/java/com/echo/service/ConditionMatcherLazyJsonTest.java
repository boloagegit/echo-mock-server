package com.echo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ConditionMatcherLazyJsonTest {
    private static final String RAW = """
            {"source":"body","selector":{"raw":true},"op":"contains","value":"token"}""";
    private final ObjectMapper parser = spy(new ObjectMapper());
    private final ConditionMatcher matcher = matcherWithParser();

    private ConditionMatcher matcherWithParser() {
        ConditionMatcher result = new ConditionMatcher();
        ReflectionTestUtils.setField(result, "objectMapper", parser);
        return result;
    }

    @Test
    void rawMatchingDoesNotParseEvenWhenThePayloadLooksLikeJson() throws Exception {
        for (String body : List.of("{\"token\":1}", "{broken token", "[\"token\"]")) {
            var prepared = matcher.prepareBody(body, List.of(matcher.conditions(RAW, null, null, null)));
            assertThat(matcher.matchesPrepared(RAW, null, null, null, prepared, null, Map.of())).isTrue();
            verify(parser, never()).readTree(body);
        }
    }

    @Test
    void jsonTreeIsSharedAcrossFieldsPathsAndDetailedExplanations() throws Exception {
        String body = "{\"token\":1,\"name\":\"銀行😀\"}";
        var prepared = matcher.prepareBody(body);
        verify(parser, never()).readTree(body);
        assertThat(matcher.matchesPrepared("token=1;$.name=銀行😀", null, null, prepared, null, Map.of())).isTrue();
        assertThat(matcher.matchesPreparedWithDetail("token=1", null, null, prepared, null, Map.of())
                .isOverallMatch()).isTrue();
        assertThat(prepared.getJsonNode()).isSameAs(prepared.getJsonNode());
        verify(parser, times(1)).readTree(body);
    }

    @Test
    void invalidJsonIsAttemptedOnlyOnceAndLegacyPlainTextFallbackStillWorks() throws Exception {
        String body = "{broken token";
        var prepared = matcher.prepareBody(body);
        assertThat(prepared.getJsonNode()).isNull();
        assertThat(prepared.getJsonNode()).isNull();
        assertThat(matcher.matchesPrepared("token", null, null, prepared, null, Map.of())).isTrue();
        verify(parser, times(1)).readTree(body);
    }

    @Test
    void nonJsonAndBodySizeContractsRemainUnchanged() throws Exception {
        String plain = " token ";
        assertThat(matcher.prepareBody(plain).getRaw()).isEqualTo("token");
        assertThat(matcher.prepareBody(plain).getJsonNode()).isNull();
        verify(parser, never()).readTree("token");
        assertThat(matcher.prepareBody("{" + "a".repeat(10 * 1024 * 1024)).getRaw()).isNull();
    }
}
