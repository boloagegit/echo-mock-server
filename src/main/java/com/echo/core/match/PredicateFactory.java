package com.echo.core.match;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.Set;

/**
 * Extension point of operator. See {@link BuiltinPredicates} for built-in operators.
 */
public interface PredicateFactory {

    /** The value of the {@code op} field in condition JSON. */
    String op();

    /** Supported option names; compilation fails when conditions use other options. */
    default Set<String> supportedOptions() {
        return Set.of();
    }

    /** Whether {@code value} is required; when it is false, the condition must not include value. */
    default boolean requiresValue() {
        return true;
    }

    /** {@code onMissing=auto} and the result cannot be obtained. */
    default boolean passesWhenMissing() {
        return false;
    }

    /** Whether to allow customization of conditions {@code onMissing}; operators that determine whether there is a value should return false. */
    default boolean allowsOnMissing() {
        return true;
    }

    /**
     * Compile operator.
     *
     * @param expected Expected value; null when {@link #requiresValue()} is false
     * @param options  Filtered to options within {@link #supportedOptions()}
     * @throws IllegalArgumentException Illegal expected value or option
     */
    CompiledPredicate compile(JsonNode expected, Map<String, JsonNode> options, MatcherLimits limits);
}
