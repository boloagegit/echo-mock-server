package com.echo.service;

/** Snapshot metadata only; never retains response content. */
public record ResponseRevision(String incarnation, Long version) {
    public boolean known() {
        return incarnation != null && !incarnation.isBlank();
    }
}
