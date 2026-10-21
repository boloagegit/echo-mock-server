package com.echo.core.match;

/** An indeterminate match must fail the request instead of selecting a fallback route. */
public final class RegexStackCapacityException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public RegexStackCapacityException(StackOverflowError cause) {
        super("REGEX_STACK_CAPACITY_EXHAUSTED", cause);
    }
}
