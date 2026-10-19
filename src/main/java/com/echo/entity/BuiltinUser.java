package com.echo.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Built-in account user
 * <p>
 * Local account stored in database, operating in parallel with LDAP authentication.
 * Administrators can create accounts for external vendors, and vendors can operate the system after logging in with the built-in account.
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

    /** Optimistic locking version number */
    @Version
    private Long version;

    /** Account name (unique, 3-50 characters) */
    @Column(nullable = false, unique = true, length = 50)
    private String username;

    /** BCrypt hash password */
    @JsonIgnore
    @Column(nullable = false)
    private String password;

    /** Role: ROLE_USER or ROLE_ADMIN */
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String role = "ROLE_USER";

    /** Enable or not */
    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    /** Forgot password request flag */
    @Column(nullable = false)
    @Builder.Default
    private Boolean passwordResetRequested = false;

    /** Forgot password request time */
    private LocalDateTime passwordResetRequestedAt;

    /** Forced change of password mark (temporary password after login is true) */
    @Column(nullable = false)
    @Builder.Default
    private Boolean forceChangePassword = false;

    /** Creation time */
    @Column(updatable = false)
    private LocalDateTime createdAt;

    /** Update time */
    private LocalDateTime updatedAt;

    /** Last login time */
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
