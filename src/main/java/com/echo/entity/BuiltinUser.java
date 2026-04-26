package com.echo.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 內建帳號使用者
 * <p>
 * 儲存於 H2 資料庫的本地帳號，與 LDAP 認證並行運作。
 * 管理員可為外部廠商建立帳號，廠商透過內建帳號登入後即可操作系統。
 */
@Entity
@Table(name = "builtin_users", indexes = {
    @Index(name = "idx_builtin_user_username", columnList = "username", unique = true)
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BuiltinUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 樂觀鎖版本號 */
    @Version
    private Long version;

    /** 帳號名稱（唯一，3-50 字元） */
    @Column(nullable = false, unique = true, length = 50)
    private String username;

    /** BCrypt 雜湊密碼 */
    @JsonIgnore
    @Column(nullable = false)
    private String password;

    /** 角色：ROLE_USER 或 ROLE_ADMIN */
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String role = "ROLE_USER";

    /** 是否啟用 */
    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    /** 忘記密碼請求標記 */
    @Column(nullable = false)
    @Builder.Default
    private Boolean passwordResetRequested = false;

    /** 忘記密碼請求時間 */
    private LocalDateTime passwordResetRequestedAt;

    /** 強制修改密碼標記（臨時密碼登入後為 true） */
    @Column(nullable = false)
    @Builder.Default
    private Boolean forceChangePassword = false;

    /** 建立時間 */
    @Column(updatable = false)
    private LocalDateTime createdAt;

    /** 更新時間 */
    private LocalDateTime updatedAt;

    /** 最後登入時間 */
    private LocalDateTime lastLoginAt;

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
