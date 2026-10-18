package com.echo.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/** 規則快照：變更後背景重建，重建期間回傳舊快照；做出變更的一方等到新快照生效。 */
class RuleSnapshotCacheTest {

    private final ExecutorService builderThread = Executors.newSingleThreadExecutor();
    private final ExecutorService callers = Executors.newCachedThreadPool();
    private final AtomicLong clock = new AtomicLong(1);

    @AfterEach
    void tearDown() {
        RuleSnapshotCache.clearPendingChanges();
        builderThread.shutdownNow();
        callers.shutdownNow();
    }

    private RuleSnapshotCache<String, String> cache(Function<String, String> builder) {
        return new RuleSnapshotCache<>("test", builder, builderThread, TimeUnit.MINUTES.toNanos(10), 2_000, clock::get);
    }

    /** 每次建立回傳「版本 N」；建立前可被擋住，模擬重建很慢。 */
    private static final class SlowBuilder implements Function<String, String> {
        final AtomicInteger builds = new AtomicInteger();
        volatile CountDownLatch gate = new CountDownLatch(0);
        volatile CountDownLatch started = new CountDownLatch(1);

        @Override
        public String apply(String key) {
            int n = builds.incrementAndGet();
            started.countDown();
            try {
                assertThat(gate.await(5, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return "v" + n;
        }

        void block() {
            gate = new CountDownLatch(1);
            started = new CountDownLatch(1);
        }
    }

    @Test
    @DisplayName("第一次由呼叫端建立；之後沒有變更時不再重建")
    void firstGetBuildsOnceAndThenReuses() {
        SlowBuilder builder = new SlowBuilder();
        RuleSnapshotCache<String, String> cache = cache(builder);

        assertThat(cache.get("k")).isEqualTo("v1");
        assertThat(cache.get("k")).isEqualTo("v1");
        assertThat(builder.builds).hasValue(1);
    }

    @Test
    @DisplayName("重建很慢時，請求立刻拿到舊快照，不必等待")
    void requestsGetThePreviousSnapshotWhileRebuilding() throws Exception {
        SlowBuilder builder = new SlowBuilder();
        RuleSnapshotCache<String, String> cache = cache(builder);
        assertThat(cache.get("k")).isEqualTo("v1");
        builder.block();

        // 變更來自網頁請求：只記下要等的序號，不在當下等待
        cache.changed(true);
        assertThat(builder.started.await(5, TimeUnit.SECONDS)).isTrue();

        Future<String> duringRebuild = callers.submit(() -> cache.get("k"));
        assertThat(duringRebuild.get(500, TimeUnit.MILLISECONDS)).isEqualTo("v1");

        builder.gate.countDown();
        RuleSnapshotCache.awaitPendingChanges();
        assertThat(cache.get("k")).isEqualTo("v2");
    }

    @Test
    @DisplayName("網頁請求做出的變更：回應前等到新快照生效；沒有變更時不等")
    void pendingChangesAreAwaitedOnceAtTheEndOfTheRequest() throws Exception {
        SlowBuilder builder = new SlowBuilder();
        RuleSnapshotCache<String, String> cache = cache(builder);
        cache.get("k");
        builder.block();
        AtomicReference<String> seenAfterWait = new AtomicReference<>();

        Future<?> request = callers.submit(() -> {
            cache.changed(true);
            cache.changed(true);
            cache.changed(true);
            RuleSnapshotCache.awaitPendingChanges();
            seenAfterWait.set(cache.get("k"));
        });
        assertThat(builder.started.await(5, TimeUnit.SECONDS)).isTrue();
        TimeUnit.MILLISECONDS.sleep(100);
        assertThat(request).isNotDone();

        builder.gate.countDown();
        request.get(5, TimeUnit.SECONDS);
        // 三次變更：正在建的那次可能只包含第一次，之後再補建一次；等到的一定包含全部
        assertThat(cache.generationOf("k")).isEqualTo(3);
        assertThat(seenAfterWait.get()).isEqualTo("v" + builder.builds.get());
        assertThat(builder.builds.get()).isBetween(2, 3);

        long started = System.nanoTime();
        RuleSnapshotCache.awaitPendingChanges();
        assertThat(System.nanoTime() - started).isLessThan(TimeUnit.MILLISECONDS.toNanos(50));
    }

    @Test
    @DisplayName("不在網頁請求中的變更（多台同步、排程）：當下等到新快照生效")
    void changesOutsideWebRequestsWaitRightAway() {
        SlowBuilder builder = new SlowBuilder();
        RuleSnapshotCache<String, String> cache = cache(builder);
        cache.get("k");

        cache.changed(false);

        assertThat(cache.get("k")).isEqualTo("v2");
        assertThat(cache.generationOf("k")).isEqualTo(1);
    }

    @Test
    @DisplayName("建立期間又有變更：再建一次，較舊的結果不會蓋掉較新的")
    void changesDuringABuildTriggerOneMoreBuild() throws Exception {
        SlowBuilder builder = new SlowBuilder();
        RuleSnapshotCache<String, String> cache = cache(builder);
        cache.get("k");
        builder.block();

        cache.changed(true);
        assertThat(builder.started.await(5, TimeUnit.SECONDS)).isTrue();
        // 第一次重建進行中又有兩次變更：合併成之後的一次重建
        cache.changed(true);
        cache.changed(true);
        builder.gate.countDown();
        RuleSnapshotCache.awaitPendingChanges();

        assertThat(builder.builds).hasValue(3);
        assertThat(cache.get("k")).isEqualTo("v3");
        assertThat(cache.generationOf("k")).isEqualTo(3);
    }

    @Test
    @DisplayName("重建失敗：繼續用舊快照，重試間隔過後自動再建，等待中的變更方隨之完成")
    void failedRebuildKeepsThePreviousSnapshotAndRetriesAfterTheBackoff() {
        AtomicInteger calls = new AtomicInteger();
        RuleSnapshotCache<String, String> cache = new RuleSnapshotCache<>("test", key -> {
            if (calls.incrementAndGet() == 2) {
                throw new IllegalStateException("database unavailable");
            }
            return "v" + calls.get();
        }, builderThread, TimeUnit.MINUTES.toNanos(10), 5_000, clock::get);
        assertThat(cache.get("k")).isEqualTo("v1");

        long started = System.nanoTime();
        cache.changed(false);
        long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        // 第二次建立失敗；約 1 秒後自動重試成功，不必等到 5 秒逾時，也不必有新的請求或變更
        assertThat(waitedMs).isBetween(800L, 3_000L);
        assertThat(cache.get("k")).isEqualTo("v3");
        assertThat(cache.generationOf("k")).isEqualTo(1);
    }

    @Test
    @DisplayName("重試間隔中又有變更：不丟掉，間隔過後重建並包含它")
    void changeDuringTheFailureBackoffIsBuiltAfterIt() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch failed = new CountDownLatch(1);
        RuleSnapshotCache<String, String> cache = new RuleSnapshotCache<>("test", key -> {
            if (calls.incrementAndGet() == 2) {
                failed.countDown();
                throw new IllegalStateException("database unavailable");
            }
            return "v" + calls.get();
        }, builderThread, TimeUnit.MINUTES.toNanos(10), 5_000, clock::get);
        cache.get("k");
        cache.changed(true);
        assertThat(failed.await(5, TimeUnit.SECONDS)).isTrue();
        RuleSnapshotCache.clearPendingChanges();

        // 失敗後的重試間隔內再改一次：變更方在間隔過後就完成
        long started = System.nanoTime();
        cache.changed(false);
        long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(waitedMs).isLessThan(3_000L);
        assertThat(cache.generationOf("k")).isEqualTo(2);
        assertThat(cache.get("k")).isEqualTo("v3");
    }

    @Test
    @DisplayName("超過最長存活時間：背景重建，請求先拿舊的")
    void expiredSnapshotIsRebuiltInTheBackground() throws Exception {
        SlowBuilder builder = new SlowBuilder();
        RuleSnapshotCache<String, String> cache = cache(builder);
        cache.get("k");
        builder.block();

        clock.addAndGet(TimeUnit.MINUTES.toNanos(11));
        assertThat(cache.get("k")).isEqualTo("v1");
        assertThat(builder.started.await(5, TimeUnit.SECONDS)).isTrue();
        builder.gate.countDown();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!"v2".equals(cache.get("k")) && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertThat(cache.get("k")).isEqualTo("v2");
    }

    @Test
    @DisplayName("延遲讀者看到舊快照過期：新快照已裝好時不再重複建立")
    void delayedExpiredReaderDoesNotRebuildAnAlreadyFreshSnapshot() throws Exception {
        var pausedReader = new AtomicReference<Thread>();
        var pauseOnce = new AtomicBoolean(true);
        var observedOldSnapshot = new CountDownLatch(1);
        var resumeReader = new CountDownLatch(1);
        var builds = new AtomicInteger();
        var cache = new RuleSnapshotCache<String, String>("test", key -> "v" + builds.incrementAndGet(),
                builderThread, TimeUnit.MINUTES.toNanos(10), 2_000, () -> {
                    long now = clock.get();
                    // get() reads its snapshot before asking the clock. Hold that reader while
                    // another request refreshes the snapshot and releases the rebuild slot.
                    if (Thread.currentThread() == pausedReader.get() && pauseOnce.compareAndSet(true, false)) {
                        observedOldSnapshot.countDown();
                        try {
                            assertThat(resumeReader.await(5, TimeUnit.SECONDS)).isTrue();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        }
                    }
                    return now;
                });
        assertThat(cache.get("k")).isEqualTo("v1");
        clock.addAndGet(TimeUnit.MINUTES.toNanos(11));
        try {
            Future<String> delayed = callers.submit(() -> {
                pausedReader.set(Thread.currentThread());
                return cache.get("k");
            });
            assertThat(observedOldSnapshot.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(cache.get("k")).isEqualTo("v1");
            builderThread.submit(() -> { }).get(5, TimeUnit.SECONDS);
            assertThat(cache.get("k")).isEqualTo("v2");

            resumeReader.countDown();
            assertThat(delayed.get(5, TimeUnit.SECONDS)).isEqualTo("v1");
            builderThread.submit(() -> { }).get(5, TimeUnit.SECONDS);
            assertThat(builds).hasValue(2);
            assertThat(cache.get("k")).isEqualTo("v2");
        } finally {
            resumeReader.countDown();
        }
    }

    @Test
    @DisplayName("第一次建立期間有變更提交：變更方等到包含變更的快照生效")
    void changeDuringTheFirstBuildWaitsForASnapshotThatIncludesIt() throws Exception {
        SlowBuilder builder = new SlowBuilder();
        builder.block();
        RuleSnapshotCache<String, String> cache = cache(builder);
        Future<String> firstRequest = callers.submit(() -> cache.get("k"));
        assertThat(builder.started.await(5, TimeUnit.SECONDS)).isTrue();

        // 第一份快照可能比這次變更舊：變更方不能在它建好前、也不能只因它建好就返回
        Future<?> change = callers.submit(() -> cache.changed(false));
        TimeUnit.MILLISECONDS.sleep(100);
        assertThat(change).isNotDone();
        builder.gate.countDown();

        change.get(5, TimeUnit.SECONDS);
        assertThat(cache.get("k")).isEqualTo("v2");
        assertThat(cache.generationOf("k")).isEqualTo(1);
        assertThat(firstRequest.get(5, TimeUnit.SECONDS)).isIn("v1", "v2");
    }

    @Test
    @DisplayName("第一次建立失敗：變更方不必等到逾時")
    void failedFirstBuildReleasesWaitingChanges() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch gate = new CountDownLatch(1);
        RuleSnapshotCache<String, String> cache = new RuleSnapshotCache<>("test", key -> {
            started.countDown();
            try {
                gate.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("database unavailable");
        }, builderThread, TimeUnit.MINUTES.toNanos(10), 5_000, clock::get);
        Future<String> firstRequest = callers.submit(() -> cache.get("k"));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        Future<?> change = callers.submit(() -> cache.changed(false));
        TimeUnit.MILLISECONDS.sleep(100);

        long released = System.nanoTime();
        gate.countDown();
        change.get(2, TimeUnit.SECONDS);

        assertThat(System.nanoTime() - released).isLessThan(TimeUnit.SECONDS.toNanos(1));
        assertThat(firstRequest).failsWithin(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("一個請求改到兩份快照：共用一個等待期限，不會等兩倍時間")
    void pendingChangesShareOneDeadline() {
        SlowBuilder httpBuilder = new SlowBuilder();
        SlowBuilder jmsBuilder = new SlowBuilder();
        RuleSnapshotCache<String, String> http = new RuleSnapshotCache<>("http", httpBuilder, builderThread,
                TimeUnit.MINUTES.toNanos(10), 400, clock::get);
        RuleSnapshotCache<String, String> jms = new RuleSnapshotCache<>("jms", jmsBuilder, builderThread,
                TimeUnit.MINUTES.toNanos(10), 400, clock::get);
        http.get("k");
        jms.get("q");
        httpBuilder.block();
        jmsBuilder.block();

        http.changed(true);
        jms.changed(true);
        long started = System.nanoTime();
        RuleSnapshotCache.awaitPendingChanges();
        long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        httpBuilder.gate.countDown();
        jmsBuilder.gate.countDown();

        assertThat(waitedMs).isBetween(350L, 700L);
    }

    @Test
    @DisplayName("同時第一次取用：只建立一次")
    void concurrentFirstGetsBuildOnce() throws Exception {
        SlowBuilder builder = new SlowBuilder();
        builder.block();
        RuleSnapshotCache<String, String> cache = cache(builder);

        Future<String> a = callers.submit(() -> cache.get("k"));
        Future<String> b = callers.submit(() -> cache.get("k"));
        assertThat(builder.started.await(5, TimeUnit.SECONDS)).isTrue();
        builder.gate.countDown();

        assertThat(a.get(5, TimeUnit.SECONDS)).isEqualTo("v1");
        assertThat(b.get(5, TimeUnit.SECONDS)).isEqualTo("v1");
        assertThat(builder.builds).hasValue(1);
    }
}
