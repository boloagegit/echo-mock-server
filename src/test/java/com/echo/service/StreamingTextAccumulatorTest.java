package com.echo.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class StreamingTextAccumulatorTest {

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
}
