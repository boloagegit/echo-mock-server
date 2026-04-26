package com.echo.pipeline;

import lombok.Builder;
import lombok.Getter;

/**
 * 規則條件集合 Value Object
 * <p>
 * 封裝規則的匹配條件，供 {@code matchRule()} 使用。
 * JMS 規則僅使用 bodyCondition，queryCondition 和 headerCondition 為 null。
 */
@Getter
@Builder
public class ConditionSet {

    /** Body 匹配條件 */
    private final String bodyCondition;

    /** Query 參數匹配條件（僅 HTTP） */
    private final String queryCondition;

    /** Header 匹配條件（僅 HTTP） */
    private final String headerCondition;
}
