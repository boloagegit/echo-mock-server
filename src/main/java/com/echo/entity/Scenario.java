package com.echo.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 狀態機場景實體
 * <p>
 * 記錄每個 scenario 的當前狀態，用於 Stateful Scenarios 功能。
 * 規則可透過 scenarioName 關聯到 Scenario，實現狀態驅動的匹配邏輯。
 */
@Entity
@Table(name = "scenarios")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Scenario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Scenario 名稱（唯一） */
    @Column(nullable = false, unique = true)
    private String scenarioName;

    /** 當前狀態 */
    @Column(nullable = false)
    private String currentState;

    /** 樂觀鎖版本號 */
    @Version
    private Long version;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
