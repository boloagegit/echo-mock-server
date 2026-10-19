package com.echo.config;

import com.echo.jms.target.JmsTargetFactoryProvider;
import jakarta.jms.ConnectionFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.connection.CachingConnectionFactory;

import java.util.List;

/**
 * Receive JMS requests from an external broker, such as TIBCO EMS or an Artemis broker on the company ESB.
 *
 * <p>When multiple Echo instances listen on the same broker queue ({@code echo.jms.queue}), the broker delivers each message to just one instance,
 * providing shared capacity and failover. The message's JMSReplyTo destination resides on the same broker.
 * Neither embedded Artemis nor {@code echo.jms.port} is started in this mode.</p>
 *
 * <p>Wrap the connection in {@link CachingConnectionFactory}, as Spring Boot does by default in embedded mode,
 * so replies and test sends reuse the connection. Recover it with the listener container after a disconnect.</p>
 */
@Configuration
@ConditionalOnProperty(name = "echo.jms.enabled", havingValue = "true")
@ConditionalOnExpression("T(com.echo.config.JmsProperties$Source).isExternalType('${echo.jms.source.type:embedded}')")
@Slf4j
public class ExternalJmsSourceConfig {

    @Bean
    public ConnectionFactory jmsConnectionFactory(JmsProperties properties,
                                                  List<JmsTargetFactoryProvider> providers) throws Exception {
        JmsProperties.Source source = properties.getSource();
        String type = source.getType().trim();
        if (source.getServerUrl() == null || source.getServerUrl().isBlank()) {
            throw new IllegalStateException("echo.jms.source.server-url is required when echo.jms.source.type is "
                    + type);
        }
        if (source.getReconnectIntervalMs() < 100) {
            throw new IllegalStateException("echo.jms.source.reconnect-interval-ms must be at least 100");
        }
        JmsTargetFactoryProvider provider = providers.stream()
                .filter(candidate -> candidate.supports(type))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Unsupported echo.jms.source.type: " + type
                        + " (use embedded, artemis or tibco)"));
        ConnectionFactory target = provider.create(source.asConnectionTarget());
        CachingConnectionFactory caching = new CachingConnectionFactory(target);
        caching.setReconnectOnException(true);
        log.info("JMS requests are received from external {} broker {} queue {}; embedded Artemis is not started",
                type, source.getServerUrl(), properties.getQueue());
        return caching;
    }
}
