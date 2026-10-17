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

    @Column(length = Length.LONG32)
    private String beforeJson;

    @Column(length = Length.LONG32)
    private String afterJson;

    @Column(nullable = false)
    private String operator;

    @Column(nullable = false)
    private LocalDateTime timestamp;

    /** 列表用摘要：HTTP、JMS 或 RESPONSE。舊資料為 null，由服務逐筆補算。 */
    @Column(length = 16)
    private String targetProtocol;

    /** 列表用摘要：路徑、佇列名稱或回應說明。 */
    @Column(length = 255)
    private String targetName;

    /** 列表用摘要：規則說明。 */
    @Column(length = 255)
    private String targetDescription;

    /** 列表用摘要 JSON（方法、主機、改了哪些欄位等），null 表示尚未計算。列表 API 會轉成物件回傳，明細不重複輸出。 */
    @JsonIgnore
    @Column(length = 1000)
    private String summary;

    public enum Action {
        CREATE, UPDATE, DELETE
    }
}
