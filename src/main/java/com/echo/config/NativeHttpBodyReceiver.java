package com.echo.config;

import io.undertow.io.Receiver;
import io.undertow.server.Connectors;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import org.xnio.channels.StreamSourceChannel;

import java.io.IOException;
import java.util.function.BooleanSupplier;

/** Non-blocking upload reader; pooled storage never escapes a single read event. */
final class NativeHttpBodyReceiver implements AutoCloseable {
    private final HttpServerExchange exchange;
    private final StreamSourceChannel channel;
    private final NativeHttpRequestBody body;
    private final Runnable complete;
    private final Receiver.ErrorCallback error;
    private final BooleanSupplier active;
    private final Runnable unavailable;
    private volatile boolean cancelled;
    // Only the connection's I/O thread changes the channel or this terminal state.
    private volatile boolean finished;

    NativeHttpBodyReceiver(HttpServerExchange exchange, NativeHttpRequestBody body,
                           Runnable complete, Receiver.ErrorCallback error,
                           BooleanSupplier active, Runnable unavailable) {
        this.exchange = exchange;
        this.channel = exchange.getRequestChannel();
        if (channel == null) throw new IllegalStateException("Request channel already obtained");
        this.body = body;
        this.complete = complete;
        this.error = error;
        this.active = active;
        this.unavailable = unavailable;
    }

    void receive(int maxBytes) {
        // Preserve the standard receiver's check for decoded Content-Length as well.
        String declared = exchange.getRequestHeaders().getFirst(Headers.CONTENT_LENGTH);
        if (declared == null) declared = exchange.getRequestHeaders().getFirst(Headers.X_CONTENT_LENGTH);
        if (declared != null && Long.parseLong(declared) > maxBytes) {
            finish();
            error.error(exchange, new Receiver.RequestToLargeException());
            return;
        }
        channel.getReadSetter().set(ignored -> {
            if (!finished) {
                // Dispatch and end-of-exchange handling must retain Undertow's root contract.
                Connectors.executeRootHandler(ex -> read(), exchange);
            }
        });
        read();
    }

    private void read() {
        if (finished) return;
        if (cancelled) {
            finish();
            return;
        }
        try (var pooled = exchange.getConnection().getByteBufferPool().allocate()) {
            var buffer = pooled.getBuffer();
            while (!cancelled) {
                int count = body.read(channel, buffer, active);
                // A continuously readable upload must honor the clock even before the
                // I/O thread gets back to its timer queue. Never append an expired chunk.
                if (count == NativeHttpRequestBody.INACTIVE) {
                    finish();
                    body.close();
                    unavailable.run();
                    return;
                }
                if (count == -1) {
                    finish();
                    if (!cancelled) complete.run();
                    return;
                }
                if (count == 0) {
                    channel.resumeReads();
                    return;
                }
            }
            finish();
        } catch (IOException failure) {
            finish();
            if (!cancelled) error.error(exchange, failure);
        }
    }

    private void finish() {
        finished = true;
        channel.suspendReads();
        channel.getReadSetter().set(null);
    }

    @Override
    public void close() {
        cancelled = true;
        body.close();
        if (!finished) exchange.getIoThread().execute(() -> {
            if (!finished) finish();
        });
    }
}
