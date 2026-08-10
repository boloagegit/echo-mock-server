package com.echo.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Raised when a request cannot be durably accepted for eventual log persistence. */
@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class RequestLogUnavailableException extends RuntimeException {

    public RequestLogUnavailableException(String message) {
        super(message);
    }

    public RequestLogUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
