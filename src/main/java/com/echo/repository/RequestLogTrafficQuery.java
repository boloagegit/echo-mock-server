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
 * 流量概況用的請求紀錄查詢。統計在資料庫內完成，只回傳每一格（或每個回應時間區間）的筆數，
 * 不把個別紀錄載入記憶體；查詢只用到 {@link RequestLog#TRAFFIC_INDEX} 涵蓋的小欄位，不讀 body 與比對過程。
 * 分格以時間比較完成（不依賴資料庫的日期函式），所以外部資料庫也適用。
 */
@Repository
public class RequestLogTrafficQuery {

    @PersistenceContext
    private EntityManager entityManager;

    private final Map<Integer, String> bucketQueries = new ConcurrentHashMap<>();
    private final Map<List<Integer>, String> responseTimeQueries = new ConcurrentHashMap<>();

    /** 一格時間內的筆數；轉發與轉發失敗只算比對到規則的請求。 */
    public record BucketCount(int bucket, long total, long unmatched, long forwarded, long forwardFailed) {
    }

    /** 沒比對到的請求，依協定、方法與網址分組的筆數。 */
    public record EndpointCount(Protocol protocol, String method, String endpoint, long count) {
    }

    /**
     * 依格子統計：{@code starts} 是每一格的開始時間（遞增），第一格之前的紀錄不算，最後一格包含之後的紀錄。
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

    /** 依回應時間區間統計：第 i 格為不超過 {@code edges[i]} 毫秒（且超過前一格），最後一格為更慢的請求。 */
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

    /** {@code from} 之後最常沒比對到的請求。 */
    @Transactional(readOnly = true)
    public List<EndpointCount> topUnmatchedSince(LocalDateTime from, int limit) {
        return entityManager.createQuery(
                        "SELECT r.protocol, r.method, r.endpoint, COUNT(r) FROM RequestLog r "
                                + "WHERE r.matched = false AND r.requestTime >= :from "
                                + "GROUP BY r.protocol, r.method, r.endpoint ORDER BY COUNT(r) DESC, r.endpoint",
                        Object[].class)
                .setParameter("from", from)
                .setMaxResults(limit)
                .getResultStream()
                .map(row -> new EndpointCount((Protocol) row[0], (String) row[1], (String) row[2], number(row[3])))
                .toList();
    }

    /** 目前保留的紀錄中最早的時間；沒有紀錄時為 null。 */
    @Transactional(readOnly = true)
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
