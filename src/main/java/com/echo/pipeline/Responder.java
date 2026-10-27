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

    /**
     * Whether this responder needs the selected rule's stored body captured before side effects.
     * Diagnostic {@link #mode()} values do not imply this capability.
     */
    default boolean requiresResponseBody() {
        return false;
    }

    /** Captured content belongs to the same read decision as the selected rule. */
    default ResponsePlan respondWithBody(T rule, MockRequest request, String rawBody) {
        return respond(rule, request);
    }
}
