package com.echo.pipeline;

import lombok.Builder;
import lombok.Getter;

/**
 * Rule condition set Value Object
 * <p>
 * Matching conditions of encapsulation rules, for use by {@code matchRule()}.
 * The JMS rule only uses bodyCondition, queryCondition and headerCondition are null.
 */
@Getter
@Builder
public class ConditionSet {

    /** Body matching conditions */
    private final String bodyCondition;

    /** Query parameter matching conditions (HTTP only) */
    private final String queryCondition;

    /** Header matching condition (HTTP only) */
    private final String headerCondition;

    /** Structured condition JSON; when there is a value, it shall prevail. The three conditions of the old version are null */
    private final String matchSpec;
}
