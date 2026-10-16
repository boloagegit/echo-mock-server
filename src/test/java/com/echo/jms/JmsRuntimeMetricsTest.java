package com.echo.jms;

import com.echo.config.MonitoringProperties;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class JmsRuntimeMetricsTest {
    @Test
    void disabledTelemetryDoesNotIncrementOrRememberTimestamps() {
        for (boolean global : new boolean[]{true, false}) {
            var properties = new MonitoringProperties();
            if (global) properties.setEnabled(false); else properties.setJmsEnabled(false);
            var metrics = new JmsRuntimeMetrics(properties);
            metrics.listenerStarted(); metrics.listenerExited(); metrics.replySent(); metrics.replyFailed();
            metrics.forwardStarted(); metrics.forwardExited(); metrics.forwardFailed(); metrics.receiveTimedOut();
            metrics.invalidReply(); metrics.cleanupFailed();
            assertThat(metrics.snapshot().values()).allMatch(value -> ((Number) value).longValue() == 0);
            assertThat(metrics.snapshot()).doesNotContainKeys("lastListenerExit", "lastForwardExit");
        }
    }

    @Test
    void concurrentEventsHaveExactFinalCountsWithoutRetainedRequestEntries() throws Exception {
        var metrics = new JmsRuntimeMetrics(new MonitoringProperties());
        var workers = Executors.newFixedThreadPool(4);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int worker = 0; worker < 4; worker++) futures.add(workers.submit(() -> {
                for (int i = 0; i < 500; i++) {
                    metrics.listenerStarted(); metrics.forwardStarted(); metrics.replySent();
                    metrics.forwardExited(); metrics.listenerExited();
                }
            }));
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
            assertThat(metrics.snapshot()).containsEntry("listenerActive", 0).containsEntry("forwardActive", 0)
                    .containsEntry("listenerExits", 2000L).containsEntry("forwardExits", 2000L).containsEntry("replySent", 2000L);
            assertThat(metrics.snapshot()).hasSize(12);
        } finally { workers.shutdownNow(); assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }
}
