package com.echo.service;

import com.echo.entity.Protocol;
import com.echo.repository.RequestLogTrafficQuery;
import com.echo.service.RequestLogTrafficService.Range;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RequestLogTrafficServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 12, 14, 47, 30);

    private final RequestLogService requestLogService = mock(RequestLogService.class);
    private final FakeQuery query = new FakeQuery();
    private final SystemConfigService config = mock(SystemConfigService.class);
    private RequestLogTrafficService service;

    @BeforeEach
    void setUp() {
        service = new RequestLogTrafficService(requestLogService, query, config);
        service.setClock(Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneId.of("UTC")));
        when(config.getRequestLogMaxRecords()).thenReturn(10_000);
    }

    @Test
    void bucketsAreAlignedToTheClockAndEndWithTheCurrentOne() {
        assertThat(RequestLogTrafficService.floor(NOW, Range.HOUR.bucketDuration())).isEqualTo(NOW.withMinute(46).withSecond(0));
        assertThat(RequestLogTrafficService.floor(NOW, Range.DAY.bucketDuration())).isEqualTo(NOW.withMinute(30).withSecond(0));
        assertThat(RequestLogTrafficService.floor(NOW, Range.WEEK.bucketDuration())).isEqualTo(NOW.withHour(12).withMinute(0).withSecond(0));

        var traffic = service.compute(Range.DAY);

        assertThat(traffic.buckets()).hasSize(48);
        assertThat(traffic.from()).isEqualTo(LocalDateTime.of(2026, 10, 11, 15, 0));
        assertThat(traffic.to()).isEqualTo(LocalDateTime.of(2026, 10, 12, 15, 0));
        assertThat(traffic.buckets().get(47).start()).isEqualTo(LocalDateTime.of(2026, 10, 12, 14, 30));
        assertThat(traffic.bucketMinutes()).isEqualTo(30);
        assertThat(service.compute(Range.HOUR).buckets()).hasSize(30);
        assertThat(service.compute(Range.WEEK).buckets()).hasSize(28);
    }

    @Test
    void recordsAreCountedByOutcome() {
        rows(row(NOW.minusMinutes(1), true, false, false, 5),     // 模擬回應
                row(NOW.minusMinutes(2), true, true, false, 40),   // 轉發
                row(NOW.minusMinutes(3), true, true, true, 3000),  // 轉發失敗
                row(NOW.minusMinutes(4), false, false, false, 2),  // 沒比對到
                row(NOW.minusMinutes(5), false, false, true, 2),   // 沒比對到（轉發錯誤只算在比對到的規則上）
                row(NOW.minusHours(3), true, false, false, 7));

        var traffic = service.compute(Range.DAY);

        assertThat(traffic.totals()).isEqualTo(new RequestLogTrafficService.Totals(6, 2, 1, 2, 1));
        var current = traffic.buckets().get(47);
        assertThat(List.of(current.mocked(), current.forwarded(), current.unmatched(), current.forwardFailed()))
                .containsExactly(1, 1, 2, 1);
        var earlier = traffic.buckets().get(41);
        assertThat(earlier.start()).isEqualTo(LocalDateTime.of(2026, 10, 12, 11, 30));
        assertThat(earlier.mocked()).isEqualTo(1);
    }

    @Test
    void responseTimeIsEstimatedFromFixedRanges() {
        List<Row> rows = new ArrayList<>();
        for (int i = 1; i <= 1000; i++) {
            rows.add(row(NOW.minusMinutes(1), true, false, false, i));
        }
        rows(rows.toArray(Row[]::new));

        var time = service.compute(Range.DAY).responseTime();

        // 實際值 500 與 950；估計值在所在區間內依名次比例取值
        assertThat(time.typicalMs()).isBetween(450, 560);
        assertThat(time.slowMs()).isBetween(900, 1000);
    }

    @Test
    void emptyLogsHaveNoResponseTime() {
        rows();

        var traffic = service.compute(Range.HOUR);

        assertThat(traffic.totals().requests()).isZero();
        assertThat(traffic.responseTime()).isEqualTo(new RequestLogTrafficService.ResponseTime(null, null));
    }

    @Test
    void reportsWhenOlderRecordsWereRemoved() {
        rows();
        query.earliest = NOW.minusDays(2);
        // id 只會遞增：最小 id 大於 1，代表較舊的紀錄被刪過（刪到上限以下之後筆數已經低於上限）
        query.smallestId = 1_001L;

        var traffic = service.compute(Range.WEEK);

        assertThat(traffic.truncated()).isTrue();
        assertThat(traffic.earliestRecord()).isEqualTo(NOW.minusDays(2));
        assertThat(traffic.maxRecords()).isEqualTo(10_000);
    }

    @Test
    void aFullDatabaseThatNeverDeletedAnythingIsNotTruncated() {
        rows();
        query.earliest = NOW.minusDays(2);
        query.smallestId = 1L;
        when(requestLogService.count()).thenReturn(9_500L);

        assertThat(service.compute(Range.WEEK).truncated()).isFalse();
    }

    @Test
    void anEmptyDatabaseIsNotTruncated() {
        rows();
        query.smallestId = null;

        assertThat(service.compute(Range.WEEK).truncated()).isFalse();
    }

    @Test
    void topUnmatchedComesFromTheDatabase() {
        rows();
        query.topUnmatched = List.of(new RequestLogTrafficQuery.EndpointCount(Protocol.HTTP, "POST", "/refund", 41));

        assertThat(service.compute(Range.DAY).topUnmatched()).containsExactly(
                new RequestLogTrafficService.Endpoint(Protocol.HTTP, "POST", "/refund", 41));
    }

    @Test
    void memoryModeCountsUnmatchedRequestsOnlyUpToTheSnapshot() {
        when(config.isRequestLogMemoryMode()).thenReturn(true);
        ConcurrentLinkedDeque<RequestLogService.LogEntry> buffer = new ConcurrentLinkedDeque<>();
        buffer.add(entry(NOW.minusMinutes(1), false, "/a"));
        buffer.add(entry(NOW, false, "/a"));
        when(requestLogService.getMemoryBuffer()).thenReturn(buffer);

        var traffic = service.compute(Range.DAY);

        assertThat(traffic.topUnmatched()).singleElement().satisfies(e -> assertThat(e.count()).isEqualTo(1));
        assertThat(traffic.generatedAt()).isEqualTo(NOW);
    }

    @Test
    void memoryModeWalksTheBufferWithoutTheDatabase() {
        when(config.isRequestLogMemoryMode()).thenReturn(true);
        ConcurrentLinkedDeque<RequestLogService.LogEntry> buffer = new ConcurrentLinkedDeque<>();
        buffer.add(entry(NOW.minusMinutes(1), false, "/a"));
        buffer.add(entry(NOW.minusMinutes(2), false, "/a"));
        buffer.add(entry(NOW.minusMinutes(3), false, "/b"));
        buffer.add(entry(NOW.minusMinutes(4), true, "/c"));
        buffer.add(entry(NOW.minusDays(3), false, "/old"));
        when(requestLogService.getMemoryBuffer()).thenReturn(buffer);

        var traffic = service.compute(Range.DAY);

        assertThat(traffic.totals().requests()).isEqualTo(4);
        assertThat(traffic.totals().unmatched()).isEqualTo(3);
        assertThat(traffic.topUnmatched()).extracting(RequestLogTrafficService.Endpoint::endpoint)
                .containsExactly("/a", "/b");
        assertThat(traffic.topUnmatched().get(0).count()).isEqualTo(2);
        assertThat(traffic.earliestRecord()).isEqualTo(NOW.minusDays(3));
        assertThat(traffic.truncated()).isFalse();
        assertThat(query.calls.get()).isZero();
    }

    @Test
    void resultsAreSharedForAShortTimeAndRecomputedAfterInvalidation() {
        rows();

        var first = service.traffic(Range.DAY);
        var second = service.traffic(Range.DAY);
        service.traffic(Range.HOUR);

        assertThat(second).isSameAs(first);
        assertThat(query.calls.get()).isEqualTo(2);

        service.invalidate();
        service.traffic(Range.DAY);
        assertThat(query.calls.get()).isEqualTo(3);
    }

    @Test
    void concurrentRequestsComputeOnlyOnce() throws Exception {
        query.delayMs = 100;

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<RequestLogTrafficService.Traffic>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Range range = i % 2 == 0 ? Range.DAY : Range.WEEK;
            results.add(pool.submit(() -> {
                start.await();
                return service.traffic(range);
            }));
        }
        start.countDown();
        for (Future<RequestLogTrafficService.Traffic> result : results) {
            assertThat(result.get(10, TimeUnit.SECONDS)).isNotNull();
        }
        pool.shutdown();

        assertThat(query.calls.get()).isEqualTo(2);
        assertThat(query.maxRunning.get()).isEqualTo(1);
    }

    @Test
    void unknownRangesAreRejected() {
        assertThat(Range.of("7d")).isEqualTo(Range.WEEK);
        assertThatThrownBy(() -> Range.of("30d")).isInstanceOf(IllegalArgumentException.class);
    }

    private void rows(Row... rows) {
        query.rows = List.of(rows);
    }

    private static Row row(LocalDateTime time, boolean matched, boolean forwarded, boolean forwardFailed,
                           int responseTimeMs) {
        return new Row(time, matched, forwarded, forwardFailed, responseTimeMs);
    }

    private record Row(LocalDateTime time, boolean matched, boolean forwarded, boolean forwardFailed,
                       int responseTimeMs) {
    }

    /** 以記憶體中的紀錄模擬資料庫端的統計（與 SQL 相同的規則），並記錄同時執行的查詢數。 */
    private static final class FakeQuery extends RequestLogTrafficQuery {
        List<Row> rows = List.of();
        List<RequestLogTrafficQuery.EndpointCount> topUnmatched = List.of();
        LocalDateTime earliest;
        Long smallestId;
        long delayMs;
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger running = new AtomicInteger();
        final AtomicInteger maxRunning = new AtomicInteger();

        @Override
        public List<BucketCount> countByBucket(List<LocalDateTime> starts) {
            calls.incrementAndGet();
            maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
            try {
                if (delayMs > 0) {
                    Thread.sleep(delayMs);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                running.decrementAndGet();
            }
            long[][] c = new long[starts.size()][4];
            for (Row row : rows) {
                if (row.time().isBefore(starts.get(0))) {
                    continue;
                }
                int b = starts.size() - 1;
                for (int i = 1; i < starts.size(); i++) {
                    if (row.time().isBefore(starts.get(i))) {
                        b = i - 1;
                        break;
                    }
                }
                c[b][0]++;
                if (!row.matched()) {
                    c[b][1]++;
                } else if (row.forwardFailed()) {
                    c[b][3]++;
                } else if (row.forwarded()) {
                    c[b][2]++;
                }
            }
            List<BucketCount> result = new ArrayList<>();
            for (int i = 0; i < c.length; i++) {
                if (c[i][0] > 0) {
                    result.add(new BucketCount(i, c[i][0], c[i][1], c[i][2], c[i][3]));
                }
            }
            return result;
        }

        @Override
        public long[] countByResponseTime(LocalDateTime from, int[] edges) {
            long[] slots = new long[edges.length + 1];
            for (Row row : rows) {
                if (row.time().isBefore(from)) {
                    continue;
                }
                int slot = edges.length;
                for (int i = 0; i < edges.length; i++) {
                    if (row.responseTimeMs() <= edges[i]) {
                        slot = i;
                        break;
                    }
                }
                slots[slot]++;
            }
            return slots;
        }

        @Override
        public List<EndpointCount> topUnmatched(LocalDateTime from, LocalDateTime to, int limit) {
            return topUnmatched;
        }

        @Override
        public LocalDateTime earliestRequestTime() {
            return earliest;
        }

        @Override
        public Long smallestId() {
            return smallestId;
        }
    }

    private static RequestLogService.LogEntry entry(LocalDateTime time, boolean matched, String endpoint) {
        return RequestLogService.LogEntry.builder().protocol(Protocol.HTTP).method("GET").endpoint(endpoint)
                .matched(matched).requestTime(time).responseTimeMs(3).build();
    }
}
