package com.echo.service;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BufferedResponseBodyTest {

    @Test
    void malformedAsciiUsesJdkReplacementInsteadOfLatin1Decoding() {
        var budget = new ResponseBufferBudget(16);
        ByteBuf input = Unpooled.wrappedBuffer(new byte[]{'A', (byte) 0xFF, 'B'});
        try (var body = new HttpOutboundForwarder.BufferedResponseBody(3, 16, budget)) {
            body.append(input);
            assertThat(body.decode(StandardCharsets.US_ASCII)).isEqualTo("A\uFFFDB");
        } finally {
            input.release();
        }
        assertThat(budget.reservedBytes()).isZero();
    }

    @Test
    void tinyFragmentsDoNotRetainTheirLargeParentAllocations() {
        var budget = new ResponseBufferBudget(4_096);
        try (var body = new HttpOutboundForwarder.BufferedResponseBody(1_024, 4_096, budget)) {
            for (int i = 0; i < 1_024; i++) {
                ByteBuf parent = Unpooled.directBuffer(8_192, 8_192).writeZero(8_192);
                try {
                    parent.setByte(0, 'a');
                    ByteBuf fragment = parent.slice(0, 1);
                    body.append(fragment);
                    assertThat(parent.refCnt()).isEqualTo(1);
                    assertThat(fragment.readerIndex()).isZero();
                    assertThat(budget.reservedBytes()).isEqualTo(1_024);
                } finally {
                    parent.release();
                }
            }
            assertThat(body.decode(StandardCharsets.UTF_8)).isEqualTo("a".repeat(1_024));
        }
        assertThat(budget.reservedBytes()).isZero();
    }

    @Test
    void lengthHintIsNotABodyLimitAndGrowthRemainsBudgeted() {
        var budget = new ResponseBufferBudget(16);
        ByteBuf first = Unpooled.copiedBuffer("abcd", StandardCharsets.UTF_8);
        ByteBuf next = Unpooled.copiedBuffer("efghi", StandardCharsets.UTF_8);
        ByteBuf excess = Unpooled.copiedBuffer("12345678", StandardCharsets.UTF_8);
        try (var body = new HttpOutboundForwarder.BufferedResponseBody(4, 16, budget)) {
            body.append(first);
            body.append(next);
            assertThat(body.decode(StandardCharsets.UTF_8)).isEqualTo("abcdefghi");
            assertThat(budget.reservedBytes()).isEqualTo(9);
            assertThatThrownBy(() -> body.append(excess)).hasMessageContaining("16");
            assertThat(budget.reservedBytes()).isEqualTo(9);
        } finally {
            first.release();
            next.release();
            excess.release();
        }
        assertThat(budget.reservedBytes()).isZero();
    }

    @Test
    void rejectedGrowthAndLateCallbacksDoNotLeakBudgetOrRetainInput() {
        var budget = new ResponseBufferBudget(8);
        var body = new HttpOutboundForwarder.BufferedResponseBody(4, 16, budget);
        ByteBuf first = Unpooled.copiedBuffer("abcd", StandardCharsets.UTF_8);
        ByteBuf next = Unpooled.copiedBuffer("efghi", StandardCharsets.UTF_8);
        try {
            body.append(first);
            assertThatThrownBy(() -> body.append(next)).hasMessageContaining("8");
            assertThat(budget.reservedBytes()).isEqualTo(4);
            body.close();
            body.close();
            body.append(next);
            assertThat(body.decode(StandardCharsets.UTF_8)).isNull();
            assertThat(budget.reservedBytes()).isZero();
            assertThat(next.refCnt()).isEqualTo(1);
        } finally {
            body.close();
            first.release();
            next.release();
        }
    }
}
