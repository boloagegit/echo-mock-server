package com.echo.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 規則修訂記錄
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

    @Lob
    @Column(columnDefinition = "CLOB")
    private String beforeJson;

    @Lob
    @Column(columnDefinition = "CLOB")
    private String afterJson;

    @Column(nullable = false)
    private String operator;

    @Column(nullable = false)
    private LocalDateTime timestamp;

    public enum Action {
        CREATE, UPDATE, DELETE
    }
}
