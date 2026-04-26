package com.echo.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import jakarta.persistence.*;
import lombok.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

/**
 * 共用回應內容
 * <p>
 * 可被多個規則共用的回應範本，適用於：
 * <ul>
 *   <li>多個端點回傳相同內容</li>
 *   <li>大型回應內容集中管理</li>
 *   <li>回應內容版本控制</li>
 * </ul>
 * 
 * <h3>使用方式</h3>
 * 規則可透過 responseId 關聯此表，或直接在規則中設定 responseBody。
 * 使用共用回應可減少重複資料並方便統一維護。
 */
@Entity
@Table(name = "responses")
@Getter
@Setter
@Builder
@NoArgsConstructor(onConstructor_ = @JsonCreator)
@AllArgsConstructor
public class Response {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "response_seq")
    @SequenceGenerator(name = "response_seq", sequenceName = "response_sequence", allocationSize = 1)
    private Long id;

    /** 樂觀鎖版本號 */
    @Version
    private Long version;

    /** 回應描述（用於識別與搜尋） */
    private String description;

    /** 
     * 回應內容
     * <p>使用 Lazy Load 避免列表查詢時載入大型內容
     */
    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(columnDefinition = "CLOB")
    private String body;

    /** 
     * Body 大小（bytes）
     * <p>用於快取決策與顯示，自動計算
     */
    private Integer bodySize;

    /** 
     * 回應內容類型
     * <p>TEXT = 一般文字/JSON/XML，SSE = SSE 事件陣列
     */
    @Column(length = 20)
    private String contentType;

    /** 建立時間 */
    @Column(updatable = false)
    private LocalDateTime createdAt;

    /** 最後更新時間 */
    private LocalDateTime updatedAt;

    /** 展延時間（用於計算孤兒回應自動清除日期） */
    private LocalDateTime extendedAt;

    /**
     * Metadata 建構子（JPQL 投影用）
     * <p>用於查詢列表時不載入 body 欄位
     */
    public Response(Long id, Long version, String description, Integer bodySize,
                    String contentType, LocalDateTime createdAt, LocalDateTime updatedAt,
                    LocalDateTime extendedAt) {
        this.id = id;
        this.version = version;
        this.description = description;
        this.bodySize = bodySize;
        this.contentType = contentType;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.extendedAt = extendedAt;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
        updateBodySize();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
        updateBodySize();
    }

    /** 計算並更新 body 大小 */
    private void updateBodySize() {
        bodySize = body != null ? body.getBytes(StandardCharsets.UTF_8).length : 0;
    }
}
