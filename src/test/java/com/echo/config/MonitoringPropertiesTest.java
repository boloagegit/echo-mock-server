package com.echo.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class MonitoringPropertiesTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(Binding.class);

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.boot.context.properties.EnableConfigurationProperties(MonitoringProperties.class)
    static class Binding {}

    @Test
    void everySwitchDefaultsToEnabled() {
        context.run(c -> {
            var p = c.getBean(MonitoringProperties.class);
            assertThat(p.isEnabled() && p.isJvmEnabled() && p.isCachesEnabled() && p.isSchedulerEnabled()
                    && p.isJmsEnabled() && p.isHttpEnabled() && p.isDatabaseEnabled()
                    && p.isRequestLogEnabled() && p.isApplicationLogEnabled() && p.isStorageEnabled()).isTrue();
        });
    }

    @Test
    void deploymentConfigurationCanDisableEveryGroupIndependently() {
        context.withPropertyValues("echo.monitoring.enabled=false", "echo.monitoring.jvm-enabled=false",
                "echo.monitoring.caches-enabled=false", "echo.monitoring.scheduler-enabled=false",
                "echo.monitoring.jms-enabled=false", "echo.monitoring.http-enabled=false",
                "echo.monitoring.database-enabled=false", "echo.monitoring.request-log-enabled=false",
                "echo.monitoring.application-log-enabled=false", "echo.monitoring.storage-enabled=false")
                .run(c -> {
                    var p = c.getBean(MonitoringProperties.class);
                    assertThat(p.isEnabled() || p.isJvmEnabled() || p.isCachesEnabled() || p.isSchedulerEnabled()
                            || p.isJmsEnabled() || p.isHttpEnabled() || p.isDatabaseEnabled()
                            || p.isRequestLogEnabled() || p.isApplicationLogEnabled() || p.isStorageEnabled()).isFalse();
                });
    }
}
