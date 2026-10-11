package com.echo.core.match;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * 單一請求的評估狀態。同一個葉節點條件在同一請求內只評估一次，結果可跨規則重用。
 * <p>
 * 不是執行緒安全的；每個請求建立一個。
 */
public final class EvalContext {

    private final RequestView request;
    private final Map<String, Boolean> outcomes;
    /** 同一請求內可跨條件重用的中間結果（例如 JSONPath 取值），第一次使用時才建立。 */
    private Map<Object, Object> values;

    public EvalContext(RequestView request) {
        this(request, new HashMap<>());
    }

    /**
     * 與呼叫端共用結果暫存。舊版 {@code PreparedBody} 以同樣的 key（例如 {@code body:a=1}）暫存結果，
     * 共用後新舊兩邊、以及 JMS 串流 XML 預先算好的結果都可以互相重用。
     */
    public EvalContext(RequestView request, Map<String, Boolean> sharedOutcomes) {
        this.request = Objects.requireNonNull(request, "request");
        this.outcomes = Objects.requireNonNull(sharedOutcomes, "sharedOutcomes");
    }

    public RequestView request() {
        return request;
    }

    /** 目前已評估的葉節點結果（key 為 {@link CompiledMatcher#cacheKey()}）。 */
    public Map<String, Boolean> outcomesSnapshot() {
        return Map.copyOf(outcomes);
    }

    /**
     * 取得同一請求內共用的中間結果，不存在時以 {@code supplier} 計算並保存。
     * 例如 30 條規則都檢查 {@code $.user.id} 但期望值不同時，JSONPath 只需評估一次。
     *
     * @param key 呼叫端自訂、具 equals/hashCode 的 key；建議使用私有 record 避免與其他實作衝突
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

    /** 已保存的中間結果數量（供測試與監控）。 */
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
