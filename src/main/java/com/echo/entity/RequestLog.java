package com.echo.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.Length;

import java.time.LocalDateTime;

/**
 * 請求記錄 Entity
 * 用於統計分析，不記錄 Request/Response Body
 */
@Entity
@Table(name = "request_log", indexes = {
    @Index(name = "idx_log_rule_id", columnList = "ruleId"),
    @Index(name = "idx_log_request_time", columnList = "requestTime"),
    @Index(name = "idx_log_protocol", columnList = "protocol"),
    @Index(name = "idx_log_request_time_id", columnList = "requestTime,id"),
    @Index(name = "idx_log_rule_time_id", columnList = "ruleId,requestTime,id"),
    @Index(name = "idx_log_protocol_matched_time_id", columnList = "protocol,matched,requestTime,id"),
    @Index(name = "idx_log_endpoint_time_id", columnList = "endpoint,requestTime,id"),
    @Index(name = "idx_log_response_time_id", columnList = "responseTimeMs,id"),
    // 流量概況只讀這幾個欄位：查詢只掃描索引，不讀排在後面的 body 與比對過程
    @Index(name = RequestLog.TRAFFIC_INDEX, columnList = "requestTime,matched,forwarded,proxyError,responseTimeMs")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RequestLog {

    public static final int MAX_FORWARD_TARGET_LENGTH = 1000;

    /** 流量概況使用的涵蓋索引。 */
    public static final String TRAFFIC_INDEX = "idx_log_traffic";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Short local diagnostic correlation only; nullable for historical rows. */
    @Column(length = 36)
    private String diagnosticId;

    /** 關聯規則 ID (404 時為 null) */
    @Column(length = 36)
    private String ruleId;

    /** 匹配鏈 (JSON 格式，記錄每個候選規則的匹配結果) */
    @Column(length = Length.LONG32)
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

    /** 是否實際嘗試轉發；nullable 讓既有 schema 可安全新增欄位。 */
    @Builder.Default
    private Boolean forwarded = false;

    /** 實際下游目標的安全顯示文字，不包含認證資料。 */
    @Column(length = MAX_FORWARD_TARGET_LENGTH)
    private String forwardTarget;

    /** 代理回應狀態碼 (無匹配轉發時) */
    private Integer proxyStatus;

    /** 代理錯誤訊息 (無匹配轉發失敗時) */
    @Column(length = 255)
    private String proxyError;

    private Integer responseStatus;

    @Column(length = Length.LONG32)
    private String requestBody;

    @Column(length = Length.LONG32)
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

    /** 參與比對的候選規則數（處理流程圖用）；舊紀錄為 null。 */
    private Integer candidateCount;

    /** 命中的是沒有條件的預設規則；沒有命中或舊紀錄為 null。 */
    private Boolean matchedByFallback;

    /** 規則設定的模擬延遲（毫秒），不含在 responseTimeMs 內；SSE 為第一個事件前的延遲。 */
    private Integer delayMs;

    /** SSE 串流的事件數；不是 SSE 時為 null。 */
    private Integer sseEventCount;

    /** 因情境狀態不符而略過的規則（匹配鏈格式的 JSON）。 */
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
     * 讓值符合欄位：截短有長度上限的欄位，並移除 NUL 字元。這些值多半直接來自請求（網址、方法、Host header），
     * SQLite 不檢查長度，但 PostgreSQL、MySQL、SQL Server、Oracle 會拒絕過長的值（PostgreSQL 也拒絕 NUL），
     * 一筆被拒絕就會讓整批紀錄寫不進去。
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

    /** 移除 NUL 後截到 {@code max} 個字元，不切開 surrogate pair。 */
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

    /** 保護日誌寫入，避免合法但過長的下游描述使整批寫入回滾。 */
    public static String limitForwardTarget(String value) {
        return fit(value, MAX_FORWARD_TARGET_LENGTH);
    }

    /** Existing proxy_error column, including replay of older durable spool records. */
    public static String limitProxyError(String value) {
        return fit(value, 255);
    }
}
