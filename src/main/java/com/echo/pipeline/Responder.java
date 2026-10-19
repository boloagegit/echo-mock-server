package com.echo.pipeline;

import com.echo.entity.BaseRule;

/**
 * Generate a response according to the hit rules. Pipeline queries {@link #appliesTo} in sequence, producing {@link ResponsePlan} from the first applicable responder.
 * <p>
 * Delay and Scenario state transfer belong to the rule level and are processed uniformly by pipeline and do not belong to individual responder.
 *
 * @param <T> Rule type
 */
public interface Responder<T extends BaseRule> {

    /** Response pattern displayed in transaction diagnostics, such as {@code MOCK}, {@code FORWARD}, {@code FAULT}. */
    String mode();

    boolean appliesTo(T rule, MockRequest request);

    ResponsePlan respond(T rule, MockRequest request);
}
