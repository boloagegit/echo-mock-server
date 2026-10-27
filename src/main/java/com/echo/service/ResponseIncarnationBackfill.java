package com.echo.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** A resumable, finite startup repair; never blocks readiness or parses response content. */
@Component
@ConditionalOnProperty(name = "echo.storage.mode", havingValue = "database", matchIfMissing = true)
@ConditionalOnProperty(
        name = "echo.response.incarnation-backfill.enabled",
        havingValue = "true",
        matchIfMissing = true)
public final class ResponseIncarnationBackfill {
    private static final Logger LOG = LoggerFactory.getLogger(ResponseIncarnationBackfill.class);
    private final ResponseService responses;
    private final int batchSize;
    private final LongSupplier clock;
    private final Counter rows;
    private final Counter batches;
    private final Counter failures;
    private volatile boolean ready;
    private volatile boolean complete;
    private Long boundary;
    private long cursor;
    private long retryAt;
    private boolean retryPending;

    @Autowired
    public ResponseIncarnationBackfill(
            ResponseService responses,
            MeterRegistry meters,
            @Value("${echo.response.incarnation-backfill.batch-size:32}") int batchSize) {
        this(responses, meters, batchSize, System::nanoTime);
    }

    ResponseIncarnationBackfill(
            ResponseService responses, MeterRegistry meters, int batchSize, LongSupplier clock) {
        if (batchSize < 1 || batchSize > 32)
            throw new IllegalArgumentException("Backfill batch size must be 1..32");
        this.responses = responses;
        this.batchSize = batchSize;
        this.clock = clock;
        rows = meters.counter("echo.response.incarnation.backfill.rows");
        batches = meters.counter("echo.response.incarnation.backfill.batches");
        failures = meters.counter("echo.response.incarnation.backfill.failures");
        Gauge.builder(
                        "echo.response.incarnation.backfill.complete",
                        this,
                        task -> task.complete ? 1 : 0)
                .register(meters);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ready() {
        ready = true;
    }

    @Scheduled(
            initialDelayString = "${echo.response.incarnation-backfill.initial-delay-ms:5000}",
            fixedDelayString = "${echo.response.incarnation-backfill.delay-ms:1000}")
    public synchronized void tick() {
        if (!ready || complete || (retryPending && clock.getAsLong() - retryAt < 0)) return;
        try {
            if (boundary == null) boundary = responses.incarnationBackfillBoundary();
            ResponseService.IncarnationBatch result =
                    responses.initializeMissingIncarnations(cursor, boundary, batchSize);
            // The proxied call has committed before either checkpoint advances.
            cursor = result.lastId();
            rows.increment(result.updated());
            batches.increment();
            retryPending = false;
            if (result.complete()) {
                complete = true;
                LOG.info("Response incarnation backfill completed through id {}", boundary);
            }
        } catch (RuntimeException failure) {
            failures.increment();
            retryAt = clock.getAsLong() + TimeUnit.SECONDS.toNanos(5);
            retryPending = true;
            // Avoid driver messages that may contain connection details. Legacy reads remain safe.
            LOG.warn(
                    "Response incarnation backfill deferred after {}; retrying later",
                    failure.getClass().getSimpleName());
        }
    }
}
