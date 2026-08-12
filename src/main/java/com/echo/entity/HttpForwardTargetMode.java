package com.echo.entity;

/** Selects the downstream used by a matched HTTP forwarding rule. */
public enum HttpForwardTargetMode {
    ORIGINAL_HOST,
    DEFAULT_CONNECTION,
    CONNECTION
}
