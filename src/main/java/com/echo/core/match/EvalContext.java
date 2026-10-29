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
    // This request-local cache stores copied XPath values, never DOM nodes.
    // Both cardinality and a conservative retained-value estimate are bounded.
    private static final int MAX_EXTRACTIONS = 128;
    private static final long MAX_EXTRACTION_WEIGHT = 512 * 1024;
    private Map<Object, Extracted> extractions;
    private long extractionWeight;

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

    /** Share deterministic built-in values within one request, bypassing the cache at its limits. */
    Extracted cachedExtraction(Object key, Supplier<Extracted> supplier) {
        Extracted cached = extractions == null ? null : extractions.get(key);
        if (cached != null) {
            return cached;
        }
        Extracted extracted = Objects.requireNonNull(supplier.get(), "extracted value");
        if (extractions != null && extractions.size() >= MAX_EXTRACTIONS) {
            return extracted;
        }
        long weight = 128;
        for (Value value : extracted.values()) {
            // JSON graphs are not part of this cache's retained-value estimate.
            if (value.json() != null) {
                return extracted;
            }
            weight += 64L + (value.text() == null ? 0 : 2L * value.text().length());
            if (weight > MAX_EXTRACTION_WEIGHT - extractionWeight) {
                return extracted;
            }
        }
        if (weight > MAX_EXTRACTION_WEIGHT - extractionWeight) {
            return extracted;
        }
        if (extractions == null) {
            extractions = new HashMap<>();
        }
        extractions.put(key, extracted);
        extractionWeight += weight;
        return extracted;
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
