package com.echo.config;

import com.echo.dto.HttpTargetConnectionRequest;
import com.echo.integration.base.BaseIntegrationTest;
import com.echo.pipeline.HttpMockPipeline;
import com.echo.repository.HttpTargetConnectionRepository;
import com.echo.service.HttpTargetConnectionService;
import com.echo.service.ServeEvent;
import com.echo.service.ServeEventListener;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@TestPropertySource(properties = {"echo.http.transport=native", "echo.jms.enabled=false",
        "echo.http.request-timeout-ms=5000", "echo.http.native.workers=1",
        "echo.http.forward.pool-acquire-timeout-ms=500",
        "echo.http.native.segmented-forwarding-enabled=true"})
class NativeUtf8ResponseLifetimeIntegrationTest extends BaseIntegrationTest {
    @LocalServerPort int port;
    @Autowired NativeHttpResources resources;
    @Autowired HttpMockPipeline pipeline;
    @Autowired HttpTargetConnectionService targets;
    @Autowired HttpTargetConnectionRepository targetRepository;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void slowReadersReleaseOwnedSegmentsAfterEitherTheDeadlineOrClientDisconnect(boolean disconnect) throws Exception {
        createHttpRule("/available", "GET", "ok");
        byte[] body = "x".repeat(4 * 1024 * 1024).getBytes(StandardCharsets.UTF_8);
        AtomicBoolean observedBytes = new AtomicBoolean();
        pipeline.setServeEventListeners(List.of(new ServeEventListener() {
            @Override public boolean requiresFullResponseBody() { return false; }
            @Override public void onServed(ServeEvent event) {
                if (event.getEndpoint().equals("/slow")) observedBytes.set(event.getUtf8ResponseBody() != null);
            }
        }));
        HttpServer downstream = HttpServer.create(new InetSocketAddress(
                InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0), 0);
        downstream.createContext("/", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/plain;charset=UTF-8");
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) { output.write(body); }
            finally { exchange.close(); }
        });
        downstream.start();
        try {
            targets.create(new HttpTargetConnectionRequest(null, "Slow raw response",
                    "http://127.0.0.1:" + downstream.getAddress().getPort(), "NONE", null, null,
                    false, 1, 3, true, true, true));
            try (Socket client = new Socket()) {
                client.setReceiveBufferSize(1024);
                client.connect(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), port));
                client.setSoTimeout(6000);
                client.getOutputStream().write("GET /mock/slow HTTP/1.1\r\nHost: localhost\r\n\r\n"
                        .getBytes(StandardCharsets.US_ASCII));
                client.getOutputStream().flush();
                StringBuilder headers = new StringBuilder();
                while (!headers.toString().endsWith("\r\n\r\n") && headers.length() < 8192) {
                    int value = client.getInputStream().read();
                    assertThat(value).isNotEqualTo(-1);
                    headers.append((char) value);
                }
                assertThat(headers.toString()).startsWith("HTTP/1.1 200");
                assertThat(observedBytes.get()).isTrue();
                assertThat(resources.active()).isEqualTo(1);
                assertThat(resources.bufferedBytes()).isGreaterThanOrEqualTo(body.length);
                if (disconnect) client.close();
                // The other case deliberately keeps the peer open without consuming its body.
                await().atMost(Duration.ofSeconds(disconnect ? 2 : 7)).untilAsserted(() -> {
                    assertThat(resources.active()).isZero();
                    assertThat(resources.bufferedBytes()).isZero();
                    assertThat(resources.workers.getQueue()).isEmpty();
                    assertThat(resources.workers.getActiveCount()).isZero();
                });
            }
            assertThat(restTemplate.getForObject("/mock/available", String.class)).isEqualTo("ok");
        } finally {
            pipeline.setServeEventListeners(List.of());
            targetRepository.deleteAll();
            downstream.stop(0);
        }
    }
}
