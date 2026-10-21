package com.echo.service;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ReadOnlyBufferException;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Utf8ForwardBodyTest {
    @Test
    void randomValidTextAndFragmentBoundariesKeepIndependentByteAndCharacterCounts() {
        Random random = new Random(20261021);
        String[] alphabet = {"a", "é", "中", "😀", "\u0000", " ", "\uFEFF"};
        for (int trial = 0; trial < 128; trial++) {
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < 1000; i++) text.append(alphabet[random.nextInt(alphabet.length)]);
            String expected = text.toString();
            byte[] bytes = expected.getBytes(StandardCharsets.UTF_8);
            var result = collect(bytes, 1 + random.nextInt(8192));
            assertThat(result.bytes().text()).isEqualTo(expected);
            assertThat(result.bytes().characterLength()).isEqualTo(expected.length());
            assertThat(result.bytes().byteLength()).isEqualTo(bytes.length);
        }
    }

    @Test
    void validBodiesPreserveBytesCharactersContentSniffingAndUtf16PrefixBoundaries() {
        for (String text : new String[]{"", " \r\n\t".repeat(10_000), "r".repeat(70_001),
                " \u0000\t{\"value\":\"" + "銀行😀é".repeat(10_000) + "\"}",
                "x".repeat(32_767) + "😀" + "y".repeat(32_768), "\uFEFF<xml/>"}) {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            for (int chunk : new int[]{1, 2, 3, 7, 4093, 8191, 32767, Math.max(1, bytes.length)}) {
                Utf8ForwardAccumulator.Result result = collect(bytes, chunk);
                assertThat(result.text()).isNull();
                Utf8ForwardBody body = result.bytes();
                assertThat(body.text()).isEqualTo(text);
                assertThat(body.byteLength()).isEqualTo(bytes.length);
                assertThat(body.characterLength()).isEqualTo(text.length());
                assertThat(body.firstSignificantCharacter()).isEqualTo(text.trim().isEmpty() ? 0 : text.trim().charAt(0));
                for (int limit : new int[]{0, 1, 2, 3, 31, 32768, 65536, 65537, Integer.MAX_VALUE}) {
                    assertThat(body.prefix(limit)).as("chunk=%s prefix=%s", chunk, limit)
                            .isEqualTo(text.substring(0, Math.min(limit, text.length())));
                }
                ByteArrayOutputStream wire = new ByteArrayOutputStream();
                for (int first = 0; first < body.segmentCount(); first += 8) {
                    for (var buffer : body.batch(first, 8)) {
                        assertThat(buffer.isReadOnly()).isTrue();
                        assertThatThrownBy(() -> buffer.put(0, (byte) 0)).isInstanceOf(ReadOnlyBufferException.class);
                        byte[] copy = new byte[buffer.remaining()];
                        buffer.get(copy);
                        wire.writeBytes(copy);
                    }
                }
                assertThat(wire.toByteArray()).isEqualTo(bytes);
                assertThat(body.text()).isEqualTo(text);
            }
        }
    }

    @Test
    void malformedAndTruncatedUtf8UsesTheExistingReplacementSemantics() {
        byte[] random = new byte[70_003];
        new Random(20261020).nextBytes(random);
        byte[] late = ("a".repeat(70_000) + "銀行😀").getBytes(StandardCharsets.UTF_8);
        late[late.length - 1] = (byte) 0xff;
        for (byte[] bytes : new byte[][]{random, late, {(byte) 0xe6}, {(byte) 0xf0, (byte) 0x9f, (byte) 0x98},
                {(byte) 0xc0, (byte) 0xaf}, {(byte) 0xed, (byte) 0xa0, (byte) 0x80}}) {
            for (int chunk : new int[]{1, 2, 3, 7, 8191, 32768, bytes.length}) {
                var result = collect(bytes, chunk);
                assertThat(result.bytes()).isNull();
                assertThat(result.text()).as("chunk=%s length=%s", chunk, bytes.length)
                        .isEqualTo(new String(bytes, StandardCharsets.UTF_8));
            }
        }
    }

    private static Utf8ForwardAccumulator.Result collect(byte[] bytes, int chunk) {
        Utf8ForwardAccumulator accumulator = new Utf8ForwardAccumulator();
        for (int offset = 0; offset < bytes.length; offset += chunk) {
            int length = Math.min(chunk, bytes.length - offset);
            ByteBuf source = Unpooled.directBuffer(length + 3).writeZero(3).writeBytes(bytes, offset, length);
            try {
                source.readerIndex(3);
                accumulator.append(source);
                assertThat(source.readerIndex()).isEqualTo(3);
                assertThat(source.refCnt()).isEqualTo(1);
                source.setZero(0, source.capacity());
            } finally { source.release(); }
        }
        var result = accumulator.finish();
        assertThatThrownBy(accumulator::finish).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> accumulator.append(Unpooled.EMPTY_BUFFER)).isInstanceOf(IllegalStateException.class);
        return result;
    }
}
