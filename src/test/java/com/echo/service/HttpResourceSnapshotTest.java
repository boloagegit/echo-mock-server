package com.echo.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.netty.resources.ConnectionPoolMetrics;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Short bounded-collection checks; no downstream connection or long-running traffic. */
class HttpResourceSnapshotTest {
    private final HttpOutboundForwarder forwarder = new HttpOutboundForwarder(mock(HttpTargetConnectionService.class));

    @AfterEach void cleanup() { forwarder.closeClients(); }

    @Test
    @SuppressWarnings("unchecked")
    void excessClientHoldersProducePartialAnonymousTotalsWithGlobalCounters() {
        Map<String, Object> clients = (Map<String, Object>) ReflectionTestUtils.getField(forwarder, "clients");
        for (int i = 0; i < 65; i++) {
            Object holder = ReflectionTestUtils.invokeMethod(forwarder, "createClient", 5, 30, true);
            registerPool(holder, i);
            clients.put("private-target-url-and-password-" + i, holder);
        }
        ((AtomicInteger) ReflectionTestUtils.getField(forwarder, "activeForwards")).set(5);
        ((LongAdder) ReflectionTestUtils.getField(forwarder, "completedForwards")).add(9);
        var values = forwarder.resourceMetricsSnapshot();
        assertThat(values).containsEntry("coverageComplete", false).containsEntry("poolLeased", 63L)
                .containsEntry("activeForwards", 5).containsEntry("completedForwards", 9L);
        assertThat(values.toString()).doesNotContain("private-target", "password", "127.0.0.1");
    }

    @Test
    void excessPoolsWithinOneHolderAreAlsoBounded() {
        Object holder = ReflectionTestUtils.getField(forwarder, "originalHostClient");
        for (int i = 0; i < 65; i++) registerPool(holder, i);
        assertThat(forwarder.resourceMetricsSnapshot()).containsEntry("coverageComplete", false)
                .containsEntry("poolLeased", 64L).containsEntry("poolCapacity", 128L);
    }

    private static void registerPool(Object holder, int index) {
        Object collector = ReflectionTestUtils.getField(holder, "metricsCollector");
        ConnectionPoolMetrics pool = mock(ConnectionPoolMetrics.class);
        when(pool.acquiredSize()).thenReturn(1);
        when(pool.maxAllocatedSize()).thenReturn(2);
        ReflectionTestUtils.invokeMethod(collector, "registerMetrics", "private-pool", "pool-" + index,
                new InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 10000 + index), pool);
    }
}
