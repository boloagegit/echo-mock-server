package com.echo.service;

import java.util.Objects;

/** Version and content are read in the same database statement. */
public record VersionedResponseBody(String incarnation, Long version, String body) {
    public boolean matches(ResponseRevision expected) {
        return expected != null
                && expected.known()
                && Objects.equals(incarnation, expected.incarnation())
                && Objects.equals(version, expected.version());
    }
}
