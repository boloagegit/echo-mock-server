package com.echo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Durable request-log delivery checkpoint.
 *
 * <p>The checkpoint is committed in the same transaction as its request-log batch.
 * A spool row is therefore either replayed after a rollback or skipped after a commit,
 * including when the process stops between the main-database commit and spool cleanup.</p>
 */
@Entity
@Table(name = "request_log_checkpoint")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RequestLogCheckpoint {

    @Id
    @Column(length = 36, nullable = false)
    private String spoolId;

    @Column(nullable = false)
    private long lastSequence;

    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
