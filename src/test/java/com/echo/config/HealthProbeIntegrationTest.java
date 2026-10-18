package com.echo.config;

import com.echo.integration.base.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthContributorRegistry;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HealthProbeIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private HealthContributorRegistry contributors;
    @Autowired
    private ApplicationContext context;

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
