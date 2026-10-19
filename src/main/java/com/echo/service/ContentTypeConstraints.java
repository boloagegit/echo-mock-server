package com.echo.service;

import com.echo.entity.Protocol;
import com.echo.entity.ResponseContentType;

/**
 * Static tool class that infers the response content type based on the protocol and SSE settings.
 */
public final class ContentTypeConstraints {

    private ContentTypeConstraints() {
        // Not instantiable
    }

    /**
     * Inferring response content type.
     *
     * @param protocol   protocol type
     * @param sseEnabled Whether to enable SSE
     * @return Inferred ResponseContentType
     */
    public static ResponseContentType infer(Protocol protocol, Boolean sseEnabled) {
        if (protocol == Protocol.HTTP && Boolean.TRUE.equals(sseEnabled)) {
            return ResponseContentType.SSE_EVENTS;
        }
        return ResponseContentType.TEXT;
    }
}
