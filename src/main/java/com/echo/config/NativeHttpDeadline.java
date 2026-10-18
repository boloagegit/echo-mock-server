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
    private volatile boolean expired;

    NativeHttpDeadline(HttpServerExchange exchange, NativeHttpResources.Lease lease,
                       long timeoutMs) {
        // XNIO owns this connection and its timer queue. Fast responses register/cancel on
        // the same I/O thread, avoiding a contended global scheduler on every request.
        var task = exchange.getIoThread().executeAfter(() -> {
            if (lease.closed()) {
                return;
            }
            expired = true; // The one-shot callback runs only on the owning I/O thread.
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
        }, timeoutMs, TimeUnit.MILLISECONDS);
        lease.onClose(task::remove);
    }

    boolean expired() {
        return expired;
    }
}
