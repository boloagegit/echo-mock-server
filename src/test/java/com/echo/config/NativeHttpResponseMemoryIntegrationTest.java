package com.echo.config;

import com.echo.integration.base.BaseIntegrationTest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@TestPropertySource(properties = {
        "echo.http.transport=native", "echo.http.request-timeout-ms=5000",
        "echo.http.forward.pool-acquire-timeout-ms=500",
        "echo.http.native.workers=1", "echo.http.native.max-requests=8",
        "echo.http.native.max-body-bytes=2097152", "echo.http.native.max-buffered-bytes=8388608"
})
class NativeHttpResponseMemoryIntegrationTest extends BaseIntegrationTest {
    @LocalServerPort int port;
    @Autowired NativeHttpResources resources;
    @Autowired com.echo.service.HttpTargetConnectionService targets;
    @Autowired com.echo.repository.HttpTargetConnectionRepository targetRepository;

    @Test
    void forwardingBodyRemainsAccountedWhileEncodingWorkerIsBlocked() throws Exception {
        CountDownLatch downstreamReady = new CountDownLatch(1);
        CountDownLatch releaseDownstream = new CountDownLatch(1);
        CountDownLatch workerBlocked = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        byte[] body = "x".repeat(65536).getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/queued", exchange -> {
            downstreamReady.countDown();
            try {
                if (!releaseDownstream.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("test timed out");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        targets.create(new com.echo.dto.HttpTargetConnectionRequest(null, "Queued response",
                "http://127.0.0.1:" + server.getAddress().getPort(), "NONE", null, null, false,
                1, 2, true, true, true));
        try {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mock/queued"))
                    .timeout(Duration.ofSeconds(6)).build();
            var reply = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
                    .sendAsync(request, HttpResponse.BodyHandlers.ofString());
            assertThat(downstreamReady.await(3, TimeUnit.SECONDS)).isTrue();
            resources.workers.execute(() -> {
                workerBlocked.countDown();
                try { releaseWorker.await(4, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertThat(workerBlocked.await(2, TimeUnit.SECONDS)).isTrue();
            releaseDownstream.countDown();
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                assertThat(resources.resourceMetricsSnapshot().get("nativeQueued")).isEqualTo(1);
                assertThat(resources.bufferedBytes()).isGreaterThanOrEqualTo(2L * body.length);
            });
            assertThat(reply.isDone()).isFalse();
            releaseWorker.countDown();
            var response = reply.get(3, TimeUnit.SECONDS);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo(new String(body, StandardCharsets.UTF_8));
            assertReleased();
        } finally {
            releaseWorker.countDown();
            releaseDownstream.countDown();
            server.stop(0);
            targetRepository.deleteAll();
        }
    }

    @Test
    void largeUnicodeAndWhitespaceBodiesKeepTheirBytesLengthAndContentType() throws Exception {
        String body = " ".repeat(300000) + "{\"value\":\"銀行😀".repeat(20000) + "\"}";
        createHttpRule("/unicode-stream", "GET", body);
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mock/unicode-stream"))
                .timeout(Duration.ofSeconds(4)).build();
        var response = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
                .send(request, HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(body.getBytes(StandardCharsets.UTF_8));
        assertThat(response.headers().firstValue("Content-Length")).contains(Integer.toString(response.body().length));
        assertThat(response.headers().firstValue("Content-Type")).contains("application/json");
        assertReleased();
    }

    private void assertReleased() {
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            assertThat(resources.active()).isZero();
            assertThat(resources.bufferedBytes()).isZero();
            assertThat(resources.resourceMetricsSnapshot().get("nativeQueued")).isEqualTo(0);
        });
    }
}
