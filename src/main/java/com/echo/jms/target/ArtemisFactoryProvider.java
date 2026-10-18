package com.echo.jms.target;

import com.echo.config.JmsProperties;
import jakarta.jms.ConnectionFactory;
import lombok.extern.slf4j.Slf4j;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.apache.activemq.artemis.core.remoting.impl.netty.TransportConstants;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class ArtemisFactoryProvider implements JmsTargetFactoryProvider {

    @Override
    public boolean supports(String type) {
        return "artemis".equalsIgnoreCase(type);
    }

    @Override
    public ConnectionFactory create(JmsProperties.Target target) {
        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(target.getServerUrl());
        if (target.getUsername() != null && !target.getUsername().isBlank()) {
            factory.setUser(target.getUsername());
            factory.setPassword(target.getPassword());
        }
        log.info("Created Artemis ConnectionFactory for: {}", target.getServerUrl());
        return factory;
    }

    @Override
    public ConnectionFactory createOutbound(JmsProperties.Target target) {
        var factory = (ActiveMQConnectionFactory) create(target);
        long timeoutMs = target.getTimeoutSeconds() * 1000L;
        factory.setCallTimeout(bounded(factory.getCallTimeout(), timeoutMs));
        factory.setCallFailoverTimeout(bounded(factory.getCallFailoverTimeout(), timeoutMs));
        // The forwarder retires a failed client and reconnects on the next request.
        // Provider retry loops must not retain the source listener indefinitely.
        factory.setInitialConnectAttempts(0);
        factory.setReconnectAttempts(0);
        var connectors = factory.getServerLocator().getStaticTransportConfigurations();
        if (connectors != null) {
            for (var connector : connectors) {
                var params = connector.getParams();
                Object existing = params.get(TransportConstants.NETTY_CONNECT_TIMEOUT);
                long configured = existing == null ? timeoutMs : Long.parseLong(existing.toString());
                params.put(TransportConstants.NETTY_CONNECT_TIMEOUT, (int) bounded(configured, timeoutMs));
            }
        }
        return factory;
    }

    private static long bounded(long configured, long maximum) {
        return configured > 0 ? Math.min(configured, maximum) : maximum;
    }
}
