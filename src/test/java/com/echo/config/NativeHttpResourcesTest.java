package com.echo.config;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class NativeHttpResourcesTest {
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
