package com.echo.config;

import com.echo.integration.base.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@TestPropertySource(properties = {
        "echo.http.transport=native", "echo.http.request-timeout-ms=1000",
        "echo.http.native.workers=1", "echo.http.native.max-requests=4"
})
class NativeHttpDeadlinesIntegrationTest extends BaseIntegrationTest {
    @LocalServerPort int port;
    @Autowired NativeHttpResources resources;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void incompleteUploadsUseNoApplicationWorkerAndCannotBlockSmallRequests(boolean chunked) throws Exception {
        createHttpRule("/available", "GET", "ok");
        assertThat(get("/available").body()).isEqualTo("ok");
        var clients = new ArrayList<Socket>();
        try {
            for (int i = 0; i < 3; i++) {
                var client = connect();
                clients.add(client);
                String framing = chunked ? "Transfer-Encoding: chunked\r\n\r\n10\r\nx"
                        : "Content-Length: 100\r\n\r\nx";
                client.getOutputStream().write(("POST /mock/upload HTTP/1.1\r\nHost: localhost\r\n"
                        + "Content-Type: text/plain\r\n" + framing).getBytes(StandardCharsets.US_ASCII));
                client.getOutputStream().flush();
            }
            await().atMost(Duration.ofSeconds(2)).pollInterval(Duration.ofMillis(5))
                    .untilAsserted(() -> assertThat(resources.active()).isEqualTo(3));
            assertThat(resources.workers.getActiveCount()).isZero();
            assertThat(get("/available").body()).isEqualTo("ok");
            // Keep the peers open: recovery must come from the server deadline.
            assertReleased();
        } finally {
            for (var client : clients) {
                client.close();
            }
        }
    }

    @Test
    void aClientThatStopsReadingCannotRetainTheResponsePastTheDeadline() throws Exception {
        createHttpRule("/large", "GET", "x".repeat(4 * 1024 * 1024));
        createHttpRule("/available", "GET", "ok");
        try (var client = new Socket()) {
            client.setReceiveBufferSize(1024);
            client.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            client.setSoTimeout(3000);
            client.getOutputStream().write("GET /mock/large HTTP/1.1\r\nHost: localhost\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            client.getOutputStream().flush();
            var header = new StringBuilder();
            while (!header.toString().endsWith("\r\n\r\n") && header.length() < 8192) {
                int value = client.getInputStream().read();
                assertThat(value).isNotEqualTo(-1);
                header.append((char) value);
            }
            assertThat(header.toString()).startsWith("HTTP/1.1 200");
            assertThat(resources.bufferedBytes()).isPositive();
            assertReleased();
            assertThat(get("/available").body()).isEqualTo("ok");
        }
    }

    @Test
    void queuedWorkTimesOutAndIsRemovedWhileTheWorkerIsStillBusy() throws Exception {
        createHttpRule("/available", "GET", "ok");
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        resources.workers.execute(() -> {
            started.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            var response = get("/available");
            assertThat(response.statusCode()).isEqualTo(504);
            assertThat(response.body()).isEqualTo("Request timeout");
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
                assertThat(resources.active()).isZero();
                assertThat(resources.workers.getQueue()).isEmpty();
            });
            assertThat(resources.workers.getActiveCount()).isEqualTo(1);
        } finally {
            release.countDown();
        }
        assertReleased();
        assertThat(get("/available").body()).isEqualTo("ok");
    }

    private Socket connect() throws Exception {
        var socket = new Socket(InetAddress.getLoopbackAddress(), port);
        socket.setSoTimeout(3000);
        return socket;
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mock" + path))
                        .timeout(Duration.ofSeconds(4)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private void assertReleased() {
        await().atMost(Duration.ofSeconds(4)).untilAsserted(() -> {
            assertThat(resources.active()).isZero();
            assertThat(resources.bufferedBytes()).isZero();
            assertThat(resources.workers.getActiveCount()).isZero();
            assertThat(resources.workers.getQueue()).isEmpty();
        });
    }
}
