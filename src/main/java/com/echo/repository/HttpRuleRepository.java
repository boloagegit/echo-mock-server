package com.echo.repository;

import com.echo.entity.HttpRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface HttpRuleRepository extends JpaRepository<HttpRule, String> {
    long countByHttpTargetConnectionId(Long httpTargetConnectionId);

    List<HttpRule> findByTargetHostAndMatchKey(String targetHost, String matchKey);

    @Query("SELECT r FROM HttpRule r WHERE (r.targetHost = :host OR r.targetHost IS NULL OR r.targetHost = '' OR r.matchKey = '*') AND r.enabled = true ORDER BY r.id")
    List<HttpRule> findByTargetHostOrWildcard(@Param("host") String host);

    List<HttpRule> findAllByOrderByIdDesc();

    /** Rule snapshot usage: All enabled rules, sorted by id (the same order as findByTargetHostOrWildcard). */
    List<HttpRule> findByEnabledTrueOrderByIdAsc();

    int countByResponseId(Long responseId);

    List<HttpRule> findByResponseId(Long responseId);

    int deleteByResponseId(Long responseId);

    @Modifying
    @Query("DELETE FROM HttpRule r WHERE COALESCE(r.extendedAt, r.createdAt) < :cutoff AND r.isProtected = :notProtected")
    int deleteExpiredRules(@Param("cutoff") LocalDateTime cutoff, @Param("notProtected") boolean notProtected);

    /** Group count by responseId */
    @Query("SELECT r.responseId, COUNT(r) FROM HttpRule r WHERE r.responseId IS NOT NULL GROUP BY r.responseId")
    List<Object[]> countGroupByResponseId();

    /** Counting the number of orphan rules */
    @Query("SELECT COUNT(r) FROM HttpRule r WHERE r.responseId IS NOT NULL AND r.responseId NOT IN (SELECT resp.id FROM Response resp)")
    long countOrphanRules();

    /**
     * Batch enable/disable. Rules that are already in the target state are not updated (the version and update time remain unchanged), and the actual number of changes is returned;
     * Repeating the same operation will therefore not cause false version conflicts to the person editing this rule, nor will it trigger a rule rebuild.
     */
    @Modifying
    @Query("UPDATE HttpRule r SET r.enabled = :enabled, r.updatedAt = :updatedAt, r.updatedBy = :updatedBy, r.version = COALESCE(r.version, 0) + 1 WHERE r.id IN :ids AND (r.enabled IS NULL OR r.enabled <> :enabled)")
    int updateEnabledByIds(@Param("ids") List<String> ids, @Param("enabled") boolean enabled,
                           @Param("updatedAt") LocalDateTime updatedAt, @Param("updatedBy") String updatedBy);

    /** Batch update protection status; rules that are already in the target status are not updated, and the actual number of changes is returned. */
    @Modifying
    @Query("UPDATE HttpRule r SET r.isProtected = :isProtected, r.updatedAt = :updatedAt, r.updatedBy = :updatedBy, r.version = COALESCE(r.version, 0) + 1 WHERE r.id IN :ids AND (r.isProtected IS NULL OR r.isProtected <> :isProtected)")
    int updateProtectedByIds(@Param("ids") List<String> ids, @Param("isProtected") boolean isProtected,
                             @Param("updatedAt") LocalDateTime updatedAt, @Param("updatedBy") String updatedBy);

    /** Batch extension */
    @Modifying
    @Query("UPDATE HttpRule r SET r.extendedAt = :extendedAt, r.updatedAt = :updatedAt, r.updatedBy = :updatedBy, r.version = COALESCE(r.version, 0) + 1 WHERE r.id IN :ids")
    int extendByIds(@Param("ids") List<String> ids, @Param("extendedAt") LocalDateTime extendedAt,
                    @Param("updatedAt") LocalDateTime updatedAt, @Param("updatedBy") String updatedBy);

    /** Query rule ID based on label pattern (avoid full table loading) */
    @Query("SELECT r.id FROM HttpRule r WHERE r.tags LIKE :pattern ESCAPE '\\'")
    List<String> findIdsByTagPattern(@Param("pattern") String pattern);
}
