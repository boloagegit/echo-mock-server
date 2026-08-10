package com.echo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Persisted outbound HTTP connection profile. Secrets are stored encrypted. */
@Entity
@Table(name = "http_target_connections", indexes = {
    @Index(name = "idx_http_target_default", columnList = "is_default"),
    @Index(name = "idx_http_target_enabled", columnList = "enabled")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HttpTargetConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Long version;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Column(nullable = false, length = 1000)
    private String baseUrl;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String authType = "NONE";

    @Column(length = 200)
    private String username;

    @Column(length = 2000)
    private String encryptedSecret;

    @Column(nullable = false)
    @Builder.Default
    private Integer connectTimeoutSeconds = 5;

    @Column(nullable = false)
    @Builder.Default
    private Integer readTimeoutSeconds = 30;

    /** False preserves the existing intranet/self-signed certificate behavior. */
    @Column(nullable = false)
    @Builder.Default
    private Boolean tlsVerificationEnabled = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    @Column(name = "is_default", nullable = false)
    @Builder.Default
    private Boolean defaultConnection = false;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
        if (authType == null) authType = "NONE";
        if (connectTimeoutSeconds == null) connectTimeoutSeconds = 5;
        if (readTimeoutSeconds == null) readTimeoutSeconds = 30;
        if (tlsVerificationEnabled == null) tlsVerificationEnabled = false;
        if (enabled == null) enabled = true;
        if (defaultConnection == null) defaultConnection = false;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
