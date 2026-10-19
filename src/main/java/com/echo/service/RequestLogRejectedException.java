package com.echo.service;

/** This request record itself cannot be stored (it is too large and cannot be serialized), and retry will not succeed. */
public class RequestLogRejectedException extends RequestLogUnavailableException {

    public RequestLogRejectedException(String message) {
        super(message);
    }

    public RequestLogRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
