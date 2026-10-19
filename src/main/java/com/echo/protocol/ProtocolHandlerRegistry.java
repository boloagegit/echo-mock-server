package com.echo.protocol;

import com.echo.dto.RuleDto;
import com.echo.entity.BaseRule;
import com.echo.entity.Protocol;
import com.echo.entity.Response;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import com.echo.util.CurrentOperator;

/**
 * Protocol handler registry
 * <p>
 * Automatically collect all ProtocolHandler implementations and provide a unified access interface.
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
     * Get all processors
     */
    public List<ProtocolHandler> getAllHandlers() {
        return handlers;
    }

    /**
     * Description of enabled protocols (in order of processor injection)
     */
    public List<ProtocolDescriptor> descriptors() {
        return handlers.stream()
                .map(ProtocolHandler::descriptor)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * Obtain description by protocol; empty when protocol is not enabled
     */
    public Optional<ProtocolDescriptor> descriptor(Protocol protocol) {
        return getHandler(protocol).map(ProtocolHandler::descriptor);
    }

    /**
     * Obtained processor according to protocol
     */
    public Optional<ProtocolHandler> getHandler(Protocol protocol) {
        return Optional.ofNullable(handlerMap.get(protocol));
    }

    /**
     * Get all rules (cross-protocol)
     */
    public List<BaseRule> findAllRules() {
        return handlers.stream()
                .flatMap(h -> h.findAllRules().stream())
                .map(r -> (BaseRule) r)
                .toList();
    }

    /**
     * Query rules according to ID (cross-protocol)
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
     * According to ID batch query rules (cross-protocol)
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
     * Delete expired rules (cross-protocol)
     */
    public int deleteExpiredRules(LocalDateTime cutoff) {
        return handlers.stream()
                .mapToInt(h -> h.deleteExpiredRules(cutoff))
                .sum();
    }

    /**
     * Batch update enabled status (cross-protocol)
     */
    public int updateEnabled(List<String> ids, boolean enabled, LocalDateTime updatedAt, String updatedBy) {
        if (ids.isEmpty()) {
            return 0;
        }
        return handlers.stream()
                .mapToInt(h -> h.updateEnabled(ids, enabled, updatedAt, updatedBy))
                .sum();
    }

    public int updateEnabled(List<String> ids, boolean enabled) {
        return updateEnabled(ids, enabled, LocalDateTime.now(), CurrentOperator.username());
    }

    /**
     * Batch update protection status (cross-protocol)
     */
    public int updateProtected(List<String> ids, boolean isProtected, LocalDateTime updatedAt, String updatedBy) {
        if (ids.isEmpty()) {
            return 0;
        }
        return handlers.stream()
                .mapToInt(h -> h.updateProtected(ids, isProtected, updatedAt, updatedBy))
                .sum();
    }

    public int updateProtected(List<String> ids, boolean isProtected) {
        return updateProtected(ids, isProtected, LocalDateTime.now(), CurrentOperator.username());
    }

    /**
     * Batch extension rules (across protocols)
     */
    public int extendRules(List<String> ids, LocalDateTime extendedAt, String updatedBy) {
        if (ids.isEmpty()) {
            return 0;
        }
        return handlers.stream()
                .mapToInt(h -> h.extendRules(ids, extendedAt, updatedBy))
                .sum();
    }

    public int extendRules(List<String> ids, LocalDateTime extendedAt) {
        return extendRules(ids, extendedAt, CurrentOperator.username());
    }

    /**
     * Deletion rule according to responseId (cross-protocol)
     */
    public int deleteByResponseId(Long responseId) {
        return handlers.stream()
                .mapToInt(h -> h.deleteByResponseId(responseId))
                .sum();
    }

    /**
     * Query rules according to responseId (cross-protocol)
     */
    public List<BaseRule> findByResponseId(Long responseId) {
        return handlers.stream()
                .flatMap(h -> h.findByResponseId(responseId).stream())
                .map(r -> (BaseRule) r)
                .toList();
    }

    /**
     * Counting the number of orphan rules (across protocols)
     */
    public long countOrphanRules() {
        return handlers.stream()
                .mapToLong(ProtocolHandler::countOrphanRules)
                .sum();
    }

    /**
     * Query rule ID based on label pattern (cross-protocol, avoid full table loading)
     */
    public List<String> findIdsByTagPattern(String pattern) {
        return handlers.stream()
                .flatMap(h -> h.findIdsByTagPattern(pattern).stream())
                .toList();
    }

    /**
     * Convert Rule to DTO (automatically find the corresponding Handler)
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
     * Check if the protocol is enabled
     */
    public boolean isEnabled(Protocol protocol) {
        return getHandler(protocol).map(ProtocolHandler::isEnabled).orElse(false);
    }

    /**
     * Generate rule description
     */
    public String generateDescription(RuleDto dto) {
        return getHandler(dto.getProtocol())
                .map(h -> h.generateDescription(dto))
                .orElse(dto.getMatchKey());
    }
}
