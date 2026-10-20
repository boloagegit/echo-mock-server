package com.echo.config;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class NativeHttpResourcesTest {
    @Test
    void shutdownDiscardsOwnedQueuedWorkWithoutLosingItsReservation() throws Exception {
        var resources = new NativeHttpResources(1, 1, 1024, 4096);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            resources.workers.execute(() -> {
                entered.countDown();
                try { release.await(3, TimeUnit.SECONDS); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            });
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            var lease = resources.acquire();
            assertThat(lease.reserve(1000)).isTrue();
            var work = lease.begin();
            AtomicInteger discarded = new AtomicInteger();
            resources.workers.execute(new NativeHttpResources.QueuedWork() {
                @Override public void run() { throw new AssertionError("queued task must not run"); }
                @Override public void discard() { discarded.incrementAndGet(); work.close(); }
            });
            lease.close();
            assertThat(resources.active()).isEqualTo(1);
            resources.close();
            assertThat(discarded).hasValue(1);
            assertThat(resources.active()).isZero();
            assertThat(resources.bufferedBytes()).isZero();
        } finally {
            release.countDown();
            resources.close();
            assertThat(resources.workers.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void closedTransportRetainsItsReservationsUntilEveryWorkScopeExits() {
        try (var resources = new NativeHttpResources(1, 1, 1024, 4096)) {
            var lease = resources.acquire();
            assertThat(lease.reserve(1000)).isTrue();
            var first = lease.begin();
            var second = lease.begin();
            AtomicInteger cancelled = new AtomicInteger();
            lease.onClose(cancelled::incrementAndGet);
            lease.close();
            lease.close();
            assertThat(cancelled).hasValue(1);
            assertThat(resources.active()).isEqualTo(1);
            assertThat(resources.bufferedBytes()).isEqualTo(1000);
            assertThat(resources.resourceMetricsSnapshot()).containsEntry("nativeDrainingRequests", 1);
            assertThat(resources.acquire()).isNull();
            org.assertj.core.api.Assertions.assertThatThrownBy(lease::begin)
                    .isInstanceOf(java.util.concurrent.CancellationException.class);
            first.close();
            first.close();
            assertThat(resources.active()).isEqualTo(1);
            second.close();
            second.close();
            assertThat(resources.active()).isZero();
            assertThat(resources.bufferedBytes()).isZero();
            assertThat(resources.resourceMetricsSnapshot()).containsEntry("nativeDrainingRequests", 0);
            var next = resources.acquire();
            assertThat(next).isNotNull();
            next.close();
        }
    }

    @Test
    void responseStorageCanShrinkWithoutReleasingOtherRequestReservations() {
        try (var resources = new NativeHttpResources(1, 2, 1024, 4096)) {
            var lease = resources.acquire();
            assertThat(lease.reserve(100)).isTrue();
            assertThat(lease.reserveResponseBody(3000)).isTrue();
            assertThat(lease.reserveResponseBody(4096)).isFalse();
            assertThat(resources.bufferedBytes()).isEqualTo(3100);
            assertThat(lease.reserveResponseBody(1000)).isTrue();
            assertThat(resources.bufferedBytes()).isEqualTo(1100);
            assertThat(lease.reserveEncodedBody(1500)).isTrue();
            assertThat(lease.reserveEncodedBody(500)).isTrue();
            assertThat(lease.reserveEncodedBody(4096)).isFalse();
            assertThat(resources.bufferedBytes()).isEqualTo(1600);
            lease.close();
            assertThat(resources.bufferedBytes()).isZero();
            assertThat(lease.reserveResponseBody(100)).isFalse();
        }
    }

    @Test
    void workersRetireAtIdleAndNewRequestsStillRunWithinTheSameCapacity() throws Exception {
        try (var resources = new NativeHttpResources(2, 2, 1024, 4096, Duration.ofMillis(50))) {
            assertThat(resources.workers.submit(() -> "before").get(2, TimeUnit.SECONDS)).isEqualTo("before");
            org.awaitility.Awaitility.await().atMost(2, TimeUnit.SECONDS)
                    .until(() -> resources.workers.getPoolSize() == 0);
            var first = resources.acquire();
            var second = resources.acquire();
            assertThat(resources.acquire()).isNull();
            assertThat(resources.workers.submit(() -> "after").get(2, TimeUnit.SECONDS)).isEqualTo("after");
            first.close();
            second.close();
            assertThat(resources.active()).isZero();
            assertThat(resources.resourceMetricsSnapshot()).containsEntry("nativeWorkerCapacity", 2);
        }
    }

    @Test
    void idleTimeoutDoesNotInterruptRunningWork() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        try (var resources = new NativeHttpResources(1, 2, 1024, 4096, Duration.ofMillis(50))) {
            try {
                var request = resources.workers.submit(() -> {
                    entered.countDown();
                    try {
                        if (!release.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("test release timed out");
                        return "completed";
                    } finally {
                        finished.countDown();
                    }
                });
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(finished.await(150, TimeUnit.MILLISECONDS)).isFalse();
                assertThat(resources.workers.getPoolSize()).isEqualTo(1);
                release.countDown();
                assertThat(request.get(2, TimeUnit.SECONDS)).isEqualTo("completed");
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void cleanupFailureCannotLeakOtherReservationsOrCallbacksAndCloseIsIdempotent() {
        try (var resources = new NativeHttpResources(1, 2, 1024, 4096)) {
            var first = resources.acquire();
            var second = resources.acquire();
            assertThat(resources.acquire()).isNull();
            assertThat(first.reserve(3000)).isTrue();
            assertThat(second.reserve(2000)).isFalse();
            AtomicInteger cancelled = new AtomicInteger();
            first.onClose(() -> { throw new IllegalStateException("broken observer"); });
            first.onClose(cancelled::incrementAndGet);
            first.close();
            first.close();
            assertThat(cancelled).hasValue(1);
            assertThat(resources.active()).isEqualTo(1);
            assertThat(resources.bufferedBytes()).isZero();
            assertThat(first.reserve(1)).isFalse();
            assertThat(second.reserve(4096)).isTrue();
            second.close();
            assertThat(resources.bufferedBytes()).isZero();
            assertThat(resources.active()).isZero();
        }
    }
}
