package com.echo.service;

import com.echo.entity.RuleAuditLog.Action;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 從修訂記錄的前後快照算出列表要顯示的小摘要：對象名稱、協定、說明，以及改了哪些欄位。
 * <p>
 * 摘要在寫入修訂記錄時算一次並存成小欄位，列表就不必讀取前後快照這兩個大欄位。
 * 舊資料沒有摘要時，由 {@link RuleAuditService} 逐筆補算。
 */
final class AuditSummaryCalculator {

    static final int NAME_LENGTH = 255;
    static final int SUMMARY_LENGTH = 1000;

    /** 不列為「改了什麼」的欄位：每次存檔都會變，或只是內部欄位。與前端 AUDIT_HIDDEN_FIELDS 一致。 */
    private static final Set<String> HIDDEN_FIELDS = Set.of(
            "id", "version", "createdAt", "updatedAt", "createdBy", "updatedBy",
            "extendedAt", "bodySize", "protocol", "condition");

    /** 只是內部編號的欄位排在最後，列表前幾項先顯示使用者看得懂的變更。 */
    private static final Set<String> TRAILING_FIELDS = Set.of(
            "responseId", "httpTargetConnectionId", "jmsTargetConnectionId");

    /** 摘要最多記幾個欄位；列表只顯示前 3 個，其餘以總數呈現。 */
    private static final int MAX_CHANGES = 6;
    private static final int MAX_VALUE_LENGTH = 60;
    private static final int LONG_VALUE_LENGTH = 80;
    /** 超過這個大小的內容不逐行計數，只標示「內容已變更」。 */
    private static final int MAX_LINE_COUNT_CHARS = 200_000;

    /** 摘要無法計算（快照不是 JSON）時存入的值，避免背景補算一再重試同一筆。 */
    static final String EMPTY_SUMMARY = "{}";

    record Result(String protocol, String name, String description, String summary) {
        static final Result EMPTY = new Result(null, null, null, EMPTY_SUMMARY);
    }

    private AuditSummaryCalculator() {
    }

    static Result compute(ObjectMapper mapper, String ruleId, Action action, String beforeJson, String afterJson) {
        JsonNode before;
        JsonNode after;
        try {
            before = beforeJson != null ? mapper.readTree(beforeJson) : null;
            after = afterJson != null ? mapper.readTree(afterJson) : null;
        } catch (JsonProcessingException e) {
            return Result.EMPTY;
        }
        JsonNode subject = after != null && after.isObject() ? after : before;
        if (subject == null || !subject.isObject()) {
            return Result.EMPTY;
        }

        boolean response = ruleId != null && ruleId.startsWith("response-");
        String protocol;
        String name;
        String description;
        ObjectNode summary = mapper.createObjectNode();
        if (response) {
            protocol = "RESPONSE";
            name = text(subject, "description");
            description = null;
            putText(summary, "contentType", text(subject, "contentType"));
        } else {
            protocol = text(subject, "protocol");
            if (protocol == null) {
                protocol = subject.has("queueName") ? "JMS" : "HTTP";
            }
            name = "JMS".equals(protocol) ? text(subject, "queueName") : text(subject, "matchKey");
            description = text(subject, "description");
            putText(summary, "method", text(subject, "method"));
            putText(summary, "host", text(subject, "targetHost"));
            if (subject.hasNonNull("httpStatus")) {
                summary.put("status", subject.get("httpStatus").asInt());
            }
            if (subject.has("enabled") && !subject.get("enabled").asBoolean(true)) {
                summary.put("enabled", false);
            }
        }

        if (action == Action.UPDATE && before != null && before.isObject() && after != null && after.isObject()) {
            addChanges(summary, before, after);
        }
        return new Result(protocol, truncate(name, NAME_LENGTH), truncate(description, NAME_LENGTH), fit(summary));
    }

    private static void addChanges(ObjectNode summary, JsonNode before, JsonNode after) {
        Set<String> keys = new LinkedHashSet<>();
        after.fieldNames().forEachRemaining(keys::add);
        before.fieldNames().forEachRemaining(keys::add);
        List<String> ordered = new ArrayList<>(keys);
        ordered.sort(Comparator.comparing(TRAILING_FIELDS::contains));
        ArrayNode changes = summary.putArray("changes");
        int count = 0;
        for (String key : ordered) {
            if (HIDDEN_FIELDS.contains(key)) {
                continue;
            }
            JsonNode b = before.get(key);
            JsonNode a = after.get(key);
            if (sameValue(b, a)) {
                continue;
            }
            count++;
            if (changes.size() >= MAX_CHANGES) {
                continue;
            }
            ObjectNode change = changes.addObject();
            change.put("f", key);
            String bt = valueText(b);
            String at = valueText(a);
            if (isLong(bt) || isLong(at)) {
                change.put("long", true);
                int beforeLength = bt == null ? 0 : bt.length();
                int afterLength = at == null ? 0 : at.length();
                if (beforeLength <= MAX_LINE_COUNT_CHARS && afterLength <= MAX_LINE_COUNT_CHARS) {
                    int[] delta = lineDelta(bt, at);
                    change.put("add", delta[0]);
                    change.put("del", delta[1]);
                }
            } else {
                putValue(change, "b", b, bt);
                putValue(change, "a", a, at);
            }
        }
        summary.put("changeCount", count);
    }

    /** null、缺少欄位與空字串都視為「沒有值」，與前端顯示一致。 */
    private static boolean sameValue(JsonNode b, JsonNode a) {
        boolean bEmpty = isEmpty(b);
        boolean aEmpty = isEmpty(a);
        if (bEmpty || aEmpty) {
            return bEmpty && aEmpty;
        }
        return b.equals(a);
    }

    private static boolean isEmpty(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() || (node.isTextual() && node.asText().isEmpty());
    }

    private static String valueText(JsonNode node) {
        if (isEmpty(node)) {
            return null;
        }
        return node.isValueNode() ? node.asText() : node.toString();
    }

    private static boolean isLong(String value) {
        return value != null && (value.length() > LONG_VALUE_LENGTH || value.indexOf('\n') >= 0);
    }

    /**
     * 估算新增與刪除的行數：把行當成可重複的集合來比，移動位置的行不算變更。
     * 一般編輯的結果與逐行比對相同，而且只需線性時間。
     */
    static int[] lineDelta(String before, String after) {
        Map<String, Integer> remaining = new HashMap<>();
        if (before != null) {
            for (String line : before.split("\n", -1)) {
                remaining.merge(line, 1, Integer::sum);
            }
        }
        int added = 0;
        if (after != null) {
            for (String line : after.split("\n", -1)) {
                Integer left = remaining.get(line);
                if (left == null || left == 0) {
                    added++;
                } else {
                    remaining.put(line, left - 1);
                }
            }
        }
        int deleted = 0;
        for (int left : remaining.values()) {
            deleted += left;
        }
        return new int[] {added, deleted};
    }

    /** 確保摘要不超過欄位長度：從最後面開始拿掉欄位明細，總數仍保留。 */
    private static String fit(ObjectNode summary) {
        String json = summary.toString();
        JsonNode changes = summary.get("changes");
        while (json.length() > SUMMARY_LENGTH && changes instanceof ArrayNode array && !array.isEmpty()) {
            array.remove(array.size() - 1);
            json = summary.toString();
        }
        if (json.length() > SUMMARY_LENGTH) {
            summary.remove("changes");
            json = summary.toString();
        }
        return json.length() > SUMMARY_LENGTH ? EMPTY_SUMMARY : json;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isValueNode()) {
            return null;
        }
        String text = value.asText();
        return text.isEmpty() ? null : text;
    }

    /** 是／否與數字保留原本型別，前端才能照欄位格式顯示；其他值存成截短後的文字。 */
    private static void putValue(ObjectNode node, String field, JsonNode value, String text) {
        if (text == null) {
            return;
        }
        if (value.isBoolean() || value.isNumber()) {
            node.set(field, value);
        } else {
            node.put(field, truncate(text, MAX_VALUE_LENGTH));
        }
    }

    private static void putText(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
    }

    static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        int end = max - 1;
        if (Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end) + "…";
    }
}
