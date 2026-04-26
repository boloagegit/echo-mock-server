package com.echo.protocol;

import com.echo.dto.RuleDto;
import com.echo.entity.BaseRule;
import com.echo.entity.Protocol;
import com.echo.entity.Response;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 協定處理器註冊中心
 * <p>
 * 自動收集所有 ProtocolHandler 實作，提供統一存取介面。
 */
@Component
public class ProtocolHandlerRegistry {

    private final List<ProtocolHandler> handlers;
    private final Map<Protocol, ProtocolHandler> handlerMap;

    public ProtocolHandlerRegistry(List<ProtocolHandler> handlers) {
        this.handlers = handlers;
        this.handlerMap = handlers.stream()
                .collect(Collectors.toMap(ProtocolHandler::getProtocol, Function.identity()));
    }

    /**
     * 取得所有處理器
     */
    public List<ProtocolHandler> getAllHandlers() {
        return handlers;
    }

    /**
     * 依協定取得處理器
     */
    public Optional<ProtocolHandler> getHandler(Protocol protocol) {
        return Optional.ofNullable(handlerMap.get(protocol));
    }

    /**
     * 取得所有規則（跨協定）
     */
    public List<BaseRule> findAllRules() {
        return handlers.stream()
                .flatMap(h -> h.findAllRules().stream())
                .map(r -> (BaseRule) r)
                .toList();
    }

    /**
     * 依 ID 查詢規則（跨協定）
     */
    public Optional<? extends BaseRule> findById(String id) {
        for (ProtocolHandler handler : handlers) {
            Optional<? extends BaseRule> rule = handler.findById(id);
            if (rule.isPresent()) {
                return rule;
            }
        }
        return Optional.empty();
    }

    /**
     * 依 ID 批次查詢規則（跨協定）
     */
    public List<BaseRule> findAllByIds(List<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return handlers.stream()
                .flatMap(h -> h.findAllByIds(ids).stream())
                .map(r -> (BaseRule) r)
                .toList();
    }


    /**
     * 刪除過期規則（跨協定）
     */
    public int deleteExpiredRules(LocalDateTime cutoff) {
        return handlers.stream()
                .mapToInt(h -> h.deleteExpiredRules(cutoff))
                .sum();
    }

    /**
     * 批次更新啟用狀態（跨協定）
     */
    public int updateEnabled(List<String> ids, boolean enabled) {
        return handlers.stream()
                .mapToInt(h -> h.updateEnabled(ids, enabled))
                .sum();
    }

    /**
     * 批次更新保護狀態（跨協定）
     */
    public int updateProtected(List<String> ids, boolean isProtected) {
        return handlers.stream()
                .mapToInt(h -> h.updateProtected(ids, isProtected))
                .sum();
    }

    /**
     * 批次展延規則（跨協定）
     */
    public int extendRules(List<String> ids, LocalDateTime extendedAt) {
        return handlers.stream()
                .mapToInt(h -> h.extendRules(ids, extendedAt))
                .sum();
    }

    /**
     * 依 responseId 刪除規則（跨協定）
     */
    public int deleteByResponseId(Long responseId) {
        return handlers.stream()
                .mapToInt(h -> h.deleteByResponseId(responseId))
                .sum();
    }

    /**
     * 依 responseId 查詢規則（跨協定）
     */
    public List<BaseRule> findByResponseId(Long responseId) {
        return handlers.stream()
                .flatMap(h -> h.findByResponseId(responseId).stream())
                .map(r -> (BaseRule) r)
                .toList();
    }

    /**
     * 計算孤兒規則數量（跨協定）
     */
    public long countOrphanRules() {
        return handlers.stream()
                .mapToLong(ProtocolHandler::countOrphanRules)
                .sum();
    }

    /**
     * 將 Rule 轉換為 DTO（自動找對應的 Handler）
     */
    public RuleDto toDto(BaseRule rule, Response response, boolean includeBody) {
        return getHandler(rule.getProtocol())
                .map(h -> h.toDto(rule, response, includeBody))
                .orElseThrow(() -> new IllegalArgumentException("Unknown protocol: " + rule.getProtocol()));
    }

    public RuleDto toDto(BaseRule rule, Response response) {
        return toDto(rule, response, false);
    }

    /**
     * 檢查協定是否啟用
     */
    public boolean isEnabled(Protocol protocol) {
        return getHandler(protocol).map(ProtocolHandler::isEnabled).orElse(false);
    }

    /**
     * 產生規則描述
     */
    public String generateDescription(RuleDto dto) {
        return getHandler(dto.getProtocol())
                .map(h -> h.generateDescription(dto))
                .orElse(dto.getMatchKey());
    }
}
