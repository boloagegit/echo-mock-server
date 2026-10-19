package com.echo.core.match;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Single value taken from the request.
 *
 * @param text Text format: JSON string takes its content, other JSON nodes take the serialization result; null when JSON null
 * @param json Original node (read only) when coming from JSON; null when coming from text sources such as header, query, XML
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
