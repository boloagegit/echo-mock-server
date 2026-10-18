package com.echo.config;

import io.undertow.io.Receiver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NativeHttpRequestBodyTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void fragmentedUnicodeIsDecodedAsOneBody(boolean knownLength) throws Exception {
        String expected = "中文🙂é𐐷".repeat(5000);
        byte[] encoded = expected.getBytes(StandardCharsets.UTF_8);
        try (var body = new NativeHttpRequestBody(encoded.length, knownLength ? encoded.length : -1)) {
            // Split every multibyte character and exercise repeated unknown-length growth.
            for (byte value : encoded) assertThat(body.append(new byte[]{value})).isTrue();
            assertThat(body.append(new byte[0])).isTrue();
            assertThat(body.take().decode(StandardCharsets.UTF_8)).isEqualTo(expected);
            assertThat(body.take()).isNull();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cumulativeLimitRejectsBeforeChangingAnAcceptedBody(boolean knownLength) throws Exception {
        try (var body = new NativeHttpRequestBody(8, knownLength ? 8 : -1)) {
            body.append("123".getBytes(StandardCharsets.UTF_8));
            body.append("45678".getBytes(StandardCharsets.UTF_8));
            assertThatThrownBy(() -> body.append(new byte[]{'9'}))
                    .isInstanceOf(Receiver.RequestToLargeException.class);
            assertThat(body.take().decode(StandardCharsets.UTF_8)).isEqualTo("12345678");
        }
    }

    @Test
    void closingAnIncompleteBodyPreventsLateCallbacksAndTransfer() throws Exception {
        var body = new NativeHttpRequestBody(1024, -1);
        body.append("partial".getBytes(StandardCharsets.UTF_8));
        body.close();
        body.close();
        assertThat(body.append(new byte[]{'x'})).isFalse();
        assertThat(body.size()).isZero();
        assertThat(body.take()).isNull();
    }

    @Test
    void transferredBytesSurviveLeaseCleanupAndHonorCharsetAndActualLength() throws Exception {
        byte[] encoded = "測試🙂".getBytes(StandardCharsets.UTF_16LE);
        var body = new NativeHttpRequestBody(1024, 1024);
        body.append(Arrays.copyOfRange(encoded, 0, 3));
        body.append(Arrays.copyOfRange(encoded, 3, encoded.length));
        var received = body.take();
        body.close();
        assertThat(received.decode(StandardCharsets.UTF_16LE)).isEqualTo("測試🙂");
        assertThat(body.append(encoded)).isFalse();
    }

    @Test
    void emptyAndMalformedBodiesRetainStringDecodingBehavior() throws Exception {
        try (var body = new NativeHttpRequestBody(4, -1)) {
            body.append(new byte[0]);
            assertThat(body.take().decode(StandardCharsets.UTF_8)).isNull();
        }
        byte[] malformed = {(byte) 0xe4, (byte) 0xb8};
        try (var body = new NativeHttpRequestBody(4, -1)) {
            body.append(malformed);
            assertThat(body.take().decode(StandardCharsets.UTF_8))
                    .isEqualTo(new String(malformed, StandardCharsets.UTF_8));
        }
    }
}
