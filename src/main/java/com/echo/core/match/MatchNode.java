package com.echo.core.match;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 尚未編譯的匹配條件樹。
 * <p>
 * 由 {@link MatchSpecCodec#parse(JsonNode)} 從 JSON 產生，再交給 {@link MatcherCompiler} 編譯。
 * 所有節點皆為不可變物件。
 */
public sealed interface MatchNode permits MatchNode.All, MatchNode.Any, MatchNode.Not, MatchNode.Leaf {

    /** 全部子節點都符合才符合；沒有子節點時視為無條件（永遠符合）。 */
    record All(List<MatchNode> children) implements MatchNode {
        public All {
            children = List.copyOf(children);
        }
    }

    /** 任一子節點符合即符合；沒有子節點時永遠不符合。 */
    record Any(List<MatchNode> children) implements MatchNode {
        public Any {
            children = List.copyOf(children);
        }
    }

    /** 子節點不符合時才符合。 */
    record Not(MatchNode child) implements MatchNode {
        public Not {
            Objects.requireNonNull(child, "child");
        }
    }

    /**
     * 葉節點：從請求的 {@code source} 取值，再以 {@code op} 判斷。
     *
     * @param source    取值來源，例如 body、query、header
     * @param name      query／header 等具名來源的名稱；不需要時為 null
     * @param selector  body 的取值方式；不需要時為 null
     * @param op        運算子名稱，例如 equals、matches
     * @param value     期望值；exists／absent 等不需要期望值時為 null
     * @param quantifier 取到多個值時的判斷方式
     * @param onMissing 取不到值時的判斷方式
     * @param options   運算子選項，例如 caseInsensitive
     */
    record Leaf(String source, String name, Selector selector, String op, JsonNode value,
                Quantifier quantifier, OnMissing onMissing, Map<String, JsonNode> options)
            implements MatchNode {

        public Leaf {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(op, "op");
            value = value == null ? null : value.deepCopy();
            quantifier = quantifier == null ? Quantifier.ANY : quantifier;
            onMissing = onMissing == null ? OnMissing.AUTO : onMissing;
            options = copyOptions(options);
        }

        @Override
        public JsonNode value() {
            return value == null ? null : value.deepCopy();
        }

        @Override
        public Map<String, JsonNode> options() {
            return copyOptions(options);
        }

        private static Map<String, JsonNode> copyOptions(Map<String, JsonNode> options) {
            if (options == null || options.isEmpty()) {
                return Map.of();
            }
            Map<String, JsonNode> copy = new LinkedHashMap<>();
            options.forEach((key, node) -> copy.put(key, node.deepCopy()));
            return Collections.unmodifiableMap(copy);
        }
    }

    /** 取到多個值（例如 XPath 命中多個節點）時的判斷方式。 */
    enum Quantifier {
        /** 任一值符合即可（預設，與舊版 XML 條件行為一致）。 */
        ANY,
        /** 所有值都要符合。 */
        ALL
    }

    /** 取不到值時的判斷方式。 */
    enum OnMissing {
        /** 由運算子決定：notEquals、absent 視為符合，其餘視為不符合。 */
        AUTO,
        PASS,
        FAIL
    }
}
