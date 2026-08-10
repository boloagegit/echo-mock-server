package com.echo.service;

import com.echo.entity.Protocol;
import com.echo.pipeline.MockRequest;
import com.echo.pipeline.MockResponse;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HttpOutboundForwarderTest {

    private HttpServer server;
    private HttpTargetConnectionService connectionService;
    private HttpOutboundForwarder forwarder;
    private ExecutorService downstreamExecutor;
    private final AtomicReference<String> requestSummary = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/base/orders", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requestSummary.set(exchange.getRequestMethod() + " " + exchange.getRequestURI()
                    + " " + exchange.getRequestHeaders().getFirst("Authorization")
                    + " " + body
                    + " " + exchange.getRequestHeaders().getFirst("Content-Length")
                    + " " + exchange.getRequestHeaders().getFirst("Transfer-Encoding")
                    + " " + exchange.getRequestHeaders().getFirst("X-Internal-Hop"));
            byte[] response = "downstream-ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(202, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        downstreamExecutor = Executors.newCachedThreadPool();
        server.setExecutor(downstreamExecutor);
        server.start();
        connectionService = mock(HttpTargetConnectionService.class);
        forwarder = new HttpOutboundForwarder(connectionService);
    }

    @AfterEach
    void tearDown() {
        forwarder.closeClients();
        server.stop(0);
        downstreamExecutor.shutdownNow();
    }

    @Test
    void forwardsThroughSelectedProfileWithPathQueryBodyAndBasicAuth() throws Exception {
        int port = server.getAddress().getPort();
        var target = new HttpTargetConnectionService.ResolvedTarget(3L, 1L, "Local",
                "http://127.0.0.1:" + port + "/base", "BASIC", "user", "pass",
                5, 30, false);
        when(connectionService.resolveEnabled(3L)).thenReturn(target);
        MockRequest request = MockRequest.builder().protocol(Protocol.HTTP).method("POST")
                .path("/orders").queryString("id=9").body("測試payload")
                .headers(Map.of(
                        "Content-Type", "text/plain",
                        "Authorization", "old",
                        "Transfer-Encoding", "chunked",
                        "Connection", "X-Internal-Hop",
                        "X-Internal-Hop", "remove-me"))
                .targetHost("default").build();

        var response = forwarder.forward(request, 3L, false);

        assertThat(response.getStatus()).isEqualTo(202);
        assertThat(response.getBody()).isEqualTo("downstream-ok");
        assertThat(response.isForwarded()).isTrue();
        assertThat(requestSummary.get())
                .isEqualTo("POST /base/orders?id=9 Basic dXNlcjpwYXNz 測試payload 13 null null");

        long poolDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (forwarder.metricsSnapshot().pool().available() == 0
                && System.nanoTime() < poolDeadline) {
            Thread.sleep(5);
        }
        var metrics = forwarder.metricsSnapshot();
        assertThat(metrics.targets().get("profile:3").failed()).isZero();
        assertThat(metrics.pool().available()).isGreaterThanOrEqualTo(1);

        forwarder.cleanupConnections(0);

        assertThat(forwarder.metricsSnapshot().pool().available()).isZero();
    }

    @Test
    void connectionFailureReturns502WithoutThrowingPipelineError() {
        when(connectionService.resolveEnabled(99L))
                .thenThrow(new IllegalArgumentException("HTTP_CONNECTION_NOT_FOUND"));
        MockRequest request = MockRequest.builder().protocol(Protocol.HTTP).method("GET")
                .path("/x").headers(Map.of()).build();

        var response = forwarder.forward(request, 99L, false);

        assertThat(response.getStatus()).isEqualTo(502);
        assertThat(response.getProxyError()).isEqualTo("HTTP_CONNECTION_NOT_FOUND");
        assertThat(response.isMatched()).isTrue();
    }

    @Test
    void defaultConnectionFailureIsForwardedButNotMatched() {
        when(connectionService.resolveDefault()).thenThrow(new IllegalStateException("database unavailable"));
        MockRequest request = MockRequest.builder().protocol(Protocol.HTTP).method("GET")
                .path("/x").headers(Map.of()).build();

        var response = forwarder.forwardDefault(request).orElseThrow();

        assertThat(response.getStatus()).isEqualTo(502);
        assertThat(response.isForwarded()).isTrue();
        assertThat(response.isMatched()).isFalse();
        assertThat(response.getProxyError()).isEqualTo("database unavailable");
    }

    @Test
    void preservesCallerAuthorizationOnlyForOriginalHostForwarding() {
        Map<String, String> source = Map.of(
                "Authorization", "Bearer caller-token",
                "X-Original-Host", "internal.example",
                "X-Trace", "trace-1");

        var originalHostHeaders = HttpOutboundForwarder.copyHeaders(source, false);
        var profileHeaders = HttpOutboundForwarder.copyHeaders(source, true);

        assertThat(originalHostHeaders.getFirst("Authorization")).isEqualTo("Bearer caller-token");
        assertThat(profileHeaders.getFirst("Authorization")).isNull();
        assertThat(originalHostHeaders.getFirst("X-Original-Host")).isNull();
        assertThat(profileHeaders.getFirst("X-Original-Host")).isNull();
        assertThat(originalHostHeaders.getFirst("X-Trace")).isEqualTo("trace-1");
        assertThat(profileHeaders.getFirst("X-Trace")).isEqualTo("trace-1");
    }

    @Test
    void stripsStandardAndConnectionNamedHopByHopHeaders() {
        Map<String, String> source = Map.ofEntries(
                Map.entry("Connection", "X-Internal-Hop, X-Second-Hop"),
                Map.entry("Keep-Alive", "timeout=5"),
                Map.entry("Proxy-Authenticate", "Basic"),
                Map.entry("Proxy-Authorization", "Basic abc"),
                Map.entry("TE", "trailers"),
                Map.entry("Trailer", "Checksum"),
                Map.entry("Transfer-Encoding", "chunked"),
                Map.entry("Upgrade", "websocket"),
                Map.entry("X-Internal-Hop", "remove"),
                Map.entry("X-Second-Hop", "remove"),
                Map.entry("X-End-To-End", "keep"));

        HttpHeaders copied = HttpOutboundForwarder.copyHeaders(source, false);

        assertThat(copied).containsEntry("X-End-To-End", List.of("keep"));
        assertThat(copied.keySet()).containsExactly("X-End-To-End");
    }

    @Test
    void productionDefaultsUseConfigurableGlobalConnectionLimit() {
        assertThat(ReflectionTestUtils.getField(forwarder, "maxConnections")).isEqualTo(1_000);
        assertThat(forwarder.metricsSnapshot().pool().maxConnections()).isEqualTo(1_000);
        assertThat(forwarder.metricsSnapshot().pool().maxConnectionsPerRoute()).isEqualTo(1_000);
        assertThat(forwarder.metricsSnapshot().pool().maxPendingRequests()).isEqualTo(1_000);
        assertThat(forwarder.metricsSnapshot().pool().maxResponseBodyBytes()).isEqualTo(10 * 1024 * 1024);
        assertThat(ReflectionTestUtils.getField(forwarder, "poolAcquireTimeoutMs")).isEqualTo(3_000);
        assertThat(forwarder.metricsSnapshot().pool().idleConnectionTimeoutSeconds()).isEqualTo(30);
        assertThat(forwarder.metricsSnapshot().pool().backgroundEvictionIntervalSeconds()).isEqualTo(30);
    }

    @Test
    void allowsMoreThanFormerFiftyConcurrentRequestsToSameDownstream() throws Exception {
        server.createContext("/one-second", exchange -> {
            try {
                Thread.sleep(1_000);
                byte[] response = "healthy-ok".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });

        int port = server.getAddress().getPort();
        var target = new HttpTargetConnectionService.ResolvedTarget(9L, 1L, "Healthy",
                "http://127.0.0.1:" + port, "NONE", null, null,
                5, 30, false);
        when(connectionService.resolveEnabled(9L)).thenReturn(target);
        MockRequest request = MockRequest.builder().protocol(Protocol.HTTP).method("GET")
                .path("/one-second").headers(Map.of()).build();

        List<CompletableFuture<MockResponse>> responses = new ArrayList<>();
        for (int index = 0; index < 60; index++) {
            responses.add(forwarder.forwardAsync(request, 9L, false).toCompletableFuture());
        }
        CompletableFuture.allOf(responses.toArray(CompletableFuture[]::new))
                .get(10, TimeUnit.SECONDS);

        assertThat(responses).allSatisfy(response -> {
            assertThat(response.join().getStatus()).isEqualTo(200);
            assertThat(response.join().getBody()).isEqualTo("healthy-ok");
        });
    }

    @Test
    void rejectsExcessForwardingWithoutBlockingMockWorkers() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/slow", exchange -> {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
                byte[] response = "slow-ok".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });

        forwarder.closeClients();
        forwarder = new HttpOutboundForwarder(connectionService,
                1, 1, 100, 5, 5, 50);
        int port = server.getAddress().getPort();
        var target = new HttpTargetConnectionService.ResolvedTarget(7L, 1L, "Slow",
                "http://127.0.0.1:" + port, "NONE", null, null,
                5, 5, false);
        when(connectionService.resolveEnabled(7L)).thenReturn(target);
        MockRequest request = MockRequest.builder().protocol(Protocol.HTTP).method("GET")
                .path("/slow").headers(Map.of()).build();

        CompletableFuture<MockResponse> first = forwarder.forwardAsync(request, 7L, false)
                .toCompletableFuture();
        try {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

            long started = System.nanoTime();
            MockResponse rejected = forwarder.forwardAsync(request, 7L, false)
                    .toCompletableFuture().get(1, TimeUnit.SECONDS);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertThat(rejected.getStatus()).isEqualTo(502);
            assertThat(rejected.getProxyError()).isEqualTo("HTTP_FORWARD_CAPACITY_EXHAUSTED");
            assertThat(elapsedMs).isBetween(40L, 500L);
            assertThat(forwarder.activeForwardCount()).isEqualTo(1);
            assertThat(forwarder.rejectedForwardCount()).isEqualTo(1);
        } finally {
            release.countDown();
        }
        assertThat(first.get(2, TimeUnit.SECONDS).getBody()).isEqualTo("slow-ok");
    }

    @Test
    void globalConnectionLimitAppliesAcrossDifferentProfiles() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/pool-slow", exchange -> {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
                byte[] response = "pool-ok".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });

        forwarder.closeClients();
        forwarder = new HttpOutboundForwarder(connectionService,
                2, 1, 100, 5, 5);
        int port = server.getAddress().getPort();
        var firstTarget = new HttpTargetConnectionService.ResolvedTarget(8L, 1L, "Pool A",
                "http://127.0.0.1:" + port, "NONE", null, null,
                5, 5, false);
        var secondTarget = new HttpTargetConnectionService.ResolvedTarget(18L, 1L, "Pool B",
                "http://127.0.0.1:" + port, "NONE", null, null,
                5, 5, false);
        when(connectionService.resolveEnabled(8L)).thenReturn(firstTarget);
        when(connectionService.resolveEnabled(18L)).thenReturn(secondTarget);
        MockRequest request = MockRequest.builder().protocol(Protocol.HTTP).method("GET")
                .path("/pool-slow").headers(Map.of()).build();

        CompletableFuture<MockResponse> first = forwarder.forwardAsync(request, 8L, false)
                .toCompletableFuture();
        try {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

            long started = System.nanoTime();
            MockResponse timedOut = forwarder.forwardAsync(request, 18L, false)
                    .toCompletableFuture().get(1, TimeUnit.SECONDS);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertThat(timedOut.getStatus()).isEqualTo(502);
            assertThat(timedOut.getProxyError()).isNotBlank();
            assertThat(elapsedMs).isBetween(50L, 900L);
        } finally {
            release.countDown();
        }
        assertThat(first.get(2, TimeUnit.SECONDS).getBody()).isEqualTo("pool-ok");
        var targetMetrics = forwarder.metricsSnapshot().targets().get("profile:18");
        assertThat(targetMetrics.failed()).isEqualTo(1);
        assertThat(targetMetrics.poolTimeouts()).isEqualTo(1);
    }

    @Test
    void cancellationStopsActiveReactiveForward() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/cancel", exchange -> {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        int port = server.getAddress().getPort();
        var target = new HttpTargetConnectionService.ResolvedTarget(10L, 1L, "Cancel",
                "http://127.0.0.1:" + port, "NONE", null, null,
                5, 30, false);
        when(connectionService.resolveEnabled(10L)).thenReturn(target);
        MockRequest request = MockRequest.builder().protocol(Protocol.HTTP).method("GET")
                .path("/cancel").headers(Map.of()).build();

        CompletableFuture<MockResponse> pending = forwarder.forwardAsync(request, 10L, false)
                .toCompletableFuture();
        try {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(pending.cancel(true)).isTrue();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while ((forwarder.activeForwardCount() != 0
                    || forwarder.metricsSnapshot().pool().leased() != 0)
                    && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertThat(forwarder.activeForwardCount()).isZero();
            assertThat(forwarder.metricsSnapshot().cancelledForwards()).isEqualTo(1);
            assertThat(forwarder.metricsSnapshot().pool().leased()).isZero();
        } finally {
            release.countDown();
        }
    }

    @Test
    void rejectsRequestsBeyondConfiguredPendingCapacity() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/pending-capacity", exchange -> {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
                byte[] response = "capacity-ok".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        forwarder.closeClients();
        forwarder = new HttpOutboundForwarder(connectionService,
                0, 1, 1, 10 * 1024 * 1024, 500, 5, 5, 0, 30);
        int port = server.getAddress().getPort();
        var target = new HttpTargetConnectionService.ResolvedTarget(19L, 1L, "Capacity",
                "http://127.0.0.1:" + port, "NONE", null, null,
                5, 5, false);
        when(connectionService.resolveEnabled(19L)).thenReturn(target);
        MockRequest request = MockRequest.builder().protocol(Protocol.HTTP).method("GET")
                .path("/pending-capacity").headers(Map.of()).build();

        CompletableFuture<MockResponse> first = forwarder.forwardAsync(request, 19L, false)
                .toCompletableFuture();
        CompletableFuture<MockResponse> waiting = null;
        try {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            waiting = forwarder.forwardAsync(request, 19L, false).toCompletableFuture();
            long pendingDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (forwarder.metricsSnapshot().pool().pending() == 0
                    && System.nanoTime() < pendingDeadline) {
                Thread.sleep(5);
            }
            assertThat(forwarder.metricsSnapshot().pool().pending()).isEqualTo(1);

            long started = System.nanoTime();
            MockResponse rejected = forwarder.forwardAsync(request, 19L, false)
                    .toCompletableFuture().get(1, TimeUnit.SECONDS);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertThat(rejected.getStatus()).isEqualTo(502);
            assertThat(rejected.getProxyError())
                    .isEqualTo("HTTP_FORWARD_PENDING_CAPACITY_EXHAUSTED");
            assertThat(elapsedMs).isLessThan(500L);
            assertThat(forwarder.rejectedForwardCount()).isEqualTo(1);
            assertThat(forwarder.metricsSnapshot().targets().get("profile:19")
                    .capacityRejected()).isEqualTo(1);
        } finally {
            if (waiting != null) waiting.cancel(true);
            release.countDown();
        }
        assertThat(first.get(2, TimeUnit.SECONDS).getBody()).isEqualTo("capacity-ok");
    }

    @Test
    void globalResponseBufferBudgetRejectsConcurrentHeapGrowthAndReleasesBytes() throws Exception {
        CountDownLatch firstChunkSent = new CountDownLatch(1);
        CountDownLatch finishFirst = new CountDownLatch(1);
        server.createContext("/budget-first", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write("123456".getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                firstChunkSent.countDown();
                finishFirst.await(5, TimeUnit.SECONDS);
                exchange.getResponseBody().write("78".getBytes(StandardCharsets.UTF_8));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.createContext("/budget-second", exchange -> {
            byte[] response = "abcdefgh".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        forwarder.closeClients();
        forwarder = new HttpOutboundForwarder(connectionService,
                0, 10, 10, 16, 10, 500, 5, 5, 0, 30);
        int port = server.getAddress().getPort();
        var target = new HttpTargetConnectionService.ResolvedTarget(21L, 1L, "Budget",
                "http://127.0.0.1:" + port, "NONE", null, null,
                5, 5, false);
        when(connectionService.resolveEnabled(21L)).thenReturn(target);

        MockRequest firstRequest = MockRequest.builder().protocol(Protocol.HTTP).method("GET")
                .path("/budget-first").headers(Map.of()).build();
        MockRequest secondRequest = MockRequest.builder().protocol(Protocol.HTTP).method("GET")
                .path("/budget-second").headers(Map.of()).build();
        CompletableFuture<MockResponse> first =
                forwarder.forwardAsync(firstRequest, 21L, false).toCompletableFuture();

        try {
            assertThat(firstChunkSent.await(2, TimeUnit.SECONDS)).isTrue();
            long reservationDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (forwarder.metricsSnapshot().pool().bufferedResponseBytes() < 6
                    && System.nanoTime() < reservationDeadline) {
                Thread.sleep(5);
            }
            assertThat(forwarder.metricsSnapshot().pool().bufferedResponseBytes())
                    .isGreaterThanOrEqualTo(6);

            MockResponse rejected = forwarder.forward(secondRequest, 21L, false);
            assertThat(rejected.getStatus()).isEqualTo(502);
            assertThat(rejected.getProxyError())
                    .startsWith("HTTP_RESPONSE_BUFFER_CAPACITY_EXHAUSTED");
            assertThat(forwarder.metricsSnapshot().targets().get("profile:21").capacityRejected())
                    .isEqualTo(1);
        } finally {
            finishFirst.countDown();
        }

        assertThat(first.get(2, TimeUnit.SECONDS).getBody()).isEqualTo("12345678");
        long releaseDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (forwarder.metricsSnapshot().pool().bufferedResponseBytes() != 0
                && System.nanoTime() < releaseDeadline) {
            Thread.sleep(5);
        }
        assertThat(forwarder.metricsSnapshot().pool().bufferedResponseBytes()).isZero();
    }

    @Test
    void evictingUpdatedProfileDoesNotInterruptActiveForward() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/profile-update", exchange -> {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
                byte[] response = "update-ok".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        int port = server.getAddress().getPort();
        var versionOne = new HttpTargetConnectionService.ResolvedTarget(20L, 1L, "Update",
                "http://127.0.0.1:" + port, "NONE", null, null,
                5, 5, false);
        var versionTwo = new HttpTargetConnectionService.ResolvedTarget(20L, 2L, "Update",
                "http://127.0.0.1:" + port, "NONE", null, null,
                5, 5, false);
        when(connectionService.resolveEnabled(20L)).thenReturn(versionOne, versionTwo);
        MockRequest request = MockRequest.builder().protocol(Protocol.HTTP).method("GET")
                .path("/profile-update").headers(Map.of()).build();

        CompletableFuture<MockResponse> active = forwarder.forwardAsync(request, 20L, false)
                .toCompletableFuture();
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

        forwarder.evict(20L);
        assertThat(forwarder.metricsSnapshot().clientPoolCount()).isEqualTo(2);
        release.countDown();

        assertThat(active.get(2, TimeUnit.SECONDS).getBody()).isEqualTo("update-ok");
        assertThat(forwarder.forward(request, 20L, false).getBody()).isEqualTo("update-ok");
        assertThat(forwarder.metricsSnapshot().clientPoolCount()).isEqualTo(2);
    }

    @Test
    void forwardsResponseBodiesLargerThanWebClientDefaultBuffer() {
        byte[] largeResponse = "x".repeat(512 * 1024).getBytes(StandardCharsets.UTF_8);
        server.createContext("/large", exchange -> {
            exchange.sendResponseHeaders(200, largeResponse.length);
            exchange.getResponseBody().write(largeResponse);
            exchange.close();
        });
        int port = server.getAddress().getPort();
        var target = new HttpTargetConnectionService.ResolvedTarget(11L, 1L, "Large",
                "http://127.0.0.1:" + port, "NONE", null, null,
                5, 30, false);
        when(connectionService.resolveEnabled(11L)).thenReturn(target);
        MockRequest request = MockRequest.builder().protocol(Protocol.HTTP).method("GET")
                .path("/large").headers(Map.of()).build();

        MockResponse response = forwarder.forward(request, 11L, false);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getBody()).hasSize(512 * 1024);
    }

    @Test
    void rejectsDeclaredResponseBodyAboveConfiguredLimit() {
        byte[] responseBody = "x".repeat(2_048).getBytes(StandardCharsets.UTF_8);
        server.createContext("/declared-too-large", exchange -> {
            exchange.sendResponseHeaders(200, responseBody.length);
            exchange.getResponseBody().write(responseBody);
            exchange.close();
        });
        replaceForwarderWithResponseLimit(1_024);
        int port = server.getAddress().getPort();
        var target = new HttpTargetConnectionService.ResolvedTarget(21L, 1L, "Large declared",
                "http://127.0.0.1:" + port, "NONE", null, null,
                5, 5, false);
        when(connectionService.resolveEnabled(21L)).thenReturn(target);
        MockRequest request = MockRequest.builder().protocol(Protocol.HTTP).method("GET")
                .path("/declared-too-large").headers(Map.of()).build();

        MockResponse response = forwarder.forward(request, 21L, false);

        assertResponseTooLarge(response, "profile:21");
    }

    @Test
    void rejectsChunkedResponseBodyWhenStreamingBytesCrossLimit() {
        byte[] chunk = "x".repeat(700).getBytes(StandardCharsets.UTF_8);
        server.createContext("/chunked-too-large", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(chunk);
            exchange.getResponseBody().write(chunk);
            exchange.close();
        });
        replaceForwarderWithResponseLimit(1_024);
        int port = server.getAddress().getPort();
        var target = new HttpTargetConnectionService.ResolvedTarget(22L, 1L, "Large chunked",
                "http://127.0.0.1:" + port, "NONE", null, null,
                5, 5, false);
        when(connectionService.resolveEnabled(22L)).thenReturn(target);
        MockRequest request = MockRequest.builder().protocol(Protocol.HTTP).method("GET")
                .path("/chunked-too-large").headers(Map.of()).build();

        MockResponse response = forwarder.forward(request, 22L, false);

        assertResponseTooLarge(response, "profile:22");
    }

    @Test
    void decompressesGzipResponsesLikePreviousClient() throws Exception {
        byte[] compressed;
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write("compressed-response".getBytes(StandardCharsets.UTF_8));
            gzip.finish();
            compressed = output.toByteArray();
        }
        server.createContext("/gzip", exchange -> {
            exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            exchange.sendResponseHeaders(200, compressed.length);
            exchange.getResponseBody().write(compressed);
            exchange.close();
        });
        int port = server.getAddress().getPort();
        var target = new HttpTargetConnectionService.ResolvedTarget(13L, 1L, "Gzip",
                "http://127.0.0.1:" + port, "NONE", null, null,
                5, 30, false);
        when(connectionService.resolveEnabled(13L)).thenReturn(target);
        MockRequest request = MockRequest.builder().protocol(Protocol.HTTP).method("GET")
                .path("/gzip").headers(Map.of()).build();

        MockResponse response = forwarder.forward(request, 13L, false);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("compressed-response");
    }

    @Test
    void decodesResponseUsingDeclaredCharset() {
        byte[] encoded = "café".getBytes(StandardCharsets.ISO_8859_1);
        server.createContext("/charset", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/plain;charset=ISO-8859-1");
            exchange.sendResponseHeaders(200, encoded.length);
            exchange.getResponseBody().write(encoded);
            exchange.close();
        });
        int port = server.getAddress().getPort();
        var target = new HttpTargetConnectionService.ResolvedTarget(14L, 1L, "Charset",
                "http://127.0.0.1:" + port, "NONE", null, null,
                5, 30, false);
        when(connectionService.resolveEnabled(14L)).thenReturn(target);
        MockRequest request = MockRequest.builder().protocol(Protocol.HTTP).method("GET")
                .path("/charset").headers(Map.of()).build();

        MockResponse response = forwarder.forward(request, 14L, false);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("café");
    }

    @Test
    void readTimeoutReturns502AndIsClassified() {
        server.createContext("/read-timeout", exchange -> {
            try {
                Thread.sleep(2_000);
                byte[] response = "too-late".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        forwarder.closeClients();
        forwarder = new HttpOutboundForwarder(connectionService,
                0, 10, 500, 5, 1);
        int port = server.getAddress().getPort();
        var target = new HttpTargetConnectionService.ResolvedTarget(12L, 1L, "Timeout",
                "http://127.0.0.1:" + port, "NONE", null, null,
                5, 1, false);
        when(connectionService.resolveEnabled(12L)).thenReturn(target);
        MockRequest request = MockRequest.builder().protocol(Protocol.HTTP).method("GET")
                .path("/read-timeout").headers(Map.of()).build();

        long started = System.nanoTime();
        MockResponse response = forwarder.forward(request, 12L, false);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(response.getStatus()).isEqualTo(502);
        assertThat(elapsedMs).isBetween(800L, 1_800L);
        var metrics = forwarder.metricsSnapshot().targets().get("profile:12");
        assertThat(metrics.readTimeouts()).isEqualTo(1);
    }

    private void replaceForwarderWithResponseLimit(int maximumBytes) {
        forwarder.closeClients();
        forwarder = new HttpOutboundForwarder(connectionService,
                0, 10, 10, maximumBytes, 500, 5, 5, 0, 30);
    }

    private void assertResponseTooLarge(MockResponse response, String metricsKey) {
        assertThat(response.getStatus()).isEqualTo(502);
        assertThat(response.getProxyError()).startsWith("HTTP_RESPONSE_BODY_TOO_LARGE");
        assertThat(forwarder.metricsSnapshot().targets().get(metricsKey).responseTooLarge())
                .isEqualTo(1);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (forwarder.metricsSnapshot().pool().leased() != 0
                && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
        }
        assertThat(forwarder.metricsSnapshot().pool().leased()).isZero();
    }
}
