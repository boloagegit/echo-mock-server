package com.echo.service;

import com.echo.entity.Protocol;
import com.echo.entity.ResponseContentType;
import net.jqwik.api.*;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ContentTypeConstraints.infer() 單元測試與屬性測試。
 */
class ContentTypeConstraintsTest {

    // --- Unit Tests ---

    @Test
    void infer_httpSseEnabled_returnsSseEvents() {
        ResponseContentType result = ContentTypeConstraints.infer(Protocol.HTTP, true);
        assertThat(result).isEqualTo(ResponseContentType.SSE_EVENTS);
    }

    @Test
    void infer_httpSseDisabled_returnsText() {
        ResponseContentType result = ContentTypeConstraints.infer(Protocol.HTTP, false);
        assertThat(result).isEqualTo(ResponseContentType.TEXT);
    }

    @Test
    void infer_httpSseNull_returnsText() {
        ResponseContentType result = ContentTypeConstraints.infer(Protocol.HTTP, null);
        assertThat(result).isEqualTo(ResponseContentType.TEXT);
    }

    @Test
    void infer_jmsSseEnabled_returnsText() {
        ResponseContentType result = ContentTypeConstraints.infer(Protocol.JMS, true);
        assertThat(result).isEqualTo(ResponseContentType.TEXT);
    }

    @Test
    void infer_jmsSseDisabled_returnsText() {
        ResponseContentType result = ContentTypeConstraints.infer(Protocol.JMS, false);
        assertThat(result).isEqualTo(ResponseContentType.TEXT);
    }

    @Test
    void infer_jmsSseNull_returnsText() {
        ResponseContentType result = ContentTypeConstraints.infer(Protocol.JMS, null);
        assertThat(result).isEqualTo(ResponseContentType.TEXT);
    }

    // --- Property Test ---

    /**
     * Property 1: contentType 推斷確定性 — 相同 (protocol, sseEnabled) 組合始終回傳相同結果。
     *
     * Validates: Requirements 1.2
     * Validates: 設計文件正確性屬性 1
     */
    @Property(tries = 200)
    void inferIsDeterministic(
            @ForAll("protocols") Protocol protocol,
            @ForAll("sseEnabledValues") Boolean sseEnabled) {

        ResponseContentType first = ContentTypeConstraints.infer(protocol, sseEnabled);
        ResponseContentType second = ContentTypeConstraints.infer(protocol, sseEnabled);

        assertThat(first).isEqualTo(second);
    }

    @Provide
    Arbitrary<Protocol> protocols() {
        return Arbitraries.of(Protocol.values());
    }

    @Provide
    Arbitrary<Boolean> sseEnabledValues() {
        return Arbitraries.of(true, false, null);
    }
}
