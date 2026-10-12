package com.echo.core.match;

/** 編譯後的運算子，判斷單一值是否符合。實作必須是執行緒安全且無狀態的。 */
@FunctionalInterface
public interface CompiledPredicate {

    boolean test(Value actual);
}
