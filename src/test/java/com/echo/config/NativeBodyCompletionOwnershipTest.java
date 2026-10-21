package com.echo.config;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class NativeBodyCompletionOwnershipTest {
    @Test
    void bodyOwnershipOutlivesCancellationUntilTheLastSynchronousUserExits() {
        try (var resources = new NativeHttpResources(1, 1, 1024, 65536)) {
            var lease = resources.acquire();
            assertThat(lease.reserve(65536)).isTrue();
            var work = lease.begin();
            AtomicInteger cancelled = new AtomicInteger();
            AtomicInteger bodyReleased = new AtomicInteger();
            lease.onClose(cancelled::incrementAndGet);
            assertThat(lease.retainUntilComplete(bodyReleased::incrementAndGet)).isTrue();
            lease.close();
            assertThat(cancelled).hasValue(1);
            assertThat(resources.resourceMetricsSnapshot()).containsEntry("nativeDrainingRequests", 1);
            assertThat(resources.active()).isEqualTo(1);
            assertThat(resources.bufferedBytes()).isEqualTo(65536);
            assertThat(bodyReleased).hasValue(0);
            work.close();
            assertThat(bodyReleased).hasValue(1);
            assertThat(resources.active()).isZero();
            assertThat(resources.bufferedBytes()).isZero();
        }
    }

    @Test
    void lateBodyRegistrationAlsoWaitsForCancelledWork() {
        try (var resources = new NativeHttpResources(1, 1, 1024, 4096)) {
            var lease = resources.acquire();
            var work = lease.begin();
            lease.close();
            AtomicInteger released = new AtomicInteger();
            assertThat(lease.retainUntilComplete(released::incrementAndGet)).isTrue();
            assertThat(released).hasValue(0);
            work.close();
            assertThat(released).hasValue(1);
        }
    }

    @Test
    void completionCallbacksAreIsolatedAndLateRegistrationRunsExactlyOnce() {
        try (var resources = new NativeHttpResources(1, 1, 1024, 4096)) {
            var lease = resources.acquire();
            var work = lease.begin();
            var released = new AtomicInteger();
            lease.retainUntilComplete(() -> { throw new IllegalStateException("injected cleanup failure"); });
            lease.retainUntilComplete(released::incrementAndGet);
            lease.close();
            work.close();
            lease.close();
            lease.retainUntilComplete(released::incrementAndGet);
            assertThat(released).hasValue(2);
            assertThat(resources.active()).isZero();
        }
    }

    @Test
    void registrationCancellationAndWorkExitRacesReleaseEveryBodyExactlyOnce() throws Exception {
        var executor = Executors.newFixedThreadPool(3);
        try (var resources = new NativeHttpResources(1, 1, 1024, 4096)) {
            for (int i = 0; i < 500; i++) {
                var lease = resources.acquire();
                var work = lease.begin();
                var released = new AtomicInteger();
                var start = new CountDownLatch(1);
                var registration = executor.submit(() -> {
                    start.await();
                    lease.retainUntilComplete(released::incrementAndGet);
                    return null;
                });
                var cancellation = executor.submit(() -> { start.await(); lease.close(); return null; });
                var completion = executor.submit(() -> { start.await(); work.close(); return null; });
                start.countDown();
                registration.get(2, TimeUnit.SECONDS);
                cancellation.get(2, TimeUnit.SECONDS);
                completion.get(2, TimeUnit.SECONDS);
                assertThat(released).hasValue(1);
                assertThat(resources.active()).isZero();
            }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        }
    }
}
