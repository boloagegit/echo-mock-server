package com.echo.repository;

import com.echo.entity.Protocol;
import com.echo.entity.RequestLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface RequestLogRepository extends JpaRepository<RequestLog, Long> {

    /** Get the latest N records of a rule */
    List<RequestLog> findByRuleIdOrderByRequestTimeDesc(String ruleId, Pageable pageable);

    /** Paginated query for recent requests */
    Page<RequestLog> findAllByOrderByRequestTimeDesc(Pageable pageable);

    /** Number of requests today */
    @Query("SELECT COUNT(r) FROM RequestLog r WHERE r.requestTime >= :startOfDay")
    long countTodayRequests(@Param("startOfDay") LocalDateTime startOfDay);

    /** Number of successful requests */
    long countByMatched(boolean matched);

    /** Statistics according to rules */
    @Query("SELECT r.ruleId, COUNT(r), AVG(r.responseTimeMs) FROM RequestLog r " +
           "WHERE r.ruleId IS NOT NULL GROUP BY r.ruleId ORDER BY COUNT(r) DESC")
    List<Object[]> findRuleStats(Pageable pageable);

    /** Delete expired records */
    @Modifying
    @Transactional
    @Query("DELETE FROM RequestLog r WHERE r.requestTime < :cutoffTime")
    int deleteByRequestTimeBefore(@Param("cutoffTime") LocalDateTime cutoffTime);

    /** Query the oldest N record ID (for deletion) */
    @Query("SELECT r.id FROM RequestLog r ORDER BY r.requestTime ASC")
    List<Long> findOldestIds(Pageable pageable);

    /** Batch deletion specified ID */
    @Modifying
    @Transactional
    @Query("DELETE FROM RequestLog r WHERE r.id IN :ids")
    int deleteByIds(@Param("ids") List<Long> ids);

    /** Delete the oldest N record (check ID first and then delete) */
    default int deleteOldest(int limit) {
        List<Long> ids = findOldestIds(Pageable.ofSize(limit));
        if (ids.isEmpty()) {
            return 0;
        }
        return deleteByIds(ids);
    }

    /** Query by time interval (used for traffic graphs, the calling end does minute grouping at the Java layer) */
    @Query("SELECT r.requestTime FROM RequestLog r WHERE r.requestTime >= :startTime ORDER BY r.requestTime")
    List<LocalDateTime> findRequestTimesSince(@Param("startTime") LocalDateTime startTime);

    /** Summary projection query (excluding LOB field: requestBody, responseBody, matchChain) */
    @Query("SELECT r.id, r.ruleId, r.protocol, r.method, r.endpoint, r.matched, " +
           "r.responseTimeMs, r.matchTimeMs, r.clientIp, r.requestTime, r.targetHost, " +
           "r.forwarded, r.forwardTarget, r.proxyStatus, r.proxyError, r.responseStatus, " +
           "r.faultType, r.scenarioName, r.scenarioFromState, r.scenarioToState, " +
           "CASE WHEN r.requestBody IS NOT NULL THEN true ELSE false END, " +
           "CASE WHEN r.responseBody IS NOT NULL THEN true ELSE false END, " +
           "CASE WHEN r.matchChain IS NOT NULL THEN true ELSE false END, r.diagnosticId " +
           "FROM RequestLog r ORDER BY r.requestTime DESC")
    List<Object[]> findSummaryProjections(Pageable pageable);

    /**
     * Summary paging query. Filtering and paging are all performed in the database to avoid loading max-records first and then filtering on Java.
     * Sorting is provided by Pageable, the caller must limit the allowed sorting fields.
     */
    @Query(value = "SELECT r.id, r.ruleId, r.protocol, r.method, r.endpoint, r.matched, " +
                   "r.responseTimeMs, r.matchTimeMs, r.clientIp, r.requestTime, r.targetHost, " +
                   "r.forwarded, r.forwardTarget, r.proxyStatus, r.proxyError, r.responseStatus, " +
                   "r.faultType, r.scenarioName, r.scenarioFromState, r.scenarioToState, " +
                   "CASE WHEN r.requestBody IS NOT NULL THEN true ELSE false END, " +
                   "CASE WHEN r.responseBody IS NOT NULL THEN true ELSE false END, " +
                   "CASE WHEN r.matchChain IS NOT NULL THEN true ELSE false END, r.diagnosticId " +
                   "FROM RequestLog r WHERE " +
                   "(:ruleId IS NULL OR r.ruleId = :ruleId) AND " +
                   "(:protocol IS NULL OR r.protocol = :protocol) AND " +
                   "(:matched IS NULL OR r.matched = :matched) AND " +
                   "(:endpoint IS NULL OR LOWER(r.endpoint) LIKE LOWER(CONCAT('%', :endpoint, '%')) " +
                   "OR LOWER(r.targetHost) LIKE LOWER(CONCAT('%', :endpoint, '%')) " +
                   "OR LOWER(r.forwardTarget) LIKE LOWER(CONCAT('%', :endpoint, '%')) " +
                   "OR LOWER(r.diagnosticId) LIKE LOWER(CONCAT('%', :endpoint, '%')) " +
                   "OR LOWER(r.ruleId) LIKE LOWER(CONCAT('%', :endpoint, '%'))) AND " +
                   "(:afterId IS NULL OR r.id > :afterId) AND " +
                   "(:fromTime IS NULL OR r.requestTime >= :fromTime) AND " +
                   "(:toTime IS NULL OR r.requestTime < :toTime)",
           countQuery = "SELECT COUNT(r) FROM RequestLog r WHERE " +
                        "(:ruleId IS NULL OR r.ruleId = :ruleId) AND " +
                        "(:protocol IS NULL OR r.protocol = :protocol) AND " +
                        "(:matched IS NULL OR r.matched = :matched) AND " +
                        "(:endpoint IS NULL OR LOWER(r.endpoint) LIKE LOWER(CONCAT('%', :endpoint, '%')) " +
                        "OR LOWER(r.targetHost) LIKE LOWER(CONCAT('%', :endpoint, '%')) " +
                        "OR LOWER(r.forwardTarget) LIKE LOWER(CONCAT('%', :endpoint, '%')) " +
                        "OR LOWER(r.diagnosticId) LIKE LOWER(CONCAT('%', :endpoint, '%')) " +
                   "OR LOWER(r.ruleId) LIKE LOWER(CONCAT('%', :endpoint, '%'))) AND " +
                        "(:afterId IS NULL OR r.id > :afterId) AND " +
                        "(:fromTime IS NULL OR r.requestTime >= :fromTime) AND " +
                        "(:toTime IS NULL OR r.requestTime < :toTime)")
    Page<Object[]> findSummaryPage(@Param("ruleId") String ruleId,
                                   @Param("protocol") Protocol protocol,
                                   @Param("matched") Boolean matched,
                                   @Param("endpoint") String endpoint,
                                   @Param("afterId") Long afterId,
                                   @Param("fromTime") LocalDateTime fromTime,
                                   @Param("toTime") LocalDateTime toTime,
                                   Pageable pageable);

    /** Summary paging query without time range. */
    default Page<Object[]> findSummaryPage(String ruleId, Protocol protocol, Boolean matched, String endpoint,
                                           Long afterId, Pageable pageable) {
        return findSummaryPage(ruleId, protocol, matched, endpoint, afterId, null, null, pageable);
    }
}
