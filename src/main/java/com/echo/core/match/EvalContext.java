package com.echo.core.match;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * 單一請求的評估狀態。同一個葉節點條件在同一請求內只評估一次，結果可跨規則重用。
 * <p>
 * 不是執行緒安全的；每個請求建立一個。
 */
public final class EvalContext {

    private final RequestView request;
    private final Map<String, Boolean> outcomes = new HashMap<>();

    public EvalContext(RequestView request) {
        this.request = Objects.requireNonNull(request, "request");
    }

    public RequestView request() {
        return request;
    }

    /** 目前已評估的葉節點結果（key 為 {@link CompiledMatcher#cacheKey()}）。 */
    public Map<String, Boolean> outcomesSnapshot() {
        return Map.copyOf(outcomes);
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
