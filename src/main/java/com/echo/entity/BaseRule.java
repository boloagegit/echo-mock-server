package com.echo.entity;

import com.echo.util.CurrentOperator;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.Length;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Rule Base Class
 * <p>
 * Defines properties common to all simulation rules, including:
 * <ul>
 *   <li>matching condition (bodyCondition)</li>
 *   <li>response setting (responseId)</li>
 *   <li>execution parameters (delayMs, priority, enabled)</li>
 *   <li>relay data (tags, description)</li>
 * </ul>
 * 
 * @see HttpRule implementation for HTTP protocol rules.
 * @see JmsRule implementation for JMS protocol rules.
 */
@MappedSuperclass
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public abstract class BaseRule {

    /** Rule unique identification code (UUID) */
    @Id
    @Column(length = 36)
    private String id;

    /** Optimistic locking version number */
    @Version
    private Long version;

    /**
     * Body matching conditions
     * <p>format: field=value; field2=value2
     * <p>supports JSONPath or XPath expressions
     */
    @Column(length = Length.LONG32)
    private String bodyCondition;

    /**
     * Structured matching condition (JSON, see {@code com.echo.core.match.MatchSpecCodec})
     * <p>will only be written if conditions that cannot be expressed by the old version of condition strings ({@code any}, {@code not}, exists, numerical comparison, etc.);
     * When there is a value, it shall prevail. The three condition fields in the old version are null.
     */
    @Column(length = Length.LONG32)
    private String matchSpec;

    /**
     * Associated response ID
     * <p>must be set, pointing to the response content of the Response table
     */
    @Column(name = "response_id")
    private Long responseId;

    /** Response delay time (milliseconds) */
    @Column(nullable = false)
    private Long delayMs;

    /** Maximum response delay time (milliseconds), enable random delay range after setting */
    private Long maxDelayMs;

    /** Rule description */
    private String description;

    /** 
     * Priority
     * <p>The larger the number, the higher the priority. If the priority is the same, the order is based on the accuracy of the conditions.
     */
    @Column(nullable = false)
    private Integer priority;

    /** Whether to enable this rule */
    @Column(nullable = false)
    @lombok.Builder.Default
    private Boolean enabled = true;

    /** Whether to protect this rule (not to be automatically cleared) */
    @Column(name = "is_protected", nullable = false)
    @lombok.Builder.Default
    private Boolean isProtected = false;

    /** 
     * Label (JSON format)
     * <p>is used for rule classification and filtering, for example: {"env":"prod","team":"payment"}
     */
    @Column(length = Length.LONG32)
    private String tags;

    /** Creation time */
    @Column(updatable = false)
    private LocalDateTime createdAt;

    /** Last updated */
    private LocalDateTime updatedAt;

    /** Account for creating rules; old data may be empty. */
    private String createdBy;

    /** The account that last modified the rules; the old information may be empty. */
    private String updatedBy;

    /** Extension time (used to calculate automatic purge date) */
    private LocalDateTime extendedAt;

    /**
     * Fault injection type
     * <p>determines the response behavior based on this field after successful matching
     * @see FaultType
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    @lombok.Builder.Default
    private FaultType faultType = FaultType.NONE;

    /** Scenario name (for state machine matching) */
    private String scenarioName;

    /** Matching prerequisite: Scenario must be in this state to match */
    private String requiredScenarioState;

    /** After successful matching, transfer the Scenario status to this value */
    private String newScenarioState;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
        createdBy = CurrentOperator.username();
        updatedBy = createdBy;
        if (priority == null) {
            priority = 0;
        }
        if (delayMs == null) {
            delayMs = 0L;
        }
        if (enabled == null) {
            enabled = true;
        }
        if (faultType == null) {
            faultType = FaultType.NONE;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /** Get the rule's protocol type. */
    public abstract Protocol getProtocol();
}
