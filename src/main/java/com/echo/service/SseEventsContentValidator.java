package com.echo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;


/**
 * SSE event response content validator.
 * Verifying body is a legitimate SSE event JSON array.
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
