package com.echo.config;

import com.echo.diagnostics.TransactionDiagnostics;
import com.echo.diagnostics.TransactionDiagnostics.Trace;
import com.echo.entity.Protocol;
import com.echo.pipeline.HttpMockPipeline;
import com.echo.pipeline.MockRequest;
import com.echo.pipeline.MockResponse;
import com.echo.pipeline.PipelineResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.observation.ObservationRegistry;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.io.UndertowInputStream;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import io.undertow.util.AttachmentKey;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.xnio.IoUtils;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import org.springframework.util.function.SingletonSupplier;

/**
 * Optional HTTP/1 adapter sharing the application pipeline, diagnostics and request logging.
 * CPU/database work and encoding run on bounded workers. Servlet-specific requests retain MVC.
 */
final class NativeMockHttpHandler implements HttpHandler {
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private final HttpHandler next;
    private record Services(HttpMockPipeline pipeline, TransactionDiagnostics diagnostics,
                            ObservationRegistry observations, Counter requests) {}
    private final SingletonSupplier<Services> services;
    private final SingletonSupplier<NativeHttpCompatibility> compatibility;
    private final ScheduledExecutorService scheduler;
    private final NativeHttpResources resources;
    private final long timeoutMs;
    private static final AttachmentKey<NativeHttpResources.Lease> LEASE = AttachmentKey.create(NativeHttpResources.Lease.class);
    private static final AttachmentKey<CloseWatch> CLOSE_WATCH = AttachmentKey.create(CloseWatch.class);

    private static final class CloseWatch {
        final Set<NativeHttpResources.Lease> active = ConcurrentHashMap.newKeySet();
    }

    NativeMockHttpHandler(HttpHandler next, ObjectProvider<HttpMockPipeline> pipelines,
                         ObjectProvider<TransactionDiagnostics> diagnostics,
                         ObjectProvider<MeterRegistry> registries, ObjectProvider<ObservationRegistry> observations,
                         java.util.function.Supplier<NativeHttpCompatibility> compatibility,
                         ScheduledExecutorService scheduler,
                         NativeHttpResources resources, long timeoutMs, String mode) {
        this.next = next;
        this.compatibility = SingletonSupplier.of(compatibility);
        this.services = SingletonSupplier.of(() -> {
            MeterRegistry meters = registries.getObject();
            meters.gauge("echo.http.native.active", resources, NativeHttpResources::active);
            meters.gauge("echo.http.native.buffered.bytes", resources, NativeHttpResources::bufferedBytes);
            meters.gauge("echo.http.native.queued", resources.workers, executor -> executor.getQueue().size());
            return new Services(pipelines.getObject(), diagnostics.getObject(), observations.getObject(),
                    meters.counter("echo.http.native.requests", "mode", mode));
        });
        this.scheduler = scheduler;
        this.resources = resources;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        if (!eligible(exchange) || !compatibility.obtain().supported()) {
            next.handleRequest(exchange);
            return;
        }
        Charset charset = StandardCharsets.UTF_8;
        String contentType = exchange.getRequestHeaders().getFirst(Headers.CONTENT_TYPE);
        if (contentType != null) {
            try {
                MediaType type = MediaType.parseMediaType(contentType);
                if (type.getType().equalsIgnoreCase("multipart")
                        || MediaType.APPLICATION_FORM_URLENCODED.isCompatibleWith(type)) {
                    next.handleRequest(exchange);
                    return;
                }
                if (type.getCharset() != null) charset = type.getCharset();
            } catch (IllegalArgumentException invalid) {
                next.handleRequest(exchange);
                return;
            }
        }
        Services current = services.obtain();
        NativeHttpObservation observation = NativeHttpObservation.start(exchange, current.observations());
        var lease = resources.acquire();
        current.requests().increment();
        exchange.addExchangeCompleteListener((ex, continuation) -> {
            try {
                observation.stop(false);
            } finally {
                try { if (lease != null) lease.close(); }
                finally { continuation.proceed(); }
            }
        });
        if (lease == null) {
            fail(exchange, 503, "Native HTTP capacity exhausted");
            return;
        }
        exchange.putAttachment(LEASE, lease);
        lease.onClose(() -> observation.stop(!exchange.isComplete()));
        watchConnection(exchange, lease);
        Charset bodyCharset = charset;
        long contentLength = exchange.getRequestContentLength();
        if (contentLength > resources.maxBodyBytes) {
            fail(exchange, 413, "Request body exceeds native HTTP limit");
            return;
        }
        // Reserve before reading/decoding. Unknown chunked bodies reserve the full configured
        // bound; four times the encoded size covers read buffers/copies and UTF-16 decoding.
        long requestBytes = exchange.isRequestComplete() ? 0
                : 4L * (contentLength < 0 ? resources.maxBodyBytes : contentLength);
        if (!lease.reserve(requestBytes)) {
            fail(exchange, 503, "Native HTTP buffer capacity exhausted");
            return;
        }
        exchange.dispatch(command -> submit(exchange, command), () -> {
            if (lease.closed()) return;
            ScheduledFuture<?> bodyDeadline = null;
            try {
                // Bodyless requests must not allocate InputStream.readAllBytes' 8 KiB scratch buffer.
                if (exchange.isRequestComplete()) {
                    execute(exchange, null);
                    return;
                }
                bodyDeadline = scheduler.schedule(() -> {
                    // An incomplete upload cannot safely reuse this HTTP/1 connection.
                    NativeHttpObservation.error(exchange, new TimeoutException("HTTP request body timeout"));
                    IoUtils.safeClose(exchange.getConnection());
                }, timeoutMs, TimeUnit.MILLISECONDS);
                ScheduledFuture<?> deadline = bodyDeadline;
                lease.onClose(() -> deadline.cancel(false));
                byte[] bytes = readBody(exchange);
                bodyDeadline.cancel(false);
                if (bytes.length > resources.maxBodyBytes) {
                    fail(exchange, 413, "Request body exceeds native HTTP limit");
                    return;
                }
                if (!lease.closed()) execute(exchange, bytes.length == 0 ? null : new String(bytes, bodyCharset));
            } catch (io.undertow.server.RequestTooBigException tooLarge) {
                fail(exchange, 413, "Request body exceeds native HTTP limit");
            } catch (IOException error) {
                NativeHttpObservation.error(exchange, error);
                fail(exchange, 400, "Invalid request body");
            } finally {
                if (bodyDeadline != null) bodyDeadline.cancel(false);
            }
        });
    }

    private static boolean eligible(HttpServerExchange exchange) {
        String path = exchange.getRequestURI();
        // HTTP/2 multiplexes requests on a connection; keep its stream cancellation in Servlet.
        if (!io.undertow.util.Protocols.HTTP_1_1.equals(exchange.getProtocol())
                || exchange.getRequestHeaders().contains(Headers.UPGRADE)
                || exchange.getRequestHeaders().contains(Headers.EXPECT)
                || !path.startsWith("/mock/") || !METHODS.contains(exchange.getRequestMethod().toString())
                || path.indexOf('%') >= 0 || path.indexOf(';') >= 0 || path.indexOf('\\') >= 0
                || path.contains("//") || path.contains("/./") || path.contains("/../")
                || path.endsWith("/.") || path.endsWith("/..")) return false;
        // Preserve servlet firewall/forwarded-header/SSE negotiation on ambiguous or special requests.
        for (var header : exchange.getRequestHeaders()) {
            String name = header.getHeaderName().toString();
            if (name.equalsIgnoreCase("Forwarded") || name.regionMatches(true, 0, "X-Forwarded-", 0, 12)) return false;
            for (String value : header) {
                for (int i = 0; i < value.length(); i++) {
                    char c = value.charAt(i);
                    if (c < 32 || c > 126) return false;
                }
            }
        }
        var accept = exchange.getRequestHeaders().get(Headers.ACCEPT);
        if (accept != null) {
            try {
                for (String value : accept) {
                    for (MediaType type : MediaType.parseMediaTypes(value)) {
                        if (type.getType().equalsIgnoreCase("text") && type.getSubtype().equalsIgnoreCase("event-stream")
                                && type.getQualityValue() > 0) return false;
                    }
                }
            } catch (IllegalArgumentException invalid) { return false; }
        }
        return true;
    }

    private byte[] readBody(HttpServerExchange exchange) throws IOException {
        if (exchange.getRequestContentLength() >= 0) {
            try (var input = new UndertowInputStream(exchange)) {
                return input.readNBytes(resources.maxBodyBytes + 1);
            }
        }
        // An unknown-length upload must stop at the bound without closing/draining a blocking
        // InputStream. UndertowInputStream.close() drains the remainder, which can wait forever
        // on a sender that deliberately never finishes. The rejection closes the connection.
        var channel = exchange.getRequestChannel();
        var body = new ByteArrayOutputStream(Math.min(8192, resources.maxBodyBytes + 1));
        var buffer = ByteBuffer.allocate(Math.min(8192, resources.maxBodyBytes + 1));
        while (body.size() <= resources.maxBodyBytes) {
            buffer.clear();
            buffer.limit(Math.min(buffer.capacity(), resources.maxBodyBytes + 1 - body.size()));
            int read = org.xnio.channels.Channels.readBlocking(channel, buffer, timeoutMs, TimeUnit.MILLISECONDS);
            if (read < 0) break;
            if (read == 0) throw new IOException("Request body read timeout");
            body.write(buffer.array(), 0, read);
        }
        return body.toByteArray();
    }

    private void execute(HttpServerExchange exchange, String body) {
        Trace trace = services.obtain().diagnostics().begin("HTTP");
        var headers = new HashMap<String, String>();
        exchange.getRequestHeaders().forEach(h -> headers.put(h.getHeaderName().toString(), h.getFirst()));
        String host = exchange.getRequestHeaders().getFirst("X-Original-Host");
        var request = MockRequest.builder().protocol(Protocol.HTTP)
                .method(exchange.getRequestMethod().toString()).path(exchange.getRequestURI().substring(5))
                .queryString(exchange.getQueryString().isEmpty() ? null : exchange.getQueryString())
                .headers(headers).body(body).targetHost(host == null || host.isBlank() ? "default" : host.trim())
                .clientIp(exchange.getSourceAddress().getAddress().getHostAddress()).trace(trace).build();
        trace.event("RECEIVED", "method", request.getMethod(), "path", request.getPath());
        CompletableFuture<PipelineResult> future = services.obtain().pipeline().executeAsync(request).toCompletableFuture();
        exchange.getAttachment(LEASE).onClose(() -> {
            if (!future.isDone()) future.cancel(true);
            trace.end("HTTP_EXCHANGE_CLOSED");
        });
        if (future.isDone() && !future.isCompletedExceptionally() && future.join().getDelayMs() <= 0) {
            PipelineResult result = future.join();
            EncodedResponse encoded = encodeResult(exchange, result);
            if (encoded != null) exchange.dispatch(exchange.getIoThread(), () -> respond(exchange, result, trace, encoded));
        } else {
            // Install callbacks after the current handler returns. In particular a completed future
            // must not race response writes against Undertow's dispatch bookkeeping.
            exchange.dispatch(exchange.getIoThread(), () -> {
                try { await(exchange, future, trace); }
                catch (RuntimeException error) { fail(exchange, 500, "Pipeline error"); }
            });
        }
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    private void await(HttpServerExchange exchange, CompletableFuture<PipelineResult> future, Trace trace) {
        AtomicBoolean terminal = new AtomicBoolean();
        AtomicReference<ScheduledFuture<?>> delay = new AtomicReference<>();
        AtomicReference<ScheduledFuture<?>> timeout = new AtomicReference<>();
        Runnable cleanup = () -> {
            terminal.set(true);
            cancel(delay); cancel(timeout);
            if (!future.isDone()) future.cancel(true);
        };
        exchange.addExchangeCompleteListener((ex, nextListener) -> {
            try { cleanup.run(); } finally { nextListener.proceed(); }
        });
        exchange.getAttachment(LEASE).onClose(cleanup);
        timeout.set(scheduler.schedule(() -> {
            if (terminal.compareAndSet(false, true)) {
                cleanup.run();
                NativeHttpObservation.error(exchange, new TimeoutException("HTTP request timeout"));
                trace.end("HTTP_TIMEOUT");
                exchange.getIoThread().execute(() -> write(exchange, MockResponse.builder()
                        .status(504).body("Request timeout").build()));
            }
        }, timeoutMs, TimeUnit.MILLISECONDS));
        if (terminal.get()) cancel(timeout);
        future.whenComplete((result, error) -> {
            if (terminal.get()) return;
            // Encoding a multi-megabyte body must not hold an inbound or outbound I/O thread.
            submit(exchange, () -> {
                if (terminal.get()) return;
                EncodedResponse encoded = error == null ? encodeResult(exchange, result) : null;
                if (error == null && encoded == null) { cleanup.run(); return; }
                Runnable finish = () -> {
                    if (!terminal.compareAndSet(false, true)) return;
                    cleanup.run();
                    exchange.getIoThread().execute(() -> {
                        if (error == null) respond(exchange, result, trace, encoded);
                        else {
                            NativeHttpObservation.error(exchange, error);
                            trace.failure("HTTP_PIPELINE_FAILED", error);
                            trace.end("PIPELINE_FAILED");
                            write(exchange, MockResponse.builder().status(500).body("Pipeline error").build());
                        }
                    });
                };
                if (error == null && result.getDelayMs() > 0) {
                    ScheduledFuture<?> task = scheduler.schedule(finish, result.getDelayMs(), TimeUnit.MILLISECONDS);
                    delay.set(task);
                    if (terminal.get()) cancel(delay);
                } else finish.run();
            });
        });
    }

    private static void cancel(AtomicReference<ScheduledFuture<?>> ref) {
        ScheduledFuture<?> task = ref.getAndSet(null);
        if (task != null) task.cancel(false);
    }

    private static void respond(HttpServerExchange exchange, PipelineResult result, Trace trace, EncodedResponse encoded) {
        if ("CONNECTION_RESET".equals(result.getFaultType())) {
            trace.end("FAULT_CONNECTION_RESET");
            IoUtils.safeClose(exchange.getConnection());
            exchange.endExchange();
            return;
        }
        trace.event("HTTP_RESULT_READY", "status", result.getResponse().getStatus());
        trace.end("RESULT_READY_NOT_DELIVERY_CONFIRMATION");
        try { write(exchange, result.getResponse(), encoded); }
        catch (RuntimeException error) { fail(exchange, 500, "Pipeline error"); }
    }

    private static void write(HttpServerExchange exchange, MockResponse response) {
        write(exchange, response, encode(response));
    }

    // Internal ownership carrier, never compared or exposed to application code.
    @SuppressWarnings("ArrayRecordComponent")
    private record EncodedResponse(byte[] bytes, String contentType) {}

    private static EncodedResponse encode(MockResponse response) {
        String value = response.getBody() == null ? "" : response.getBody();
        String trimmed = value.trim();
        String contentType = trimmed.startsWith("{") || trimmed.startsWith("[") ? "application/json" :
                trimmed.startsWith("<") ? "application/xml;charset=UTF-8" : "text/plain;charset=UTF-8";
        return new EncodedResponse(value.getBytes(StandardCharsets.UTF_8), contentType);
    }

    private static void write(HttpServerExchange exchange, MockResponse response, EncodedResponse encoded) {
        var lease = exchange.getAttachment(LEASE);
        if (lease != null && lease.closed()) return;
        exchange.setStatusCode(response.getStatus());
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, encoded.contentType());
        // Match the current MVC adapter's default Content-Type followed by additional rule headers.
        if (response.getHeaders() != null) {
            response.getHeaders().forEach((name, val) -> exchange.getResponseHeaders().add(new HttpString(name), val));
        }
        exchange.getResponseSender().send(ByteBuffer.wrap(encoded.bytes()));
    }

    private EncodedResponse encodeResult(HttpServerExchange exchange, PipelineResult result) {
        // A reset sends no body. A configured body must not change this fault into a 502/503.
        if ("CONNECTION_RESET".equals(result.getFaultType())) return new EncodedResponse(new byte[0], "text/plain");
        MockResponse response = result.getResponse();
        String value = response.getBody() == null ? "" : response.getBody();
        if (value.length() > resources.maxBodyBytes) {
            fail(exchange, 502, "Response body exceeds native HTTP limit");
            return null;
        }
        // UTF-8 needs at most three bytes per UTF-16 code unit. Reserve before allocation.
        if (!exchange.getAttachment(LEASE).reserve(3L * value.length())) {
            fail(exchange, 503, "Native HTTP buffer capacity exhausted");
            return null;
        }
        EncodedResponse encoded = encode(response);
        if (encoded.bytes().length > resources.maxBodyBytes) {
            fail(exchange, 502, "Response body exceeds native HTTP limit");
            return null;
        }
        return encoded;
    }

    private void submit(HttpServerExchange exchange, Runnable command) {
        try {
            resources.workers.execute(() -> {
                var lease = exchange.getAttachment(LEASE);
                if (lease != null && lease.closed()) return;
                try (var scope = NativeHttpObservation.scope(exchange)) { command.run(); }
                catch (RuntimeException failure) {
                    NativeHttpObservation.error(exchange, failure);
                    fail(exchange, 500, "Pipeline error");
                }
            });
        } catch (RejectedExecutionException rejected) {
            fail(exchange, 503, "Native HTTP worker capacity exhausted");
        }
    }

    private static void fail(HttpServerExchange exchange, int status, String message) {
        Runnable send = () -> {
            var lease = exchange.getAttachment(LEASE);
            if (lease != null && lease.closed()) return;
            // Do not drain a rejected or incomplete body and wait on a slow uploader.
            exchange.setPersistent(false);
            io.undertow.server.Connectors.terminateRequest(exchange);
            try { write(exchange, MockResponse.builder().status(status).body(message).build()); }
            catch (RuntimeException failure) {
                IoUtils.safeClose(exchange.getConnection());
                if (lease != null) lease.close();
            }
        };
        if (exchange.isInIoThread()) send.run();
        else exchange.dispatch(exchange.getIoThread(), send);
    }

    private static void watchConnection(HttpServerExchange exchange, NativeHttpResources.Lease lease) {
        var connection = exchange.getConnection();
        CloseWatch watch;
        synchronized (connection) {
            watch = connection.getAttachment(CLOSE_WATCH);
            if (watch == null) {
                watch = new CloseWatch();
                connection.putAttachment(CLOSE_WATCH, watch);
                CloseWatch registered = watch;
                connection.addCloseListener(closed -> registered.active.forEach(NativeHttpResources.Lease::close));
            }
            watch.active.add(lease);
        }
        CloseWatch registered = watch;
        lease.onClose(() -> registered.active.remove(lease));
        if (!connection.isOpen()) lease.close();
    }

}
