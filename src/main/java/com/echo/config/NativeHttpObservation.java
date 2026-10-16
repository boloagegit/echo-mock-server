package com.echo.config;

import io.micrometer.common.KeyValues;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationConvention;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.transport.RequestReplyReceiverContext;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.AttachmentKey;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** One server observation across worker handoffs, deferred responses and connection closure. */
final class NativeHttpObservation {
    private static final AttachmentKey<NativeHttpObservation> KEY =
            AttachmentKey.create(NativeHttpObservation.class);
    private static final Convention CONVENTION = new Convention();
    private final Context context;
    private final Observation observation;
    private final AtomicBoolean stopped = new AtomicBoolean();

    private NativeHttpObservation(HttpServerExchange exchange, ObservationRegistry registry) {
        context = new Context(exchange);
        observation = Observation.createNotStarted(CONVENTION, () -> context, registry).start();
    }

    static NativeHttpObservation start(HttpServerExchange exchange, ObservationRegistry registry) {
        NativeHttpObservation result = new NativeHttpObservation(exchange, registry);
        exchange.putAttachment(KEY, result);
        return result;
    }

    static Observation.Scope scope(HttpServerExchange exchange) {
        NativeHttpObservation value = exchange.getAttachment(KEY);
        return (value == null ? Observation.NOOP : value.observation).openScope();
    }

    static void error(HttpServerExchange exchange, Throwable error) {
        NativeHttpObservation value = exchange.getAttachment(KEY);
        if (value != null && !value.stopped.get()) value.observation.error(error);
    }

    void stop(boolean disconnected) {
        if (!stopped.compareAndSet(false, true)) return;
        if (disconnected) {
            if (context.getError() == null) observation.error(new IOException("HTTP connection closed before completion"));
            if (!context.exchange.isResponseStarted()) context.responseUnavailable = true;
        }
        observation.stop();
    }

    /** ReceiverContext lets standard Micrometer tracing handlers extract the incoming parent. */
    static final class Context extends RequestReplyReceiverContext<HttpServerExchange, HttpServerExchange> {
        private final HttpServerExchange exchange;
        private boolean responseUnavailable;

        Context(HttpServerExchange exchange) {
            super((carrier, key) -> carrier.getRequestHeaders().getFirst(key));
            this.exchange = exchange;
            setCarrier(exchange);
            setResponse(exchange);
        }
    }

    private static final class Convention implements ObservationConvention<Context> {
        @Override
        public boolean supportsContext(Observation.Context context) { return context instanceof Context; }

        @Override
        public String getName() { return "http.server.requests"; }

        @Override
        public String getContextualName(Context context) {
            return "http " + context.exchange.getRequestMethod().toString().toLowerCase(Locale.ROOT) + " /mock/**";
        }

        @Override
        public KeyValues getLowCardinalityKeyValues(Context context) {
            Throwable error = context.getError();
            int status = context.responseUnavailable ? 0 : context.exchange.getStatusCode();
            return KeyValues.of("method", context.exchange.getRequestMethod().toString(),
                    "uri", "/mock/**", "status", status == 0 ? "UNKNOWN" : Integer.toString(status),
                    "exception", error == null ? "none" : error.getClass().getSimpleName(),
                    "outcome", switch (status / 100) {
                        case 1 -> "INFORMATIONAL";
                        case 2 -> "SUCCESS";
                        case 3 -> "REDIRECTION";
                        case 4 -> "CLIENT_ERROR";
                        case 5 -> "SERVER_ERROR";
                        default -> "UNKNOWN";
                    });
        }

        @Override
        public KeyValues getHighCardinalityKeyValues(Context context) {
            // As in Spring MVC, queries and bodies are not added to HTTP observation tags.
            return KeyValues.of("http.url", context.exchange.getRequestURL());
        }
    }
}
