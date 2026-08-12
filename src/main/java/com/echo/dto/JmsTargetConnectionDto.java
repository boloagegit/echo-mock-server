package com.echo.dto;

import java.time.LocalDateTime;

/** Safe outbound JMS profile representation; the password is intentionally omitted. */
public record JmsTargetConnectionDto(
        String id,
        Long version,
        String name,
        String providerType,
        String serverUrl,
        String username,
        boolean passwordConfigured,
        String queueName,
        int timeoutSeconds,
        boolean enabled,
        boolean defaultConnection,
        boolean legacy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
