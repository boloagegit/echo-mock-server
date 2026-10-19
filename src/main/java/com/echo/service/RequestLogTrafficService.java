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
 * Traffic overview of request records: how many simulated responses, forwards, unmatched and forwarded failures there were in each grid within a period of time, plus the response time and the most frequently unmatched requests.
 * <p>
 * Resource usage:
 * <ul>
 *   <li>database mode counts cells in the database and only retrieves dozens of rows of results; memory mode directly accesses existing records without additional copies.
 *       The response time is estimated based on fixed interval counts, and the memory has nothing to do with the number of records.</li>
 *   <li>The  result is temporarily stored in {@link #CACHE_TTL} according to the time range. During this period, everyone shares the same copy; only one statistics is executed at the same time.
 *       Therefore, if many people open the page at the same time, they will only query it once, occupying at most one database connection.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class RequestLogTrafficService {

    static final Duration CACHE_TTL = Duration.ofSeconds(5);
    static final int TOP_UNMATCHED = 5;

    /** The upper limit of the response time interval (milliseconds), about 6 bars per order of magnitude; the last bar is for slower requests. */
    static final int[] RESPONSE_TIME_EDGES = {
            1, 2, 3, 5, 7, 10, 15, 20, 30, 50, 70, 100, 150, 200, 300, 500, 700,
            1_000, 1_500, 2_000, 3_000, 5_000, 7_000, 10_000, 15_000, 20_000, 30_000, 60_000};

    private final RequestLogService requestLogService;
    private final RequestLogTrafficQuery trafficQuery;
    private final SystemConfigService configService;

    private final Object computeLock = new Object();
    private final Map<Range, Cached> cache = new EnumMap<>(Range.class);
    private Clock clock = Clock.systemDefaultZone();

    /** Optional time range and length of each grid. */
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

    /** The number of various results in one grid; {@code start} is included, and start in the next grid is not included. */
    public record Bucket(LocalDateTime start, int mocked, int forwarded, int unmatched, int forwardFailed) {
    }

    public record Totals(long requests, long mocked, long forwarded, long unmatched, long forwardFailed) {
    }

    /** Estimated response time (milliseconds): average (median) and slowest 5% starting point; null when not logged. */
    public record ResponseTime(Integer typicalMs, Integer slowMs) {
    }

    public record Endpoint(Protocol protocol, String method, String endpoint, long count) {
    }

    /**
     * Traffic overview. {@code earliestRecord} is the oldest retained record; {@code truncated} indicates that the retention-count limit has been reached,
     * The earlier records have been deleted, and the grid before earliestRecord has no data (not no traffic).
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
        // Only calculated once at the same time: the waiting request directly uses the newly calculated result after getting the lock.
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

    /** Called when the record is cleared, so that the next query can be recalculated. */
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
            // The oldest ring buffer will be discarded when it is full.
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
            // When the database exceeds the upper limit, it will be deleted below the upper limit to make room. The number of records cannot be used to tell whether they have been deleted; the minimum id is greater than 1, which is evidence of deletion.
            Long smallestId = trafficQuery.smallestId();
            truncated = smallestId != null && smallestId > 1;
        }
        return new Traffic(range.id, from, to, (int) range.bucket.toMinutes(), acc.buckets(), acc.totals(),
                acc.responseTime(), topUnmatched, earliest, truncated, maxRecords, now);
    }

    /** Memory mode: Direct access to the ring buffer without additional copying. */
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
            // The most frequently unmatched requests are only counted to the time when the profile was generated, which is the same range as listed in "Look at these requests"
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

    /** Accumulate per-record counts for each time bucket's four outcomes and each latency bucket. */
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

        /** The database statistics are good. */
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

        /** The number of response time intervals calculated by the database (corresponding to {@link #RESPONSE_TIME_EDGES}). */
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

        /** Estimating the percentile by interval counting: whichever grid it falls on, the value will be calculated in proportion to the ranking between the upper and lower limits of the grid. */
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
