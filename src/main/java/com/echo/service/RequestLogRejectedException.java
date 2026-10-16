package com.echo.service;

/** 這一筆請求記錄本身無法儲存（太大、無法序列化），重試也不會成功。 */
public class RequestLogRejectedException extends RequestLogUnavailableException {

    public RequestLogRejectedException(String message) {
        super(message);
    }

    public RequestLogRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
