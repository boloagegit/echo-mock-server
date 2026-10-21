package com.echo.service;

import com.echo.agent.AgentStatus;
import com.echo.agent.LogAgent;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** Observe existing workers and counters without reading spool bodies or opening database connections. */
@Component
public final class RequestLogHealthIndicator implements HealthIndicator {
    private final SystemConfigService config;
    private final ObjectProvider<LogAgent> agents;

    public RequestLogHealthIndicator(SystemConfigService config, ObjectProvider<LogAgent> agents) {
        this.config = config;
        this.agents = agents;
    }

    @Override
    public Health health() {
        try {
            if (config.isRequestLogDisabled()) return Health.up().build();
            LogAgent agent = agents.getIfAvailable();
            if (agent == null || agent.getStatus() != AgentStatus.RUNNING) return Health.down().build();
            if (!agent.isDurableMode()) return Health.up().build();
            var stats = agent.getStats();
            boolean healthy = agent.isDurableConsumerRunning() && !agent.isStorageUnavailable();
            return (healthy ? Health.up() : Health.down())
                    .withDetail("consumerRunning", stats.isDurableConsumerRunning())
                    .withDetail("lastSuccessfulWriteEpochMillis", stats.getLastSuccessfulWriteEpochMillis())
                    .withDetail("stoppedAtSequence", stats.getStoppedAtSequence())
                    .withDetail("retainedBytes", stats.getQueueBytes()).build();
        } catch (RuntimeException failure) {
            return Health.down().build();
        }
    }
}
