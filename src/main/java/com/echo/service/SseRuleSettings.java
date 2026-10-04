package com.echo.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Set;

/** Settings which can be represented faithfully by an SSE HTTP response. */
public final class SseRuleSettings {
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long TIMEOUT_MARGIN_MS = 30_000L;

    private SseRuleSettings() {}

    public static void validateMethod(String method) {
        if (method != null && !METHODS.contains(method.toUpperCase(java.util.Locale.ROOT))) {
            throw new IllegalArgumentException("SSE 支援 GET、POST、PUT、PATCH、DELETE；null 表示所有適用方法");
        }
    }

    public static HttpHeaders headers(String json, int status) {
        if (status < 200 || status > 599) throw new IllegalArgumentException("SSE HTTP status 必須介於 200 與 599");
        HttpHeaders headers = new HttpHeaders();
        if (json != null && !json.isBlank()) {
            JsonNode root;
            try {
                root = MAPPER.readTree(json);
            } catch (JsonProcessingException error) {
                throw new IllegalArgumentException("SSE responseHeaders 必須為 JSON 物件", error);
            }
            if (root == null || !root.isObject()) throw new IllegalArgumentException("SSE responseHeaders 必須為 JSON 物件");
            root.fields().forEachRemaining(field -> {
                String name = field.getKey();
                JsonNode value = field.getValue();
                if (!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+") || !value.isTextual()
                        || value.textValue().indexOf('\r') >= 0 || value.textValue().indexOf('\n') >= 0) {
                    throw new IllegalArgumentException("無效 SSE response header: " + name);
                }
                if (name.equalsIgnoreCase("Content-Length") || name.equalsIgnoreCase("Transfer-Encoding")
                        || name.equalsIgnoreCase("Content-Encoding")) {
                    throw new IllegalArgumentException("SSE 不支援 framing header: " + name);
                }
                headers.set(name, value.textValue());
            });
        }
        MediaType contentType = headers.getContentType();
        if (status == 200) {
            if (contentType != null && (!MediaType.TEXT_EVENT_STREAM.isCompatibleWith(contentType)
                    || (contentType.getCharset() != null && !java.nio.charset.StandardCharsets.UTF_8.equals(contentType.getCharset())))) {
                throw new IllegalArgumentException("SSE 200 response Content-Type 必須為 text/event-stream UTF-8");
            }
            headers.setContentType(new MediaType("text", "event-stream", java.nio.charset.StandardCharsets.UTF_8));
            if (!headers.containsKey(HttpHeaders.CACHE_CONTROL)) headers.setCacheControl("no-cache");
        } else if (contentType != null && MediaType.TEXT_EVENT_STREAM.isCompatibleWith(contentType)) {
            throw new IllegalArgumentException("非 200 SSE response 不支援 text/event-stream Content-Type");
        }
        return headers;
    }

    public static long timeout(List<SseEventSequence.Event> events, long initialDelay, boolean loop) {
        long duration = initialDelay;
        if (duration < 0 || duration > SseEventSequence.MAX_STREAM_DURATION_MS - TIMEOUT_MARGIN_MS) {
            throw new IllegalArgumentException("SSE 總延遲須少於 24 小時");
        }
        for (var event : events) {
            duration += event.delayMs() == null ? 0 : event.delayMs();
            if (duration > SseEventSequence.MAX_STREAM_DURATION_MS - TIMEOUT_MARGIN_MS) {
                throw new IllegalArgumentException("SSE 總延遲須少於 24 小時");
            }
            if ("error".equals(event.type()) || "abort".equals(event.type())) break;
        }
        return loop ? SseEventSequence.MAX_STREAM_DURATION_MS : duration + TIMEOUT_MARGIN_MS;
    }
}
