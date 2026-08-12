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

/** Persisted outbound JMS broker profile. Credentials are never serialized from this entity. */
@Entity
@Table(name = "jms_target_connections", indexes = {
    @Index(name = "idx_jms_target_default", columnList = "is_default"),
    @Index(name = "idx_jms_target_enabled", columnList = "enabled")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JmsTargetConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Long version;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Column(nullable = false, length = 20)
    private String providerType;

    @Column(nullable = false, length = 500)
    private String serverUrl;

    @Column(length = 200)
    private String username;

    @Column(length = 2000)
    private String encryptedPassword;

    @Column(nullable = false, length = 255)
    private String queueName;

    @Column(nullable = false)
    private Integer timeoutSeconds;

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
        if (timeoutSeconds == null) {
            timeoutSeconds = 30;
        }
        if (enabled == null) {
            enabled = true;
        }
        if (defaultConnection == null) {
            defaultConnection = false;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
