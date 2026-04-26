package com.echo.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * HTTP 協定模擬規則
 * <p>
 * 定義 HTTP 請求的匹配條件與回應設定：
 * <ul>
 *   <li>匹配條件：targetHost + matchKey (URI) + method + queryCondition + bodyCondition + headerCondition</li>
 *   <li>回應設定：httpStatus + httpHeaders + responseBody/responseId</li>
 * </ul>
 * 
 * <h3>匹配優先順序</h3>
 * <ol>
 *   <li>priority 數值（越大越優先）</li>
 *   <li>條件精確度（有條件 > 無條件）</li>
 *   <li>路徑精確度（精確匹配 > 萬用字元）</li>
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

    /** 
     * 目標主機
     * <p>用於區分不同後端服務，可為空表示匹配所有主機
     */
    private String targetHost;

    /** 
     * URI 路徑匹配模式
     * <p>支援萬用字元：/api/* 匹配 /api/users, /api/orders 等
     */
    @Column(nullable = false)
    private String matchKey;

    /** 
     * HTTP 方法
     * <p>GET, POST, PUT, DELETE 等，可為空表示匹配所有方法
     */
    private String method;

    /** 
     * Query 參數匹配條件
     * <p>格式：param=value;param2=value2
     */
    @Column(length = 500)
    private String queryCondition;

    /**
     * Header 匹配條件
     * <p>格式：HeaderName=value;HeaderName2~=pattern
     */
    @Column(length = 500)
    private String headerCondition;

    /** 是否啟用 SSE（Server-Sent Events）模式 */
    @Column(name = "sse_enabled", nullable = false, columnDefinition = "boolean default false")
    @lombok.Builder.Default
    private Boolean sseEnabled = false;

    /** SSE 事件序列是否循環播放 */
    @Column(name = "sse_loop_enabled", nullable = false, columnDefinition = "boolean default false")
    @lombok.Builder.Default
    private Boolean sseLoopEnabled = false;

    /** HTTP 回應狀態碼（預設 200） */
    @Column(nullable = false)
    private Integer httpStatus;

    /** 
     * HTTP 回應標頭（JSON 格式）
     * <p>例如：{"Content-Type":"application/json","X-Custom":"value"}
     */
    @Lob
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
    }

    /**
     * 取得合併後的條件字串（供顯示用）
     * @return 格式化的條件字串，Query 參數以 ? 前綴，Header 以 @ 前綴標示
     */
    @Transient
    public String getCondition() {
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
