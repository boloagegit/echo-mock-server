package com.echo.core.match;

import java.util.List;

/**
 * The value result of {@link ValueExtractor}.
 *
 * @param state  Value status
 * @param values There is at least one value for {@link State#FOUND}, and the other states are empty.
 */
public record Extracted(State state, List<Value> values) {

    private static final Extracted MISSING = new Extracted(State.MISSING, List.of());
    private static final Extracted UNAVAILABLE = new Extracted(State.UNAVAILABLE, List.of());

    public Extracted {
        values = List.copyOf(values);
        if ((state == State.FOUND) == values.isEmpty()) {
            throw new IllegalArgumentException("FOUND requires values; other states must not have values");
        }
    }

    public static Extracted missing() {
        return MISSING;
    }

    public static Extracted unavailable() {
        return UNAVAILABLE;
    }

    public static Extracted of(Value value) {
        return new Extracted(State.FOUND, List.of(value));
    }

    /** Empty list treated as {@link State#MISSING}. */
    public static Extracted of(List<Value> values) {
        return values.isEmpty() ? MISSING : new Extracted(State.FOUND, values);
    }

    public enum State {
        /** More than one value was obtained. */
        FOUND,
        /** This field does not exist in the source. */
        MISSING,
        /** Unable to judge (for example, body is too large, the value is too long), any condition is considered not met. */
        UNAVAILABLE
    }
}
