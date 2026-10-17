package com.echo.repository;

import com.echo.entity.RuleAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

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
     * 關鍵字比對規則／回應 ID、對象名稱與說明；targetType 為 RULE 或 RESPONSE；ruleId 只看單一對象。
     */
    @Query(value = "SELECT a.id, a.ruleId, a.action, a.operator, a.timestamp, " +
                   "CASE WHEN a.beforeJson IS NOT NULL THEN true ELSE false END, " +
                   "CASE WHEN a.afterJson IS NOT NULL THEN true ELSE false END, " +
                   "a.targetProtocol, a.targetName, a.targetDescription, a.summary " +
                   "FROM RuleAuditLog a WHERE " + SUMMARY_FILTER,
           countQuery = "SELECT COUNT(a) FROM RuleAuditLog a WHERE " + SUMMARY_FILTER)
    Page<Object[]> findSummaryPage(@Param("action") RuleAuditLog.Action action,
                                   @Param("operator") String operator,
                                   @Param("keyword") String keyword,
                                   @Param("targetType") String targetType,
                                   @Param("ruleId") String ruleId,
                                   Pageable pageable);

    String SUMMARY_FILTER =
            "(:action IS NULL OR a.action = :action) AND " +
            "(:operator IS NULL OR LOWER(a.operator) LIKE LOWER(CONCAT('%', :operator, '%'))) AND " +
            "(:keyword IS NULL OR LOWER(a.ruleId) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
            "  OR LOWER(a.targetName) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
            "  OR LOWER(a.targetDescription) LIKE LOWER(CONCAT('%', :keyword, '%'))) AND " +
            "(:ruleId IS NULL OR a.ruleId = :ruleId) AND " +
            "(:targetType IS NULL " +
            "  OR (:targetType = 'RESPONSE' AND a.ruleId LIKE 'response-%') " +
            "  OR (:targetType = 'RULE' AND a.ruleId NOT LIKE 'response-%'))";

    /** 尚未計算列表摘要的舊資料 ID，由舊到新；只取 ID，不載入快照。 */
    @Query("SELECT a.id FROM RuleAuditLog a WHERE a.summary IS NULL ORDER BY a.id")
    List<Long> findIdsWithoutSummary(Pageable pageable);

    /** 只寫入摘要欄位；已有摘要（例如另一台實例先補好）就不覆寫。 */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("UPDATE RuleAuditLog a SET a.targetProtocol = :protocol, a.targetName = :name, " +
           "a.targetDescription = :description, a.summary = :summary WHERE a.id = :id AND a.summary IS NULL")
    int fillSummary(@Param("id") Long id,
                    @Param("protocol") String protocol,
                    @Param("name") String name,
                    @Param("description") String description,
                    @Param("summary") String summary);

    @Modifying
    @Query("DELETE FROM RuleAuditLog a WHERE a.timestamp < :cutoff")
    int deleteByTimestampBefore(LocalDateTime cutoff);
}
