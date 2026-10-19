package com.echo.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.Length;

import java.time.LocalDateTime;

/**
 * Rule revision history
 */
@Entity
@Table(name = "rule_audit_logs", indexes = {
    @Index(name = "idx_audit_rule_id", columnList = "ruleId"),
    @Index(name = "idx_audit_timestamp", columnList = "timestamp")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuleAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 36)
    private String ruleId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Action action;

    @Column(length = Length.LONG32)
    private String beforeJson;

    @Column(length = Length.LONG32)
    private String afterJson;

    @Column(nullable = false)
    private String operator;

    @Column(nullable = false)
    private LocalDateTime timestamp;

    /** Summary for list views: HTTP, JMS, or RESPONSE. Older rows have null summaries, which the service backfills individually. */
    @Column(length = 16)
    private String targetProtocol;

    /** Summary for list: path, queue name, or response description. */
    @Column(length = 255)
    private String targetName;

    /** Summary for list: Rule description. */
    @Column(length = 255)
    private String targetDescription;

    /** The list uses summary JSON (method, host, changed fields, etc.), null means it has not been calculated yet. The list API will be converted into an object and returned, and the details will not be output repeatedly. */
    @JsonIgnore
    @Column(length = 1000)
    private String summary;

    public enum Action {
        CREATE, UPDATE, DELETE
    }
}
