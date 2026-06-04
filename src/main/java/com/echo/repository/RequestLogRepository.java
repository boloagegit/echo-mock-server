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

    /** 查詢最舊的 N 筆記錄 ID（供刪除用） */
    @Query("SELECT r.id FROM RequestLog r ORDER BY r.requestTime ASC")
    List<Long> findOldestIds(Pageable pageable);

    /** 批次刪除指定 ID */
    @Modifying
    @Transactional
    @Query("DELETE FROM RequestLog r WHERE r.id IN :ids")
    int deleteByIds(@Param("ids") List<Long> ids);

    /** 刪除最舊的 N 筆記錄（先查 ID 再刪除） */
    default int deleteOldest(int limit) {
        List<Long> ids = findOldestIds(Pageable.ofSize(limit));
        if (ids.isEmpty()) {
            return 0;
        }
        return deleteByIds(ids);
    }

    /** 依時間區間查詢 (用於流量圖，由呼叫端在 Java 層做分鐘分組) */
    @Query("SELECT r.requestTime FROM RequestLog r WHERE r.requestTime >= :startTime ORDER BY r.requestTime")
    List<LocalDateTime> findRequestTimesSince(@Param("startTime") LocalDateTime startTime);
}
