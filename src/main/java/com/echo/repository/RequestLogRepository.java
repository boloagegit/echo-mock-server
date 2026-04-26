package com.echo.repository;

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

    /** 取得某規則最近 N 筆記錄 */
    List<RequestLog> findByRuleIdOrderByRequestTimeDesc(String ruleId, Pageable pageable);

    /** 分頁查詢最近請求 */
    Page<RequestLog> findAllByOrderByRequestTimeDesc(Pageable pageable);

    /** 今日請求數 */
    @Query("SELECT COUNT(r) FROM RequestLog r WHERE r.requestTime >= :startOfDay")
    long countTodayRequests(@Param("startOfDay") LocalDateTime startOfDay);

    /** 成功請求數 */
    long countByMatched(boolean matched);

    /** 依規則統計 */
    @Query("SELECT r.ruleId, COUNT(r), AVG(r.responseTimeMs) FROM RequestLog r " +
           "WHERE r.ruleId IS NOT NULL GROUP BY r.ruleId ORDER BY COUNT(r) DESC")
    List<Object[]> findRuleStats(Pageable pageable);

    /** 刪除過期記錄 */
    @Modifying
    @Transactional
    @Query("DELETE FROM RequestLog r WHERE r.requestTime < :cutoffTime")
    int deleteByRequestTimeBefore(@Param("cutoffTime") LocalDateTime cutoffTime);

    /** 刪除最舊的 N 筆記錄 */
    @Modifying
    @Transactional
    @Query("DELETE FROM RequestLog r WHERE r.id IN (SELECT r2.id FROM RequestLog r2 ORDER BY r2.requestTime ASC LIMIT :limit)")
    int deleteOldest(@Param("limit") int limit);

    /** 依時間區間統計 (用於流量圖) */
    @Query("SELECT FUNCTION('MINUTE', r.requestTime), COUNT(r) FROM RequestLog r " +
           "WHERE r.requestTime >= :startTime GROUP BY FUNCTION('MINUTE', r.requestTime)")
    List<Object[]> countByMinute(@Param("startTime") LocalDateTime startTime);
}
