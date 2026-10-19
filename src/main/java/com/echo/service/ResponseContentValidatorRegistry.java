package com.echo.service;

import com.echo.entity.ResponseContentType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * Responding to the Content Validator Registry.
 * Manage all validators with ResponseContentType as key,
 * The validator fallback to TEXT cannot be found when the corresponding validator is found.
 */
@Component
public class ResponseContentValidatorRegistry {

    private final Map<ResponseContentType, ResponseContentValidator> validators;

    public ResponseContentValidatorRegistry(TextContentValidator textValidator,
                                            SseEventsContentValidator sseEventsValidator) {
        this.validators = new EnumMap<>(ResponseContentType.class);
        this.validators.put(ResponseContentType.TEXT, textValidator);
        this.validators.put(ResponseContentType.SSE_EVENTS, sseEventsValidator);
    }

    /**
     * Obtain the corresponding validator according to contentType.
     * fallback to TEXT validator not found.
     *
     * @param contentType Response content type
     * @return Corresponding validator
     */
    public ResponseContentValidator getValidator(ResponseContentType contentType) {
        return validators.getOrDefault(contentType, validators.get(ResponseContentType.TEXT));
    }
}
