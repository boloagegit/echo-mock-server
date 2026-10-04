package com.echo.service;

import org.junit.jupiter.api.Test;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class SseRuleSettingsTest {
    @Test
    void timeoutIncludesInitialAndCumulativeEventDelaysWithFiniteMargin() {
        var events = SseEventSequence.parse("[{\"data\":\"a\",\"delayMs\":20000},{\"data\":\"b\",\"delayMs\":20000}]");
        assertThat(SseRuleSettings.timeout(events, 5000, false)).isEqualTo(75_000);
        assertThat(SseRuleSettings.timeout(events, 5000, true)).isEqualTo(86_400_000);
        assertThatThrownBy(() -> SseRuleSettings.timeout(events, Long.MAX_VALUE, false)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void timeoutStopsCountingAfterTerminalEventAndRejectsExcessiveSequence() {
        var events = SseEventSequence.parse("[{\"data\":\"a\",\"delayMs\":100,\"type\":\"abort\"},{\"data\":\"b\",\"delayMs\":30000}]");
        assertThat(SseRuleSettings.timeout(events, 0, false)).isEqualTo(30_100);
        var excessive = java.util.Collections.nCopies(2880,
                new SseEventSequence.Event(null, "synthetic", null, 30_000L, null));
        assertThatThrownBy(() -> SseRuleSettings.timeout(excessive, 0, false)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void strictContractRejectsUnrepresentableOrMalformedEvents() {
        for (String body : List.of("null", "[null]", "[1]", "[]", "[{\"data\":{}}]", "[{\"data\":1}]",
                "[{\"data\":true}]", "[{\"data\":\"x\",\"delayMs\":1.5}]", "[{\"data\":\"x\",\"delayMs\":\"1\"}]",
                "[{\"data\":\"x\",\"id\":2}]", "[{\"data\":\"x\",\"id\":\"bad\\u0000id\"}]",
                "[{\"data\":\"x\",\"event\":\"bad\\nevent\"}]", "[{\"data\":\"x\",\"comment\":\"x\"}]",
                "[{\"data\":\"x\",\"retry\":100}]", "[{\"data\":\"x\"}] null")) {
            assertThatThrownBy(() -> SseEventSequence.parse(body)).as(body).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void contractPreservesDataAndNormalizesDelayCompatibly() {
        var events = SseEventSequence.parse("[{\"data\":\"\\n中文😀\\r\\n\",\"event\":null,\"id\":null,\"delayMs\":-1},"
                + "{\"data\":\"  \",\"delayMs\":999999}]");
        assertThat(events.get(0).data()).isEqualTo("\n中文😀\r\n");
        assertThat(events.get(0).delayMs()).isZero();
        assertThat(events.get(1).delayMs()).isEqualTo(30_000);
    }

    @Test
    void incompatibleHeadersAndStatusesFailClearly() {
        for (String headers : List.of("null", "[]", "{\"Content-Type\":\"application/json\"}",
                "{\"Content-Type\":\"text/event-stream;charset=ISO-8859-1\"}", "{\"Content-Length\":\"1\"}",
                "{\"Transfer-Encoding\":\"chunked\"}", "{\"X-Test\":\"bad\\nheader\"}", "{\"X-Test\":1}")) {
            assertThatThrownBy(() -> SseRuleSettings.headers(headers, 200)).as(headers).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> SseRuleSettings.headers(null, 101)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SseRuleSettings.headers("{\"Content-Type\":\"text/event-stream\"}", 429))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
