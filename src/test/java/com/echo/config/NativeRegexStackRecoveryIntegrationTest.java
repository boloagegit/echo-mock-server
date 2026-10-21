package com.echo.config;

import com.echo.integration.base.BaseIntegrationTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@TestPropertySource(properties = {"echo.http.transport=native", "echo.jms.enabled=false",
        "echo.request-log.enabled=false", "echo.http.request-timeout-ms=2000"})
class NativeRegexStackRecoveryIntegrationTest extends BaseIntegrationTest {
    @LocalServerPort int port;
    @Autowired NativeHttpResources resources;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void recursivePatternReturnsAnExplicitErrorWithoutFallingBackOrLosingServiceCapacity(boolean legacy) throws Exception {
        var rule = new HashMap<String, Object>(Map.of("protocol", "HTTP", "method", "GET",
                "matchKey", "/regex-stack", "status", 200, "responseBody", "matched"));
        if (legacy) rule.put("headerCondition", "X-Probe~=^(a|aa)+$");
        else rule.put("matchSpec", Map.of("source", "header", "name", "X-Probe", "op", "matches", "value", "^(a|aa)+$"));
        assertThat(adminClient().postForEntity("/api/admin/rules", rule, Map.class).getStatusCode().value()).isEqualTo(201);
        // An unconditional rule would otherwise mask an indeterminate condition as a successful route.
        assertThat(adminClient().postForEntity("/api/admin/rules", Map.of("protocol", "HTTP", "method", "GET",
                "matchKey", "/regex-stack", "status", 200, "responseBody", "fallback"), Map.class)
                .getStatusCode().value()).isEqualTo(201);
        var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mock/regex-stack"))
                .header("X-Probe", "a".repeat(5000)).timeout(Duration.ofSeconds(5)).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).contains("REGEX_STACK_CAPACITY_EXHAUSTED");
        var healthy = client.send(HttpRequest.newBuilder(request.uri()).header("X-Probe", "a").build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(healthy.statusCode()).isEqualTo(200);
        assertThat(healthy.body()).isEqualTo("matched");
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            assertThat(resources.active()).isZero();
            assertThat(resources.bufferedBytes()).isZero();
        });
    }
}
