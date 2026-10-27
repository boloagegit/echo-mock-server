package com.echo.config;

import io.undertow.io.Sender;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.ServerConnection;
import io.undertow.util.HeaderMap;
import org.junit.jupiter.api.Test;
import org.xnio.XnioExecutor;
import org.xnio.XnioIoThread;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NativeHttpDeadlineTest {
    @Test
    @SuppressWarnings("DirectInvocationOnMock") // The live handler uses a controlled availability seam.
    void inactiveRootHandoffProduces504RatherThanUndertowsImplicit200() throws Exception {
        var server = io.undertow.Undertow.builder()
                .addHttpListener(0, "127.0.0.1")
                .setIoThreads(1).setWorkerThreads(2)
                .setHandler(exchange -> {
                    var active = new java.util.concurrent.atomic.AtomicBoolean(true);
                    var lease = mock(NativeHttpResources.Lease.class);
                    when(lease.available()).thenAnswer(ignored -> active.get());
                    var cleanup = new java.util.ArrayList<Runnable>();
                    doAnswer(call -> { cleanup.add(call.getArgument(0)); return null; })
                            .when(lease).onClose(any(Runnable.class));
                    exchange.addExchangeCompleteListener((ex, next) -> {
                        try { cleanup.forEach(Runnable::run); }
                        finally { next.proceed(); }
                    });
                    var deadline = new NativeHttpDeadline(exchange, lease, 60_000);
                    exchange.getRequestReceiver().receiveFullBytes((ex, bytes) -> {
                        // Deterministically change availability between EOF and handoff.
                        // The timer has not run; the root handler still owns this exchange.
                        assertThat(lease.available()).isTrue();
                        active.set(false);
                        boolean allowed = ex.getRequestPath().equals("/guarded")
                                ? deadline.checkActive() : lease.available();
                        if (allowed) ex.getResponseSender().send("unexpected work");
                    });
                }).build();
        server.start();
        try {
            int port = ((java.net.InetSocketAddress) server.getListenerInfo().get(0).getAddress()).getPort();
            var client = java.net.http.HttpClient.newBuilder()
                    .version(java.net.http.HttpClient.Version.HTTP_1_1).build();
            for (boolean guarded : new boolean[]{false, true}) {
                var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                                "http://127.0.0.1:" + port + (guarded ? "/guarded" : "/bare")))
                        .timeout(java.time.Duration.ofSeconds(5))
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString("body")).build();
                var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(guarded ? 504 : 200);
                assertThat(response.body()).isEqualTo(guarded ? "Request timeout" : "");
            }
        } finally {
            server.stop();
        }
    }

    @Test
    void expiryBetweenTheLastReadAndIoHandoffEnforcesTimeoutInsteadOfFallingThrough() {
        var exchange = mock(HttpServerExchange.class);
        var io = mock(XnioIoThread.class);
        var key = mock(XnioExecutor.Key.class);
        var sender = mock(Sender.class);
        var lease = mock(NativeHttpResources.Lease.class);
        when(exchange.getIoThread()).thenReturn(io);
        when(exchange.isRequestComplete()).thenReturn(true);
        when(exchange.getResponseHeaders()).thenReturn(new HeaderMap());
        when(exchange.getResponseSender()).thenReturn(sender);
        when(io.executeAfter(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(key);
        when(lease.available()).thenReturn(true, false);
        var deadline = new NativeHttpDeadline(exchange, lease, 1000);
        assertThat(deadline.checkActive()).isTrue();
        verifyNoInteractions(sender);
        assertThat(deadline.checkActive()).isFalse();
        verify(exchange).setStatusCode(504);
        verify(sender).send(any(ByteBuffer.class));
    }

    @Test
    void observedDeadlineClosesIncompleteUploadOnceBeforeTheTimerRuns() throws Exception {
        var exchange = mock(HttpServerExchange.class);
        var connection = mock(ServerConnection.class);
        var io = mock(XnioIoThread.class);
        var key = mock(XnioExecutor.Key.class);
        when(exchange.getIoThread()).thenReturn(io);
        when(exchange.getConnection()).thenReturn(connection);
        when(io.executeAfter(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(key);
        try (var resources = new NativeHttpResources(1, 1, 1024, 4096)) {
            var lease = resources.acquire();
            var timer = org.mockito.ArgumentCaptor.forClass(Runnable.class);
            var deadline = new NativeHttpDeadline(exchange, lease, 1000);
            verify(io).executeAfter(timer.capture(), eq(1000L), eq(TimeUnit.MILLISECONDS));
            deadline.enforce();
            deadline.enforce();
            timer.getValue().run();
            assertThat(deadline.expired()).isTrue();
            verify(connection, times(1)).close();
            verify(exchange, never()).getResponseSender();
            lease.close();
            verify(key).remove();
        }
    }

    @Test
    void completedUploadGetsTheExistingTimeoutResponseOnlyOnce() {
        var exchange = mock(HttpServerExchange.class);
        var io = mock(XnioIoThread.class);
        var key = mock(XnioExecutor.Key.class);
        var sender = mock(Sender.class);
        when(exchange.getIoThread()).thenReturn(io);
        when(exchange.isRequestComplete()).thenReturn(true);
        when(exchange.getResponseHeaders()).thenReturn(new HeaderMap());
        when(exchange.getResponseSender()).thenReturn(sender);
        when(io.executeAfter(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(key);
        try (var resources = new NativeHttpResources(1, 1, 1024, 4096)) {
            var lease = resources.acquire();
            var deadline = new NativeHttpDeadline(exchange, lease, 1000);
            deadline.enforce();
            deadline.enforce();
            verify(exchange, times(1)).setStatusCode(504);
            verify(exchange).setPersistent(false);
            verify(sender, times(1)).send(any(ByteBuffer.class));
            lease.close();
        }
    }
}
