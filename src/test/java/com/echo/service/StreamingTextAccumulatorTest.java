package com.echo.service;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class StreamingTextAccumulatorTest {

    @ParameterizedTest
    @ValueSource(strings = {"heap", "direct", "composite"})
    void decodesBuffersBeforeTheirStorageIsReusedWithoutRetainingOrAdvancingThem(String storage) {
        for (String name : new String[]{"UTF-8", "UTF-16", "GB18030", "ISO-2022-JP",
                "US-ASCII", "ISO-8859-1"}) {
            Charset charset = Charset.forName(name);
            byte[] bytes = ("ASCII".repeat(2_000) + "銀行😀".repeat(2_000)).getBytes(charset);
            bytes[bytes.length - 1] = (byte) 0xFF;
            for (int chunkSize : new int[]{257, 8_191, 65_536}) {
                var accumulator = new StreamingTextAccumulator(charset);
                for (int offset = 0; offset < bytes.length; offset += chunkSize) {
                    int length = Math.min(chunkSize, bytes.length - offset);
                    ByteBuf source = switch (storage) {
                        case "heap" -> Unpooled.buffer(length + 7).writeZero(7);
                        case "direct" -> Unpooled.directBuffer(length + 7).writeZero(7);
                        default -> Unpooled.compositeBuffer().addComponents(false,
                                Unpooled.directBuffer(7).writeZero(7),
                                Unpooled.buffer(length).writeZero(length)).writerIndex(7);
                    };
                    try {
                        source.writeBytes(bytes, offset, length);
                        source.readerIndex(7);
                        ByteBuf slice = source.slice(3, length + 4).readerIndex(4);
                        int references = source.refCnt();
                        accumulator.appendBuffer(slice);
                        assertThat(slice.readerIndex()).isEqualTo(4);
                        assertThat(source.refCnt()).isEqualTo(references);
                        source.setZero(0, source.capacity());
                    } finally {
                        source.release();
                    }
                }
                assertThat(accumulator.finish()).as("%s, %s, chunks of %s", storage, name, chunkSize)
                        .isEqualTo(new String(bytes, charset));
            }
        }
    }

    @Test
    void emptyBuffersPreserveDecoderStateAndFinishedAccumulatorsRejectFurtherBuffers() {
        var accumulator = new StreamingTextAccumulator(StandardCharsets.UTF_8);
        accumulator.appendBuffer(Unpooled.EMPTY_BUFFER);
        accumulator.append(new byte[]{(byte) 0xE6});
        accumulator.appendBuffer(Unpooled.EMPTY_BUFFER);
        assertThat(accumulator.finish()).isEqualTo("�");
        assertThatIllegalStateException().isThrownBy(() -> accumulator.appendBuffer(Unpooled.EMPTY_BUFFER));
    }

    @Test
    void preservesAsciiPrefixWhenLaterChunksRequireUtf16AndEndWithMalformedInput() {
        String prefix = "ASCII".repeat(8_000);
        String suffix = "銀行😀".repeat(4_000);
        byte[] tail = (suffix + "A").getBytes(StandardCharsets.UTF_8);
        tail[tail.length - 1] = (byte) 0xE6;
        var accumulator = new StreamingTextAccumulator(StandardCharsets.UTF_8);
        accumulator.append(prefix.getBytes(StandardCharsets.UTF_8));
        for (int offset = 0; offset < tail.length; offset += 257) {
            accumulator.append(Arrays.copyOfRange(tail, offset, Math.min(tail.length, offset + 257)));
        }
        assertThat(accumulator.finish()).isEqualTo(prefix + new String(tail, StandardCharsets.UTF_8));
    }

    @Test
    void decodesUtf8CharacterSplitAcrossChunks() {
        byte[] encoded = "A測試B".getBytes(StandardCharsets.UTF_8);
        StreamingTextAccumulator accumulator =
                new StreamingTextAccumulator(StandardCharsets.UTF_8);

        accumulator.append(Arrays.copyOfRange(encoded, 0, 3));
        accumulator.append(Arrays.copyOfRange(encoded, 3, 6));
        accumulator.append(Arrays.copyOfRange(encoded, 6, encoded.length));

        assertThat(accumulator.finish()).isEqualTo("A測試B");
    }

    @Test
    void replacesMalformedInputLikeStringDecoding() {
        byte[] malformed = {(byte) 0xE6, (byte) 0xB8};
        StreamingTextAccumulator accumulator =
                new StreamingTextAccumulator(StandardCharsets.UTF_8);
        accumulator.append(malformed);

        assertThat(accumulator.finish()).isEqualTo(new String(malformed, StandardCharsets.UTF_8));
    }

    @Test
    void largeAndFragmentedBodiesMatchWholeBodyDecodingAcrossCharsets() {
        String body = "銀行轉發😀\r\nquoted\"\\\tABC".repeat(10_000);
        for (String name : new String[]{"UTF-8", "UTF-16", "UTF-16LE", "ISO-8859-1",
                "GB18030", "ISO-2022-JP"}) {
            Charset charset = Charset.forName(name);
            byte[] encoded = body.getBytes(charset);
            String expected = new String(encoded, charset);
            for (int chunkSize : new int[]{1, 257, 8_191, 65_536, encoded.length}) {
                var accumulator = new StreamingTextAccumulator(charset);
                for (int offset = 0; offset < encoded.length; offset += chunkSize) {
                    accumulator.append(Arrays.copyOfRange(encoded, offset,
                            Math.min(encoded.length, offset + chunkSize)));
                }
                assertThat(accumulator.finish()).as("%s, chunks of %s", name, chunkSize)
                        .isEqualTo(expected);
            }
        }
    }

    @Test
    void malformedAndTruncatedInputMatchesWholeBodyDecodingAcrossBoundaries() {
        Random random = new Random(20261019);
        byte[] bytes = new byte[25_003];
        random.nextBytes(bytes);
        for (Charset charset : new Charset[]{StandardCharsets.UTF_8, StandardCharsets.UTF_16LE}) {
            for (int chunkSize : new int[]{1, 31, 8_191, bytes.length}) {
                var accumulator = new StreamingTextAccumulator(charset);
                for (int offset = 0; offset < bytes.length; offset += chunkSize) {
                    accumulator.append(Arrays.copyOfRange(bytes, offset, Math.min(bytes.length, offset + chunkSize)));
                }
                assertThat(accumulator.finish()).as("%s, chunks of %s", charset, chunkSize)
                        .isEqualTo(new String(bytes, charset));
            }
        }
    }

    @Test
    void smallFirstChunkCanBeFollowedByLargeChunksAndEmptyCallbacks() {
        var accumulator = new StreamingTextAccumulator(StandardCharsets.UTF_8);
        accumulator.append(null);
        accumulator.append(new byte[0]);
        accumulator.append(new byte[]{'a'});
        accumulator.append("b".repeat(131_072).getBytes(StandardCharsets.UTF_8));
        accumulator.append(new byte[0]);
        assertThat(accumulator.finish()).isEqualTo("a" + "b".repeat(131_072));
        assertThatIllegalStateException().isThrownBy(() -> accumulator.append(new byte[]{'c'}));
        assertThatIllegalStateException().isThrownBy(accumulator::finish);
        assertThat(new StreamingTextAccumulator(StandardCharsets.UTF_8).finish()).isEmpty();
    }
}
