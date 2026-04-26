package com.echo.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 請求記錄 Entity
 * 用於統計分析，不記錄 Request/Response Body
 */
@Entity
@Table(name = "request_log", indexes = {
    @Index(name = "idx_log_rule_id", columnList = "ruleId"),
    @Index(name = "idx_log_request_time", columnList = "requestTime"),
    @Index(name = "idx_log_protocol", columnList = "protocol")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RequestLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 關聯規則 ID (404 時為 null) */
    @Column(length = 36)
    private String ruleId;

    /** 匹配鏈 (JSON 格式，記錄每個候選規則的匹配結果) */
    @Lob
    private String matchChain;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Protocol protocol;

    /** HTTP Method (GET/POST/PUT/DELETE 等) */
    @Column(length = 10)
    private String method;

    /** Path 或 Queue Name */
    @Column(nullable = false, length = 500)
    private String endpoint;

    /** 是否匹配成功 */
    @Column(nullable = false)
    private boolean matched;

    /** 回應時間 (ms) */
    @Column(nullable = false)
    private int responseTimeMs;

    /** 規則匹配耗時 (ms) */
    private Integer matchTimeMs;

    /** 來源 IP */
    @Column(length = 50)
    private String clientIp;

    /** 目標主機 (X-Original-Host) */
    @Column(length = 255)
    private String targetHost;

    /** 代理回應狀態碼 (無匹配轉發時) */
    private Integer proxyStatus;

    /** 代理錯誤訊息 (無匹配轉發失敗時) */
    @Column(length = 255)
    private String proxyError;

    private Integer responseStatus;

    @Lob
    private String requestBody;

    @Lob
    private String responseBody;

    /** 故障注入類型（NONE / CONNECTION_RESET / EMPTY_RESPONSE） */
    @Column(length = 20)
    private String faultType;

    /** Scenario 名稱（狀態轉移時記錄） */
    @Column(length = 100)
    private String scenarioName;

    /** Scenario 轉移前狀態 */
    @Column(length = 100)
    private String scenarioFromState;

    /** Scenario 轉移後狀態 */
    @Column(length = 100)
    private String scenarioToState;

    @Column(nullable = false)
    private LocalDateTime requestTime;

    @PrePersist
    public void onCreate() {
        if (this.requestTime == null) {
            this.requestTime = LocalDateTime.now();
        }
    }
}
