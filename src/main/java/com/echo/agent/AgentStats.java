package com.echo.agent;

import lombok.Builder;
import lombok.Getter;

/**
 * Agent 統計資訊，包含佇列大小、已處理數、已丟棄數。
 */
@Getter
@Builder
public class AgentStats {

    private final int queueSize;
    private final long processedCount;
    private final long droppedCount;
    /** Serialized durable backlog bytes, when the agent has one. */
    private final long queueBytes;
    /** Configured durable backlog byte limit, when the agent has one. */
    private final long queueCapacityBytes;
    /** Bytes retained in the bounded producer-to-spool hand-off. */
    private final long inFlightBytes;
    /** Heap byte limit for the producer-to-spool hand-off. */
    private final long inFlightByteLimit;
    /** Producers currently waiting for durable or heap capacity. */
    private final int waitingProducers;
    /** Hysteresis state for durable/heap backpressure. */
    private final boolean backpressureActive;
}
