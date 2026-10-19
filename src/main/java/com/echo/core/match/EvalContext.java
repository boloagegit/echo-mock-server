package com.echo.core.match;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Evaluation status of a single request. The same leaf node condition is evaluated only once within the same request, and the results can be reused across rules.
 * <p>
 * Not thread-safe; one is created per request.
 */
public final class EvalContext {

    private final RequestView request;
    private final Map<String, Boolean> outcomes;
    /** Intermediate results that can be reused across conditions within the same request (such as the value of JSONPath) are created only when used for the first time. */
    private Map<Object, Object> values;

    public EvalContext(RequestView request) {
        this(request, new HashMap<>());
    }

    /**
     * Shared result buffer with caller. The old version {@code PreparedBody} temporarily stores the results with the same key (for example, {@code body:a=1}),
     * After sharing, the old and new sides, as well as the pre-calculated results of JMS stream XML, can be reused with each other.
     */
    public EvalContext(RequestView request, Map<String, Boolean> sharedOutcomes) {
        this.request = Objects.requireNonNull(request, "request");
        this.outcomes = Objects.requireNonNull(sharedOutcomes, "sharedOutcomes");
    }

    public RequestView request() {
        return request;
    }

    /** The currently evaluated leaf node results (key is {@link CompiledMatcher#cacheKey()}). */
    public Map<String, Boolean> outcomesSnapshot() {
        return Map.copyOf(outcomes);
    }

    /**
     * Obtain the intermediate result shared within the same request. If it does not exist, it will be calculated and saved as {@code supplier}.
     * For example, when 30 rules all check {@code $.user.id} but the expected values are different, JSONPath only needs to be evaluated once.
     *
     * @param key Caller-customized key with equals/hashCode; it is recommended to use private record to avoid conflicts with other implementations
     */
    @SuppressWarnings("unchecked")
    public <T> T cachedValue(Object key, Supplier<T> supplier) {
        if (values == null) {
            values = new HashMap<>();
        }
        Object cached = values.get(key);
        if (cached == null) {
            cached = Objects.requireNonNull(supplier.get(), "cached value");
            values.put(key, cached);
        }
        return (T) cached;
    }

    /** Number of intermediate results saved (for testing and monitoring). */
    public int cachedValueCount() {
        return values == null ? 0 : values.size();
    }

    boolean memoized(String key, BooleanSupplier evaluation) {
        Boolean cached = outcomes.get(key);
        if (cached == null) {
            cached = evaluation.getAsBoolean();
            outcomes.put(key, cached);
        }
        return cached;
    }
}
