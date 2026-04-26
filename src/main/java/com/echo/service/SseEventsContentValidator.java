package com.echo.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * SSE 事件回應內容驗證器。
 * 驗證 body 為合法的 SSE 事件 JSON 陣列。
 */
@Component
public class SseEventsContentValidator implements ResponseContentValidator {

    private static final Set<String> VALID_TYPES = Set.of("normal", "error", "abort");

    private final ObjectMapper objectMapper;

    public SseEventsContentValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void validate(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("SSE 規則的回應內容不可為空");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("SSE 回應內容必須為 JSON 陣列格式");
        }

        if (!root.isArray()) {
            throw new IllegalArgumentException("SSE 回應內容必須為 JSON 陣列格式");
        }

        if (root.isEmpty()) {
            throw new IllegalArgumentException("SSE 事件陣列不可為空");
        }

        for (int i = 0; i < root.size(); i++) {
            JsonNode event = root.get(i);
            JsonNode dataNode = event.get("data");
            if (dataNode == null || dataNode.asText().isEmpty()) {
                throw new IllegalArgumentException(
                        "第 " + (i + 1) + " 個 SSE 事件的 data 欄位不可為空");
            }

            JsonNode typeNode = event.get("type");
            if (typeNode != null && !typeNode.isNull()) {
                String type = typeNode.asText();
                if (!VALID_TYPES.contains(type)) {
                    throw new IllegalArgumentException(
                            "第 " + (i + 1) + " 個 SSE 事件的 type 必須為 normal、error 或 abort");
                }
            }
        }
    }
}
