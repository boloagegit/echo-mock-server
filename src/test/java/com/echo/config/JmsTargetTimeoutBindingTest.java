package com.echo.config;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class JmsTargetTimeoutBindingTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @ParameterizedTest
    @ValueSource(ints = {1, 30, 300})
    void acceptsSupportedTimeouts(int seconds) {
        contextRunner.withPropertyValues("echo.jms.target.timeout-seconds=" + seconds).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(JmsProperties.class).getTarget().getTimeoutSeconds()).isEqualTo(seconds);
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 301, 536870912, Integer.MAX_VALUE})
    void rejectsInvalidTimeoutsDuringConfigurationBinding(int seconds) {
        contextRunner.withPropertyValues("echo.jms.target.timeout-seconds=" + seconds).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .hasStackTraceContaining("echo.jms.target.timeout-seconds must be between 1 and 300");
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(JmsProperties.class)
    static class PropertiesConfiguration { }
}
