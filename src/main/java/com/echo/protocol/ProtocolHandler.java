package com.echo.protocol;

import com.echo.dto.RuleDto;
import com.echo.entity.BaseRule;
import com.echo.entity.Protocol;
import com.echo.entity.Response;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 協定處理器介面
 * <p>
 * 定義各協定（HTTP、JMS 等）的規則操作，新增協定只需實作此介面。
 */
public interface ProtocolHandler {

    /**
     * 取得此處理器支援的協定
     */
    Protocol getProtocol();

    /**
     * 查詢所有規則
     */
    List<? extends BaseRule> findAllRules();

    /**
     * 依 ID 查詢規則
     */
    Optional<? extends BaseRule> findById(String id);

    /**
     * 依 ID 批次查詢規則
     */
    List<? extends BaseRule> findAllByIds(List<String> ids);


    /**
     * 儲存規則
     */
    BaseRule save(BaseRule rule);

    /**
     * 刪除規則
     */
    void deleteById(String id);

    /**
     * 刪除所有規則
     */
    int deleteAll();

    /**
     * 計算規則數量
     */
    long count();

    /**
     * 刪除過期規則
     */
    int deleteExpiredRules(LocalDateTime cutoff);

    /**
     * 批次更新啟用狀態
     */
    int updateEnabled(List<String> ids, boolean enabled);

    /**
     * 批次更新保護狀態
     */
    int updateProtected(List<String> ids, boolean isProtected);

    /**
     * 批次更新展延時間
     */
    int extendRules(List<String> ids, LocalDateTime extendedAt);

    /**
     * 依 responseId 分組計數
     */
    List<Object[]> countGroupByResponseId();

    /**
     * 依 responseId 刪除規則
     */
    int deleteByResponseId(Long responseId);

    /**
     * 依 responseId 查詢規則
     */
    List<? extends BaseRule> findByResponseId(Long responseId);

    /**
     * 計算孤兒規則數量（responseId 指向不存在的 Response）
     */
    long countOrphanRules();

    /**
     * 將 Rule 轉換為 DTO
     */
    RuleDto toDto(BaseRule rule, Response response, boolean includeBody);

    /**
     * 將 DTO 轉換為 Rule
     */
    BaseRule fromDto(RuleDto dto);

    /**
     * 測試規則
     * @return 測試結果 Map，包含 status, body, elapsed
     */
    Map<String, Object> testRule(BaseRule rule, Map<String, Object> request);

    /**
     * 此協定是否啟用
     */
    default boolean isEnabled() {
        return true;
    }

    /**
     * 產生規則描述（用於自動建立 Response）
     */
    String generateDescription(RuleDto dto);
}
