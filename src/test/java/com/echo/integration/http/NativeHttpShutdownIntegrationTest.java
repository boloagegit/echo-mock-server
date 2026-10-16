package com.echo.integration.http;

import com.echo.dto.RuleDto;
import com.echo.entity.Protocol;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.server.GracefulShutdownResult;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "echo.http.transport=native", "echo.jms.enabled=false", "server.shutdown=graceful"})
@ActiveProfiles("test")
@AutoConfigureObservability
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class NativeHttpShutdownIntegrationTest {
    @Autowired ServletWebServerApplicationContext context;
    @Autowired TestRestTemplate rest;
    @Autowired MeterRegistry meters;
    @LocalServerPort int port;

    @Test
    void gracefulShutdownDrainsTheNativeExchangeAndRefusesNewRequests() throws Exception {
        var rule = RuleDto.builder().protocol(Protocol.HTTP).matchKey("/drain").method("GET")
                .status(200).responseBody("completed before shutdown").delayMs(1200L).sseEnabled(false).build();
        assertThat(rest.withBasicAuth("admin", "admin").postForEntity("/api/admin/rules", rule, String.class)
                .getStatusCode().value()).isEqualTo(201);
        var client = HttpClient.newHttpClient();
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mock/drain"))
                .version(HttpClient.Version.HTTP_1_1).timeout(Duration.ofSeconds(5)).build();
        var pending = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        await().atMost(Duration.ofSeconds(3)).until(() -> {
            var gauge = meters.find("echo.http.native.active").gauge();
            return gauge != null && gauge.value() == 1;
        });
        var drained = new CompletableFuture<GracefulShutdownResult>();
        context.getWebServer().shutDownGracefully(drained::complete);
        assertThat(drained.isDone()).isFalse();
        assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(503);
        var response = pending.get(5, TimeUnit.SECONDS);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("completed before shutdown");
        assertThat(drained.get(5, TimeUnit.SECONDS)).isEqualTo(GracefulShutdownResult.IDLE);
        assertThat(meters.find("echo.http.native.active").gauge().value()).isZero();
        assertThat(meters.find("echo.http.native.buffered.bytes").gauge().value()).isZero();
    }
}
