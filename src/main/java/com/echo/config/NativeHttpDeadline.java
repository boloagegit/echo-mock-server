package com.echo.config;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import org.xnio.IoUtils;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** One deadline from admission through response delivery, including queued work and uploads. */
final class NativeHttpDeadline {
    private static final byte[] TIMEOUT_BODY = "Request timeout".getBytes(StandardCharsets.UTF_8);
    private final HttpServerExchange exchange;
    private final NativeHttpResources.Lease lease;
    private volatile boolean expired;
    private final long deadlineNanos;

    NativeHttpDeadline(HttpServerExchange exchange, NativeHttpResources.Lease lease,
                       long timeoutMs) {
        this.exchange = exchange;
        this.lease = lease;
        deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        // XNIO owns this connection and its timer queue. Fast responses register/cancel on
        // the same I/O thread, avoiding a contended global scheduler on every request.
        var task = exchange.getIoThread().executeAfter(this::enforce, timeoutMs, TimeUnit.MILLISECONDS);
        lease.onClose(task::remove);
    }

    /** An inactive I/O handoff must not fall through to Undertow's implicit empty 200. */
    boolean checkActive() {
        if (lease.available()) return true;
        enforceInactive();
        return false;
    }

    void enforceInactive() {
        if (lease.closed()) IoUtils.safeClose(exchange.getConnection());
        else enforce();
    }

    /** Enforce an observed deadline on the owning I/O thread without awaiting its timer. */
    void enforce() {
        if (lease.closed() || expired) return;
        expired = true;
        NativeHttpObservation.error(exchange, new TimeoutException("HTTP request timeout"));
        try {
            if (exchange.isRequestComplete() && !exchange.isResponseStarted()) {
                // A completed request can receive the existing 504 response. An incomplete
                // upload or partial response must close: HTTP/1 cannot resume either safely.
                exchange.setPersistent(false);
                exchange.setStatusCode(504);
                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain;charset=UTF-8");
                exchange.getResponseSender().send(ByteBuffer.wrap(TIMEOUT_BODY));
            } else {
                IoUtils.safeClose(exchange.getConnection());
            }
        } catch (RuntimeException failure) {
            IoUtils.safeClose(exchange.getConnection());
            lease.close();
        }
    }

    boolean expired() {
        // Stage guards must not depend on an I/O timer getting CPU time first.
        return expired || System.nanoTime() - deadlineNanos >= 0;
    }
}
