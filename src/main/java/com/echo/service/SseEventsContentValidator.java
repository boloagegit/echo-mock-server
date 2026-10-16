package com.echo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;


/**
 * SSE 事件回應內容驗證器。
 * 驗證 body 為合法的 SSE 事件 JSON 陣列。
 */
@Component
public class SseEventsContentValidator implements ResponseContentValidator {

    public SseEventsContentValidator(ObjectMapper objectMapper) {
        // The SSE contract has its own strict parser, shared with runtime playback.
    }

    @Override
    public void validate(String body) {
        SseEventSequence.parse(body);
    }
}
