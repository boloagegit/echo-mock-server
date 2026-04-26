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

    public MatchChainEntry(String ruleId, String reason, String endpoint,
                           String description, String condition) {
        this(ruleId, reason, endpoint, description, condition, null, null, false);
    }

    public MatchChainEntry(String ruleId, String reason, String endpoint,
                           String description, String condition,
                           String score, String detail, boolean nearMiss) {
        this.ruleId = ruleId;
        this.reason = reason;
        this.endpoint = endpoint;
        this.description = description;
        this.condition = condition;
        this.score = score;
        this.detail = detail;
        this.nearMiss = nearMiss;
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
        return from(rule, rule.getQueueName(), rule.getBodyCondition(), reason);
    }

    private static String buildHttpCondition(HttpRule rule) {
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
