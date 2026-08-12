package com.echo.service;

import lombok.Getter;

import java.util.Map;

/** Structured validation failure for a declarative rule field. */
@Getter
public class RuleApplyValidationException extends IllegalArgumentException {

    private final String validationCode;
    private final String path;
    private final Map<String, Object> details;

    public RuleApplyValidationException(String validationCode, String path, String message) {
        this(validationCode, path, message, Map.of());
    }

    public RuleApplyValidationException(
            String validationCode,
            String path,
            String message,
            Map<String, Object> details) {
        super(message);
        this.validationCode = validationCode;
        this.path = path;
        this.details = Map.copyOf(details);
    }
}
