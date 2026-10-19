package com.echo.core.match;

/** The compiled operator determines whether a single value is consistent. The implementation must be thread-safe and stateless. */
@FunctionalInterface
public interface CompiledPredicate {

    boolean test(Value actual);
}
