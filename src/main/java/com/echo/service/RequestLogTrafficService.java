package com.echo.service;

import com.echo.entity.Protocol;
import com.echo.repository.RequestLogTrafficQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 請求紀錄的流量概況：一段時間內每一格各有多少模擬回應、轉發、沒比對到與轉發失敗，加上回應時間與最常沒比對到的請求。
 * <p>
 * 資源使用：
 * <ul>
 *   <li>資料庫模式在資料庫內分格計數，只取回幾十列結果；記憶體模式直接走訪既有的紀錄，不另外複製。
 *       回應時間以固定的區間計數估算，記憶體與紀錄筆數無關。</li>
 *   <li>結果依時間範圍暫存 {@link #CACHE_TTL}，期間所有人共用同一份；同一時間只有一個統計在執行，
 *       所以很多人同時開啟頁面也只會查詢一次、最多佔用一條資料庫連線。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class RequestLogTrafficService {

    static final Duration CACHE_TTL = Duration.ofSeconds(5);
    static final int TOP_UNMATCHED = 5;

    /** 回應時間區間的上限（毫秒），每個數量級約 6 格；最後一格為更慢的請求。 */
    static final int[] RESPONSE_TIME_EDGES = {
            1, 2, 3, 5, 7, 10, 15, 20, 30, 50, 70, 100, 150, 200, 300, 500, 700,
            1_000, 1_500, 2_000, 3_000, 5_000, 7_000, 10_000, 15_000, 20_000, 30_000, 60_000};

    private final RequestLogService requestLogService;
    private final RequestLogTrafficQuery trafficQuery;
    private final SystemConfigService configService;

    private final Object computeLock = new Object();
    private final Map<Range, Cached> cache = new EnumMap<>(Range.class);
    private Clock clock = Clock.systemDefaultZone();

    /** 可選的時間範圍與每一格的長度。 */
    public enum Range {
        HOUR("1h", 30, Duration.ofMinutes(2)),
        DAY("24h", 48, Duration.ofMinutes(30)),
        WEEK("7d", 28, Duration.ofHours(6));

        private final String id;
        private final int buckets;
        private final Duration bucket;

        Range(String id, int buckets, Duration bucket) {
            this.id = id;
            this.buckets = buckets;
            this.bucket = bucket;
        }

        public String id() {
            return id;
        }

        public Duration bucketDuration() {
            return bucket;
        }

        public static Range of(String id) {
            for (Range range : values()) {
                if (range.id.equals(id)) {
                    return range;
                }
            }
            throw new IllegalArgumentException("Unsupported range: " + id);
        }
    }

    /** 一格時間內各種結果的筆數；{@code start} 含、下一格的 start 不含。 */
    public record Bucket(LocalDateTime start, int mocked, int forwarded, int unmatched, int forwardFailed) {
    }

    public record Totals(long requests, long mocked, long forwarded, long unmatched, long forwardFailed) {
    }

    /** 估計的回應時間（毫秒）：一般（中位數）與最慢 5% 的起點；沒有紀錄時為 null。 */
    public record ResponseTime(Integer typicalMs, Integer slowMs) {
    }

    public record Endpoint(Protocol protocol, String method, String endpoint, long count) {
    }

    /**
     * 流量概況。{@code earliestRecord} 是目前保留的最早紀錄；{@code truncated} 為 true 表示已達筆數上限，
     * 更早的紀錄已被刪除，earliestRecord 之前的格子沒有資料（不是沒有流量）。
     */
    public record Traffic(String range, LocalDateTime from, LocalDateTime to, int bucketMinutes,
                          List<Bucket> buckets, Totals totals, ResponseTime responseTime,
                          List<Endpoint> topUnmatched, LocalDateTime earliestRecord, boolean truncated,
                          int maxRecords, LocalDateTime generatedAt) {
        public Traffic {
            buckets = List.copyOf(buckets);
            topUnmatched = List.copyOf(topUnmatched);
        }
    }

    private record Cached(Traffic traffic, long expiresAtNanos) {
    }

    public Traffic traffic(Range range) {
        Cached cached = cachedIfFresh(range);
        if (cached != null) {
            return cached.traffic();
        }
        // 同一時間只計算一次：等待中的請求在拿到鎖後直接使用剛算好的結果
        synchronized (computeLock) {
            cached = cachedIfFresh(range);
            if (cached != null) {
                return cached.traffic();
            }
            Traffic traffic = compute(range);
            synchronized (cache) {
                cache.put(range, new Cached(traffic, System.nanoTime() + CACHE_TTL.toNanos()));
            }
            return traffic;
        }
    }

    /** 紀錄被清除時呼叫，讓下次查詢重新計算。 */
    public void invalidate() {
        synchronized (cache) {
            cache.clear();
        }
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    private Cached cachedIfFresh(Range range) {
        synchronized (cache) {
            Cached cached = cache.get(range);
            return cached != null && cached.expiresAtNanos() - System.nanoTime() > 0 ? cached : null;
        }
    }

    Traffic compute(Range range) {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime currentStart = floor(now, range.bucket);
        LocalDateTime from = currentStart.minus(range.bucket.multipliedBy(range.buckets - 1L));
        LocalDateTime to = currentStart.plus(range.bucket);
        Accumulator acc = new Accumulator(from, range);

        LocalDateTime earliest;
        List<Endpoint> topUnmatched;
        int maxRecords = configService.getRequestLogMaxRecords();
        boolean truncated;
        if (configService.isRequestLogMemoryMode()) {
            MemoryTotals memory = scanMemory(from, now, acc);
            earliest = memory.earliest;
            topUnmatched = memory.topUnmatched();
            // 環形緩衝區滿了才會丟掉最舊的
            truncated = memory.count >= maxRecords;
        } else {
            List<LocalDateTime> starts = new ArrayList<>(range.buckets);
            for (int i = 0; i < range.buckets; i++) {
                starts.add(from.plus(range.bucket.multipliedBy(i)));
            }
            trafficQuery.countByBucket(starts).forEach(acc::addCounts);
            acc.addResponseTimes(trafficQuery.countByResponseTime(from, RESPONSE_TIME_EDGES));
            earliest = trafficQuery.earliestRequestTime();
            topUnmatched = trafficQuery.topUnmatched(from, now, TOP_UNMATCHED).stream()
                    .map(e -> new Endpoint(e.protocol(), e.method(), e.endpoint(), e.count()))
                    .toList();
            // 資料庫超過上限時會刪到上限以下騰出空間，筆數無法看出是否刪過；最小 id 大於 1 才是刪過的證據
            Long smallestId = trafficQuery.smallestId();
            truncated = smallestId != null && smallestId > 1;
        }
        return new Traffic(range.id, from, to, (int) range.bucket.toMinutes(), acc.buckets(), acc.totals(),
                acc.responseTime(), topUnmatched, earliest, truncated, maxRecords, now);
    }

    /** 記憶體模式：直接走訪環形緩衝區，不另外複製。 */
    private MemoryTotals scanMemory(LocalDateTime from, LocalDateTime now, Accumulator acc) {
        MemoryTotals totals = new MemoryTotals();
        Iterable<RequestLogService.LogEntry> buffer = requestLogService.getMemoryBuffer();
        if (buffer == null) {
            return totals;
        }
        for (RequestLogService.LogEntry entry : buffer) {
            LocalDateTime time = entry.getRequestTime();
            if (time == null) {
                continue;
            }
            totals.count++;
            if (totals.earliest == null || time.isBefore(totals.earliest)) {
                totals.earliest = time;
            }
            if (time.isBefore(from)) {
                continue;
            }
            acc.add(time, entry.isMatched(), entry.isForwarded(), entry.getProxyError() != null,
                    entry.getResponseTimeMs());
            // 最常沒比對到的請求只算到概況產生的時間，與「看這些請求」列出的範圍相同
            if (!entry.isMatched() && time.isBefore(now)) {
                totals.unmatched.merge(new EndpointKey(entry.getProtocol(), entry.getMethod(), entry.getEndpoint()),
                        1L, Long::sum);
            }
        }
        return totals;
    }

    static LocalDateTime floor(LocalDateTime time, Duration bucket) {
        long minutes = bucket.toMinutes();
        LocalDateTime day = time.truncatedTo(ChronoUnit.DAYS);
        long sinceMidnight = Duration.between(day, time).toMinutes();
        return day.plusMinutes(sinceMidnight - Math.floorMod(sinceMidnight, minutes));
    }

    private record EndpointKey(Protocol protocol, String method, String endpoint) {
    }

    private static final class MemoryTotals {
        private long count;
        private LocalDateTime earliest;
        private final Map<EndpointKey, Long> unmatched = new HashMap<>();

        List<Endpoint> topUnmatched() {
            return unmatched.entrySet().stream()
                    .sorted(Map.Entry.<EndpointKey, Long>comparingByValue().reversed()
                            .thenComparing(e -> String.valueOf(e.getKey().endpoint())))
                    .limit(TOP_UNMATCHED)
                    .map(e -> new Endpoint(e.getKey().protocol(), e.getKey().method(), e.getKey().endpoint(),
                            e.getValue()))
                    .toList();
        }
    }

    /** 逐筆累加：每一格四種結果的筆數與回應時間區間的筆數。 */
    static final class Accumulator {
        private final LocalDateTime from;
        private final long bucketSeconds;
        private final Range range;
        private final int[][] counts;
        private final long[] responseTimes = new long[RESPONSE_TIME_EDGES.length + 1];

        Accumulator(LocalDateTime from, Range range) {
            this.from = from;
            this.range = range;
            this.bucketSeconds = range.bucket.toSeconds();
            this.counts = new int[range.buckets][4];
        }

        void add(LocalDateTime time, boolean matched, boolean forwarded, boolean forwardFailed, int responseTimeMs) {
            long seconds = Duration.between(from, time).toSeconds();
            if (seconds < 0) {
                return;
            }
            int index = (int) Math.min(seconds / bucketSeconds, range.buckets - 1L);
            int kind = !matched ? 2 : forwardFailed ? 3 : forwarded ? 1 : 0;
            counts[index][kind]++;
            responseTimes[responseTimeSlot(responseTimeMs)]++;
        }

        /** 資料庫統計好的一格。 */
        void addCounts(RequestLogTrafficQuery.BucketCount count) {
            if (count.bucket() < 0 || count.bucket() >= counts.length) {
                return;
            }
            int[] c = counts[count.bucket()];
            c[2] += (int) count.unmatched();
            c[1] += (int) count.forwarded();
            c[3] += (int) count.forwardFailed();
            c[0] += (int) (count.total() - count.unmatched() - count.forwarded() - count.forwardFailed());
        }

        /** 資料庫統計好的回應時間區間筆數（與 {@link #RESPONSE_TIME_EDGES} 對應）。 */
        void addResponseTimes(long[] slots) {
            for (int i = 0; i < Math.min(slots.length, responseTimes.length); i++) {
                responseTimes[i] += slots[i];
            }
        }

        List<Bucket> buckets() {
            List<Bucket> buckets = new ArrayList<>(counts.length);
            for (int i = 0; i < counts.length; i++) {
                int[] c = counts[i];
                buckets.add(new Bucket(from.plus(range.bucket.multipliedBy(i)), c[0], c[1], c[2], c[3]));
            }
            return buckets;
        }

        Totals totals() {
            long[] sums = new long[4];
            for (int[] c : counts) {
                for (int k = 0; k < 4; k++) {
                    sums[k] += c[k];
                }
            }
            return new Totals(sums[0] + sums[1] + sums[2] + sums[3], sums[0], sums[1], sums[2], sums[3]);
        }

        ResponseTime responseTime() {
            long total = 0;
            for (long count : responseTimes) {
                total += count;
            }
            if (total == 0) {
                return new ResponseTime(null, null);
            }
            return new ResponseTime(percentile(total, 0.5), percentile(total, 0.95));
        }

        /** 以區間計數估計百分位數：落在哪一格，就在該格的上下限之間依名次比例取值。 */
        private int percentile(long total, double quantile) {
            double rank = quantile * total;
            long seen = 0;
            for (int slot = 0; slot < responseTimes.length; slot++) {
                long count = responseTimes[slot];
                if (count > 0 && seen + count >= rank) {
                    int lower = slot == 0 ? 0 : RESPONSE_TIME_EDGES[slot - 1];
                    if (slot == RESPONSE_TIME_EDGES.length) {
                        return lower;
                    }
                    int upper = RESPONSE_TIME_EDGES[slot];
                    double within = Math.max(0, rank - seen) / count;
                    return (int) Math.round(lower + (upper - lower) * within);
                }
                seen += count;
            }
            return RESPONSE_TIME_EDGES[RESPONSE_TIME_EDGES.length - 1];
        }

        static int responseTimeSlot(int ms) {
            for (int i = 0; i < RESPONSE_TIME_EDGES.length; i++) {
                if (ms <= RESPONSE_TIME_EDGES[i]) {
                    return i;
                }
            }
            return RESPONSE_TIME_EDGES.length;
        }
    }
}
