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

    /** 查詢 metadata（不含 body） */
    @Query("SELECT new Response(r.id, r.version, r.description, r.bodySize, r.contentType, r.createdAt, r.updatedAt, r.extendedAt) FROM Response r WHERE r.id = :id")
    Optional<Response> findMetadataById(@Param("id") Long id);

    /** 查詢全部 summary（不含 body） */
    @Query("SELECT r.id, r.description, r.bodySize, r.contentType, r.createdAt, r.updatedAt, r.extendedAt FROM Response r")
    List<Object[]> findAllSummary();

    /** 只查詢 body */
    @Query("SELECT r.body FROM Response r WHERE r.id = :id")
    Optional<String> findBodyById(@Param("id") Long id);

    /** 只查詢存在的 ID，不載入 Response entity 或 body LOB。 */
    @Query("SELECT r.id FROM Response r WHERE r.id IN :ids")
    List<Long> findExistingIds(@Param("ids") Collection<Long> ids);

    /** 所有 Response 的 ID；只取主鍵，供大量比對時在記憶體中求交集。 */
    @Query("SELECT r.id FROM Response r")
    List<Long> findAllIds();

    /** 搜尋（描述包含關鍵字） */
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

    /** 查詢所有未被任何規則引用的 Response ID（不考慮時間，用於一鍵清除） */
    @Query("SELECT r.id FROM Response r " +
           "WHERE r.id NOT IN (SELECT DISTINCT h.responseId FROM HttpRule h WHERE h.responseId IS NOT NULL) " +
           "AND r.id NOT IN (SELECT DISTINCT j.responseId FROM JmsRule j WHERE j.responseId IS NOT NULL)")
    List<Long> findAllOrphanResponseIds();

    /** 計算未被任何規則引用的 Response 數量 */
    @Query("SELECT COUNT(r) FROM Response r " +
           "WHERE r.id NOT IN (SELECT DISTINCT h.responseId FROM HttpRule h WHERE h.responseId IS NOT NULL) " +
           "AND r.id NOT IN (SELECT DISTINCT j.responseId FROM JmsRule j WHERE j.responseId IS NOT NULL)")
    long countOrphanResponses();

    /** 批次更新展延時間 */
    @Modifying
    @Query("UPDATE Response r SET r.extendedAt = :extendedAt WHERE r.id IN :ids")
    int extendResponses(@Param("ids") List<Long> ids, @Param("extendedAt") LocalDateTime extendedAt);
}
