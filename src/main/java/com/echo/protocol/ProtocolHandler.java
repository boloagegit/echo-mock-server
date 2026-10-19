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
 * Protocol handler interface
 * <p>
 * Define the rule operations of each protocol (HTTP, JMS, etc.). To add a new protocol, you only need to implement this interface.
 */
public interface ProtocolHandler {

    /**
     * Get the protocols supported by this processor
     */
    Protocol getProtocol();

    /**
     * Protocol information required by protocol-independent programs (rule query, cache invalidation)
     */
    ProtocolDescriptor descriptor();

    /**
     * Query all rules
     */
    List<? extends BaseRule> findAllRules();

    /**
     * Query rules according to ID
     */
    Optional<? extends BaseRule> findById(String id);

    /**
     * According to ID batch query rules
     */
    List<? extends BaseRule> findAllByIds(List<String> ids);


    /**
     * Storage rules
     */
    BaseRule save(BaseRule rule);

    /**
     * Delete rule
     */
    void deleteById(String id);

    /**
     * Delete all rules
     */
    int deleteAll();

    /**
     * Counting the number of rules
     */
    long count();

    /**
     * Delete expired rules
     */
    int deleteExpiredRules(LocalDateTime cutoff);

    /**
     * Batch update enabled status
     */
    int updateEnabled(List<String> ids, boolean enabled, LocalDateTime updatedAt, String updatedBy);

    /**
     * Batch update protection status
     */
    int updateProtected(List<String> ids, boolean isProtected, LocalDateTime updatedAt, String updatedBy);

    /**
     * Batch update extension time
     */
    int extendRules(List<String> ids, LocalDateTime extendedAt, String updatedBy);

    /**
     * Count by group responseId
     */
    List<Object[]> countGroupByResponseId();

    /**
     * Delete rule according to responseId
     */
    int deleteByResponseId(Long responseId);

    /**
     * Query rules according to responseId
     */
    List<? extends BaseRule> findByResponseId(Long responseId);

    /**
     * Counting the number of orphan rules (responseId points to non-existent Response)
     */
    long countOrphanRules();

    /**
     * Convert Rule to DTO
     */
    RuleDto toDto(BaseRule rule, Response response, boolean includeBody);

    /**
     * Convert DTO to Rule
     */
    BaseRule fromDto(RuleDto dto);

    /**
     * Test rules
     * @return Test result Map, including status, body, elapsed
     */
    Map<String, Object> testRule(BaseRule rule, Map<String, Object> request);

    /**
     * Is this protocol enabled?
     */
    default boolean isEnabled() {
        return true;
    }

    /**
     * Generate rule description (used to automatically create Response)
     */
    String generateDescription(RuleDto dto);

    /**
     * Query rule ID based on label pattern (avoid full table loading)
     */
    List<String> findIdsByTagPattern(String pattern);
}
