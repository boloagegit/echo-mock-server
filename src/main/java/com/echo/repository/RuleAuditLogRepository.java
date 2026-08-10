package com.echo.repository;

import com.echo.entity.RuleAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface RuleAuditLogRepository extends JpaRepository<RuleAuditLog, Long> {

    List<RuleAuditLog> findByRuleIdOrderByTimestampDesc(String ruleId, Pageable pageable);

    List<RuleAuditLog> findAllByOrderByTimestampDesc(Pageable pageable);

    /** 摘要投影：排除 beforeJson/afterJson LOB 欄位，減少 heap 負載 */
    @Query("SELECT a.id, a.ruleId, a.action, a.operator, a.timestamp " +
           "FROM RuleAuditLog a WHERE a.ruleId = :ruleId ORDER BY a.timestamp DESC")
    List<Object[]> findSummaryByRuleId(@Param("ruleId") String ruleId, Pageable pageable);

    /** 摘要投影：排除 beforeJson/afterJson LOB 欄位，減少 heap 負載 */
    @Query("SELECT a.id, a.ruleId, a.action, a.operator, a.timestamp " +
           "FROM RuleAuditLog a ORDER BY a.timestamp DESC")
    List<Object[]> findAllSummary(Pageable pageable);

    /**
     * 後台列表用的摘要分頁查詢。beforeJson / afterJson 只用來判斷是否存在，
     * 不會被投影到結果中，避免一般瀏覽修訂記錄時載入大量 LOB。
     */
    @Query(value = "SELECT a.id, a.ruleId, a.action, a.operator, a.timestamp, " +
                   "CASE WHEN a.beforeJson IS NOT NULL THEN true ELSE false END, " +
                   "CASE WHEN a.afterJson IS NOT NULL THEN true ELSE false END " +
                   "FROM RuleAuditLog a WHERE " +
                   "(:action IS NULL OR a.action = :action) AND " +
                   "(:operator IS NULL OR LOWER(a.operator) LIKE LOWER(CONCAT('%', :operator, '%'))) AND " +
                   "(:keyword IS NULL OR LOWER(a.ruleId) LIKE LOWER(CONCAT('%', :keyword, '%')))",
           countQuery = "SELECT COUNT(a) FROM RuleAuditLog a WHERE " +
                        "(:action IS NULL OR a.action = :action) AND " +
                        "(:operator IS NULL OR LOWER(a.operator) LIKE LOWER(CONCAT('%', :operator, '%'))) AND " +
                        "(:keyword IS NULL OR LOWER(a.ruleId) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    Page<Object[]> findSummaryPage(@Param("action") RuleAuditLog.Action action,
                                   @Param("operator") String operator,
                                   @Param("keyword") String keyword,
                                   Pageable pageable);

    @Modifying
    @Query("DELETE FROM RuleAuditLog a WHERE a.timestamp < :cutoff")
    int deleteByTimestampBefore(LocalDateTime cutoff);
}
