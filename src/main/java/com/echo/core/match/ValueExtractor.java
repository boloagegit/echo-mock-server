package com.echo.core.match;

/** 從請求取出條件要判斷的值。實作必須是執行緒安全且無狀態的。 */
@FunctionalInterface
public interface ValueExtractor {

    Extracted extract(RequestView request);
}
