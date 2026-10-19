package com.echo.service;

/**
 * Response to content verification interface.
 * Different ResponseContentType have different verification implementations.
 */
public interface ResponseContentValidator {

    /**
     * Verify response content.
     *
     * @param body Response content string
     * @throws IllegalArgumentException Thrown when verification fails
     */
    void validate(String body);
}
