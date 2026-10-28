package com.echo.service;

import com.echo.config.AdminLogQueryProperties;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class AdminLogQueryAdmissionTest {
    @Test
    void boundedQueueAdmitsInFifoOrderWithoutBargingOrLeakingPermits() throws Exception {
        var admission = admission(1, 8, 3000);
        var first = admission.acquire();
        var pool = Executors.newFixedThreadPool(8);
        List<Integer> order = Collections.synchronizedList(new ArrayList<>());
        try {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 8; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    try (var lease = admission.acquire()) {
                        assertThat(lease).isNotNull();
                        assertThat(admission.snapshot()).containsEntry("active", 1);
                        order.add(index);
                    } catch (InterruptedException e) { throw new AssertionError(e); }
                }));
                int queued = i + 1;
                await().atMost(2, TimeUnit.SECONDS).untilAsserted(() ->
                        assertThat(admission.snapshot()).containsEntry("queued", queued));
            }
            assertThat(admission.acquire()).isNull();
            first.close();
            first.close();
            for (var future : futures) future.get(5, TimeUnit.SECONDS);
            assertThat(order).containsExactly(0, 1, 2, 3, 4, 5, 6, 7);
            assertThat(admission.snapshot()).containsEntry("active", 0).containsEntry("queued", 0)
                    .containsEntry("admitted", 9L).containsEntry("rejected", 1L);
        } finally {
            first.close();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void timeoutAndInterruptionRemoveWaitersWithoutReleasingAnActiveQuery() throws Exception {
        var admission = admission(1, 2, 150);
        var first = admission.acquire();
        var pool = Executors.newSingleThreadExecutor();
        try {
            assertThat(pool.submit(admission::acquire).get(2, TimeUnit.SECONDS)).isNull();
            assertThat(admission.snapshot()).containsEntry("active", 1).containsEntry("queued", 0)
                    .containsEntry("timedOut", 1L);
            AtomicBoolean interrupted = new AtomicBoolean();
            var done = new CountDownLatch(1);
            Thread waiter = new Thread(() -> {
                try (var unexpected = admission.acquire()) {
                    assertThat(unexpected).as("Interrupted waiter must not acquire a permit").isNull();
                    throw new AssertionError("Expected interruption");
                } catch (InterruptedException expected) { interrupted.set(true); }
                finally { done.countDown(); }
            });
            waiter.start();
            await().atMost(2, TimeUnit.SECONDS).untilAsserted(() ->
                    assertThat(admission.snapshot()).containsEntry("queued", 1));
            waiter.interrupt();
            assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(interrupted).isTrue();
            assertThat(admission.snapshot()).containsEntry("active", 1).containsEntry("queued", 0)
                    .containsEntry("interrupted", 1L);
        } finally {
            first.close();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        try (var recovered = admission.acquire()) { assertThat(recovered).isNotNull(); }
        assertThat(admission.snapshot()).containsEntry("active", 0);
    }

    @Test
    void configurableConcurrencyAndZeroWaitHaveStrictCapacity() throws Exception {
        var admission = admission(2, 8, 0);
        try (var first = admission.acquire(); var second = admission.acquire()) {
            assertThat(first).isNotNull();
            assertThat(second).isNotNull();
            assertThat(admission.acquire()).isNull();
            assertThat(admission.snapshot()).containsEntry("active", 2).containsEntry("queued", 0);
        }
        assertThat(admission.snapshot()).containsEntry("active", 0);
    }

    private static AdminLogQueryAdmission admission(int concurrency, int queue, long wait) {
        var properties = new AdminLogQueryProperties();
        properties.setEnabled(true);
        properties.setConcurrency(concurrency);
        properties.setQueueCapacity(queue);
        properties.setMaxWaitMillis(wait);
        return new AdminLogQueryAdmission(properties);
    }
}
