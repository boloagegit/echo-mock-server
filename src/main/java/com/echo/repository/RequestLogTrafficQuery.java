package com.echo.repository;

import com.echo.entity.Protocol;
import com.echo.entity.RequestLog;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Request-log queries for the traffic overview. Aggregate in the database and return only counts per cell or latency bucket,
 * Do not load individual records into memory; query only uses the small field covered by {@link RequestLog#TRAFFIC_INDEX}, and does not read body and compare the process.
 * The division is done by time comparison (does not rely on the date function of the database), so external databases are also suitable.
 */
@Repository
public class RequestLogTrafficQuery {

    @PersistenceContext
    private EntityManager entityManager;

    private final Map<Integer, String> bucketQueries = new ConcurrentHashMap<>();
    private final Map<List<Integer>, String> responseTimeQueries = new ConcurrentHashMap<>();

    /** Count records in a time bucket; forwarding and forwarding failures include only requests that matched a rule. */
    public record BucketCount(int bucket, long total, long unmatched, long forwarded, long forwardFailed) {
    }

    /** The number of unmatched requests grouped by protocol, method and URL. */
    public record EndpointCount(Protocol protocol, String method, String endpoint, long count) {
    }

    /**
     * Statistics by grid: {@code starts} is the starting time of each grid (increasingly), the records before the first grid are not counted, and the last grid includes the records after that.
     */
    @Transactional(readOnly = true)
    public List<BucketCount> countByBucket(List<LocalDateTime> starts) {
        int buckets = starts.size();
        TypedQuery<Object[]> query = entityManager.createQuery(
                bucketQueries.computeIfAbsent(buckets, RequestLogTrafficQuery::bucketQuery), Object[].class)
                .setParameter("from", starts.get(0));
        for (int i = 1; i < buckets; i++) {
            query.setParameter("b" + i, starts.get(i));
        }
        return query.getResultStream()
                .map(row -> new BucketCount(((Number) row[0]).intValue(), number(row[1]), number(row[2]),
                        number(row[3]), number(row[4])))
                .toList();
    }

    /** Statistics based on response time interval: the i grid is no more than {@code edges[i]} milliseconds (and exceeds the previous grid), and the last grid is a slower request. */
    @Transactional(readOnly = true)
    public long[] countByResponseTime(LocalDateTime from, int[] edges) {
        List<Integer> key = java.util.Arrays.stream(edges).boxed().toList();
        long[] counts = new long[edges.length + 1];
        entityManager.createQuery(responseTimeQueries.computeIfAbsent(key, RequestLogTrafficQuery::responseTimeQuery),
                        Object[].class)
                .setParameter("from", from)
                .getResultStream()
                .forEach(row -> counts[((Number) row[0]).intValue()] += number(row[1]));
        return counts;
    }

    /** The most frequently unmatched request after {@code from}. */
    @Transactional(readOnly = true)
    /** {@code from} to {@code to} (excluding the time when the profile was generated): the same range as listed in "Look at these requests". */
    public List<EndpointCount> topUnmatched(LocalDateTime from, LocalDateTime to, int limit) {
        return entityManager.createQuery(
                        "SELECT r.protocol, r.method, r.endpoint, COUNT(r) FROM RequestLog r "
                                + "WHERE r.matched = false AND r.requestTime >= :from AND r.requestTime < :to "
                                + "GROUP BY r.protocol, r.method, r.endpoint ORDER BY COUNT(r) DESC, r.endpoint",
                        Object[].class)
                .setParameter("from", from)
                .setParameter("to", to)
                .setMaxResults(limit)
                .getResultStream()
                .map(row -> new EndpointCount((Protocol) row[0], (String) row[1], (String) row[2], number(row[3])))
                .toList();
    }

    /** The earliest time in the currently retained record; when there is no record, it is null. */
    @Transactional(readOnly = true)
    /**
     * The smallest record is id (null when there is no record). id will only increase, so if it is greater than 1, it means that the older record has been deleted.
     * including retention limits and clearing logs, even after restart or across instances. Uses the primary-key index.
     */
    public Long smallestId() {
        return entityManager.createQuery("SELECT MIN(r.id) FROM RequestLog r", Long.class).getSingleResult();
    }

    public LocalDateTime earliestRequestTime() {
        return entityManager.createQuery("SELECT MIN(r.requestTime) FROM RequestLog r", LocalDateTime.class)
                .getSingleResult();
    }

    private static String bucketQuery(int buckets) {
        StringBuilder bucket = new StringBuilder("CASE");
        for (int i = 1; i < buckets; i++) {
            bucket.append(" WHEN r.requestTime < :b").append(i).append(" THEN ").append(i - 1);
        }
        bucket.append(" ELSE ").append(buckets - 1).append(" END");
        return "SELECT " + bucket + ", COUNT(r), "
                + "SUM(CASE WHEN r.matched = false THEN 1 ELSE 0 END), "
                + "SUM(CASE WHEN r.matched = true AND r.proxyError IS NULL AND r.forwarded = true THEN 1 ELSE 0 END), "
                + "SUM(CASE WHEN r.matched = true AND r.proxyError IS NOT NULL THEN 1 ELSE 0 END) "
                + "FROM RequestLog r WHERE r.requestTime >= :from GROUP BY " + bucket;
    }

    private static String responseTimeQuery(List<Integer> edges) {
        StringBuilder slot = new StringBuilder("CASE");
        for (int i = 0; i < edges.size(); i++) {
            slot.append(" WHEN r.responseTimeMs <= ").append(edges.get(i)).append(" THEN ").append(i);
        }
        slot.append(" ELSE ").append(edges.size()).append(" END");
        return "SELECT " + slot + ", COUNT(r) FROM RequestLog r WHERE r.requestTime >= :from GROUP BY " + slot;
    }

    private static long number(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }
}
