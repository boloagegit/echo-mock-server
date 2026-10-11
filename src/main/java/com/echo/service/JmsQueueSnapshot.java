package com.echo.service;

import com.echo.entity.JmsRule;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 某一 queue 的 JMS 規則不可變快照，存放在 {@code jmsRules} 快取中（key 為 queue 名稱，來自設定檔，數量有限）。
 * <p>
 * 依 endpoint 欄位值（例如 ServiceName）分桶時，只為規則條件中出現過的值建立桶；
 * 其他任意值（來自訊息內容，數量不受控）共用「沒有指定 endpoint 的規則」這一桶，因此快照大小只隨規則數量成長。
 * 桶的內容與原本 {@code findBucketedJmsRules} 相同。
 */
final class JmsQueueSnapshot {

    private final List<JmsRule> rules;
    private final String endpointField;
    /** 與 {@link #rules} 對應：該規則條件中 endpoint 欄位的值；沒有提到時為 null。 */
    private final List<String> endpointValues;
    private final Set<String> mentionedValues;
    private final List<JmsRule> unmentioned;
    private final Map<String, List<JmsRule>> buckets = new ConcurrentHashMap<>();
    /** 只用來保持強參照，讓 compiled 模式的條件編譯結果與快照同生命週期。 */
    @SuppressWarnings({"unused", "FieldCanBeLocal"})
    private final List<ConditionMatcher.RuleConditions> pinnedConditions;

    /**
     * @param rules         已排序、Response 有效的規則
     * @param endpointField 分桶用的欄位名稱；空白時不分桶
     * @param conditions    用來取得並持有各規則的條件物件；可為 null（不持有）
     */
    JmsQueueSnapshot(List<JmsRule> rules, String endpointField, ConditionMatcher conditions) {
        this.rules = List.copyOf(rules);
        this.endpointField = endpointField;
        List<String> values = new ArrayList<>(this.rules.size());
        Set<String> mentioned = new HashSet<>();
        List<JmsRule> withoutEndpoint = new ArrayList<>();
        List<ConditionMatcher.RuleConditions> pinned = new ArrayList<>(this.rules.size());
        for (JmsRule rule : this.rules) {
            String value = bucketingEnabled() ? endpointValueOf(rule.getBodyCondition(), endpointField) : null;
            values.add(value);
            if (value == null) {
                withoutEndpoint.add(rule);
            } else {
                mentioned.add(value);
            }
            if (conditions != null) {
                ConditionMatcher.RuleConditions ruleConditions =
                        conditions.conditions(rule.getBodyCondition(), null, null);
                if (ruleConditions != null) {
                    pinned.add(ruleConditions);
                }
            }
        }
        this.endpointValues = values;
        this.mentionedValues = Set.copyOf(mentioned);
        this.unmentioned = List.copyOf(withoutEndpoint);
        this.pinnedConditions = List.copyOf(pinned);
    }

    List<JmsRule> rules() {
        return rules;
    }

    /** 與原本 {@code findBucketedJmsRules} 相同：沒有提到 endpoint 欄位，或提到的值相同的規則，順序不變。 */
    List<JmsRule> bucket(String endpointValue) {
        if (endpointValue == null || endpointValue.isBlank() || !bucketingEnabled()) {
            return rules;
        }
        if (!mentionedValues.contains(endpointValue)) {
            return unmentioned;
        }
        return buckets.computeIfAbsent(endpointValue, this::computeBucket);
    }

    /** 已建立的桶數（供測試確認不會隨任意 endpoint 值成長）。 */
    int bucketCount() {
        return buckets.size();
    }

    private boolean bucketingEnabled() {
        return endpointField != null && !endpointField.isBlank();
    }

    private List<JmsRule> computeBucket(String endpointValue) {
        List<JmsRule> bucket = new ArrayList<>();
        for (int i = 0; i < rules.size(); i++) {
            String value = endpointValues.get(i);
            if (value == null || value.equals(endpointValue)) {
                bucket.add(rules.get(i));
            }
        }
        return List.copyOf(bucket);
    }

    /**
     * 條件中第一個提到 endpoint 欄位（不分大小寫）的片段之值；沒有提到時回傳 null。
     * 解析方式與原本 {@code bodyConditionMatchesEndpoint} 相同。
     */
    static String endpointValueOf(String bodyCondition, String endpointField) {
        if (bodyCondition == null || bodyCondition.isBlank()) {
            return null;
        }
        for (String part : bodyCondition.split(";")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String normalized = trimmed.startsWith("//") ? trimmed.substring(2) : trimmed;
            int eqIdx = normalized.indexOf('=');
            if (eqIdx <= 0) {
                continue;
            }
            char prev = normalized.charAt(eqIdx - 1);
            String field = prev == '!' || prev == '*' || prev == '~'
                    ? normalized.substring(0, eqIdx - 1).trim()
                    : normalized.substring(0, eqIdx).trim();
            if (!field.equalsIgnoreCase(endpointField)) {
                continue;
            }
            String value = normalized.substring(eqIdx + 1).trim();
            // 單一個 " 時舊版會丟出例外並讓所有分桶失敗；這裡與新引擎一致，視為字面值
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            return value;
        }
        return null;
    }
}
