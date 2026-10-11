package com.echo.core.match;

import java.util.Set;

/**
 * 取值來源的擴充點。內建來源見 {@link BuiltinSources}；協定可註冊自己的來源，
 * 例如 {@code jms.property}。
 */
public interface ValueSourceFactory {

    /** 條件 JSON 中 {@code source} 欄位的值。 */
    String source();

    /** 是否需要 {@code name}（例如 header、query）。 */
    boolean requiresName();

    /** 支援的 selector；空集合表示不接受 selector，非空時 selector 為必填。 */
    Set<Selector.Kind> supportedSelectors();

    /**
     * 建立取值器。參數已通過 {@link #requiresName()} 與 {@link #supportedSelectors()} 的檢查。
     *
     * @throws IllegalArgumentException 參數不合法（例如 JSONPath 語法錯誤）
     */
    ValueExtractor create(String name, Selector selector, MatcherLimits limits);
}
