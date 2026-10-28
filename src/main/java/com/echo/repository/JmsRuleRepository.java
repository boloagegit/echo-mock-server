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

    /** Group count by responseId */
    @Query("SELECT r.responseId, COUNT(r) FROM JmsRule r WHERE r.responseId IS NOT NULL GROUP BY r.responseId")
    List<Object[]> countGroupByResponseId();

    /** Counting the number of orphan rules */
    @Query("SELECT COUNT(r) FROM JmsRule r WHERE r.responseId IS NOT NULL AND r.responseId NOT IN (SELECT resp.id FROM Response resp)")
    long countOrphanRules();

    /**
     * Batch enable/disable. Rules that are already in the target state are not updated (the version and update time remain unchanged), and the actual number of changes is returned;
     * Repeating the same operation will therefore not cause false version conflicts to the person editing this rule, nor will it trigger a rule rebuild.
     */
    @Modifying
    @Query("UPDATE JmsRule r SET r.enabled = :enabled, r.updatedAt = :updatedAt, r.updatedBy = :updatedBy, r.version = COALESCE(r.version, 0) + 1 WHERE r.id IN :ids AND (r.enabled IS NULL OR r.enabled <> :enabled)")
    int updateEnabledByIds(@Param("ids") List<String> ids, @Param("enabled") boolean enabled,
                           @Param("updatedAt") LocalDateTime updatedAt, @Param("updatedBy") String updatedBy);

    /** Batch update protection status; rules that are already in the target status are not updated, and the actual number of changes is returned. */
    @Modifying
    @Query("UPDATE JmsRule r SET r.isProtected = :isProtected, r.updatedAt = :updatedAt, r.updatedBy = :updatedBy, r.version = COALESCE(r.version, 0) + 1 WHERE r.id IN :ids AND (r.isProtected IS NULL OR r.isProtected <> :isProtected)")
    int updateProtectedByIds(@Param("ids") List<String> ids, @Param("isProtected") boolean isProtected,
                             @Param("updatedAt") LocalDateTime updatedAt, @Param("updatedBy") String updatedBy);

    /** Batch extension */
    @Modifying
    @Query("UPDATE JmsRule r SET r.extendedAt = :extendedAt, r.updatedAt = :updatedAt, r.updatedBy = :updatedBy, r.version = COALESCE(r.version, 0) + 1 WHERE r.id IN :ids")
    int extendByIds(@Param("ids") List<String> ids, @Param("extendedAt") LocalDateTime extendedAt,
                    @Param("updatedAt") LocalDateTime updatedAt, @Param("updatedBy") String updatedBy);

    /** Query rule ID based on label pattern (avoid full table loading) */
    @Query("SELECT r.id FROM JmsRule r WHERE r.tags LIKE :pattern ESCAPE '\\'")
    List<String> findIdsByTagPattern(@Param("pattern") String pattern);

    /** One statement obtains rule metadata and its response revision, without body LOBs. */
    @Query(
            "SELECT r, p.incarnation, p.version FROM JmsRule r LEFT JOIN Response p ON p.id = r.responseId WHERE (r.queueName = :queue OR r.queueName = '*') AND r.enabled = true AND (r.responseId IS NULL OR p.id IS NOT NULL) ORDER BY r.id")
    List<Object[]> findSnapshotRows(@Param("queue") String queueName);
}
