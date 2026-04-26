package com.echo.service;

import org.springframework.stereotype.Component;

/**
 * 純文字回應內容驗證器，不做任何格式驗證。
 */
@Component
public class TextContentValidator implements ResponseContentValidator {

    @Override
    public void validate(String body) {
        // 純文字不做格式驗證
    }
}
