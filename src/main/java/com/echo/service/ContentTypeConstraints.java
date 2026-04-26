package com.echo.service;

import com.echo.entity.Protocol;
import com.echo.entity.ResponseContentType;

/**
 * 靜態工具類，根據協定與 SSE 設定推斷回應內容類型。
 */
public final class ContentTypeConstraints {

    private ContentTypeConstraints() {
        // 不可實例化
    }

    /**
     * 推斷回應內容類型。
     *
     * @param protocol   協定類型
     * @param sseEnabled 是否啟用 SSE
     * @return 推斷的 ResponseContentType
     */
    public static ResponseContentType infer(Protocol protocol, Boolean sseEnabled) {
        if (protocol == Protocol.HTTP && Boolean.TRUE.equals(sseEnabled)) {
            return ResponseContentType.SSE_EVENTS;
        }
        return ResponseContentType.TEXT;
    }
}
