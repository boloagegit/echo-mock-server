package com.echo.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.settings.impl.AddressFullMessagePolicy;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

class JmsPolicyDiagnosticsTest {
    @Test
    void policySummaryPreservesRoutingAndRedeliveryAndDoesNotExposeCredentials() throws Exception {
        var properties = new JmsProperties();
        properties.setPassword("private-token");
        var configuration = new ConfigurationImpl();
        var events = capture(properties, configuration);
        var settings = configuration.getAddressSettings().get("#");
        assertThat((Object) settings.getDeadLetterAddress()).isNull();
        assertThat((Object) settings.getExpiryAddress()).isNull();
        assertThat(settings.getMaxDeliveryAttempts()).isEqualTo(-1);
        assertThat(settings.getAddressFullMessagePolicy()).isEqualTo(AddressFullMessagePolicy.PAGE);
        assertThat(events).anySatisfy(event -> assertThat(event.getFormattedMessage()).contains("Echo JMS wildcard policy:"));
        assertThat(events).allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain("private-token"));
    }

    @Test
    void finiteRetryRiskRemainsVisibleEvenWhenSummaryIsDisabled() throws Exception {
        var properties = new JmsProperties();
        properties.setPolicySummaryEnabled(false);
        properties.setMaxDeliveryAttempts(3);
        var events = capture(properties, new ConfigurationImpl());
        assertThat(events).noneSatisfy(event -> assertThat(event.getFormattedMessage()).contains("Echo JMS wildcard policy:"));
        assertThat(events).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
            assertThat(event.getFormattedMessage()).contains("messages are removed when attempts are exhausted");
        });
    }

    private static java.util.List<ILoggingEvent> capture(JmsProperties properties, ConfigurationImpl configuration) throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(ArtemisConfig.class);
        var previous = logger.getLevel();
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.setLevel(ch.qos.logback.classic.Level.INFO);
        logger.addAppender(appender);
        try {
            new ArtemisConfig().artemisConfigurationCustomizer(properties).customize(configuration);
            return java.util.List.copyOf(appender.list);
        } finally { logger.detachAppender(appender); appender.stop(); logger.setLevel(previous); }
    }
}
