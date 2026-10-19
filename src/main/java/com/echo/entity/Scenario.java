package com.echo.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * State machine scene entity
 * <p>
 * Record the current status of each scenario for Stateful Scenarios functions.
 * Rules can be associated to Scenario through scenarioName to implement state-driven matching logic.
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

    /** Scenario name (unique) */
    @Column(nullable = false, unique = true)
    private String scenarioName;

    /** Current status */
    @Column(nullable = false)
    private String currentState;

    /** Optimistic locking version number */
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
