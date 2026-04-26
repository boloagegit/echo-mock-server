package com.echo.repository;

import com.echo.entity.RuleAuditLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;

public interface RuleAuditLogRepository extends JpaRepository<RuleAuditLog, Long> {

    List<RuleAuditLog> findByRuleIdOrderByTimestampDesc(String ruleId, Pageable pageable);

    List<RuleAuditLog> findAllByOrderByTimestampDesc(Pageable pageable);

    @Modifying
    @Query("DELETE FROM RuleAuditLog a WHERE a.timestamp < :cutoff")
    int deleteByTimestampBefore(LocalDateTime cutoff);
}
