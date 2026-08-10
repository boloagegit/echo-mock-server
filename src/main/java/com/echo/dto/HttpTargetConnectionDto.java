package com.echo.dto;

import java.time.LocalDateTime;

/** Safe outbound HTTP profile representation; the secret is intentionally omitted. */
public record HttpTargetConnectionDto(
        Long id,
        Long version,
        String name,
        String baseUrl,
        String authType,
        String username,
        boolean secretConfigured,
        int connectTimeoutSeconds,
        int readTimeoutSeconds,
        boolean tlsVerificationEnabled,
        boolean enabled,
        boolean defaultConnection,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
