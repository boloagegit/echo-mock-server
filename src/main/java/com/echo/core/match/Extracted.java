package com.echo.core.match;

import java.util.List;

/**
 * {@link ValueExtractor} 的取值結果。
 *
 * @param state  取值狀態
 * @param values {@link State#FOUND} 時至少有一個值，其他狀態為空
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

    /** 空清單視為 {@link State#MISSING}。 */
    public static Extracted of(List<Value> values) {
        return values.isEmpty() ? MISSING : new Extracted(State.FOUND, values);
    }

    public enum State {
        /** 取到一個以上的值。 */
        FOUND,
        /** 來源中不存在此欄位。 */
        MISSING,
        /** 無法判斷（例如 body 過大、值過長），任何條件都視為不符合。 */
        UNAVAILABLE
    }
}
