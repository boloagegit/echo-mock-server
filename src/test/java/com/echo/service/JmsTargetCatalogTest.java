package com.echo.service;

import com.echo.config.JmsProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class JmsTargetCatalogTest {
    static Stream<Throwable> loadFailures() {
        return Stream.of(new IllegalStateException("Database unavailable"),
                new java.util.concurrent.CompletionException(new IllegalArgumentException("Original cause")),
                new AssertionError("Synthetic loader error"));
    }

    @ParameterizedTest
    @MethodSource("loadFailures")
    void waitingCallersShareTheOriginalFailureAndALaterRequestCanRetry(Throwable expected) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var loads = new AtomicInteger();
        var cache = new JmsTargetCatalog(() -> {
            if (loads.incrementAndGet() == 1) {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Load gate timed out");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                if (expected instanceof RuntimeException runtime) throw runtime;
                throw (Error) expected;
            }
            return snapshot(2);
        });
        var ownerFailure = new AtomicReference<Throwable>();
        var waiterFailure = new AtomicReference<Throwable>();
        var owner = new Thread(() -> captureFailure(cache, ownerFailure), "catalog-load-owner");
        var waiter = new Thread(() -> captureFailure(cache, waiterFailure), "catalog-load-waiter");
        try {
            owner.start();
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            waiter.start();
            // Wait for the peer to enter the lookup, independent of its locking primitive.
            await().atMost(5, TimeUnit.SECONDS).until(() -> waiter.getState() == Thread.State.BLOCKED
                    || waiter.getState() == Thread.State.WAITING);
            release.countDown();
            owner.join(5000);
            waiter.join(5000);
            assertThat(owner.isAlive()).isFalse();
            assertThat(waiter.isAlive()).isFalse();
            assertThat(ownerFailure.get()).isSameAs(expected);
            assertThat(waiterFailure.get()).isSameAs(expected);
            assertThat(loads).hasValue(1);
            assertThat(cache.snapshot().enabled(1).cacheKey()).isEqualTo("db:1:2");
            assertThat(loads).hasValue(2);
        } finally {
            release.countDown();
            owner.interrupt();
            waiter.interrupt();
            owner.join(5000);
            waiter.join(5000);
            assertThat(owner.isAlive()).isFalse();
            assertThat(waiter.isAlive()).isFalse();
        }
    }

    private static void captureFailure(JmsTargetCatalog cache, AtomicReference<Throwable> result) {
        try {
            cache.snapshot();
        } catch (RuntimeException | Error failure) {
            result.set(failure);
        }
    }

    @Test
    void anInvalidatedFailedLoadCannotClearANewerCommittedSnapshot() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var loads = new AtomicInteger();
        var expected = new IllegalStateException("Old database attempt failed");
        var cache = new JmsTargetCatalog(() -> {
            if (loads.incrementAndGet() == 1) {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Load gate timed out");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                throw expected;
            }
            return snapshot(2);
        });
        var failure = new AtomicReference<Throwable>();
        var owner = new Thread(() -> captureFailure(cache, failure), "obsolete-catalog-load");
        var worker = Executors.newSingleThreadExecutor();
        try {
            owner.start();
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            cache.invalidate();
            assertThat(worker.submit(cache::snapshot).get(2, TimeUnit.SECONDS)
                    .enabled(1).cacheKey()).isEqualTo("db:1:2");
            release.countDown();
            owner.join(5000);
            assertThat(owner.isAlive()).isFalse();
            assertThat(failure.get()).isSameAs(expected);
            assertThat(cache.snapshot().enabled(1).cacheKey()).isEqualTo("db:1:2");
            assertThat(loads).hasValue(2);
        } finally {
            release.countDown();
            owner.interrupt();
            owner.join(5000);
            worker.shutdownNow();
            assertThat(worker.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            assertThat(owner.isAlive()).isFalse();
        }
    }

    @Test
    void aNullLoaderResultFailsWithoutPoisoningSubsequentLookups() {
        var loads = new AtomicInteger();
        var cache = new JmsTargetCatalog(() -> loads.incrementAndGet() == 1 ? null : snapshot(2));
        assertThatThrownBy(cache::snapshot).isInstanceOf(NullPointerException.class);
        assertThat(cache.snapshot().enabled(1).cacheKey()).isEqualTo("db:1:2");
        assertThat(loads).hasValue(2);
    }

    private static JmsTargetCatalog.Snapshot snapshot(int revision) {
        var target = new JmsProperties.Target();
        target.setQueue("QUEUE." + revision);
        return new JmsTargetCatalog.Snapshot(Map.of(1L, new JmsTargetCatalog.Profile(true,
                () -> new JmsTargetConnectionService.ResolvedTarget("db:1:" + revision, "Primary", target, false))), 1L);
    }

    @Test
    void unchangedSettingsLoadOnceAndInvalidationLeavesExistingRequestsPinned() {
        var loads = new AtomicInteger();
        var cache = new JmsTargetCatalog(() -> snapshot(loads.incrementAndGet()));
        var first = cache.snapshot();
        for (int i = 0; i < 100; i++) assertThat(cache.snapshot()).isSameAs(first);
        assertThat(loads).hasValue(1);

        cache.invalidate();

        assertThat(cache.snapshot().active().orElseThrow().target().getQueue()).isEqualTo("QUEUE.2");
        assertThat(first.active().orElseThrow().target().getQueue()).isEqualTo("QUEUE.1");
        var mutable = first.enabled(1).target();
        mutable.setQueue("caller mutation");
        assertThat(first.enabled(1).target().getQueue()).isEqualTo("QUEUE.1");
    }

    @Test
    void anOlderDatabaseLoadCannotPublishAfterACommitInvalidatesIt() throws Exception {
        var captured = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var loads = new AtomicInteger();
        var cache = new JmsTargetCatalog(() -> {
            int version = loads.getAndIncrement();
            if (version == 0) {
                captured.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test load timed out");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            return snapshot(version);
        });
        var executor = Executors.newFixedThreadPool(2);
        try {
            var loading = executor.submit(cache::snapshot);
            assertThat(captured.await(5, TimeUnit.SECONDS)).isTrue();
            executor.submit(cache::invalidate).get(2, TimeUnit.SECONDS);
            release.countDown();
            assertThat(loading.get(5, TimeUnit.SECONDS).enabled(1).cacheKey()).isEqualTo("db:1:1");
            assertThat(cache.snapshot().enabled(1).cacheKey()).isEqualTo("db:1:1");
            assertThat(loads).hasValue(2);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void failedLoadsAreNotPublishedAndConcurrentFirstRequestsShareOneLoad() throws Exception {
        var loads = new AtomicInteger();
        var cache = new JmsTargetCatalog(() -> {
            if (loads.incrementAndGet() == 1) throw new IllegalStateException("Database unavailable");
            return snapshot(2);
        });
        assertThatThrownBy(cache::snapshot).hasMessage("Database unavailable");
        var executor = Executors.newFixedThreadPool(8);
        try {
            var calls = new java.util.ArrayList<java.util.concurrent.Future<JmsTargetCatalog.Snapshot>>();
            for (int i = 0; i < 32; i++) calls.add(executor.submit(cache::snapshot));
            for (var call : calls) assertThat(call.get(5, TimeUnit.SECONDS).enabled(1).cacheKey()).isEqualTo("db:1:2");
            assertThat(loads).hasValue(2);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void profileFailuresStayIsolatedAndSnapshotMembershipCannotBeMutated() {
        var profiles = new HashMap<Long, JmsTargetCatalog.Profile>();
        var decryptions = new AtomicInteger();
        profiles.put(1L, new JmsTargetCatalog.Profile(true,
                () -> snapshot(1).enabled(1)));
        profiles.put(2L, new JmsTargetCatalog.Profile(true, () -> {
            decryptions.incrementAndGet();
            throw new IllegalStateException("Invalid credential");
        }));
        profiles.put(3L, new JmsTargetCatalog.Profile(false, () -> {
            throw new AssertionError("Disabled profile must not decrypt");
        }));
        var catalog = new JmsTargetCatalog.Snapshot(profiles, 1L);
        profiles.clear();
        assertThat(catalog.active().orElseThrow().cacheKey()).isEqualTo("db:1:1");
        assertThat(decryptions).hasValue(0);
        assertThatThrownBy(() -> catalog.enabled(2)).hasMessage("Invalid credential");
        assertThatThrownBy(() -> catalog.enabled(3)).hasMessage("JMS_CONNECTION_DISABLED");
        assertThatThrownBy(() -> catalog.enabled(4)).hasMessage("JMS_CONNECTION_NOT_FOUND");
        assertThat(new JmsTargetCatalog.Snapshot(Map.of(), null).active()).isEmpty();
    }
}
