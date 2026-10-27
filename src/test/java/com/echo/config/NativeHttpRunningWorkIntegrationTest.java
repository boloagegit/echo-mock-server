package com.echo.config;

import com.echo.dto.HttpTargetConnectionRequest;
import com.echo.integration.base.BaseIntegrationTest;
import com.echo.pipeline.HttpMockPipeline;
import com.echo.pipeline.MockRequest;
import com.echo.repository.HttpTargetConnectionRepository;
import com.echo.service.HttpTargetConnectionService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;

@TestPropertySource(properties = {"echo.http.transport=native", "echo.http.request-timeout-ms=3000",
        "echo.http.forward.pool-acquire-timeout-ms=100",
        "echo.http.native.workers=1", "echo.http.native.max-requests=1", "echo.jms.enabled=false"})
class NativeHttpRunningWorkIntegrationTest extends BaseIntegrationTest {
    @LocalServerPort int port;
    @Autowired NativeHttpResources resources;
    @Autowired com.echo.service.HttpOutboundForwarder forwarder;
    @Autowired HttpTargetConnectionService targets;
    @Autowired HttpTargetConnectionRepository targetRepository;
    @MockitoSpyBean HttpMockPipeline pipeline;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void timedOutAsyncCompletionRetainsItsBodyUntilTheListenerActuallyReturns(boolean segmented) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch downstreamEntered = new CountDownLatch(1);
        CountDownLatch allowResponse = new CountDownLatch(1);
        HttpServer downstream = HttpServer.create(new InetSocketAddress(
                InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0), 0);
        byte[] body = new byte[65536];
        java.util.Arrays.fill(body, (byte) 'x');
        downstream.createContext("/", exchange -> {
            downstreamEntered.countDown();
            try {
                allowResponse.await();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, segmented ? 0 : body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
            finally { exchange.close(); }
        });
        downstream.start();
        try {
            targets.create(new HttpTargetConnectionRequest(null, "Async completion ownership",
                    "http://127.0.0.1:" + downstream.getAddress().getPort(), "NONE", null, null,
                    false, 1, 1, true, true, true));
            // Deliberately violate the non-blocking listener contract to model uncooperative work.
            pipeline.setServeEventListeners(List.of(new com.echo.service.ServeEventListener() {
                @Override public boolean requiresFullResponseBody() { return !segmented; }
                @Override public void onServed(com.echo.service.ServeEvent event) {
                    if (!event.getEndpoint().equals("/after-downstream")) return;
                    assertThat(event.getUtf8ResponseBody() != null).isEqualTo(segmented);
                    entered.countDown();
                    try { assertThat(release.await(10, TimeUnit.SECONDS)).isTrue(); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
                }
            }));
            var reply = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
                    .sendAsync(request("/after-downstream").build(), HttpResponse.BodyHandlers.ofString());
            assertThat(downstreamEntered.await(3, TimeUnit.SECONDS)).isTrue();
            // A completed future may invoke its continuation inline on the worker.
            // Hold the response until that worker has returned, making this path asynchronous.
            await().atMost(Duration.ofSeconds(1)).until(() -> resources.workers.getActiveCount() == 0);
            allowResponse.countDown();
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(reply.get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(504);
            assertThat(resources.workers.getActiveCount()).isZero();
            assertThat(resources.active()).isEqualTo(1);
            assertThat(resources.bufferedBytes()).isGreaterThanOrEqualTo(body.length);
            // The client can receive 504 before the exchange-complete listener closes the lease.
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
                assertThat(resources.active()).isEqualTo(1);
                assertThat(resources.bufferedBytes()).isGreaterThanOrEqualTo(body.length);
                assertThat(resources.resourceMetricsSnapshot()).containsEntry("nativeDrainingRequests", 1);
                assertThat(forwarder.resourceMetricsSnapshot())
                        .containsEntry("utf8BodiesActive", segmented ? 1 : 0);
            });
            release.countDown();
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                assertThat(resources.active()).isZero();
                assertThat(resources.bufferedBytes()).isZero();
                assertThat(resources.resourceMetricsSnapshot()).containsEntry("nativeDrainingRequests", 0);
                assertThat(forwarder.resourceMetricsSnapshot()).containsEntry("utf8BodiesActive", 0);
            });
        } finally {
            allowResponse.countDown();
            release.countDown();
            await().atMost(Duration.ofSeconds(3)).until(() -> resources.active() == 0);
            pipeline.setServeEventListeners(List.of());
            targetRepository.deleteAll();
            downstream.stop(0);
        }
    }

    @Test
    void timedOutSynchronousWorkRetainsCapacityAndCannotStartALaterForward() throws Exception {
        createHttpRule("/available", "GET", "ok");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger downstreamCalls = new AtomicInteger();
        HttpServer downstream = HttpServer.create(new InetSocketAddress(
                InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0), 0);
        downstream.createContext("/", exchange -> {
            downstreamCalls.incrementAndGet();
            exchange.sendResponseHeaders(200, 2);
            try (var output = exchange.getResponseBody()) { output.write(new byte[]{'o', 'k'}); }
            finally { exchange.close(); }
        });
        downstream.start();
        try {
            targets.create(new HttpTargetConnectionRequest(null, "No work after deadline",
                    "http://127.0.0.1:" + downstream.getAddress().getPort(), "NONE", null, null,
                    false, 1, 1, true, true, true));
            doAnswer(invocation -> {
                MockRequest request = invocation.getArgument(0);
                if (request.getPath().equals("/blocked")) {
                    entered.countDown();
                    assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                }
                return invocation.callRealMethod();
            }).when(pipeline).prepareBodyForMatching(any(MockRequest.class), anyList());
            HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
            var reply = client.sendAsync(request("/blocked").POST(HttpRequest.BodyPublishers.ofString("x".repeat(65536)))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(reply.get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(504);
            assertThat(resources.workers.getActiveCount()).isEqualTo(1);
            assertThat(resources.active()).as("unfinished work still owns the admission slot").isEqualTo(1);
            assertThat(resources.bufferedBytes()).as("unfinished work still owns its body").isGreaterThanOrEqualTo(65536);
            assertThat(client.send(request("/available").build(), HttpResponse.BodyHandlers.ofString()).statusCode())
                    .isEqualTo(503);
            release.countDown();
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                assertThat(resources.workers.getActiveCount()).isZero();
                assertThat(resources.active()).isZero();
                assertThat(resources.bufferedBytes()).isZero();
            });
            assertThat(downstreamCalls).hasValue(0);
            var recovered = client.send(request("/available").build(), HttpResponse.BodyHandlers.ofString());
            assertThat(recovered.statusCode()).isEqualTo(200);
            assertThat(recovered.body()).isEqualTo("ok");
        } finally {
            release.countDown();
            await().atMost(Duration.ofSeconds(3)).until(() -> resources.workers.getActiveCount() == 0);
            targetRepository.deleteAll();
            downstream.stop(0);
        }
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mock" + path))
                .timeout(Duration.ofSeconds(6));
    }
}
