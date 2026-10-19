package com.echo.pipeline;

import com.echo.entity.FaultType;

import java.util.concurrent.CompletionStage;

/**
 * {@link Responder}’s decision response: pipeline applies the delay uniformly, writes the request record, and then writes it back to the protocol.
 */
public sealed interface ResponsePlan {

    /** Immediately available response content (mock). */
    record Content(MockResponse response) implements ResponsePlan {
    }

    /** Fault injection; {@code response} is the content to be written back before the fault occurs (the protocol ignores it when the connection is reset). */
    record Fault(FaultType type, MockResponse response) implements ResponsePlan {
    }

    /** Forwarded to downstream, response will not be available until I/O is completed. */
    record Forwarded(CompletionStage<MockResponse> response) implements ResponsePlan {
    }
}
