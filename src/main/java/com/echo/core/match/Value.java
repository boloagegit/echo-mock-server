package com.echo.core.match;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 從請求取出的單一值。
 *
 * @param text 文字形式：JSON 字串取其內容、其他 JSON 節點取序列化結果；JSON null 時為 null
 * @param json 來自 JSON 時的原始節點（唯讀）；來自 header、query、XML 等文字來源時為 null
 */
public record Value(String text, JsonNode json) {

    public static Value ofText(String text) {
        return new Value(text, null);
    }

    public static Value ofJson(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return new Value(null, node);
        }
        return new Value(node.isTextual() ? node.asText() : node.toString(), node);
    }
}
