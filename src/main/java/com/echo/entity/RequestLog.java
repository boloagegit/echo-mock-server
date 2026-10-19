package com.echo.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.Length;

import java.time.LocalDateTime;

/**
 * Request record Entity
 * used for statistical analysis, not recorded Request/Response Body
 */
@Entity
@Table(name = "request_log", indexes = {
    @Index(name = "idx_log_rule_id", columnList = "ruleId"),
    @Index(name = "idx_log_request_time", columnList = "requestTime"),
    @Index(name = "idx_log_protocol", columnList = "protocol"),
    @Index(name = "idx_log_request_time_id", columnList = "requestTime,id"),
    @Index(name = "idx_log_rule_time_id", columnList = "ruleId,requestTime,id"),
    @Index(name = RequestLog.PROTOCOL_TIME_INDEX, columnList = "protocol,requestTime,id"),
    @Index(name = "idx_log_protocol_matched_time_id", columnList = "protocol,matched,requestTime,id"),
    @Index(name = "idx_log_endpoint_time_id", columnList = "endpoint,requestTime,id"),
    @Index(name = "idx_log_response_time_id", columnList = "responseTimeMs,id"),
    // The traffic profile only reads these fields: the query only scans the index, and does not read the body and comparison process ranked later.
    @Index(name = RequestLog.TRAFFIC_INDEX, columnList = "requestTime,matched,forwarded,proxyError,responseTimeMs")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RequestLog {

    public static final int MAX_FORWARD_TARGET_LENGTH = 1000;

    /** Coverage index used by traffic profile. */
    public static final String TRAFFIC_INDEX = "idx_log_traffic";

    /** Protocol-only lists must retain time/id ordering without sorting all matching logs. */
    public static final String PROTOCOL_TIME_INDEX = "idx_log_protocol_time_id";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Short local diagnostic correlation only; nullable for historical rows. */
    @Column(length = 36)
    private String diagnosticId;

    /** Association rule ID (null when 404) */
    @Column(length = 36)
    private String ruleId;

    /** Matching chain (JSON format, records the matching results of each candidate rule) */
    @Column(length = Length.LONG32)
    private String matchChain;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Protocol protocol;

    /** HTTP Method (GET/POST/PUT/DELETE, etc.) */
    @Column(length = 10)
    private String method;

    /** Path or Queue Name */
    @Column(nullable = false, length = 500)
    private String endpoint;

    /** Whether the match is successful */
    @Column(nullable = false)
    private boolean matched;

    /** Response time (ms) */
    @Column(nullable = false)
    private int responseTimeMs;

    /** Rule matching takes time (ms) */
    private Integer matchTimeMs;

    /** Source IP */
    @Column(length = 50)
    private String clientIp;

    /** Target host (X-Original-Host) */
    @Column(length = 255)
    private String targetHost;

    /** Whether to actually try to forward; nullable allows existing schema to safely add new fields. */
    @Builder.Default
    private Boolean forwarded = false;

    /** The security display text of the actual downstream target does not contain certification information. */
    @Column(length = MAX_FORWARD_TARGET_LENGTH)
    private String forwardTarget;

    /** Agent response status code (when there is no matching forwarding) */
    private Integer proxyStatus;

    /** Agent error message (when forwarding fails without matching) */
    @Column(length = 255)
    private String proxyError;

    private Integer responseStatus;

    @Column(length = Length.LONG32)
    private String requestBody;

    @Column(length = Length.LONG32)
    private String responseBody;

    /** Fault injection type (NONE / CONNECTION_RESET / EMPTY_RESPONSE) */
    @Column(length = 20)
    private String faultType;

    /** Scenario name (recorded during state transfer) */
    @Column(length = 100)
    private String scenarioName;

    /** Scenario state before transfer */
    @Column(length = 100)
    private String scenarioFromState;

    /** Scenario status after transfer */
    @Column(length = 100)
    private String scenarioToState;

    /** The number of candidate rules participating in the comparison (for processing flow chart); the old record is null. */
    private Integer candidateCount;

    /** The hit is a default rule without conditions; there is no hit or the old record is null. */
    private Boolean matchedByFallback;

    /** The simulation delay (milliseconds) set by the rule is not included in responseTimeMs; SSE is the delay before the first event. */
    private Integer delayMs;

    /** The number of events streamed by SSE; when it is not SSE, it is null. */
    private Integer sseEventCount;

    /** Rule skipped due to inconsistent situation status (match chain format JSON). */
    @Column(length = Length.LONG32)
    private String scenarioSkipped;

    @Column(nullable = false)
    private LocalDateTime requestTime;

    public boolean isForwarded() {
        return Boolean.TRUE.equals(forwarded);
    }

    @PrePersist
    public void onCreate() {
        if (this.requestTime == null) {
            this.requestTime = LocalDateTime.now();
        }
        fitColumns();
    }

    @PreUpdate
    public void onUpdate() {
        fitColumns();
    }

    /**
     * Fit the value into the field: Truncate the field with a maximum length and remove the NUL character. Most of these values come directly from the request (url, method, Host header),
     * SQLite does not check the length, but PostgreSQL, MySQL, SQL Server, Oracle will reject too long values (PostgreSQL also rejects NUL),
     * One rejected record would otherwise prevent the entire batch from being written.
     */
    void fitColumns() {
        diagnosticId = fit(diagnosticId, 36);
        ruleId = fit(ruleId, 36);
        method = fit(method, 10);
        endpoint = fit(endpoint, 500);
        clientIp = fit(clientIp, 50);
        targetHost = fit(targetHost, 255);
        forwardTarget = fit(forwardTarget, MAX_FORWARD_TARGET_LENGTH);
        proxyError = fit(proxyError, 255);
        faultType = fit(faultType, 20);
        scenarioName = fit(scenarioName, 100);
        scenarioFromState = fit(scenarioFromState, 100);
        scenarioToState = fit(scenarioToState, 100);
        matchChain = withoutNul(matchChain);
        requestBody = withoutNul(requestBody);
        responseBody = withoutNul(responseBody);
        scenarioSkipped = withoutNul(scenarioSkipped);
    }

    /** After removing NUL, truncate {@code max} characters, and do not cut surrogate pair. */
    static String fit(String value, int max) {
        String clean = withoutNul(value);
        if (clean == null || clean.length() <= max) {
            return clean;
        }
        int end = Character.isHighSurrogate(clean.charAt(max - 1)) ? max - 1 : max;
        return clean.substring(0, end);
    }

    static String withoutNul(String value) {
        return value == null || value.indexOf('\0') < 0 ? value : value.replace("\0", "");
    }

    /** Protect log writes to prevent valid but too long downstream descriptions from rolling back the entire batch of writes. */
    public static String limitForwardTarget(String value) {
        return fit(value, MAX_FORWARD_TARGET_LENGTH);
    }

    /** Existing proxy_error column, including replay of older durable spool records. */
    public static String limitProxyError(String value) {
        return fit(value, 255);
    }
}
