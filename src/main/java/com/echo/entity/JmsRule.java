package com.echo.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * JMS protocol simulation rules
 * <p>
 * Define matching conditions and response settings for JMS message:
 * <ul>
 *   <li>matching conditions: queueName + correlationIdPattern + bodyCondition</li>
 *   <li>response setting: responseBody/responseId + delayMs</li>
 * </ul>
 * 
 * <h3>Queue-name matching</h3>
 * <ul>
 *   <li>exactly matches: ORDER.REQUEST</li>
 *   <li>wildcard: ORDER.* matches ORDER.CREATE, ORDER.UPDATE, etc.</li>
 *   <li>Catch-all: * matches every queue.</li>
 * </ul>
 */
@Entity
@Table(name = "jms_rules", indexes = {
    @Index(name = "idx_jms_rule_queue", columnList = "queueName"),
    @Index(name = "idx_jms_rule_enabled", columnList = "enabled"),
    @Index(name = "idx_jms_rule_response", columnList = "response_id")
})
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class JmsRule extends BaseRule {

    /** Existing rows with null are interpreted as MOCK for backward compatibility. */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    @lombok.Builder.Default
    private JmsRuleAction action = JmsRuleAction.MOCK;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    @lombok.Builder.Default
    private JmsForwardTargetMode forwardTargetMode = JmsForwardTargetMode.DEFAULT_CONNECTION;

    /** String keeps the legacy application.yml identifier compatible with stored profiles. */
    private String jmsTargetConnectionId;

    /** 
     * Queue name matching pattern
     * <p>Supports the wildcard * to match any string.
     */
    @Column(nullable = false)
    private String queueName;

    /** 
     * Correlation ID matching pattern
     * <p>Message correlation for request-reply.
     */
    private String correlationIdPattern;

    @Override
    public Protocol getProtocol() {
        return Protocol.JMS;
    }

    @PrePersist
    @Override
    protected void onCreate() {
        super.onCreate();
        if (action == null) action = JmsRuleAction.MOCK;
        if (forwardTargetMode == null) {
            forwardTargetMode = JmsForwardTargetMode.DEFAULT_CONNECTION;
        }
    }

    /**
     * Get condition string (backwards compatible)
     * @return bodyCondition value
     */
    @Transient
    public String getCondition() {
        return getMatchSpec() != null ? getMatchSpec() : getBodyCondition();
    }

    /**
     * Get matching key value (backwards compatible)
     * @return queueName value
     */
    @Transient
    public String getMatchKey() {
        return queueName;
    }
}
