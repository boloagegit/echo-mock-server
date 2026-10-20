package com.echo.config;

import com.echo.diagnostics.TransactionDiagnostics;
import com.echo.diagnostics.TransactionDiagnostics.Trace;
import com.echo.entity.Protocol;
import com.echo.pipeline.HttpMockPipeline;
import com.echo.pipeline.MockRequest;
import com.echo.pipeline.MockResponse;
import com.echo.pipeline.PipelineResult;
import com.echo.pipeline.RequestWorkContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.observation.ObservationRegistry;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.io.Receiver;
import io.undertow.io.IoCallback;
import io.undertow.io.Sender;
import java.io.IOException;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import io.undertow.util.AttachmentKey;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.xnio.IoUtils;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.RejectedExecutionException;
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
    private static final AttachmentKey<NativeHttpDeadline> DEADLINE = AttachmentKey.create(NativeHttpDeadline.class);
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
        var deadline = new NativeHttpDeadline(exchange, lease, timeoutMs);
        lease.deadline(deadline);
        exchange.putAttachment(DEADLINE, deadline);
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
        if (exchange.isRequestComplete()) {
            dispatch(exchange, () -> execute(exchange, null));
            return;
        }
        // Undertow's non-blocking receiver waits on channel readiness, not an application
        // worker. Both declared and chunked bodies use the same finite allocation limit.
        Receiver receiver = exchange.getRequestReceiver();
        receiver.setMaxBufferSize(resources.maxBodyBytes);
        var body = new NativeHttpRequestBody(resources.maxBodyBytes, contentLength);
        lease.onClose(body::close);
        receiver.receivePartialBytes((ex, chunk, last) -> {
            if (!available(ex)) return;
            try {
                if (!body.append(chunk)) return;
            } catch (Receiver.RequestToLargeException tooLarge) {
                receiver.pause();
                body.close();
                NativeHttpObservation.error(ex, tooLarge);
                fail(ex, 413, "Request body exceeds native HTTP limit");
                return;
            }
            if (last) {
                // Match the full receiver's handling of Undertow's decoded-length header.
                var headers = ex.getRequestHeaders();
                if (headers.contains(Headers.X_CONTENT_LENGTH) && !headers.contains(Headers.CONTENT_LENGTH)) {
                    headers.put(Headers.CONTENT_LENGTH, body.size());
                }
                dispatch(ex, () -> {
                    var received = body.take();
                    if (received != null) execute(ex, received.decode(bodyCharset));
                });
            }
        },
                (ex, error) -> {
                    body.close();
                    NativeHttpObservation.error(ex, error);
                    if (error instanceof Receiver.RequestToLargeException
                            || error instanceof io.undertow.server.RequestTooBigException) {
                        fail(ex, 413, "Request body exceeds native HTTP limit");
                    } else {
                        fail(ex, 400, "Invalid request body");
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

    private void dispatch(HttpServerExchange exchange, Runnable work) {
        if (available(exchange)) {
            exchange.dispatch(command -> submit(exchange, command), work);
        }
    }

    private static boolean available(HttpServerExchange exchange) {
        var lease = exchange.getAttachment(LEASE);
        var deadline = exchange.getAttachment(DEADLINE);
        return (lease == null || !lease.closed()) && (deadline == null || !deadline.expired());
    }

    private void execute(HttpServerExchange exchange, String body) {
        if (!available(exchange)) {
            return;
        }
        Trace trace = services.obtain().diagnostics().begin("HTTP");
        var headers = new HashMap<String, String>();
        exchange.getRequestHeaders().forEach(h -> headers.put(h.getHeaderName().toString(), h.getFirst()));
        String host = exchange.getRequestHeaders().getFirst("X-Original-Host");
        var request = MockRequest.builder().protocol(Protocol.HTTP)
                .method(exchange.getRequestMethod().toString()).path(exchange.getRequestURI().substring(5))
                .queryString(exchange.getQueryString().isEmpty() ? null : exchange.getQueryString())
                .headers(headers).body(body).targetHost(host == null || host.isBlank() ? "default" : host.trim())
                .clientIp(exchange.getSourceAddress().getAddress().getHostAddress()).trace(trace)
                .responseBodyMemory(exchange.getAttachment(LEASE)::reserveResponseBody)
                .workContext(exchange.getAttachment(LEASE)).build();
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
        Runnable cleanup = () -> {
            terminal.set(true);
            cancel(delay);
            if (!future.isDone()) future.cancel(true);
        };
        exchange.addExchangeCompleteListener((ex, nextListener) -> {
            try { cleanup.run(); } finally { nextListener.proceed(); }
        });
        exchange.getAttachment(LEASE).onClose(cleanup);
        future.whenComplete((result, error) -> {
            if (terminal.get() || !available(exchange)) {
                return;
            }
            // Encoding a multi-megabyte body must not hold an inbound or outbound I/O thread.
            submit(exchange, () -> {
                if (terminal.get() || !available(exchange)) {
                    return;
                }
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

    private void respond(HttpServerExchange exchange, PipelineResult result, Trace trace, EncodedResponse encoded) {
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

    private void write(HttpServerExchange exchange, MockResponse response) {
        write(exchange, response, encode(response));
    }

    private record EncodedResponse(ByteBuffer first, Utf8ResponseBody stream, int length, String contentType) {}

    private static String responseContentType(String value) {
        int first = 0;
        while (first < value.length() && value.charAt(first) <= ' ') first++;
        char character = first == value.length() ? 0 : value.charAt(first);
        return character == '{' || character == '[' ? "application/json" :
                character == '<' ? "application/xml;charset=UTF-8" : "text/plain;charset=UTF-8";
    }

    private static EncodedResponse encode(MockResponse response) {
        String value = response.getBody() == null ? "" : response.getBody();
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return new EncodedResponse(ByteBuffer.wrap(bytes), null, bytes.length, responseContentType(value));
    }

    private void write(HttpServerExchange exchange, MockResponse response, EncodedResponse encoded) {
        if (!available(exchange)) return;
        exchange.setStatusCode(response.getStatus());
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, encoded.contentType());
        // Match the current MVC adapter's default Content-Type followed by additional rule headers.
        if (response.getHeaders() != null) {
            response.getHeaders().forEach((name, val) -> exchange.getResponseHeaders().add(new HttpString(name), val));
        }
        if (encoded.stream() == null) {
            exchange.getResponseSender().send(encoded.first());
            return;
        }
        if (!exchange.getResponseHeaders().contains(Headers.CONTENT_LENGTH)
                && !exchange.getResponseHeaders().contains(Headers.TRANSFER_ENCODING)) {
            exchange.setResponseContentLength(encoded.length());
        }
        exchange.getResponseSender().send(encoded.stream().batch(), new IoCallback() {
            @Override
            public void onComplete(HttpServerExchange completed, Sender sender) {
                if (encoded.stream().finished()) {
                    completed.endExchange();
                } else if (available(completed)) {
                    // Reuse the buffer only after the previous send completed. Encoding never blocks an I/O thread.
                    submit(completed, () -> {
                        encoded.stream().prepareBatch();
                        completed.getIoThread().execute(() -> {
                            if (available(completed)) sender.send(encoded.stream().batch(), this);
                        });
                    });
                }
            }

            @Override
            public void onException(HttpServerExchange failed, Sender sender, IOException exception) {
                NativeHttpObservation.error(failed, exception);
                IoUtils.safeClose(failed.getConnection());
                failed.getAttachment(LEASE).close();
                failed.endExchange();
            }
        });
    }

    private EncodedResponse encodeResult(HttpServerExchange exchange, PipelineResult result) {
        // A reset sends no body. A configured body must not change this fault into a 502/503.
        if ("CONNECTION_RESET".equals(result.getFaultType())) {
            return new EncodedResponse(ByteBuffer.allocate(0), null, 0, "text/plain");
        }
        MockResponse response = result.getResponse();
        String value = response.getBody() == null ? "" : response.getBody();
        if (value.length() > resources.maxBodyBytes) {
            fail(exchange, 502, "Response body exceeds native HTTP limit");
            return null;
        }
        var lease = exchange.getAttachment(LEASE);
        boolean streamCandidate = value.length() > 256 * 1024 && response.getStatus() >= 200
                && response.getStatus() != 204 && response.getStatus() != 304;
        if (!streamCandidate) return encodeSinglePass(exchange, response, value, 0);
        if (!lease.reserveEncodedBody(Utf8ResponseBody.BUFFER_BYTES)) {
            fail(exchange, 503, "Native HTTP buffer capacity exhausted");
            return null;
        }
        Utf8ResponseBody body = new Utf8ResponseBody(value);
        if (body.needsSinglePass()) {
            return encodeSinglePass(exchange, response, value, Utf8ResponseBody.BUFFER_BYTES);
        }
        body.prepareBatch();
        // No UTF-8 expansion: every consumed UTF-16 unit produced exactly one byte.
        return new EncodedResponse(null, body, value.length(), responseContentType(value));
    }

    private EncodedResponse encodeSinglePass(HttpServerExchange exchange, MockResponse response,
                                             String value, int preflightBytes) {
        var lease = exchange.getAttachment(LEASE);
        // JDK Unicode encoding may trim its initial allocation; cover both arrays until it returns.
        if (!lease.reserveEncodedBody(6L * value.length() + preflightBytes)) {
            fail(exchange, 503, "Native HTTP buffer capacity exhausted");
            return null;
        }
        EncodedResponse encoded = encode(response);
        if (encoded.length() > resources.maxBodyBytes) {
            fail(exchange, 502, "Response body exceeds native HTTP limit");
            return null;
        }
        if (!lease.reserveEncodedBody(encoded.length())) return null;
        return encoded;
    }

    private final class Work implements NativeHttpResources.QueuedWork {
        private final HttpServerExchange exchange;
        private final RequestWorkContext.Scope ownership;
        private Runnable command;
        private volatile boolean started;

        private Work(HttpServerExchange exchange, Runnable command, RequestWorkContext.Scope ownership) {
            this.exchange = exchange;
            this.command = command;
            this.ownership = ownership;
        }

        @Override
        public void run() {
            started = true;
            Runnable task = command;
            command = null; // Cleanup must not retain an uploaded byte array until a slow response ends.
            try (var work = ownership) {
                if (!available(exchange)) return;
                try (var scope = NativeHttpObservation.scope(exchange)) { task.run(); }
            }
            catch (CancellationException cancelled) {
                // The deadline/connection close owns the wire response. Work exits here.
            }
            catch (RuntimeException failure) {
                NativeHttpObservation.error(exchange, failure);
                fail(exchange, 500, "Pipeline error");
            }
        }

        private void removeIfQueued() {
            if (!started && resources.workers.remove(this)) {
                discard();
            }
        }

        @Override
        public void discard() {
            command = null;
            ownership.close();
        }
    }

    private void submit(HttpServerExchange exchange, Runnable command) {
        var lease = exchange.getAttachment(LEASE);
        RequestWorkContext.Scope ownership;
        try { ownership = lease.begin(); }
        catch (CancellationException cancelled) { return; }
        // Own the queue-to-worker handoff too: dequeued work may not have started
        // when cancellation tries to unlink it from the executor queue.
        var work = new Work(exchange, command, ownership);
        try {
            resources.workers.execute(work);
            lease.onClose(work::removeIfQueued);
        } catch (RejectedExecutionException rejected) {
            work.discard();
            fail(exchange, 503, "Native HTTP worker capacity exhausted");
        }
    }

    private void fail(HttpServerExchange exchange, int status, String message) {
        Runnable send = () -> {
            if (exchange.isResponseStarted()) {
                IoUtils.safeClose(exchange.getConnection());
                exchange.endExchange();
                return;
            }
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
