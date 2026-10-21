package com.echo.config;

import com.echo.dto.HttpTargetConnectionRequest;
import com.echo.integration.base.BaseIntegrationTest;
import com.echo.repository.HttpTargetConnectionRepository;
import com.echo.service.HttpOutboundForwarder;
import com.echo.service.HttpTargetConnectionService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@TestPropertySource(properties = {"echo.http.transport=native", "echo.jms.enabled=false",
        "echo.http.request-timeout-ms=10000", "echo.http.native.max-forwarding-bodies=1",
        "echo.http.forward.pool-acquire-timeout-ms=500",
        "echo.http.native.segmented-forwarding-enabled=true"})
class NativeForwardBodyAdmissionIntegrationTest extends BaseIntegrationTest {
    private static final int LARGE_BYTES = 4 * 1024 * 1024;
    private static final String SMALL = "s".repeat(65536);
    @LocalServerPort int port;
    @Autowired NativeHttpResources resources;
    @Autowired HttpOutboundForwarder forwarder;
    @Autowired HttpTargetConnectionService targets;
    @Autowired HttpTargetConnectionRepository targetRepository;
    private HttpServer downstream;
    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @BeforeEach
    void startDownstream() throws Exception {
        byte[] large = "x".repeat(LARGE_BYTES).getBytes(StandardCharsets.UTF_8);
        byte[] small = SMALL.getBytes(StandardCharsets.UTF_8);
        downstream = HttpServer.create(new InetSocketAddress(loopback(), 0), 0);
        downstream.createContext("/", exchange -> {
            boolean held = exchange.getRequestURI().getPath().equals("/hold");
            boolean empty = exchange.getRequestURI().getPath().equals("/empty");
            byte[] body = held ? large : empty ? new byte[0] : small;
            exchange.getResponseHeaders().set("Content-Type", "text/plain;charset=UTF-8");
            exchange.sendResponseHeaders(200, empty ? -1
                    : exchange.getRequestURI().getPath().equals("/known") ? body.length : 0);
            try (var output = exchange.getResponseBody()) {
                if (body.length > 0) output.write(body);
            }
            finally { exchange.close(); }
        });
        downstream.start();
        targets.create(new HttpTargetConnectionRequest(null, "Body admission",
                "http://127.0.0.1:" + downstream.getAddress().getPort(), "NONE", null, null,
                false, 1, 3, true, true, true));
    }

    @AfterEach
    void stopDownstream() {
        targetRepository.deleteAll();
        if (downstream != null) downstream.stop(0);
    }

    @Test
    void deliveryOwnsTheSlotWhileKnownSmallResponsesAndCancelledWaitersDoNotConsumeIt() throws Exception {
        try (Socket held = holdResponse()) {
            try (Socket cancelled = open("/queued", false)) {
                await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertBodies(1, 1));
                cancelled.close();
                await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertBodies(1, 0));
            }
            assertSmall("/known");
            var empty = client.send(request("/empty"), HttpResponse.BodyHandlers.ofString());
            assertThat(empty.statusCode()).isEqualTo(200);
            assertThat(empty.body()).isEmpty();
            assertBodies(1, 0);
            assertThat(resources.bufferedBytes()).isGreaterThanOrEqualTo(LARGE_BYTES);
            var queued = client.sendAsync(request("/after-close"), HttpResponse.BodyHandlers.ofString());
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertBodies(1, 1));
            assertThat(queued.isDone()).isFalse();
            held.close();
            var response = queued.get(5, TimeUnit.SECONDS);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo(SMALL);
        }
        assertDrained();
    }

    @Test
    void aBodyQueueTimeoutIsControlledOverloadAndDoesNotReleaseAnotherResponsesSlot() throws Exception {
        try (Socket held = holdResponse()) {
            var response = client.send(request("/timeout"), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.body()).contains("HTTP_TRANSPORT_RESPONSE_CAPACITY_EXHAUSTED");
            assertBodies(1, 0);
            assertThat(resources.bufferedBytes()).isGreaterThanOrEqualTo(LARGE_BYTES);
            assertSmall("/known");
        }
        assertDrained();
        assertSmall("/recovered");
        assertDrained();
    }

    private Socket holdResponse() throws Exception {
        Socket held = open("/hold", true);
        StringBuilder headers = new StringBuilder();
        while (!headers.toString().endsWith("\r\n\r\n") && headers.length() < 8192) {
            int value = held.getInputStream().read();
            assertThat(value).isNotEqualTo(-1);
            headers.append((char) value);
        }
        assertThat(headers.toString()).startsWith("HTTP/1.1 200");
        assertBodies(1, 0);
        assertThat(resources.bufferedBytes()).isGreaterThanOrEqualTo(LARGE_BYTES);
        return held;
    }

    private Socket open(String path, boolean slow) throws Exception {
        Socket socket = new Socket();
        if (slow) socket.setReceiveBufferSize(1024);
        socket.connect(new InetSocketAddress(loopback(), port));
        socket.setSoTimeout(6000);
        socket.getOutputStream().write(("GET /mock" + path + " HTTP/1.1\r\nHost: localhost\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        return socket;
    }

    private HttpRequest request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mock" + path))
                .timeout(Duration.ofSeconds(5)).build();
    }

    private void assertSmall(String path) throws Exception {
        var response = client.send(request(path), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(SMALL);
    }

    private void assertBodies(int active, int pending) {
        assertThat(forwarder.resourceMetricsSnapshot()).containsEntry("utf8BodiesActive", active)
                .containsEntry("utf8BodiesPending", pending).containsEntry("utf8BodiesCapacity", 1);
    }

    private void assertDrained() {
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            assertBodies(0, 0);
            assertThat(resources.active()).isZero();
            assertThat(resources.bufferedBytes()).isZero();
            assertThat(forwarder.resourceMetricsSnapshot()).containsEntry("bufferedBytes", 0L);
        });
    }

    private static InetAddress loopback() throws Exception {
        return InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
    }
}
