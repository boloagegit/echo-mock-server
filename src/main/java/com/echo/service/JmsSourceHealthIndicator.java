package com.echo.service;

import com.echo.config.JmsProperties;
import com.echo.jms.JmsConnectionManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** Observe the existing listener; a health probe must never open another broker connection. */
@Component
public class JmsSourceHealthIndicator implements HealthIndicator {
    private final JmsProperties properties;
    private final ObjectProvider<JmsConnectionManager> connectionManager;

    public JmsSourceHealthIndicator(JmsProperties properties,
                                    ObjectProvider<JmsConnectionManager> connectionManager) {
        this.properties = properties;
        this.connectionManager = connectionManager;
    }

    @Override
    public Health health() {
        if (!properties.isEnabled()) {
            return Health.up().build();
        }
        try {
            JmsConnectionManager manager = connectionManager.getIfAvailable();
            if (manager != null && manager.listenerState() == JmsConnectionManager.ListenerState.RECEIVING) {
                return Health.up().build();
            }
            return Health.outOfService().build();
        } catch (RuntimeException unavailable) {
            // No endpoint, credentials or provider exception is exposed by a public probe.
            return Health.down().build();
        }
    }
}
