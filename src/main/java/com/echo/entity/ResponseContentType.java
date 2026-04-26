package com.echo.entity;

/**
 * 回應內容類型，由規則的 protocol + sseEnabled 動態推斷。
 */
public enum ResponseContentType {
    TEXT,
    SSE_EVENTS
}
