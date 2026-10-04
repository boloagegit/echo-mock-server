package com.echo.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Shared save-time and playback contract for SSE event sequences. */
public final class SseEventSequence {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> FIELDS = Set.of("data", "event", "id", "delayMs", "type");
    private static final Set<String> TYPES = Set.of("normal", "error", "abort");
    public static final long MAX_EVENT_DELAY_MS = 30_000L;
    public static final long MAX_STREAM_DURATION_MS = 86_400_000L;

    private SseEventSequence() {}

    public record Event(String event, String data, String id, Long delayMs, String type) {}

    public static List<Event> parse(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("SSE 規則的回應內容不可為空");
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("SSE 回應內容必須為 JSON 陣列格式", error);
        }
        if (root == null || !root.isArray()) {
            throw new IllegalArgumentException("SSE 回應內容必須為 JSON 陣列格式");
        }
        if (root.isEmpty()) throw new IllegalArgumentException("SSE 事件陣列不可為空");
        List<Event> events = new ArrayList<>();
        for (int i = 0; i < root.size(); i++) {
            JsonNode node = root.get(i);
            if (!node.isObject()) throw new IllegalArgumentException("SSE 事件必須為 JSON 物件");
            node.fieldNames().forEachRemaining(field -> {
                if (!FIELDS.contains(field)) throw new IllegalArgumentException("不支援 SSE 欄位: " + field);
            });
            String data = text(node, "data");
            if (data == null || data.isEmpty()) throw new IllegalArgumentException("第 " + (i + 1) + " 個 SSE 事件的 data 欄位不可為空");
            String event = text(node, "event");
            String id = text(node, "id");
            validateSingleLine(event, "event");
            validateSingleLine(id, "id");
            if (id != null && id.indexOf('\0') >= 0) throw new IllegalArgumentException("SSE id 不可含 NUL");
            String type = text(node, "type");
            if (type != null && !TYPES.contains(type)) {
                throw new IllegalArgumentException("SSE 事件的 type 必須為 normal、error 或 abort");
            }
            JsonNode delayNode = node.get("delayMs");
            Long delay = null;
            if (delayNode != null && !delayNode.isNull()) {
                if (!delayNode.isIntegralNumber() || !delayNode.canConvertToLong()) {
                    throw new IllegalArgumentException("SSE delayMs 必須為整數");
                }
                delay = Math.min(MAX_EVENT_DELAY_MS, Math.max(0, delayNode.longValue()));
            }
            events.add(new Event(event, data, id, delay, type));
        }
        return List.copyOf(events);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw new IllegalArgumentException("SSE " + field + " 必須為字串");
        return value.textValue();
    }

    private static void validateSingleLine(String value, String field) {
        if (value != null && (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0)) {
            throw new IllegalArgumentException("SSE " + field + " 不可含換行");
        }
    }
}
