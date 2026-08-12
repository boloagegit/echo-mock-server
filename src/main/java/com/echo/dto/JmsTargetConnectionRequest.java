package com.echo.dto;

/** Create/update payload. A null password preserves the existing encrypted credential. */
public record JmsTargetConnectionRequest(
        Long version,
        String name,
        String providerType,
        String serverUrl,
        String username,
        String password,
        boolean clearPassword,
        String queueName,
        Integer timeoutSeconds,
        Boolean enabled,
        Boolean defaultConnection
) {
}
