package com.echo.service;

import com.echo.entity.BaseRule;
import com.echo.entity.Protocol;
import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

/**
 * An event sent when a request processing is completed (a response has been decided) and handed over to {@link ServeEventListener} (request record, future indicators and Webhook).
 * <p>
 * Only passed synchronously on the thread processing the request, listener cannot retain references to variable fields (headers, candidates, preparedBody);
 * listener that needs to be deferred should copy the required data by itself.
 */
@Value
@Builder
public class ServeEvent {
    String ruleId;
    Protocol protocol;
    String method;
    /** HTTP path or JMS endpoint field value. */
    String endpoint;
    boolean matched;
    int responseTimeMs;
    Integer matchTimeMs;
    String clientIp;
    /** Matching chain JSON. */
    String matchChain;
    String targetHost;
    boolean forwarded;
    String forwardTarget;
    Integer proxyStatus;
    String proxyError;
    /** Actual response status; when there is no response such as connection reset, it is null. */
    Integer responseStatus;
    String requestBody;
    String responseBody;
    /** Candidate rules to participate in comparison (for matching chain analysis); if not available, it is null or empty. */
    List<? extends BaseRule> candidates;
    ConditionMatcher.PreparedBody preparedBody;
    String queryString;
    Map<String, String> headers;
    String faultType;
    String scenarioName;
    String scenarioFromState;
    String scenarioToState;
    /** The number of candidate rules participating in the comparison; if there is no comparison, it is null. */
    Integer candidateCount;
    /** The default rule without conditions is hit (select it only when none of the conditions are met); when there is no hit, it is null. */
    Boolean matchedByFallback;
    /** The simulation delay (milliseconds) set by the rule is outside the recorded processing time; SSE is the delay before the first event. */
    Integer delayMs;
    /** The number of events streamed by SSE; when it is not SSE, it is null. */
    Integer sseEventCount;
    /** Rule skipped due to inconsistent situation status (JSON in matching chain format); null if none. */
    String scenarioSkipped;
    /** Transaction diagnostic ID; null when diagnostics are not enabled. */
    String diagnosticId;
}
