package com.echo.pipeline;

import lombok.Builder;
import lombok.Getter;

/**
 * Pipeline execution result Value Object
 * <p>
 * Encapsulates the complete result of pipeline execution for use by Controller / Listener.
 * Contains response content, matching information, timing data and delay settings.
 */
@Getter
@Builder
public class PipelineResult {

    /** Response content */
    private final MockResponse response;

    /** Matched rule ID */
    private final String ruleId;

    /** Whether the match is successful */
    private final boolean matched;

    /** Matching time (milliseconds) */
    private final int matchTimeMs;

    /** Response time (milliseconds) */
    private final int responseTimeMs;

    /** Matching chain JSON */
    private final String matchChainJson;

    /** Delay time (milliseconds) */
    private final long delayMs;

    /** Fault injection type (null means NONE) */
    private final String faultType;

    /** Scenario name (recorded during state transfer, optional) */
    private final String scenarioName;

    /** Scenario New status after transfer (optional) */
    private final String scenarioNewState;
}
