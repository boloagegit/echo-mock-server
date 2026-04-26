package com.echo.service;

/**
 * 回應內容驗證介面。
 * 不同的 ResponseContentType 有不同的驗證實作。
 */
public interface ResponseContentValidator {

    /**
     * 驗證回應內容。
     *
     * @param body 回應內容字串
     * @throws IllegalArgumentException 驗證失敗時拋出
     */
    void validate(String body);
}
