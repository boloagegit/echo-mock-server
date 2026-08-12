package com.echo.service;

import com.echo.dto.HttpForwardMetricsDto;
import com.echo.pipeline.MockRequest;
import com.echo.pipeline.MockResponse;
import com.echo.util.CancellableStages;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelOption;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.publisher.SynchronousSink;
import reactor.netty.ByteBufFlux;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionPoolMetrics;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.resources.LoopResources;

import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Executes outbound requests through saved HTTP profiles. */
@Component
@Slf4j
public final class HttpOutboundForwarder {

    private static final String ORIGINAL_HOST_HEADER = "X-Original-Host";
    /** Zero preserves compatibility by disabling the application-level concurrency cap. */
    private static final int DEFAULT_MAX_CONCURRENT = 0;
    private static final int DEFAULT_MAX_CONNECTIONS = 1_000;
    private static final int DEFAULT_MAX_PENDING_REQUESTS = 1_000;
    private static final int DEFAULT_MAX_RESPONSE_BODY_BYTES = 10 * 1024 * 1024;
    private static final int DEFAULT_MAX_BUFFERED_RESPONSE_BYTES = 128 * 1024 * 1024;
    private static final int DEFAULT_POOL_ACQUIRE_TIMEOUT_MS = 3_000;
    private static final int DEFAULT_LEGACY_CONNECT_TIMEOUT_SECONDS = 5;
    private static final int DEFAULT_LEGACY_READ_TIMEOUT_SECONDS = 30;
    private static final int DEFAULT_OVERLOAD_BACKOFF_MS = 50;
    private static final int DEFAULT_IDLE_CONNECTION_TIMEOUT_SECONDS = 30;
    private static final long REJECTION_WARNING_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final AtomicInteger CLIENT_SEQUENCE = new AtomicInteger();
    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade");

    private final HttpTargetConnectionService connectionService;
    private final Map<String, ClientHolder> clients = new ConcurrentHashMap<>();
    private final Set<ClientHolder> retiredClients = ConcurrentHashMap.newKeySet();
    private final Map<String, TargetMetrics> targetMetrics = new ConcurrentHashMap<>();
    private final Semaphore forwardingSlots;
    private final AtomicInteger activeForwards = new AtomicInteger();
    private final LongAdder completedForwards = new LongAdder();
    private final LongAdder cancelledForwards = new LongAdder();
    private final ScheduledExecutorService forwardScheduler;
    private final ReactiveConnectionLimiter connectionLimiter;
    private final ClientHolder originalHostClient;
    private final int maxConnections;
    private final int maxPendingRequests;
    private final int maxResponseBodyBytes;
    private final ResponseBufferBudget responseBufferBudget;
    private final int poolAcquireTimeoutMs;
    private final int overloadBackoffMs;
    private final int idleConnectionTimeoutSeconds;
    private final LongAdder rejectedForwards = new LongAdder();
    private final AtomicLong lastRejectionWarning = new AtomicLong();

    /** Test-compatible constructor using production defaults. */
    public HttpOutboundForwarder(HttpTargetConnectionService connectionService) {
        this(connectionService, DEFAULT_MAX_CONCURRENT, DEFAULT_MAX_CONNECTIONS,
                DEFAULT_MAX_PENDING_REQUESTS, DEFAULT_MAX_RESPONSE_BODY_BYTES,
                DEFAULT_MAX_BUFFERED_RESPONSE_BYTES,
                DEFAULT_POOL_ACQUIRE_TIMEOUT_MS, DEFAULT_LEGACY_CONNECT_TIMEOUT_SECONDS,
                DEFAULT_LEGACY_READ_TIMEOUT_SECONDS, DEFAULT_OVERLOAD_BACKOFF_MS,
                DEFAULT_IDLE_CONNECTION_TIMEOUT_SECONDS);
    }

    public HttpOutboundForwarder(
            HttpTargetConnectionService connectionService,
            int maxConcurrent,
            int maxConnections,
            int poolAcquireTimeoutMs,
            int legacyConnectTimeoutSeconds,
            int legacyReadTimeoutSeconds) {
        this(connectionService, maxConcurrent, maxConnections,
                DEFAULT_MAX_PENDING_REQUESTS, DEFAULT_MAX_RESPONSE_BODY_BYTES,
                DEFAULT_MAX_BUFFERED_RESPONSE_BYTES, poolAcquireTimeoutMs,
                legacyConnectTimeoutSeconds, legacyReadTimeoutSeconds, 0,
                DEFAULT_IDLE_CONNECTION_TIMEOUT_SECONDS);
    }

    public HttpOutboundForwarder(
            HttpTargetConnectionService connectionService,
            int maxConcurrent,
            int maxConnections,
            int poolAcquireTimeoutMs,
            int legacyConnectTimeoutSeconds,
            int legacyReadTimeoutSeconds,
            int overloadBackoffMs) {
        this(connectionService, maxConcurrent, maxConnections,
                DEFAULT_MAX_PENDING_REQUESTS, DEFAULT_MAX_RESPONSE_BODY_BYTES,
                DEFAULT_MAX_BUFFERED_RESPONSE_BYTES, poolAcquireTimeoutMs,
                legacyConnectTimeoutSeconds, legacyReadTimeoutSeconds,
                overloadBackoffMs, DEFAULT_IDLE_CONNECTION_TIMEOUT_SECONDS);
    }

    public HttpOutboundForwarder(
            HttpTargetConnectionService connectionService,
            int maxConcurrent,
            int maxConnections,
            int maxPendingRequests,
            int maxResponseBodyBytes,
            int poolAcquireTimeoutMs,
            int legacyConnectTimeoutSeconds,
            int legacyReadTimeoutSeconds,
            int overloadBackoffMs,
            int idleConnectionTimeoutSeconds) {
        this(connectionService, maxConcurrent, maxConnections, maxPendingRequests,
                maxResponseBodyBytes, DEFAULT_MAX_BUFFERED_RESPONSE_BYTES,
                poolAcquireTimeoutMs, legacyConnectTimeoutSeconds, legacyReadTimeoutSeconds,
                overloadBackoffMs, idleConnectionTimeoutSeconds);
    }

    @Autowired
    public HttpOutboundForwarder(
            HttpTargetConnectionService connectionService,
            @Value("${echo.http.forward.max-concurrent:0}") int maxConcurrent,
            @Value("${echo.http.forward.max-connections:1000}") int maxConnections,
            @Value("${echo.http.forward.max-pending-requests:1000}") int maxPendingRequests,
            @Value("${echo.http.forward.max-response-body-bytes:10485760}") int maxResponseBodyBytes,
            @Value("${echo.http.forward.max-buffered-response-bytes:134217728}") int maxBufferedResponseBytes,
            @Value("${echo.http.forward.pool-acquire-timeout-ms:3000}") int poolAcquireTimeoutMs,
            @Value("${echo.http.forward.legacy-connect-timeout-seconds:5}") int legacyConnectTimeoutSeconds,
            @Value("${echo.http.forward.legacy-read-timeout-seconds:30}") int legacyReadTimeoutSeconds,
            @Value("${echo.http.forward.overload-backoff-ms:50}") int overloadBackoffMs,
            @Value("${echo.http.forward.idle-connection-timeout-seconds:30}") int idleConnectionTimeoutSeconds) {
        if (maxConcurrent < 0) {
            throw new IllegalArgumentException("maxConcurrent must not be negative");
        }
        requirePositive("maxConnections", maxConnections);
        requireNotNegative("maxPendingRequests", maxPendingRequests);
        requirePositive("maxResponseBodyBytes", maxResponseBodyBytes);
        requirePositive("maxBufferedResponseBytes", maxBufferedResponseBytes);
        requirePositive("poolAcquireTimeoutMs", poolAcquireTimeoutMs);
        requirePositive("legacyConnectTimeoutSeconds", legacyConnectTimeoutSeconds);
        requirePositive("legacyReadTimeoutSeconds", legacyReadTimeoutSeconds);
        requirePositive("idleConnectionTimeoutSeconds", idleConnectionTimeoutSeconds);
        if (overloadBackoffMs < 0) {
            throw new IllegalArgumentException("overloadBackoffMs must not be negative");
        }
        this.connectionService = connectionService;
        this.maxConnections = maxConnections;
        this.maxPendingRequests = maxPendingRequests;
        this.maxResponseBodyBytes = maxResponseBodyBytes;
        this.responseBufferBudget = new ResponseBufferBudget(maxBufferedResponseBytes);
        this.poolAcquireTimeoutMs = poolAcquireTimeoutMs;
        this.overloadBackoffMs = overloadBackoffMs;
        this.idleConnectionTimeoutSeconds = idleConnectionTimeoutSeconds;
        this.forwardingSlots = maxConcurrent == 0 ? null : new Semaphore(maxConcurrent);
        if (maxConcurrent == 0) {
            log.warn("HTTP forwarding application concurrency limit is disabled; "
                    + "connection-pool and timeout limits remain active");
        }
        this.forwardScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "http-forward-maintenance");
            thread.setDaemon(true);
            return thread;
        });
        this.connectionLimiter = new ReactiveConnectionLimiter(
                maxConnections, maxPendingRequests, forwardScheduler);
        this.originalHostClient = createClient(
                legacyConnectTimeoutSeconds, legacyReadTimeoutSeconds, false);
    }

    public MockResponse forward(MockRequest request, Long connectionId, boolean useDefault) {
        return forwardAsync(request, connectionId, useDefault).toCompletableFuture().join();
    }

    public CompletionStage<MockResponse> forwardAsync(MockRequest request,
                                                       Long connectionId,
                                                       boolean useDefault) {
        try {
            HttpTargetConnectionService.ResolvedTarget target = useDefault
                    ? connectionService.resolveDefault().orElseThrow(
                            () -> new IllegalArgumentException("DEFAULT_HTTP_CONNECTION_NOT_FOUND"))
                    : connectionService.resolveEnabled(connectionId);
            TargetMetrics metrics = metricsFor("profile:" + target.id(), target.name());
            return submit(() -> exchange(request, target, true), true, metrics);
        } catch (Exception e) {
            return CompletableFuture.completedFuture(proxyError(e, true));
        }
    }

    /**
     * Forwards an unmatched request through the configured default connection.
     * An empty result means no enabled default exists, so the caller may use its
     * legacy fallback without turning a normal configuration choice into a 502.
     */
    public Optional<MockResponse> forwardDefault(MockRequest request) {
        return forwardDefaultAsync(request).toCompletableFuture().join();
    }

    public CompletionStage<Optional<MockResponse>> forwardDefaultAsync(MockRequest request) {
        try {
            Optional<HttpTargetConnectionService.ResolvedTarget> target = connectionService.resolveDefault();
            if (target.isEmpty()) {
                return CompletableFuture.completedFuture(Optional.empty());
            }
            HttpTargetConnectionService.ResolvedTarget resolved = target.orElseThrow();
            TargetMetrics metrics = metricsFor("profile:" + resolved.id(), resolved.name());
            return CancellableStages.map(
                    submit(() -> exchange(request, resolved, false), false, metrics),
                    Optional::of);
        } catch (Exception e) {
            return CompletableFuture.completedFuture(Optional.of(proxyError(e, false)));
        }
    }

    /** Legacy X-Original-Host forwarding with the same bounded execution policy. */
    public MockResponse forwardOriginalHost(MockRequest request, boolean matched) {
        return forwardOriginalHostAsync(request, matched).toCompletableFuture().join();
    }

    public CompletionStage<MockResponse> forwardOriginalHostAsync(MockRequest request, boolean matched) {
        String targetHost = request.getTargetHost();
        if (targetHost == null || targetHost.isBlank() || "default".equals(targetHost)) {
            return CompletableFuture.completedFuture(proxyError(
                    new IllegalArgumentException("X-Original-Host is required for this forwarding rule"),
                    matched));
        }
        TargetMetrics metrics = metricsFor("original-host", "X-Original-Host");
        return submit(() -> exchangeOriginalHost(request, targetHost, matched),
                matched, metrics);
    }

    public ConnectionTestResult test(Long id) {
        long started = System.nanoTime();
        try {
            HttpTargetConnectionService.ResolvedTarget target = connectionService.resolveEnabled(id);
            ClientUse holder = clientFor(target);
            HttpHeaders headers = new HttpHeaders();
            applyAuthentication(headers, target);
            ForwardResponse response = exchangeResponse(
                    holder, URI.create(target.baseUrl()), HttpMethod.GET, headers, null)
                    .toFuture().join();
            return new ConnectionTestResult(true, elapsedMillis(started), response.status(), null);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            return new ConnectionTestResult(false, elapsedMillis(started), null,
                    safeError(unwrap(e)));
        }
    }

    private Mono<MockResponse> exchange(MockRequest request,
                                        HttpTargetConnectionService.ResolvedTarget target,
                                        boolean matched) {
        String url = joinUrl(target.baseUrl(), request.getPath(), request.getQueryString());
        log.debug("HTTP profile forwarding via '{}' to: {} {}", target.name(), request.getMethod(), url);
        HttpHeaders headers = copyHeaders(request.getHeaders(), true);
        applyAuthentication(headers, target);
        HttpMethod method = HttpMethod.valueOf(request.getMethod().toUpperCase(Locale.ROOT));
        return exchangeResponse(clientFor(target), URI.create(url), method, headers, request.getBody())
                .map(response -> MockResponse.builder()
                        .status(response.status())
                        .body(response.body())
                        .matched(matched)
                        .forwarded(true)
                        .build());
    }

    private ClientUse clientFor(HttpTargetConnectionService.ResolvedTarget target) {
        String key = target.cacheKey();
        while (true) {
            clients.entrySet().removeIf(entry -> {
                boolean stale = entry.getKey().startsWith(target.id() + ":")
                        && !entry.getKey().equals(key);
                if (stale) retireClient(entry.getValue());
                return stale;
            });
            ClientHolder holder = clients.computeIfAbsent(key, ignored -> createClient(target));
            ClientUse use = holder.tryUse();
            if (use != null) return use;
            clients.remove(key, holder);
        }
    }

    private ClientHolder createClient(HttpTargetConnectionService.ResolvedTarget target) {
        return createClient(target.connectTimeoutSeconds(), target.readTimeoutSeconds(),
                target.tlsVerificationEnabled());
    }

    private ClientHolder createClient(int connectTimeoutSeconds,
                                      int readTimeoutSeconds,
                                      boolean tlsVerificationEnabled) {
        try {
            PoolMetricsCollector metricsCollector = new PoolMetricsCollector();
            ConnectionProvider connectionProvider = ConnectionProvider.builder(
                            "echo-http-" + CLIENT_SEQUENCE.incrementAndGet())
                    // Reactor Netty creates one pool per remote host. The shared
                    // limiter enforces maxConnections across every HTTP target.
                    .maxConnections(maxConnections)
                    .pendingAcquireMaxCount(Math.max(1, maxPendingRequests))
                    .pendingAcquireTimeout(Duration.ofMillis(poolAcquireTimeoutMs))
                    .maxIdleTime(Duration.ofSeconds(idleConnectionTimeoutSeconds))
                    .evictInBackground(Duration.ofSeconds(idleConnectionTimeoutSeconds))
                    .metrics(true, () -> metricsCollector)
                    .build();
            HttpClient httpClient = HttpClient.create(connectionProvider)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                            Math.toIntExact(Math.min(Integer.MAX_VALUE,
                                    TimeUnit.SECONDS.toMillis(connectTimeoutSeconds))))
                    .responseTimeout(Duration.ofSeconds(readTimeoutSeconds))
                    .compress(true)
                    .followRedirect(true);
            if (!tlsVerificationEnabled) {
                var sslContext = SslContextBuilder.forClient()
                        .trustManager(InsecureTrustManagerFactory.INSTANCE)
                        .build();
                httpClient = httpClient.secure(spec -> spec
                        .sslContext(sslContext)
                        .handlerConfigurator(handler -> {
                            var parameters = handler.engine().getSSLParameters();
                            parameters.setEndpointIdentificationAlgorithm(null);
                            handler.engine().setSSLParameters(parameters);
                        }));
            }
            return new ClientHolder(httpClient, connectionProvider, metricsCollector,
                    retiredClients::remove);
        } catch (Exception e) {
            throw new IllegalStateException("HTTP_CLIENT_INITIALIZATION_FAILED", e);
        }
    }

    private Mono<MockResponse> exchangeOriginalHost(MockRequest request,
                                                    String targetHost,
                                                    boolean matched) {
        String baseUrl = "https://" + targetHost;
        String url = joinUrl(baseUrl, request.getPath(), request.getQueryString());
        log.debug("X-Original-Host forwarding to: {} {}", request.getMethod(), url);
        HttpHeaders headers = copyHeaders(request.getHeaders(), false);
        HttpMethod method = HttpMethod.valueOf(request.getMethod().toUpperCase(Locale.ROOT));
        ClientUse client = originalHostClient.tryUse();
        if (client == null) {
            return Mono.error(new IllegalStateException("HTTP_CLIENT_CLOSED"));
        }
        return exchangeResponse(client, URI.create(url), method, headers, request.getBody())
                .map(response -> MockResponse.builder()
                        .status(response.status())
                        .body(response.body())
                        .matched(matched)
                        .forwarded(true)
                        .build());
    }

    private Mono<ForwardResponse> exchangeResponse(ClientUse holder,
                                                    URI uri,
                                                    HttpMethod method,
                                                    HttpHeaders headers,
                                                    String body) {
        return connectionLimiter.acquire(Duration.ofMillis(poolAcquireTimeoutMs))
                .flatMap(permit -> exchangeResponseWithPermit(
                        holder, uri, method, headers, body)
                        .doFinally(ignored -> permit.close()))
                .doFinally(ignored -> holder.close());
    }

    private Mono<ForwardResponse> exchangeResponseWithPermit(ClientUse holder,
                                                              URI uri,
                                                              HttpMethod method,
                                                              HttpHeaders headers,
                                                              String body) {
        byte[] payload = body == null ? null : body.getBytes(contentCharset(headers));
        HttpClient.RequestSender request = holder.httpClient()
                .headers(outbound -> {
                    headers.forEach(outbound::set);
                    if (payload != null) {
                        outbound.setInt(HttpHeaderNames.CONTENT_LENGTH, payload.length);
                    }
                })
                .request(io.netty.handler.codec.http.HttpMethod.valueOf(method.name()))
                .uri(uri);
        HttpClient.ResponseReceiver<?> prepared = payload == null
                ? request
                : request.send(Mono.fromSupplier(() -> Unpooled.wrappedBuffer(payload)));
        return prepared.response((response, responseBody) -> {
            int status = response.status().code();
            String declaredLength = response.responseHeaders().get(HttpHeaderNames.CONTENT_LENGTH);
            if (declaredLength != null && exceedsResponseLimit(declaredLength)) {
                return Mono.error(new ResponseBodyTooLargeException(maxResponseBodyBytes));
            }
            return readResponseBody(responseBody, contentCharset(
                            response.responseHeaders().get(HttpHeaderNames.CONTENT_TYPE)))
                    .map(value -> new ForwardResponse(status, value))
                    .switchIfEmpty(Mono.just(new ForwardResponse(status, null)));
        }).single();
    }

    private boolean exceedsResponseLimit(String declaredLength) {
        try {
            return Long.parseLong(declaredLength) > maxResponseBodyBytes;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private Mono<String> readResponseBody(ByteBufFlux responseBody, Charset charset) {
        AtomicInteger receivedBytes = new AtomicInteger();
        ResponseBufferBudget.Reservation reservation = responseBufferBudget.openReservation();
        StreamingTextAccumulator output = new StreamingTextAccumulator(charset);
        return responseBody.asByteArray()
                .handle((byte[] chunk, SynchronousSink<Integer> sink) -> {
                    long nextSize = (long) receivedBytes.get() + chunk.length;
                    if (nextSize > maxResponseBodyBytes) {
                        sink.error(new ResponseBodyTooLargeException(maxResponseBodyBytes));
                        return;
                    }
                    if (!reservation.tryReserve(chunk.length)) {
                        sink.error(new ResponseBufferCapacityException(
                                responseBufferBudget.maximumBytes()));
                        return;
                    }
                    output.append(chunk);
                    receivedBytes.set((int) nextSize);
                    sink.next(chunk.length);
                })
                .then(Mono.defer(() -> receivedBytes.get() == 0
                        ? Mono.empty()
                        : Mono.fromSupplier(output::finish)))
                .doFinally(ignored -> reservation.close());
    }

    private static Charset contentCharset(HttpHeaders headers) {
        try {
            MediaType contentType = headers.getContentType();
            return contentType == null || contentType.getCharset() == null
                    ? StandardCharsets.UTF_8 : contentType.getCharset();
        } catch (IllegalArgumentException ignored) {
            return StandardCharsets.UTF_8;
        }
    }

    private static Charset contentCharset(String contentType) {
        if (contentType == null || contentType.isBlank()) return StandardCharsets.UTF_8;
        try {
            Charset charset = MediaType.parseMediaType(contentType).getCharset();
            return charset == null ? StandardCharsets.UTF_8 : charset;
        } catch (IllegalArgumentException ignored) {
            return StandardCharsets.UTF_8;
        }
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    private CompletionStage<MockResponse> submit(Supplier<Mono<MockResponse>> action,
                                                  boolean matched,
                                                  TargetMetrics metrics) {
        if (forwardingSlots != null && !forwardingSlots.tryAcquire()) {
            rejectedForwards.increment();
            metrics.recordCapacityRejection();
            warnRejectedForward();
            MockResponse overloadResponse = proxyError(
                    new IllegalStateException("HTTP_FORWARD_CAPACITY_EXHAUSTED"), matched);
            CompletableFuture<MockResponse> rejected = new CompletableFuture<>();
            if (overloadBackoffMs == 0 || forwardScheduler.isShutdown()) {
                rejected.complete(overloadResponse);
            } else {
                forwardScheduler.schedule(() -> rejected.complete(overloadResponse),
                        overloadBackoffMs, TimeUnit.MILLISECONDS);
            }
            return rejected;
        }

        activeForwards.incrementAndGet();
        return Mono.defer(action)
                .onErrorResume(error -> {
                    Throwable cause = unwrap(error);
                    if (cause instanceof ReactiveConnectionLimiter.CapacityException
                            || cause instanceof ResponseBufferCapacityException) {
                        rejectedForwards.increment();
                        warnRejectedForward();
                    }
                    metrics.recordFailure(cause);
                    log.debug("HTTP proxy error: {}", cause.getMessage());
                    return Mono.just(proxyError(cause, matched));
                })
                .doFinally(signal -> finishForward(signal))
                .toFuture();
    }

    private void finishForward(SignalType signal) {
        activeForwards.decrementAndGet();
        if (signal == SignalType.CANCEL) {
            cancelledForwards.increment();
        } else {
            completedForwards.increment();
        }
        if (forwardingSlots != null) forwardingSlots.release();
    }

    private void warnRejectedForward() {
        long now = System.nanoTime();
        long previous = lastRejectionWarning.get();
        if (now - previous >= REJECTION_WARNING_INTERVAL_NANOS
                && lastRejectionWarning.compareAndSet(previous, now)) {
            log.warn("HTTP forward capacity exhausted: active={}, rejected={}",
                    activeForwards.get(), rejectedForwards.sum());
        }
    }

    void cleanupConnections(long idleMillis) {
        // Reactor Netty evicts expired/idle connections in the background. Zero is
        // retained as the deterministic test/admin hook for closing currently idle pools.
        if (idleMillis > 0) return;
        clientHolders().forEach(ClientHolder::disposePools);
    }

    public HttpForwardMetricsDto metricsSnapshot() {
        int leased = 0;
        int pending = 0;
        int available = 0;
        long capacity = 0;
        var holders = clientHolders();
        for (ClientHolder holder : holders) {
            PoolSnapshot snapshot = holder.metricsCollector().snapshot();
            leased += snapshot.acquired();
            pending += snapshot.pending();
            available += snapshot.idle();
            capacity += snapshot.capacity();
        }
        pending += connectionLimiter.pendingCount();

        Map<String, HttpForwardMetricsDto.Target> targets = new LinkedHashMap<>();
        new TreeMap<>(targetMetrics).forEach((key, metrics) -> targets.put(key, metrics.snapshot()));
        return new HttpForwardMetricsDto(
                activeForwards.get(),
                LoopResources.DEFAULT_IO_WORKER_COUNT,
                completedForwards.sum(),
                cancelledForwards.sum(),
                rejectedForwards.sum(),
                holders.size(),
                new HttpForwardMetricsDto.Pool(
                        leased, pending, available,
                        (int) Math.min(Integer.MAX_VALUE, capacity),
                        maxConnections, maxPendingRequests, maxResponseBodyBytes,
                        responseBufferBudget.reservedBytes(),
                        responseBufferBudget.maximumBytes(),
                        poolAcquireTimeoutMs,
                        idleConnectionTimeoutSeconds, idleConnectionTimeoutSeconds),
                Map.copyOf(targets));
    }

    private TargetMetrics metricsFor(String key, String name) {
        TargetMetrics metrics = targetMetrics.get(key);
        if (metrics == null) {
            TargetMetrics created = new TargetMetrics(name);
            TargetMetrics existing = targetMetrics.putIfAbsent(key, created);
            metrics = existing == null ? created : existing;
        }
        metrics.setName(name);
        return metrics;
    }

    private java.util.List<ClientHolder> clientHolders() {
        Set<ClientHolder> holders = new java.util.LinkedHashSet<>(clients.values());
        holders.addAll(retiredClients);
        holders.add(originalHostClient);
        return List.copyOf(holders);
    }

    private void retireClient(ClientHolder holder) {
        retiredClients.add(holder);
        holder.retire();
    }

    public void evict(Long connectionId) {
        if (connectionId == null) return;
        String prefix = connectionId + ":";
        clients.entrySet().removeIf(entry -> {
            boolean matches = entry.getKey().startsWith(prefix);
            if (matches) retireClient(entry.getValue());
            return matches;
        });
    }

    static HttpHeaders copyHeaders(Map<String, String> source,
                                   boolean stripAuthorization) {
        HttpHeaders headers = new HttpHeaders();
        if (source == null) return headers;
        Set<String> blocked = new HashSet<>(HOP_BY_HOP_HEADERS);
        blocked.add(ORIGINAL_HOST_HEADER.toLowerCase(Locale.ROOT));
        blocked.add("host");
        blocked.add("content-length");
        source.forEach((name, value) -> {
            if (name.equalsIgnoreCase(HttpHeaders.CONNECTION) && value != null) {
                Arrays.stream(value.split(","))
                        .map(String::trim)
                        .filter(token -> !token.isEmpty())
                        .map(token -> token.toLowerCase(Locale.ROOT))
                        .forEach(blocked::add);
            }
        });
        source.forEach((name, value) -> {
            if (!blocked.contains(name.toLowerCase(Locale.ROOT))
                    && !(stripAuthorization && name.equalsIgnoreCase("authorization"))) {
                headers.add(name, value);
            }
        });
        return headers;
    }

    private static void applyAuthentication(HttpHeaders headers,
                                            HttpTargetConnectionService.ResolvedTarget target) {
        if ("BASIC".equals(target.authType())) {
            headers.setBasicAuth(target.username(), target.secret() == null ? "" : target.secret());
        } else if ("BEARER".equals(target.authType()) && target.secret() != null) {
            headers.setBearerAuth(target.secret());
        }
    }

    static String joinUrl(String baseUrl, String path, String queryString) {
        String effectivePath = path == null || path.isBlank() ? "/" : path;
        if (!effectivePath.startsWith("/")) effectivePath = "/" + effectivePath;
        String url = baseUrl + effectivePath;
        if (queryString != null && !queryString.isEmpty()) url += "?" + queryString;
        return url;
    }

    private static MockResponse proxyError(Throwable exception, boolean matched) {
        String error = safeError(exception);
        return MockResponse.builder()
                .status(502)
                .body("Proxy error: " + error)
                .matched(matched)
                .forwarded(true)
                .proxyError(error)
                .build();
    }

    private static String safeError(Throwable exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) message = exception.getClass().getSimpleName();
        return message.length() <= 300 ? message : message.substring(0, 300);
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    @PreDestroy
    void closeClients() {
        forwardScheduler.shutdownNow();
        clientHolders().forEach(ClientHolder::close);
        clients.clear();
        retiredClients.clear();
    }

    int activeForwardCount() {
        return activeForwards.get();
    }

    long rejectedForwardCount() {
        return rejectedForwards.sum();
    }

    private static void requirePositive(String name, int value) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
    }

    private static void requireNotNegative(String name, int value) {
        if (value < 0) throw new IllegalArgumentException(name + " must not be negative");
    }

    private static final class ClientHolder {
        private final HttpClient httpClient;
        private final ConnectionProvider connectionProvider;
        private final PoolMetricsCollector metricsCollector;
        private final Consumer<ClientHolder> closeListener;
        private int users;
        private boolean retired;
        private boolean closed;

        private ClientHolder(HttpClient httpClient,
                             ConnectionProvider connectionProvider,
                             PoolMetricsCollector metricsCollector,
                             Consumer<ClientHolder> closeListener) {
            this.httpClient = httpClient;
            this.connectionProvider = connectionProvider;
            this.metricsCollector = metricsCollector;
            this.closeListener = closeListener;
        }

        private synchronized ClientUse tryUse() {
            if (retired || closed) return null;
            users++;
            return new ClientUse(this);
        }

        private synchronized void release() {
            users--;
            if (users < 0) {
                users++;
                throw new IllegalStateException("HTTP client use released twice");
            }
            if (retired && users == 0) closeProvider();
        }

        private synchronized void retire() {
            retired = true;
            if (users == 0) closeProvider();
        }

        private synchronized void close() {
            retired = true;
            closeProvider();
        }

        private void closeProvider() {
            if (closed) return;
            closed = true;
            connectionProvider.dispose();
            closeListener.accept(this);
        }

        private void disposePools() {
            metricsCollector.addresses().forEach(connectionProvider::disposeWhen);
        }

        private PoolMetricsCollector metricsCollector() {
            return metricsCollector;
        }
    }

    private static final class ClientUse implements AutoCloseable {
        private final ClientHolder owner;
        private final AtomicBoolean closed = new AtomicBoolean();

        private ClientUse(ClientHolder owner) {
            this.owner = owner;
        }

        private HttpClient httpClient() {
            return owner.httpClient;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) owner.release();
        }
    }

    private static final class PoolMetricsCollector implements ConnectionProvider.MeterRegistrar {
        private final Map<PoolKey, ConnectionPoolMetrics> pools = new ConcurrentHashMap<>();

        @Override
        public void registerMetrics(String poolName,
                                    String id,
                                    java.net.SocketAddress remoteAddress,
                                    ConnectionPoolMetrics metrics) {
            pools.put(new PoolKey(poolName, id, remoteAddress), metrics);
        }

        @Override
        public void deRegisterMetrics(String poolName,
                                      String id,
                                      java.net.SocketAddress remoteAddress) {
            pools.remove(new PoolKey(poolName, id, remoteAddress));
        }

        private PoolSnapshot snapshot() {
            int acquired = 0;
            int pending = 0;
            int idle = 0;
            int capacity = 0;
            for (ConnectionPoolMetrics metrics : pools.values()) {
                acquired += metrics.acquiredSize();
                pending += metrics.pendingAcquireSize();
                idle += metrics.idleSize();
                capacity += metrics.maxAllocatedSize();
            }
            return new PoolSnapshot(acquired, pending, idle, capacity);
        }

        private List<java.net.SocketAddress> addresses() {
            return pools.keySet().stream().map(PoolKey::remoteAddress).distinct().toList();
        }
    }

    private record PoolKey(String poolName, String id, java.net.SocketAddress remoteAddress) {
    }

    private record PoolSnapshot(int acquired, int pending, int idle, int capacity) {
    }

    private record ForwardResponse(int status, String body) {
    }

    private enum FailureKind {
        CAPACITY, POOL_TIMEOUT, CONNECT_TIMEOUT, READ_TIMEOUT, RESPONSE_TOO_LARGE, OTHER
    }

    private static FailureKind classifyFailure(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof ConnectionRequestTimeoutException) {
                return FailureKind.POOL_TIMEOUT;
            }
            if (current instanceof ReactiveConnectionLimiter.CapacityException) {
                return FailureKind.CAPACITY;
            }
            if (current instanceof ResponseBufferCapacityException) {
                return FailureKind.CAPACITY;
            }
            if (current instanceof ConnectTimeoutException) {
                return FailureKind.CONNECT_TIMEOUT;
            }
            if (current instanceof io.netty.channel.ConnectTimeoutException) {
                return FailureKind.CONNECT_TIMEOUT;
            }
            if (current instanceof SocketTimeoutException
                    || current instanceof io.netty.handler.timeout.ReadTimeoutException) {
                return FailureKind.READ_TIMEOUT;
            }
            if (current instanceof ResponseBodyTooLargeException) {
                return FailureKind.RESPONSE_TOO_LARGE;
            }
            current = current.getCause();
        }
        return FailureKind.OTHER;
    }

    private static final class TargetMetrics {
        private volatile String name;
        private final LongAdder failed = new LongAdder();
        private final LongAdder capacityRejected = new LongAdder();
        private final LongAdder poolTimeouts = new LongAdder();
        private final LongAdder connectTimeouts = new LongAdder();
        private final LongAdder readTimeouts = new LongAdder();
        private final LongAdder responseTooLarge = new LongAdder();
        private final LongAdder otherFailures = new LongAdder();

        private TargetMetrics(String name) {
            this.name = name;
        }

        private void setName(String name) {
            if (name != null && !name.isBlank() && !name.equals(this.name)) this.name = name;
        }

        private void recordFailure(Throwable error) {
            failed.increment();
            switch (classifyFailure(error)) {
                case CAPACITY -> capacityRejected.increment();
                case POOL_TIMEOUT -> poolTimeouts.increment();
                case CONNECT_TIMEOUT -> connectTimeouts.increment();
                case READ_TIMEOUT -> readTimeouts.increment();
                case RESPONSE_TOO_LARGE -> responseTooLarge.increment();
                case OTHER -> otherFailures.increment();
            }
        }

        private void recordCapacityRejection() {
            failed.increment();
            capacityRejected.increment();
        }

        private HttpForwardMetricsDto.Target snapshot() {
            return new HttpForwardMetricsDto.Target(
                    name, failed.sum(), capacityRejected.sum(),
                    poolTimeouts.sum(), connectTimeouts.sum(), readTimeouts.sum(),
                    responseTooLarge.sum(), otherFailures.sum());
        }
    }

    private static final class ResponseBodyTooLargeException extends RuntimeException {
        private ResponseBodyTooLargeException(int maximumBytes) {
            super("HTTP_RESPONSE_BODY_TOO_LARGE: max=" + maximumBytes);
        }
    }

    private static final class ResponseBufferCapacityException extends RuntimeException {
        private ResponseBufferCapacityException(long maximumBytes) {
            super("HTTP_RESPONSE_BUFFER_CAPACITY_EXHAUSTED: max=" + maximumBytes);
        }
    }

    public record ConnectionTestResult(boolean success, long elapsedMs,
                                       Integer status, String error) {
    }
}
