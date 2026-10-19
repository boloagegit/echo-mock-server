package com.echo.entity;

/**
 * Response content type, dynamically inferred from rule protocol + sseEnabled.
 */
public enum ResponseContentType {
    TEXT,
    SSE_EVENTS
}
