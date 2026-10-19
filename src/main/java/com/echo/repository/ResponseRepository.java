package com.echo.repository;

import com.echo.entity.Response;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ResponseRepository extends JpaRepository<Response, Long> {

    /** Query metadata (excluding body) */
    @Query("SELECT new Response(r.id, r.version, r.description, r.bodySize, r.contentType, r.createdAt, r.updatedAt, r.extendedAt) FROM Response r WHERE r.id = :id")
    Optional<Response> findMetadataById(@Param("id") Long id);

    /** Query all summary (excluding body) */
    @Query("SELECT r.id, r.description, r.bodySize, r.contentType, r.createdAt, r.updatedAt, r.extendedAt FROM Response r")
    List<Object[]> findAllSummary();

    /** Query only body */
    @Query("SELECT r.body FROM Response r WHERE r.id = :id")
    Optional<String> findBodyById(@Param("id") Long id);

    /** Only query the existing ID, do not load Response entity or body LOB. */
    @Query("SELECT r.id FROM Response r WHERE r.id IN :ids")
    List<Long> findExistingIds(@Param("ids") Collection<Long> ids);

    /** ID of all Response; only the primary key is taken for intersection in memory when a large number of comparisons are made. */
    @Query("SELECT r.id FROM Response r")
    List<Long> findAllIds();

    /** Search (description contains keywords) */
    List<Response> findByDescriptionContainingIgnoreCase(String keyword);

    String EXPIRED_ORPHAN = "COALESCE(r.extendedAt, r.updatedAt) < :cutoff " +
            "AND NOT EXISTS (SELECT h.id FROM HttpRule h WHERE h.responseId = r.id) " +
            "AND NOT EXISTS (SELECT j.id FROM JmsRule j WHERE j.responseId = r.id)";

    /** A finite run boundary: rows created after cleanup starts belong to the next run. */
    @Query("SELECT COALESCE(MAX(r.id), 0) FROM Response r")
    long findMaximumId();

    /** Keyset page of IDs only; never materializes response bodies. */
    @Query("SELECT r.id FROM Response r WHERE r.id > :after AND r.id <= :through AND " +
            EXPIRED_ORPHAN + " ORDER BY r.id")
    List<Long> findOrphanResponseIds(@Param("cutoff") LocalDateTime cutoff,
                                   @Param("after") long after, @Param("through") long through, Pageable page);

    /** Recheck age and references when deleting, including rows changed since selection. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM Response r WHERE r.id IN :ids AND " + EXPIRED_ORPHAN)
    int deleteExpiredOrphans(@Param("ids") Collection<Long> ids, @Param("cutoff") LocalDateTime cutoff);

    /** Query all Response ID that are not referenced by any rule (regardless of time, used for one-click clearing) */
    @Query("SELECT r.id FROM Response r " +
           "WHERE r.id NOT IN (SELECT DISTINCT h.responseId FROM HttpRule h WHERE h.responseId IS NOT NULL) " +
           "AND r.id NOT IN (SELECT DISTINCT j.responseId FROM JmsRule j WHERE j.responseId IS NOT NULL)")
    List<Long> findAllOrphanResponseIds();

    /** Counting the number of Response not referenced by any rule */
    @Query("SELECT COUNT(r) FROM Response r " +
           "WHERE r.id NOT IN (SELECT DISTINCT h.responseId FROM HttpRule h WHERE h.responseId IS NOT NULL) " +
           "AND r.id NOT IN (SELECT DISTINCT j.responseId FROM JmsRule j WHERE j.responseId IS NOT NULL)")
    long countOrphanResponses();

    /** Batch update extension time */
    @Modifying
    @Query("UPDATE Response r SET r.extendedAt = :extendedAt WHERE r.id IN :ids")
    int extendResponses(@Param("ids") List<Long> ids, @Param("extendedAt") LocalDateTime extendedAt);
}
