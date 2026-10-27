package com.echo.service;

/** Internal fast-path miss; caught before any response/scenario side effect. */
public final class ResponseRevisionChanged extends RuntimeException {
    public ResponseRevisionChanged() {
        super(null, null, false, false);
    }
}
