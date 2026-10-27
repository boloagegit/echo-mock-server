package com.echo.config;

import io.undertow.io.Receiver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NativeHttpRequestBodyTest {
    @Test
    void anExpiredOwnedViewReadNeverPublishesItsNewBytes() throws Exception {
        var channel = org.mockito.Mockito.mock(org.xnio.channels.StreamSourceChannel.class);
        var active = new java.util.concurrent.atomic.AtomicBoolean(true);
        org.mockito.Mockito.when(channel.read(org.mockito.ArgumentMatchers.any(ByteBuffer.class)))
                .thenAnswer(call -> {
                    ByteBuffer target = call.getArgument(0);
                    assertThat(target.hasArray()).isTrue();
                    target.put("late".getBytes(StandardCharsets.UTF_8));
                    active.set(false);
                    return 4;
                });
        try (var body = new NativeHttpRequestBody(32, 32)) {
            body.append("kept".getBytes(StandardCharsets.UTF_8));
            assertThat(body.read(channel, ByteBuffer.allocateDirect(8), active::get))
                    .isEqualTo(NativeHttpRequestBody.INACTIVE);
            assertThat(body.size()).isEqualTo(4);
            assertThat(body.take().decode(StandardCharsets.UTF_8)).isEqualTo("kept");
        }
    }

    @Test
    void growthAndReadinessGapsNeverReuseTheOldArrayView() throws Exception {
        byte[] source = "abcdefghij".getBytes(StandardCharsets.UTF_8);
        var channel = org.mockito.Mockito.mock(org.xnio.channels.StreamSourceChannel.class);
        var offset = new java.util.concurrent.atomic.AtomicInteger();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.when(channel.read(org.mockito.ArgumentMatchers.any(ByteBuffer.class)))
                .thenAnswer(call -> {
                    if (calls.incrementAndGet() == 2) return 0;
                    ByteBuffer target = call.getArgument(0);
                    int count = Math.min(target.remaining(), source.length - offset.get());
                    if (count == 0) return -1;
                    target.put(source, offset.get(), count);
                    offset.addAndGet(count);
                    return count;
                });
        try (var body = new NativeHttpRequestBody(32, 4)) {
            var scratch = ByteBuffer.allocateDirect(1);
            while (body.read(channel, scratch, () -> true) != -1) { }
            assertThat(body.take().decode(StandardCharsets.UTF_8)).isEqualTo("abcdefghij");
            assertThat(calls).hasValue(8);
        }
    }

    @Test
    void directChannelReadsUseBoundedViewsAndTransferTheWholeBody() throws Exception {
        byte[] source = "z".repeat(1024 * 1024).getBytes(StandardCharsets.UTF_8);
        var channel = org.mockito.Mockito.mock(org.xnio.channels.StreamSourceChannel.class);
        var offset = new java.util.concurrent.atomic.AtomicInteger();
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        var largest = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.when(channel.read(org.mockito.ArgumentMatchers.any(ByteBuffer.class)))
                .thenAnswer(call -> {
                    ByteBuffer target = call.getArgument(0);
                    largest.accumulateAndGet(target.remaining(), Math::max);
                    reads.incrementAndGet();
                    int count = Math.min(target.remaining(), source.length - offset.get());
                    if (count == 0) return -1;
                    target.put(source, offset.get(), count);
                    offset.addAndGet(count);
                    return count;
                });
        try (var body = new NativeHttpRequestBody(source.length, source.length)) {
            var scratch = ByteBuffer.allocateDirect(16384);
            while (body.read(channel, scratch, () -> true) != -1) { }
            assertThat(largest).hasValue(65536);
            assertThat(reads.get()).isLessThan(20);
            var received = body.take();
            body.close();
            assertThat(received.decode(StandardCharsets.UTF_8)).isEqualTo(new String(source, StandardCharsets.UTF_8));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void pooledBufferPositionsAndOwnershipArePreserved(boolean direct) throws Exception {
        var buffer = direct ? ByteBuffer.allocateDirect(32) : ByteBuffer.allocate(32);
        byte[] encoded = "中文🙂".getBytes(StandardCharsets.UTF_8);
        buffer.position(3);
        buffer.put(encoded).flip().position(3);
        try (var body = new NativeHttpRequestBody(encoded.length, -1)) {
            var view = buffer.asReadOnlyBuffer();
            assertThat(body.append(view)).isTrue();
            assertThat(view.hasRemaining()).isFalse();
            buffer.clear();
            while (buffer.hasRemaining()) buffer.put((byte) 'x');
            assertThat(body.take().decode(StandardCharsets.UTF_8)).isEqualTo("中文🙂");
        }
    }

    @Test
    void rejectedOrCancelledBuffersAreNotConsumed() throws Exception {
        var buffer = ByteBuffer.wrap(new byte[]{'x', 'y', 'z'});
        try (var body = new NativeHttpRequestBody(2, -1)) {
            assertThatThrownBy(() -> body.append(buffer)).isInstanceOf(Receiver.RequestToLargeException.class);
            assertThat(buffer.position()).isZero();
            assertThat(body.size()).isZero();
            body.close();
            assertThat(body.append(buffer)).isFalse();
            assertThat(buffer.position()).isZero();
        }
    }

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
