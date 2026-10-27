package com.echo.config;

import io.undertow.connector.ByteBufferPool;
import io.undertow.connector.PooledByteBuffer;
import io.undertow.io.Receiver;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.ServerConnection;
import io.undertow.util.HeaderMap;
import io.undertow.util.Headers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xnio.ChannelListener;
import org.xnio.XnioIoThread;
import org.xnio.channels.StreamSourceChannel;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class NativeHttpBodyReceiverTest {
    private final HttpServerExchange exchange = mock(HttpServerExchange.class);
    private final StreamSourceChannel channel = mock(StreamSourceChannel.class);
    private final XnioIoThread ioThread = mock(XnioIoThread.class);
    private final PooledByteBuffer pooled = mock(PooledByteBuffer.class);
    private final Receiver.ErrorCallback errors = mock(Receiver.ErrorCallback.class);
    private final Runnable complete = mock(Runnable.class);
    private final ChannelListener.SimpleSetter<StreamSourceChannel> setter = new ChannelListener.SimpleSetter<>();
    private final HeaderMap headers = new HeaderMap();

    @BeforeEach
    void setUp() {
        var connection = mock(ServerConnection.class);
        var pool = mock(ByteBufferPool.class);
        when(exchange.getConnection()).thenReturn(connection);
        when(connection.getByteBufferPool()).thenReturn(pool);
        when(pool.allocate()).thenReturn(pooled);
        when(pooled.getBuffer()).thenReturn(ByteBuffer.allocateDirect(32));
        when(exchange.getRequestChannel()).thenReturn(channel);
        when(exchange.getRequestHeaders()).thenReturn(headers);
        when(exchange.getIoThread()).thenReturn(ioThread);
        doReturn(setter).when(channel).getReadSetter();
    }

    private NativeHttpBodyReceiver receiver(NativeHttpRequestBody body) {
        return new NativeHttpBodyReceiver(exchange, body, complete, errors, () -> true,
                () -> { throw new AssertionError("Unexpected deadline"); });
    }

    @Test
    void deadlineDuringAReadableUploadStopsBeforeAppendingTheExpiredChunk() throws Exception {
        var reads = new AtomicInteger();
        when(channel.read(any(ByteBuffer.class))).thenAnswer(invocation -> {
            reads.incrementAndGet();
            invocation.<ByteBuffer>getArgument(0).put((byte) 'x');
            return 1;
        });
        var body = spy(new NativeHttpRequestBody(32, -1));
        var unavailable = mock(Runnable.class);
        var receiver = new NativeHttpBodyReceiver(exchange, body, complete, errors,
                () -> reads.get() < 3, unavailable);
        receiver.receive(32);
        verify(body, times(2)).append(any(ByteBuffer.class));
        verify(unavailable, times(1)).run();
        verify(pooled).close();
        verifyNoInteractions(complete, errors);
        assertThat(reads).hasValue(3);
        assertThat(body.take()).isNull();
        assertThat(setter.get()).isNull();
    }

    @Test
    void readinessEventReturnsItsOwnBufferAndIgnoresAStaleListenerAfterCompletion() throws Exception {
        when(channel.read(any(ByteBuffer.class))).thenReturn(0);
        var body = new NativeHttpRequestBody(32, -1);
        receiver(body).receive(32);
        var listener = setter.get();
        when(channel.read(any(ByteBuffer.class))).thenAnswer(invocation -> {
            invocation.<ByteBuffer>getArgument(0).put((byte) 'x');
            return 1;
        }).thenReturn(-1);
        listener.handleEvent(channel);
        listener.handleEvent(channel);
        verify(complete, times(1)).run();
        verify(pooled, times(2)).close();
        assertThat(body.take().decode(StandardCharsets.UTF_8)).isEqualTo("x");
        assertThat(setter.get()).isNull();
    }

    @Test
    void fragmentedReadReleasesThePoolAndDispatchesCompletionOnlyOnce() throws Exception {
        byte[] encoded = "中文🙂".getBytes(StandardCharsets.UTF_8);
        var body = new NativeHttpRequestBody(32, -1);
        var receiver = receiver(body);
        AtomicInteger index = new AtomicInteger();
        when(channel.read(any(ByteBuffer.class))).thenAnswer(invocation -> {
            int i = index.getAndIncrement();
            if (i == encoded.length) return -1;
            invocation.<ByteBuffer>getArgument(0).put(encoded[i]);
            return 1;
        });
        receiver.receive(32);
        verify(complete, times(1)).run();
        verify(pooled, times(1)).close();
        verify(channel).suspendReads();
        assertThat(setter.get()).isNull();
        assertThat(body.take().decode(StandardCharsets.UTF_8)).isEqualTo("中文🙂");
        receiver.close();
        verifyNoInteractions(ioThread, errors);
    }

    @Test
    void readFailureReturnsPoolBeforeReportingAndDetachesListener() throws Exception {
        var failure = new IOException("injected truncated upload");
        when(channel.read(any(ByteBuffer.class))).thenThrow(failure);
        var receiver = receiver(new NativeHttpRequestBody(32, -1));
        receiver.receive(32);
        var order = inOrder(pooled, errors);
        order.verify(pooled).close();
        order.verify(errors).error(exchange, failure);
        assertThat(setter.get()).isNull();
        verifyNoInteractions(complete);
    }

    @Test
    void cumulativeOverflowReturnsPoolAndRetainsNoListener() throws Exception {
        when(channel.read(any(ByteBuffer.class))).thenAnswer(invocation -> {
            invocation.<ByteBuffer>getArgument(0).put(new byte[]{1, 2, 3});
            return 3;
        });
        var body = new NativeHttpRequestBody(2, -1);
        receiver(body).receive(2);
        verify(pooled).close();
        verify(errors).error(eq(exchange), isA(Receiver.RequestToLargeException.class));
        verifyNoInteractions(complete);
        assertThat(body.size()).isZero();
        assertThat(setter.get()).isNull();
    }

    @Test
    void cancellationOfAWaitingReadClearsListenerOnTheOwningIoThread() throws Exception {
        when(channel.read(any(ByteBuffer.class))).thenReturn(0);
        var body = new NativeHttpRequestBody(32, -1);
        var receiver = receiver(body);
        receiver.receive(32);
        verify(channel).resumeReads();
        verify(pooled).close();
        assertThat(setter.get()).isNotNull();
        var cleanup = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        receiver.close();
        verify(ioThread).execute(cleanup.capture());
        cleanup.getValue().run();
        assertThat(setter.get()).isNull();
        assertThat(body.take()).isNull();
        verifyNoInteractions(complete, errors);
    }

    @Test
    void decodedLengthIsCheckedBeforeBorrowingOrReading() throws Exception {
        headers.put(Headers.X_CONTENT_LENGTH, 33);
        receiver(new NativeHttpRequestBody(32, -1)).receive(32);
        verify(errors).error(eq(exchange), isA(Receiver.RequestToLargeException.class));
        verify(channel, never()).read(any(ByteBuffer.class));
        verifyNoInteractions(complete, pooled);
    }
}
