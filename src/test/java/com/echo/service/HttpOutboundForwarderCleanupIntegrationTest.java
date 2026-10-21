package com.echo.service;

import com.echo.entity.Protocol;
import com.echo.pipeline.MockRequest;
import com.echo.pipeline.MockResponse;
import com.echo.pipeline.RequestWorkContext;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
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
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Real loopback HTTP regression; pool cleanup is automatic, never triggered by a test hook. */
class HttpOutboundForwarderCleanupIntegrationTest {

    private HttpServer server;
    private HttpTargetConnectionService connectionService;
    private HttpOutboundForwarder forwarder;
    private ExecutorService downstreamExecutor;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(
                InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0), 0);
        downstreamExecutor = Executors.newCachedThreadPool();
        server.setExecutor(downstreamExecutor);
        server.start();
        connectionService = mock(HttpTargetConnectionService.class);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        try {
            if (forwarder != null) forwarder.closeClients();
        } finally {
            try {
                if (server != null) server.stop(0);
            } finally {
                if (downstreamExecutor != null) {
                    downstreamExecutor.shutdownNow();
                    assertThat(downstreamExecutor.awaitTermination(3, TimeUnit.SECONDS))
                            .as("HTTP test workers stopped").isTrue();
                }
            }
        }
    }

    @Test
    void cancelledWorkBeforeExchangeCreationStillReturnsTheOnlyPermit() throws Exception {
        server.createContext("/ok", exchange -> {
            exchange.sendResponseHeaders(200, 2);
            try (var output = exchange.getResponseBody()) { output.write(new byte[]{'o', 'k'}); }
            finally { exchange.close(); }
        });
        forwarder = new HttpOutboundForwarder(connectionService,
                0, 1, 2, 1024, 100, 1, 1, 0, 1);
        var target = new HttpTargetConnectionService.ResolvedTarget(27L, 1L, "Cancellation",
                "http://127.0.0.1:" + server.getAddress().getPort(), "NONE", null, null, 1, 1, false);
        when(connectionService.resolveEnabled(27L)).thenReturn(target);
        RequestWorkContext cancelled = new RequestWorkContext() {
            @Override public void checkActive() { throw new java.util.concurrent.CancellationException("cancelled test request"); }
            @Override public Scope begin() { checkActive(); return () -> { }; }
        };
        MockRequest stopped = MockRequest.builder().protocol(Protocol.HTTP).method("GET").path("/ok")
                .headers(Map.of()).workContext(cancelled).build();
        assertThat(forwarder.forward(stopped, 27L, false).getStatus()).isNotEqualTo(200);
        MockRequest next = MockRequest.builder().protocol(Protocol.HTTP).method("GET").path("/ok")
                .headers(Map.of()).build();
        assertThat(forwarder.forward(next, 27L, false).getBody()).isEqualTo("ok");
        awaitCondition("cancelled exchange released capacity", () ->
                forwarder.metricsSnapshot().pool().leased() == 0, 2);
    }

    @Test
    void adminProbeHasATotalDeadlineAndReleasesResourcesDuringADrippingReply() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        server.createContext("/drip", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                started.countDown();
                for (int index = 0; index < 50; index++) {
                    output.write('x');
                    output.flush();
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(200));
                    if (Thread.currentThread().isInterrupted()) break;
                }
            } catch (java.io.IOException ignored) {
                // Cancellation closes the client connection before the peer finishes its reply.
            }
        });
        forwarder = new HttpOutboundForwarder(connectionService,
                1, 1, 1, 1024, 1_000, 1, 1, 0, 1);
        forwarder.configureTestTimeout(2);
        var target = new HttpTargetConnectionService.ResolvedTarget(25L, 1L, "Dripping",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/drip", "NONE", null, null,
                1, 1, false);
        when(connectionService.resolveEnabled(25L)).thenReturn(target);

        var probe = forwarder.testAsync(25L);
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(probe.isDone()).isFalse();
        var result = probe.get(4, TimeUnit.SECONDS);
        assertThat(result.success()).isFalse();
        assertThat(result.elapsedMs()).isBetween(1_500L, 3_500L);
        awaitCondition("probe resources released", () ->
                forwarder.metricsSnapshot().pool().leased() == 0
                        && forwarder.metricsSnapshot().pool().bufferedResponseBytes() == 0, 3);
    }

    @Test
    void automaticallyDisposesEmptyPoolsAndMetricsAcrossRedirectTargets() throws Exception {
        forwarder = new HttpOutboundForwarder(connectionService,
                0, 8, 8, 1024, 1_000, 2, 5, 0, 1);
        List<HttpServer> destinations = new ArrayList<>();
        try {
            for (int index = 0; index < 3; index++) {
                HttpServer destination = HttpServer.create(new InetSocketAddress(
                        InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0), 0);
                destinations.add(destination);
                destination.createContext("/", exchange -> {
                    byte[] body = "redirect-ok".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    try (var output = exchange.getResponseBody()) { output.write(body); }
                });
                destination.start();
            }
            server.createContext("/redirect", exchange -> {
                int index = Integer.parseInt(exchange.getRequestURI().getQuery());
                exchange.getResponseHeaders().set("Location",
                        "http://127.0.0.1:" + destinations.get(index).getAddress().getPort() + "/reply");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            });
            var target = new HttpTargetConnectionService.ResolvedTarget(25L, 1L, "Redirects",
                    "http://127.0.0.1:" + server.getAddress().getPort(), "NONE", null, null,
                    2, 5, false);
            when(connectionService.resolveEnabled(25L)).thenReturn(target);
            for (int index = 0; index < destinations.size(); index++) {
                MockRequest request = MockRequest.builder().protocol(Protocol.HTTP).method("GET")
                        .path("/redirect").queryString(Integer.toString(index)).headers(Map.of()).build();
                assertThat(forwarder.forward(request, 25L, false).getBody()).isEqualTo("redirect-ok");
            }
            assertThat(forwarder.metricsSnapshot().pool().capacity()).isGreaterThan(8);

            // No explicit cleanup hook: both the pools and metric registrations must disappear.
            awaitCondition("empty pools and metrics removed",
                    () -> forwarder.metricsSnapshot().pool().capacity() == 0, 6);
            assertThat(forwarder.metricsSnapshot().pool().leased()).isZero();
            assertThat(forwarder.metricsSnapshot().pool().available()).isZero();
            assertThat(forwarder.metricsSnapshot().pool().pending()).isZero();

            MockRequest afterIdle = MockRequest.builder().protocol(Protocol.HTTP).method("GET")
                    .path("/redirect").queryString("0").headers(Map.of()).build();
            assertThat(forwarder.forward(afterIdle, 25L, false).getBody()).isEqualTo("redirect-ok");
            assertThat(forwarder.metricsSnapshot().pool().capacity()).isPositive();
        } finally {
            destinations.forEach(destination -> destination.stop(0));
        }
    }

    @Test
    void backgroundPoolDisposalPreservesActiveAndWaitingRequests() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/held", exchange -> {
            entered.countDown();
            try {
                release.await(8, TimeUnit.SECONDS);
                byte[] body = "held-ok".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        forwarder = new HttpOutboundForwarder(connectionService,
                0, 1, 2, 1024, 7_000, 2, 8, 0, 1);
        var target = new HttpTargetConnectionService.ResolvedTarget(26L, 1L, "Held",
                "http://127.0.0.1:" + server.getAddress().getPort(), "NONE", null, null,
                2, 8, false);
        when(connectionService.resolveEnabled(26L)).thenReturn(target);
        MockRequest request = MockRequest.builder().protocol(Protocol.HTTP).method("GET")
                .path("/held").headers(Map.of()).build();
        CompletableFuture<MockResponse> first = forwarder.forwardAsync(request, 26L, false).toCompletableFuture();
        CompletableFuture<MockResponse> waiting = null;
        try {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            waiting = forwarder.forwardAsync(request, 26L, false).toCompletableFuture();
            awaitCondition("second request waiting", () -> forwarder.metricsSnapshot().pool().pending() == 1, 2);
            // Cover multiple one-second disposal ticks with a leased connection and a waiter.
            Thread.sleep(2_500);
            assertThat(first).isNotDone();
            assertThat(waiting).isNotDone();
            assertThat(forwarder.metricsSnapshot().pool().leased()).isEqualTo(1);
            release.countDown();
            assertThat(first.get(2, TimeUnit.SECONDS).getBody()).isEqualTo("held-ok");
            assertThat(waiting.get(2, TimeUnit.SECONDS).getBody()).isEqualTo("held-ok");
            awaitCondition("pool removed after both requests finish",
                    () -> forwarder.metricsSnapshot().pool().capacity() == 0, 6);
        } finally {
            release.countDown();
            first.cancel(true);
            if (waiting != null) waiting.cancel(true);
        }
    }

    private static void awaitCondition(String description, BooleanSupplier condition, int timeoutSeconds) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        assertThat(condition.getAsBoolean()).as(description).isTrue();
    }
}
