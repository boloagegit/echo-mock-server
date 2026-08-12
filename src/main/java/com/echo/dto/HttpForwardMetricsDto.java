package com.echo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/** Read-only operational snapshot for outbound HTTP forwarding. */
public record HttpForwardMetricsDto(
        int activeForwards,
        int ioThreads,
        long completedForwards,
        long cancelledForwards,
        long rejectedForwards,
        int clientPoolCount,
        Pool pool,
        Map<String, Target> targets
) {
    /** Compatibility alias retained for existing monitoring clients. */
    @Deprecated
    @JsonProperty("executorThreads")
    public int executorThreads() {
        return ioThreads;
    }

    /** Compatibility alias retained for existing monitoring clients. */
    @Deprecated
    @JsonProperty("completedExecutorTasks")
    public long completedExecutorTasks() {
        return completedForwards;
    }

    public record Pool(
            int leased,
            int pending,
            int available,
            int capacity,
            int maxConnections,
            int maxPendingRequests,
            int maxResponseBodyBytes,
            long bufferedResponseBytes,
            long maxBufferedResponseBytes,
            int poolAcquireTimeoutMs,
            int idleConnectionTimeoutSeconds,
            int backgroundEvictionIntervalSeconds
    ) {
        /** Compatibility constructor for callers compiled against the previous metric shape. */
        public Pool(int leased, int pending, int available, int capacity,
                    int maxConnections, int maxPendingRequests, int maxResponseBodyBytes,
                    int poolAcquireTimeoutMs, int idleConnectionTimeoutSeconds,
                    int backgroundEvictionIntervalSeconds) {
            this(leased, pending, available, capacity,
                    maxConnections, maxPendingRequests, maxResponseBodyBytes,
                    0, 0, poolAcquireTimeoutMs,
                    idleConnectionTimeoutSeconds, backgroundEvictionIntervalSeconds);
        }

        /** Compatibility alias retained for existing monitoring clients. */
        @Deprecated
        @JsonProperty("maxConnectionsPerPool")
        public int maxConnectionsPerPool() {
            return maxConnections;
        }

        /** The separate per-route limit was removed; report the global limit. */
        @Deprecated
        @JsonProperty("maxConnectionsPerRoute")
        public int maxConnectionsPerRoute() {
            return maxConnections;
        }

        /** Reactor Netty validates connections through channel lifecycle handling. */
        @Deprecated
        @JsonProperty("validateAfterInactivitySeconds")
        public int validateAfterInactivitySeconds() {
            return 0;
        }
    }

    public record Target(
            String name,
            long failed,
            long capacityRejected,
            long poolTimeouts,
            long connectTimeouts,
            long readTimeouts,
            long responseTooLarge,
            long otherFailures
    ) {
    }
}
