package com.echo.integration.http;

import com.echo.integration.base.BaseIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@AutoConfigureObservability
@Import(NativeHttpExtensionIntegrationTest.Configuration.class)
@TestPropertySource(properties = {"echo.http.transport=native", "echo.jms.enabled=false"})
class NativeHttpExtensionIntegrationTest extends BaseIntegrationTest {
    @Autowired MeterRegistry meters;

    @Test
    void registeredServletFilterStillRunsWhenNativeTransportIsSelected() {
        createHttpRule("/custom-filter", "GET", "checked");
        var response = restTemplate.getForEntity("/mock/custom-filter", String.class);
        assertThat(response.getBody()).isEqualTo("checked");
        assertThat(response.getHeaders().getFirst("X-Custom-Filter")).isEqualTo("present");
        assertThat(meters.find("echo.http.native.requests").counter()).isNull();
        // Servlet observations also finish after the response may have reached the caller.
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            var timer = meters.find("http.server.requests").tag("uri", "/mock/**").timer();
            assertThat(timer).isNotNull();
            assertThat(timer.count()).isEqualTo(1);
        });
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean
        Filter customFilter() {
            return (request, response, chain) -> {
                ((HttpServletResponse) response).setHeader("X-Custom-Filter", "present");
                chain.doFilter(request, response);
            };
        }
    }
}
