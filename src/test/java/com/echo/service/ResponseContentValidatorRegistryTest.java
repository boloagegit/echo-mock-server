package com.echo.service;

import com.echo.entity.ResponseContentType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ResponseContentValidatorRegistry.getValidator() 單元測試。
 */
class ResponseContentValidatorRegistryTest {

    private ResponseContentValidatorRegistry registry;
    private TextContentValidator textValidator;
    private SseEventsContentValidator sseValidator;

    @BeforeEach
    void setUp() {
        textValidator = new TextContentValidator();
        sseValidator = new SseEventsContentValidator(new ObjectMapper());
        registry = new ResponseContentValidatorRegistry(textValidator, sseValidator);
    }

    @Test
    void getValidator_sseEvents_returnsSseEventsContentValidator() {
        ResponseContentValidator result = registry.getValidator(ResponseContentType.SSE_EVENTS);
        assertThat(result).isSameAs(sseValidator);
    }

    @Test
    void getValidator_text_returnsTextContentValidator() {
        ResponseContentValidator result = registry.getValidator(ResponseContentType.TEXT);
        assertThat(result).isSameAs(textValidator);
    }

    @Test
    void getValidator_null_fallsBackToTextValidator() {
        ResponseContentValidator result = registry.getValidator(null);
        assertThat(result).isSameAs(textValidator);
    }
}
