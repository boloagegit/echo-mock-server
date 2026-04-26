package com.echo.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 規則基底類別
 * <p>
 * 定義所有模擬規則的共用屬性，包含：
 * <ul>
 *   <li>匹配條件（bodyCondition）</li>
 *   <li>回應設定（responseId）</li>
 *   <li>執行參數（delayMs, priority, enabled）</li>
 *   <li>中繼資料（tags, description）</li>
 * </ul>
 * 
 * @see HttpRule HTTP 協定規則實作
 * @see JmsRule JMS 協定規則實作
 */
@MappedSuperclass
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public abstract class BaseRule {

    /** 規則唯一識別碼 (UUID) */
    @Id
    @Column(length = 36)
    private String id;

    /** 樂觀鎖版本號 */
    @Version
    private Long version;

    /** 
     * Body 匹配條件
     * <p>格式：field=value;field2=value2
     * <p>支援 JSONPath 或 XPath 表達式
     */
    @Lob
    private String bodyCondition;

    /** 
     * 關聯的回應 ID
     * <p>必須設定，指向 Response 表的回應內容
     */
    @Column(name = "response_id")
    private Long responseId;

    /** 回應延遲時間（毫秒） */
    @Column(nullable = false)
    private Long delayMs;

    /** 最大回應延遲時間（毫秒），設定後啟用隨機延遲範圍 */
    private Long maxDelayMs;

    /** 規則描述說明 */
    private String description;

    /** 
     * 優先順序
     * <p>數字越大優先順序越高，相同優先順序時依條件精確度排序
     */
    @Column(nullable = false)
    private Integer priority;

    /** 是否啟用此規則 */
    @Column(nullable = false)
    @lombok.Builder.Default
    private Boolean enabled = true;

    /** 是否保護此規則（不被自動清除） */
    @Column(name = "is_protected", nullable = false, columnDefinition = "boolean default false")
    @lombok.Builder.Default
    private Boolean isProtected = false;

    /** 
     * 標籤（JSON 格式）
     * <p>用於規則分類與篩選，例如：{"env":"prod","team":"payment"}
     */
    @Lob
    private String tags;

    /** 建立時間 */
    @Column(updatable = false)
    private LocalDateTime createdAt;

    /** 最後更新時間 */
    private LocalDateTime updatedAt;

    /** 展延時間（用於計算自動清除日期） */
    private LocalDateTime extendedAt;

    /** 
     * 故障注入類型
     * <p>匹配成功後根據此欄位決定回應行為
     * @see FaultType
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    @lombok.Builder.Default
    private FaultType faultType = FaultType.NONE;

    /** Scenario 名稱（用於狀態機匹配） */
    private String scenarioName;

    /** 匹配前提：Scenario 必須處於此狀態才能匹配 */
    private String requiredScenarioState;

    /** 匹配成功後將 Scenario 狀態轉移為此值 */
    private String newScenarioState;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
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

    /** 取得規則協定類型 */
    public abstract Protocol getProtocol();
}
