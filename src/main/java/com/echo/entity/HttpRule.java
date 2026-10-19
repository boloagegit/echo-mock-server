package com.echo.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.Length;

/**
 * HTTP protocol simulation rules
 * <p>
 * Define the matching conditions and response settings for the HTTP request:
 * <ul>
 *   <li>matching conditions: targetHost + matchKey (URI) + method + queryCondition + bodyCondition + headerCondition</li>
 *   <li>response setting: httpStatus + httpHeaders + responseBody/responseId</li>
 * </ul>
 * 
 * <h3>matches priority</h3>
 * <ol>
 *   <li>priority Value (the larger the priority)</li>
 *   <li>Conditional accuracy (conditional > unconditional)</li>
 *   <li>Path accuracy (exact match > wildcard)</li>
 * </ol>
 */
@Entity
@Table(name = "http_rules", indexes = {
    @Index(name = "idx_http_rule_lookup", columnList = "targetHost, matchKey, method"),
    @Index(name = "idx_http_rule_enabled", columnList = "enabled"),
    @Index(name = "idx_http_rule_response", columnList = "response_id")
})
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class HttpRule extends BaseRule {

    /** Existing rows with null are interpreted as MOCK for backward compatibility. */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    @lombok.Builder.Default
    private HttpRuleAction action = HttpRuleAction.MOCK;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    @lombok.Builder.Default
    private HttpForwardTargetMode forwardTargetMode = HttpForwardTargetMode.ORIGINAL_HOST;

    private Long httpTargetConnectionId;

    /** 
     * Target host
     * <p>is used to distinguish different backend services. It can be empty to match all hosts.
     */
    private String targetHost;

    /** 
     * URI path matching pattern
     * <p>Supports wildcards: /api/* matches /api/users, /api/orders, and so on.
     */
    @Column(nullable = false)
    private String matchKey;

    /** 
     * HTTP method
     * <p>GET, POST, PUT, DELETE, etc., can be empty to match all methods
     */
    private String method;

    /** 
     * Query parameter matching conditions
     * <p>format: param=value; param2=value2
     */
    @Column(length = 500)
    private String queryCondition;

    /**
     * Header matching conditions
     * <p>format: HeaderName=value;HeaderName2~=pattern
     */
    @Column(length = 500)
    private String headerCondition;

    /** Whether to enable SSE (Server-Sent Events) mode */
    @Column(name = "sse_enabled", nullable = false)
    @lombok.Builder.Default
    private Boolean sseEnabled = false;

    /** Is the SSE event sequence played in a loop? */
    @Column(name = "sse_loop_enabled", nullable = false)
    @lombok.Builder.Default
    private Boolean sseLoopEnabled = false;

    /** HTTP response status code (default 200) */
    @Column(nullable = false)
    private Integer httpStatus;

    /** 
     * HTTP response header (JSON format)
     * <p>For example: {"Content-Type":"application/json","X-Custom":"value"}
     */
    @Column(length = Length.LONG32)
    private String httpHeaders;

    @Override
    public Protocol getProtocol() {
        return Protocol.HTTP;
    }

    @PrePersist
    @Override
    protected void onCreate() {
        super.onCreate();
        if (httpStatus == null) {
            httpStatus = 200;
        }
        if (action == null) action = HttpRuleAction.MOCK;
        if (forwardTargetMode == null) forwardTargetMode = HttpForwardTargetMode.ORIGINAL_HOST;
    }

    /**
     * Get the merged condition string (for display)
     * @return Formatted condition string, Query parameter is prefixed with ?, Header is marked with @ prefix
     */
    @Transient
    public String getCondition() {
        if (getMatchSpec() != null) {
            return getMatchSpec();
        }
        StringBuilder sb = new StringBuilder();
        if (getBodyCondition() != null && !getBodyCondition().isBlank()) {
            sb.append(getBodyCondition());
        }
        if (queryCondition != null && !queryCondition.isBlank()) {
            for (String q : queryCondition.split(";")) {
                if (sb.length() > 0) {
                    sb.append(";");
                }
                sb.append("?").append(q.trim());
            }
        }
        if (headerCondition != null && !headerCondition.isBlank()) {
            for (String h : headerCondition.split(";")) {
                if (sb.length() > 0) {
                    sb.append(";");
                }
                sb.append("@").append(h.trim());
            }
        }
        return sb.length() > 0 ? sb.toString() : null;
    }
}
