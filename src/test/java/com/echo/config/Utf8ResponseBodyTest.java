package com.echo.config;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class Utf8ResponseBodyTest {
    @Test
    void preservesUnicodeMalformedSurrogatesAndBufferBoundaries() {
        for (String body : new String[]{"", "a".repeat(1048576), "銀行😀".repeat(100000),
                "a".repeat(Utf8ResponseBody.CHAR_CAPACITY - 1) + "😀" + "b".repeat(65536),
                "a".repeat(Utf8ResponseBody.CHAR_CAPACITY - 1) + "\ud800x\udc00",
                randomUtf16()}) {
            byte[] expected = body.getBytes(StandardCharsets.UTF_8);
            assertThat(Utf8ResponseBody.encodedLength(body, Integer.MAX_VALUE)).isEqualTo(expected.length);
            if (expected.length > 0) {
                assertThat(Utf8ResponseBody.encodedLength(body, expected.length - 1)).isEqualTo(-1);
            }
            Utf8ResponseBody encoder = new Utf8ResponseBody(body);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            while (!encoder.finished()) {
                var buffer = encoder.next();
                assertThat(buffer.remaining()).isLessThanOrEqualTo(Utf8ResponseBody.BYTE_CAPACITY);
                output.write(buffer.array(), buffer.position(), buffer.remaining());
            }
            assertThat(output.toByteArray()).isEqualTo(expected);
        }
    }

    private static String randomUtf16() {
        Random random = new Random(87463);
        char[] value = new char[200000];
        for (int index = 0; index < value.length; index++) value[index] = (char) random.nextInt(65536);
        return new String(value);
    }
}
