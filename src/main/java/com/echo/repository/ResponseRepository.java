package com.echo.repository;

import com.echo.entity.Response;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
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

    /** 搜尋（描述包含關鍵字） */
    List<Response> findByDescriptionContainingIgnoreCase(String keyword);

    /** 查詢過期且未被任何規則引用的 Response ID（以 extendedAt 或 updatedAt 為基準） */
    @Query("SELECT r.id FROM Response r WHERE COALESCE(r.extendedAt, r.updatedAt) < :cutoff " +
           "AND r.id NOT IN (SELECT DISTINCT h.responseId FROM HttpRule h WHERE h.responseId IS NOT NULL) " +
           "AND r.id NOT IN (SELECT DISTINCT j.responseId FROM JmsRule j WHERE j.responseId IS NOT NULL)")
    List<Long> findOrphanResponseIds(@Param("cutoff") LocalDateTime cutoff);

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
