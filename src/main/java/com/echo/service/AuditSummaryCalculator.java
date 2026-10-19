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
 * Calculate a small summary for the list to display from the before and after snapshots of the revision record: object name, protocol, description, and which fields were changed.
 * <p>
 * The summary is counted once when writing the revision record and stored in a small field, so that the list does not have to read the two large fields of the before and after snapshots.
 * When the old data does not have a summary, {@link RuleAuditService} will make up the calculation one by one.
 */
final class AuditSummaryCalculator {

    static final int NAME_LENGTH = 255;
    static final int SUMMARY_LENGTH = 1000;

    /** Fields not listed as "What changed": it changes every time you save, or it's just an internal field. Consistent with front-end AUDIT_HIDDEN_FIELDS. */
    private static final Set<String> HIDDEN_FIELDS = Set.of(
            "id", "version", "createdAt", "updatedAt", "createdBy", "updatedBy",
            "extendedAt", "bodySize", "protocol", "condition");

    /** Only the internal number field is sorted last, and the first few items in the list display the changes that users can understand first. */
    private static final Set<String> TRAILING_FIELDS = Set.of(
            "responseId", "httpTargetConnectionId", "jmsTargetConnectionId");

    /** The summary can record a maximum of several fields; only the first 3 are displayed in the list, and the rest are presented in total. */
    private static final int MAX_CHANGES = 6;
    private static final int MAX_VALUE_LENGTH = 60;
    private static final int LONG_VALUE_LENGTH = 80;
    /** Content exceeding this size will not be counted line by line, and will only be marked as "Content has changed". */
    private static final int MAX_LINE_COUNT_CHARS = 200_000;
    /** Line-by-line comparison can find at most a few additions and deletions, which is consistent with MAX_EDITS of front-end audit-diff.js. */
    private static final int MAX_EDITS = 2000;

    /** Sentinel for an uncomputable summary, such as a non-JSON snapshot, preventing repeated backfill attempts for the same record. */
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

    /** null, missing fields and empty strings are all regarded as "no value", which is consistent with the front-end display. */
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
     * The number of added and deleted lines is the same as the line-by-line comparison of the detail screen (Myers least edited): first skip the lines with the same beginning and end, and then find the smallest additions and deletions.
     * When the difference exceeds {@link #MAX_EDITS}, the details are changed to display the full text before and after, and the number of rows is not displayed. Here, the estimation without looking at the order is used to avoid spending too much time.
     */
    static int[] lineDelta(String before, String after) {
        String[] a = before == null ? new String[0] : before.split("\n", -1);
        String[] b = after == null ? new String[0] : after.split("\n", -1);
        int start = 0;
        while (start < a.length && start < b.length && a[start].equals(b[start])) {
            start++;
        }
        int endA = a.length;
        int endB = b.length;
        while (endA > start && endB > start && a[endA - 1].equals(b[endB - 1])) {
            endA--;
            endB--;
        }
        int n = endA - start;
        int m = endB - start;
        int edits = editDistance(a, b, start, n, m);
        if (edits < 0) {
            return unorderedDelta(a, b, start, endA, endB);
        }
        // The minimum number of edits = new additions + deletions, and new additions − deletions = the difference in the number of lines on both sides.
        int added = (edits + m - n) >>> 1;
        return new int[] {added, edits - added};
    }

    /** Myers algorithm only counts the minimum number of added and deleted rows (no need to restore the path); if the upper limit is exceeded, -1 is returned. */
    private static int editDistance(String[] a, String[] b, int start, int n, int m) {
        int max = Math.min(n + m, MAX_EDITS);
        int offset = max + 1;
        int[] v = new int[2 * max + 3];
        for (int d = 0; d <= max; d++) {
            for (int k = -d; k <= d; k += 2) {
                int x = (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1]))
                        ? v[offset + k + 1] : v[offset + k - 1] + 1;
                int y = x - k;
                while (x < n && y < m && a[start + x].equals(b[start + y])) {
                    x++;
                    y++;
                }
                v[offset + k] = x;
                if (x >= n && y >= m) {
                    return d;
                }
            }
        }
        return -1;
    }

    /** Estimation when there are too many differences: compare rows as repeatable sets in linear time. */
    private static int[] unorderedDelta(String[] a, String[] b, int start, int endA, int endB) {
        Map<String, Integer> remaining = new HashMap<>();
        for (int i = start; i < endA; i++) {
            remaining.merge(a[i], 1, Integer::sum);
        }
        int added = 0;
        for (int i = start; i < endB; i++) {
            Integer left = remaining.get(b[i]);
            if (left == null || left == 0) {
                added++;
            } else {
                remaining.put(b[i], left - 1);
            }
        }
        int deleted = 0;
        for (int left : remaining.values()) {
            deleted += left;
        }
        return new int[] {added, deleted};
    }

    /** Make sure the summary does not exceed the field length: remove the field details starting from the end, but keep the total. */
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

    /** Yes/no and numbers retain their original types so that the front end can display them in field format; other values are saved as truncated text. */
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
