package com.echo.agent;

import lombok.Builder;
import lombok.Getter;

/**
 * Agent statistics: queue size, processed count, and dropped count.
 */
@Getter
@Builder
public class AgentStats {

    private final int queueSize;
    private final long processedCount;
    private final long droppedCount;
    private final long retentionSkippedCount;
    private final long bufferOverflowDroppedCount;
    /** Known failed or unconfirmed hand-off writes; not proof that a late commit cannot exist. */
    private final long writeNotConfirmedCount;
    private final long otherDroppedCount;
    /** Retry attempts, not dropped-record counts. */
    private final long storageRetryCount;
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
    /** Durable consumer state; meaningful when the log agent uses database storage. */
    private final boolean durableConsumerRunning;
    private final long lastSuccessfulWriteEpochMillis;
    /** A retained corrupt/oversized record stopped the consumer here; zero means no terminal record. */
    private final long stoppedAtSequence;
}
