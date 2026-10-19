package com.echo.service;

import org.springframework.stereotype.Component;

/**
 * Plain text response content validator, without any format verification.
 */
@Component
public class TextContentValidator implements ResponseContentValidator {

    @Override
    public void validate(String body) {
        // Plain text does not perform format verification
    }
}
