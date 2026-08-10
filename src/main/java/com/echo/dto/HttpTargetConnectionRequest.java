package com.echo.dto;

/** Create/update payload. A null secret preserves the existing encrypted value. */
public record HttpTargetConnectionRequest(
        Long version,
        String name,
        String baseUrl,
        String authType,
        String username,
        String secret,
        boolean clearSecret,
        Integer connectTimeoutSeconds,
        Integer readTimeoutSeconds,
        Boolean tlsVerificationEnabled,
        Boolean enabled,
        Boolean defaultConnection
) {
}
