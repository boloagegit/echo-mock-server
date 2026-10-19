package com.echo.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Make the value fit into the field before writing: The external database will reject values that are too long and NUL, and a single rejected value will block the entire batch of records. */
class RequestLogColumnsTest {

    @Test
    void boundedColumnsAreShortenedAndNulRemoved() {
        RequestLog log = RequestLog.builder()
                .method("PROPFINDXXXXXXXXXX")
                .endpoint("/" + "a".repeat(800))
                .targetHost("h".repeat(400))
                .clientIp("1".repeat(80))
                .scenarioName("s".repeat(150))
                .requestBody("before\0after")
                .matchChain("[\0]")
                .build();

        log.onCreate();

        assertThat(log.getMethod()).hasSize(10);
        assertThat(log.getEndpoint()).hasSize(500).startsWith("/a");
        assertThat(log.getTargetHost()).hasSize(255);
        assertThat(log.getClientIp()).hasSize(50);
        assertThat(log.getScenarioName()).hasSize(100);
        assertThat(log.getRequestBody()).isEqualTo("beforeafter");
        assertThat(log.getMatchChain()).isEqualTo("[]");
        assertThat(log.getRequestTime()).isNotNull();
    }

    @Test
    void shortValuesAreUntouchedAndSurrogatePairsAreNotSplit() {
        assertThat(RequestLog.fit("/orders", 500)).isEqualTo("/orders");
        assertThat(RequestLog.fit(null, 10)).isNull();
        String emoji = "a".repeat(9) + "😀";
        assertThat(RequestLog.fit(emoji, 10)).isEqualTo("a".repeat(9));
        assertThat(RequestLog.fit("ab\0c", 10)).isEqualTo("abc");
    }
}
