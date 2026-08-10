package com.echo.service;

import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ReactiveConnectionLimiterTest {

    private ScheduledExecutorService scheduler;
    private ReactiveConnectionLimiter limiter;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        limiter = new ReactiveConnectionLimiter(1, 1_000, scheduler);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    void waitingRequestExpiresAtItsOwnDeadline() throws Exception {
        var first = limiter.acquire(Duration.ofSeconds(1)).block();
        long started = System.nanoTime();

        CompletableFuture<ReactiveConnectionLimiter.Lease> waiting = limiter
                .acquire(Duration.ofMillis(100)).toFuture();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> waiting.get(1, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(ConnectionRequestTimeoutException.class);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertThat(elapsedMillis).isBetween(70L, 700L);
        assertThat(limiter.pendingCount()).isZero();
        first.close();
    }

    @Test
    void cancellingWaiterRemovesItWithoutConsumingNextPermit() throws Exception {
        var first = limiter.acquire(Duration.ofSeconds(1)).block();
        CompletableFuture<ReactiveConnectionLimiter.Lease> cancelled = limiter
                .acquire(Duration.ofSeconds(5)).toFuture();
        assertThat(limiter.pendingCount()).isEqualTo(1);

        assertThat(cancelled.cancel(true)).isTrue();
        first.close();

        var next = limiter.acquire(Duration.ofMillis(200))
                .block(Duration.ofSeconds(1));
        assertThat(next).isNotNull();
        next.close();
        assertThat(limiter.pendingCount()).isZero();
    }

    @Test
    void releasesCapacityAfterEachRequestCompletes() {
        for (int index = 0; index < 100; index++) {
            var lease = limiter.acquire(Duration.ofSeconds(1)).block();
            assertThat(lease).isNotNull();
            lease.close();
        }

        assertThat(limiter.pendingCount()).isZero();
    }

    @Test
    void rejectsImmediatelyWhenPendingCapacityIsFull() {
        limiter = new ReactiveConnectionLimiter(1, 1, scheduler);
        var first = limiter.acquire(Duration.ofSeconds(1)).block();
        CompletableFuture<ReactiveConnectionLimiter.Lease> waiting = limiter
                .acquire(Duration.ofSeconds(5)).toFuture();

        CompletableFuture<ReactiveConnectionLimiter.Lease> rejected = limiter
                .acquire(Duration.ofSeconds(5)).toFuture();

        assertThat(rejected.handle((value, error) -> error).join())
                .isInstanceOf(ReactiveConnectionLimiter.CapacityException.class);
        assertThat(limiter.pendingCount()).isEqualTo(1);
        waiting.cancel(true);
        first.close();
        assertThat(limiter.pendingCount()).isZero();
    }

    @Test
    void cancellingManyWaitersReleasesEveryPendingSlot() {
        limiter = new ReactiveConnectionLimiter(1, 500, scheduler);
        var first = limiter.acquire(Duration.ofSeconds(1)).block();
        List<CompletableFuture<ReactiveConnectionLimiter.Lease>> waiting = new ArrayList<>();
        for (int index = 0; index < 500; index++) {
            waiting.add(limiter.acquire(Duration.ofSeconds(5)).toFuture());
        }
        assertThat(limiter.pendingCount()).isEqualTo(500);

        waiting.forEach(future -> future.cancel(true));

        assertThat(limiter.pendingCount()).isZero();
        first.close();
        var next = limiter.acquire(Duration.ofMillis(200)).block(Duration.ofSeconds(1));
        assertThat(next).isNotNull();
        next.close();
    }
}
