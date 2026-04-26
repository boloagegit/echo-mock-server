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

    @Query("SELECT r FROM JmsRule r WHERE r.description LIKE %:keyword% OR r.queueName LIKE %:keyword% ORDER BY r.id DESC")
    List<JmsRule> searchByKeyword(@Param("keyword") String keyword);

    List<JmsRule> findAllByOrderByIdDesc();

    int countByResponseId(Long responseId);

    List<JmsRule> findByResponseId(Long responseId);

    int deleteByResponseId(Long responseId);

    @Modifying
    @Query("DELETE FROM JmsRule r WHERE COALESCE(r.extendedAt, r.createdAt) < :cutoff AND r.isProtected = false")
    int deleteExpiredRules(@Param("cutoff") LocalDateTime cutoff);

    /** 按 responseId 分組計數 */
    @Query("SELECT r.responseId, COUNT(r) FROM JmsRule r WHERE r.responseId IS NOT NULL GROUP BY r.responseId")
    List<Object[]> countGroupByResponseId();

    /** 計算孤兒規則數量 */
    @Query("SELECT COUNT(r) FROM JmsRule r WHERE r.responseId IS NOT NULL AND r.responseId NOT IN (SELECT resp.id FROM Response resp)")
    long countOrphanRules();

    /** 批次啟用/停用 */
    @Modifying
    @Query("UPDATE JmsRule r SET r.enabled = :enabled WHERE r.id IN :ids")
    int updateEnabledByIds(@Param("ids") List<String> ids, @Param("enabled") boolean enabled);

    /** 批次更新保護狀態 */
    @Modifying
    @Query("UPDATE JmsRule r SET r.isProtected = :isProtected WHERE r.id IN :ids")
    int updateProtectedByIds(@Param("ids") List<String> ids, @Param("isProtected") boolean isProtected);

    /** 批次展延 */
    @Modifying
    @Query("UPDATE JmsRule r SET r.extendedAt = :extendedAt WHERE r.id IN :ids")
    int extendByIds(@Param("ids") List<String> ids, @Param("extendedAt") LocalDateTime extendedAt);
}
