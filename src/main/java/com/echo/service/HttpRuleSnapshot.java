package com.echo.service;

import com.echo.entity.HttpRule;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 某一時點所有啟用 HTTP 規則的不可變快照，存放在 {@code httpRules} 快取的單一項目中，
 * 因此所有既有的快取清除路徑（規則異動、Response 刪除、清理排程、多實例同步）都會一併淘汰它。
 * <p>
 * 候選規則依 (host, method) 第一次使用時計算並保留。沒有任何規則提到的 host 或 method 會對應到同一個
 * 共用 key（結果只取決於「沒有指定 host／method 的規則」），所以快照大小只隨規則數量成長，
 * 不會因為用戶端送出任意 {@code X-Original-Host} 或 method 而無限增加。
 */
final class HttpRuleSnapshot {

    /** 不屬於任何規則的 host／method（NUL 不會出現在合法的 host 與 method 中）。 */
    private static final String UNLISTED = "\u0000";

    private final List<HttpRule> rules;
    private final Set<String> hosts;
    /** 規則中出現過的 method 原始字串（去重）；通常只有少數幾個。 */
    private final List<String> methods;
    private final Comparator<HttpRule> order;
    /** 只用來保持強參照，讓 compiled 模式的條件編譯結果與快照同生命週期。 */
    @SuppressWarnings({"unused", "FieldCanBeLocal"})
    private final List<ConditionMatcher.RuleConditions> pinnedConditions;
    private final Map<Key, List<HttpRule>> candidates = new ConcurrentHashMap<>();

    /**
     * @param rules      啟用中且 Response 有效的規則，依 id 排序（與原本查詢的順序相同）
     * @param order      候選規則的優先順序
     * @param conditions 用來取得並持有各規則的條件物件；可為 null（不持有）
     */
    HttpRuleSnapshot(List<HttpRule> rules, Comparator<HttpRule> order, ConditionMatcher conditions) {
        this.rules = List.copyOf(rules);
        this.order = order;
        Set<String> ruleHosts = new HashSet<>();
        Set<String> ruleMethods = new LinkedHashSet<>();
        List<ConditionMatcher.RuleConditions> pinned = new ArrayList<>(rules.size());
        for (HttpRule rule : this.rules) {
            if (rule.getTargetHost() != null && !rule.getTargetHost().isEmpty()) {
                ruleHosts.add(rule.getTargetHost());
            }
            if (rule.getMethod() != null) {
                ruleMethods.add(rule.getMethod());
            }
            if (conditions != null) {
                ConditionMatcher.RuleConditions ruleConditions = conditions.conditions(rule.getMatchSpec(),
                        rule.getBodyCondition(), rule.getQueryCondition(), rule.getHeaderCondition());
                if (ruleConditions != null) {
                    pinned.add(ruleConditions);
                }
            }
        }
        this.hosts = Set.copyOf(ruleHosts);
        this.methods = List.copyOf(ruleMethods);
        this.pinnedConditions = List.copyOf(pinned);
    }

    /**
     * 與原本 {@code findByHostAndMethod} + 排序的結果相同：host 完全相同或規則沒有指定 host，
     * 且 method 不分大小寫相同或規則沒有指定 method；不含路徑篩選。
     */
    List<HttpRule> candidates(String host, String method) {
        String hostKey = host != null && hosts.contains(host) ? host : UNLISTED;
        return candidates.computeIfAbsent(new Key(hostKey, methodKey(method)), key -> compute(host, method));
    }

    int ruleCount() {
        return rules.size();
    }

    /** 已計算的候選集合數（供測試確認不會隨任意輸入成長）。 */
    int candidateSetCount() {
        return candidates.size();
    }

    private List<HttpRule> compute(String host, String method) {
        List<HttpRule> result = new ArrayList<>();
        for (HttpRule rule : rules) {
            boolean hostMatches = rule.getTargetHost() == null || rule.getTargetHost().isEmpty()
                    || rule.getTargetHost().equals(host);
            boolean methodMatches = rule.getMethod() == null || rule.getMethod().equalsIgnoreCase(method);
            if (hostMatches && methodMatches) {
                result.add(rule);
            }
        }
        result.sort(order);
        return List.copyOf(result);
    }

    /**
     * 結果只取決於「哪些規則 method 與請求 method 不分大小寫相同」，因此以這組 method 當 key；
     * 與原本的 {@code equalsIgnoreCase} 判斷完全一致，也不會因任意 method 字串增加 key。
     */
    private String methodKey(String method) {
        if (method == null) {
            return UNLISTED;
        }
        String single = null;
        StringBuilder several = null;
        for (String ruleMethod : methods) {
            if (!ruleMethod.equalsIgnoreCase(method)) {
                continue;
            }
            if (single == null) {
                single = ruleMethod;
            } else {
                if (several == null) {
                    several = new StringBuilder(single);
                }
                several.append(UNLISTED).append(ruleMethod);
            }
        }
        if (several != null) {
            return several.toString();
        }
        return single != null ? single : UNLISTED;
    }

    private record Key(String host, String method) {
    }
}
