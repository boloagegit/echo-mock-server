package com.echo.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JmsReplyFailurePolicyBindingTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void shippedConfigurationPreservesLegacyPolicyByDefault() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(JmsProperties.class).isRollbackOnReplyFailure()).isFalse();
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bindsExplicitProperty(boolean enabled) {
        runner.withPropertyValues("echo.jms.rollback-on-reply-failure=" + enabled).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(JmsProperties.class).isRollbackOnReplyFailure()).isEqualTo(enabled);
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bindsDocumentedEnvironmentVariable(boolean enabled) {
        runner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                new SystemEnvironmentPropertySource("testEnvironment", Map.of(
                        "ECHO_JMS_ROLLBACK_ON_REPLY_FAILURE", Boolean.toString(enabled)))))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(JmsProperties.class).isRollbackOnReplyFailure()).isEqualTo(enabled);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(JmsProperties.class)
    static class PropertiesConfiguration { }
}
