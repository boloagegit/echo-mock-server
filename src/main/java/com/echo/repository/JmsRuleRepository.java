package com.echo.repository;

import com.echo.entity.JmsRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface JmsRuleRepository extends JpaRepository<JmsRule, String> {

    List<JmsRule> findByQueueName(String queueName);

    @Query("SELECT r FROM JmsRule r WHERE (r.queueName = :queue OR r.queueName = '*') AND r.enabled = true ORDER BY r.id")
    List<JmsRule> findByQueueNameOrWildcard(@Param("queue") String queueName);

    List<JmsRule> findAllByOrderByIdDesc();

    int countByResponseId(Long responseId);

    long countByJmsTargetConnectionId(String jmsTargetConnectionId);

    List<JmsRule> findByResponseId(Long responseId);

    int deleteByResponseId(Long responseId);

    @Modifying
    @Query("DELETE FROM JmsRule r WHERE COALESCE(r.extendedAt, r.createdAt) < :cutoff AND r.isProtected = :notProtected")
    int deleteExpiredRules(@Param("cutoff") LocalDateTime cutoff, @Param("notProtected") boolean notProtected);

    /** 按 responseId 分組計數 */
    @Query("SELECT r.responseId, COUNT(r) FROM JmsRule r WHERE r.responseId IS NOT NULL GROUP BY r.responseId")
    List<Object[]> countGroupByResponseId();

    /** 計算孤兒規則數量 */
    @Query("SELECT COUNT(r) FROM JmsRule r WHERE r.responseId IS NOT NULL AND r.responseId NOT IN (SELECT resp.id FROM Response resp)")
    long countOrphanRules();

    /**
     * 批次啟用/停用。已經是目標狀態的規則不更新（版本與更新時間不變），回傳實際改變的筆數；
     * 重複的同一個操作因此不會讓正在編輯這條規則的人遇到假的版本衝突，也不會觸發規則重建。
     */
    @Modifying
    @Query("UPDATE JmsRule r SET r.enabled = :enabled, r.updatedAt = :updatedAt, r.updatedBy = :updatedBy, r.version = COALESCE(r.version, 0) + 1 WHERE r.id IN :ids AND (r.enabled IS NULL OR r.enabled <> :enabled)")
    int updateEnabledByIds(@Param("ids") List<String> ids, @Param("enabled") boolean enabled,
                           @Param("updatedAt") LocalDateTime updatedAt, @Param("updatedBy") String updatedBy);

    /** 批次更新保護狀態；已經是目標狀態的規則不更新，回傳實際改變的筆數。 */
    @Modifying
    @Query("UPDATE JmsRule r SET r.isProtected = :isProtected, r.updatedAt = :updatedAt, r.updatedBy = :updatedBy, r.version = COALESCE(r.version, 0) + 1 WHERE r.id IN :ids AND (r.isProtected IS NULL OR r.isProtected <> :isProtected)")
    int updateProtectedByIds(@Param("ids") List<String> ids, @Param("isProtected") boolean isProtected,
                             @Param("updatedAt") LocalDateTime updatedAt, @Param("updatedBy") String updatedBy);

    /** 批次展延 */
    @Modifying
    @Query("UPDATE JmsRule r SET r.extendedAt = :extendedAt, r.updatedAt = :updatedAt, r.updatedBy = :updatedBy, r.version = COALESCE(r.version, 0) + 1 WHERE r.id IN :ids")
    int extendByIds(@Param("ids") List<String> ids, @Param("extendedAt") LocalDateTime extendedAt,
                    @Param("updatedAt") LocalDateTime updatedAt, @Param("updatedBy") String updatedBy);

    /** 依標籤 pattern 查詢規則 ID（避免全表載入） */
    @Query("SELECT r.id FROM JmsRule r WHERE r.tags LIKE :pattern ESCAPE '\\'")
    List<String> findIdsByTagPattern(@Param("pattern") String pattern);
}
