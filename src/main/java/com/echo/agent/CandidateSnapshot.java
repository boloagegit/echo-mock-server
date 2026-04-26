package com.echo.agent;

import com.echo.entity.BaseRule;
import com.echo.entity.HttpRule;
import com.echo.entity.JmsRule;
import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.Objects;

/**
 * 候選規則的不可變快照。
 * <p>
 * 在 Pipeline 主執行緒建立快照後傳入 LogAgent 背景執行緒，
 * 避免跨執行緒存取 JPA managed entity。
 */
@Getter
@Builder
public class CandidateSnapshot {

    private final String ruleId;
    private final String endpoint;
    private final String description;
    private final boolean enabled;
    private final String bodyCondition;
    private final String queryCondition;
    private final String headerCondition;
    private final int priority;

    /**
     * 從 HttpRule 建立深拷貝快照。
     */
    public static CandidateSnapshot fromHttpRule(HttpRule rule) {
        return CandidateSnapshot.builder()
                .ruleId(rule.getId())
                .endpoint(rule.getMatchKey())
                .description(rule.getDescription())
                .enabled(rule.getEnabled())
                .bodyCondition(rule.getBodyCondition())
                .queryCondition(rule.getQueryCondition())
                .headerCondition(rule.getHeaderCondition())
                .priority(rule.getPriority())
                .build();
    }

    /**
     * 從 JmsRule 建立深拷貝快照。
     * <p>
     * JMS 規則沒有 queryCondition 與 headerCondition，設為 null。
     */
    public static CandidateSnapshot fromJmsRule(JmsRule rule) {
        return CandidateSnapshot.builder()
                .ruleId(rule.getId())
                .endpoint(rule.getQueueName())
                .description(rule.getDescription())
                .enabled(rule.getEnabled())
                .bodyCondition(rule.getBodyCondition())
                .queryCondition(null)
                .headerCondition(null)
                .priority(rule.getPriority())
                .build();
    }

    /**
     * 將 BaseRule 候選列表轉換為 CandidateSnapshot 不可變列表（深拷貝）。
     * <p>
     * 根據規則的實際型別（HttpRule 或 JmsRule）呼叫對應的工廠方法。
     * 不支援的規則型別會被過濾掉。
     *
     * @param candidates 候選規則列表
     * @param <T> BaseRule 的子型別
     * @return 不可變的 CandidateSnapshot 列表
     */
    public static <T extends BaseRule> List<CandidateSnapshot> toCandidateSnapshots(List<T> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        return candidates.stream()
                .map(rule -> {
                    if (rule instanceof HttpRule httpRule) {
                        return CandidateSnapshot.fromHttpRule(httpRule);
                    }
                    if (rule instanceof JmsRule jmsRule) {
                        return CandidateSnapshot.fromJmsRule(jmsRule);
                    }
                    return null;
                })
                .filter(Objects::nonNull)
                .toList();
    }
}
