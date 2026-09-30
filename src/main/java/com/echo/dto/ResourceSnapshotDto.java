package com.echo.dto;

import java.time.Instant;
import java.util.Map;

/** Allowlisted, process-local observations; values are not an atomic global snapshot. */
public record ResourceSnapshotDto(boolean enabled, Instant collectedAt, Instant processStartedAt,
                                  Map<String, Section> sections) {
    public enum State { AVAILABLE, PARTIAL, DISABLED, UNSUPPORTED, FAILED }

    public record Section(State state, Map<String, Object> values) {
        public static Section empty(State state) {
            return new Section(state, Map.of());
        }
    }
}
