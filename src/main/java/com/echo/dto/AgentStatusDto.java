package com.echo.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * Agent status data transmission object, used for Admin API return.
 */
@Getter
@Builder
public class AgentStatusDto {

    private final String name;
    private final String description;
    private final String status;
    private final int queueSize;
    private final long processedCount;
    private final long droppedCount;
    private final long queueBytes;
    private final long queueCapacityBytes;
    private final long inFlightBytes;
    private final long inFlightByteLimit;
    private final int waitingProducers;
    private final boolean backpressureActive;
    private final boolean durableConsumerRunning;
    private final long lastSuccessfulWriteEpochMillis;
    private final long stoppedAtSequence;
}
