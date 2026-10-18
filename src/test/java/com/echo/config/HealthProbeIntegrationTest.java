package com.echo.config;

import com.echo.integration.base.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthContributorRegistry;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class HealthProbeIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private HealthContributorRegistry contributors;
    @Autowired
    private ApplicationContext context;

    @BeforeEach
    void waitForTheAsynchronouslyStartedSourceListener() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            var response = restTemplate.getForEntity("/actuator/health/readiness", String.class);
            if (response.getStatusCode() == HttpStatus.OK) {
                return;
            }
            if (System.nanoTime() >= deadline) {
                assertThat(response.getStatusCode()).as("initial readiness: %s", response.getBody())
                        .isEqualTo(HttpStatus.OK);
            }
            Thread.sleep(50);
        }
    }

    @Test
    void anonymousProbesExposeOnlyStatusAndOtherActuatorPathsStayProtected() {
        assertThat(restTemplate.getForEntity("/actuator/health/liveness", String.class).getBody())
                .isEqualTo("{\"status\":\"UP\"}");
        var readiness = restTemplate.getForEntity("/actuator/health/readiness", Map.class);
        assertThat(readiness.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readiness.getBody()).containsOnlyKeys("status").containsEntry("status", "UP");
        assertThat(restTemplate.getForEntity("/actuator/health", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(restTemplate.postForEntity("/actuator/health/liveness", null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(context.containsBean("jmsHealthContributor")).isFalse();
        assertThat(context.containsBean("jmsHealthIndicator")).isFalse();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"*/*", "application/json"})
    void plainHttpClientsCanReadProbesWithoutSpringAddingAnAcceptHeader(String accept) throws Exception {
        try (var client = HttpClients.createDefault()) {
            for (String probe : new String[]{"liveness", "readiness"}) {
                var request = new HttpGet(restTemplate.getRootUri() + "/actuator/health/" + probe);
                if (accept != null) {
                    request.setHeader("Accept", accept);
                }
                client.execute(request, response -> {
                    assertThat(response.getCode()).as("%s with Accept=%s", probe, accept).isEqualTo(200);
                    assertThat(EntityUtils.toString(response.getEntity())).isEqualTo("{\"status\":\"UP\"}");
                    return null;
                });
            }
        }
    }

    @Test
    void databaseOutageAffectsReadinessWithoutMakingTheProcessUnlive() {
        var original = contributors.unregisterContributor("db");
        assertThat(original).isNotNull();
        try {
            contributors.registerContributor("db", (HealthIndicator) () -> Health.down().build());

            assertThat(restTemplate.getForEntity("/actuator/health/readiness", String.class).getStatusCode())
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(restTemplate.getForEntity("/actuator/health/liveness", String.class).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        } finally {
            contributors.unregisterContributor("db");
            contributors.registerContributor("db", original);
        }
        assertThat(restTemplate.getForEntity("/actuator/health/readiness", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }
}
