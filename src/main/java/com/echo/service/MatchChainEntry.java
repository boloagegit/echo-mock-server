package com.echo.service;

import com.echo.entity.BaseRule;
import com.echo.entity.HttpRule;
import com.echo.entity.JmsRule;
import lombok.Getter;

@Getter
public class MatchChainEntry implements MatchChainInfo {
    private final String ruleId;
    private final String reason;
    private final String endpoint;
    private final String description;
    private final String condition;
    private final String score;
    private final String detail;
    private final boolean nearMiss;
    private final String detailTree;

    public MatchChainEntry(String ruleId, String reason, String endpoint,
                           String description, String condition) {
        this(ruleId, reason, endpoint, description, condition, null, null, false);
    }

    public MatchChainEntry(String ruleId, String reason, String endpoint,
                           String description, String condition,
                           String score, String detail, boolean nearMiss) {
        this(ruleId, reason, endpoint, description, condition, score, detail, nearMiss, null);
    }

    /** @param detailTree 樹狀比對說明的 JSON（見 {@link MatchExplanationJson}），可為 null */
    public MatchChainEntry(String ruleId, String reason, String endpoint,
                           String description, String condition,
                           String score, String detail, boolean nearMiss, String detailTree) {
        this.ruleId = ruleId;
        this.reason = reason;
        this.endpoint = endpoint;
        this.description = description;
        this.condition = condition;
        this.score = score;
        this.detail = detail;
        this.nearMiss = nearMiss;
        this.detailTree = detailTree;
    }

    public static MatchChainEntry from(BaseRule rule, String endpoint, String condition,
                                       String reason) {
        return new MatchChainEntry(rule.getId(), reason, endpoint,
                rule.getDescription(), condition);
    }

    public static MatchChainEntry fromHttp(HttpRule rule, String reason) {
        return from(rule, rule.getMatchKey(), buildHttpCondition(rule), reason);
    }

    public static MatchChainEntry fromJms(JmsRule rule, String reason) {
        return from(rule, rule.getQueueName(),
                rule.getMatchSpec() != null ? "match: " + rule.getMatchSpec() : rule.getBodyCondition(), reason);
    }

    private static String buildHttpCondition(HttpRule rule) {
        if (rule.getMatchSpec() != null) {
            return "match: " + rule.getMatchSpec();
        }
        String body = rule.getBodyCondition();
        String query = rule.getQueryCondition();
        if ((body == null || body.isBlank()) && (query == null || query.isBlank())) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        if (body != null && !body.isBlank()) {
            sb.append("body: ").append(body);
        }
        if (query != null && !query.isBlank()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append("query: ").append(query);
        }
        return sb.toString();
    }
}
