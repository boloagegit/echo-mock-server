package com.echo.repository;

import com.echo.entity.RuleAuditLog;
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

    @Modifying
    @Query("DELETE FROM RuleAuditLog a WHERE a.timestamp < :cutoff")
    int deleteByTimestampBefore(LocalDateTime cutoff);
}
