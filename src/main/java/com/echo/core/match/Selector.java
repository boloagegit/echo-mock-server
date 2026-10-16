package com.echo.core.match;

import java.util.Objects;

/**
 * Body 的取值方式。
 *
 * @param kind       取值方式
 * @param expression JSONPath、XPath 或點路徑；{@link Kind#RAW} 時為 null
 */
public record Selector(Kind kind, String expression) {

    public Selector {
        Objects.requireNonNull(kind, "kind");
        if (kind == Kind.RAW) {
            expression = null;
        } else if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException(kind.jsonName() + " expression must not be blank");
        }
    }

    public static Selector raw() {
        return new Selector(Kind.RAW, null);
    }

    public enum Kind {
        /** Jayway JSONPath，例如 {@code $.order.items[*].sku}。 */
        JSON_PATH("jsonPath"),
        /** XPath 1.0，例如 {@code //Order/Status}。 */
        XPATH("xpath"),
        /** JSON 點路徑，例如 {@code order.items[0].sku}。 */
        FIELD("field"),
        /** 整個 body 原文。 */
        RAW("raw");

        private final String jsonName;

        Kind(String jsonName) {
            this.jsonName = jsonName;
        }

        /** JSON 表示中使用的鍵名。 */
        public String jsonName() {
            return jsonName;
        }

        static Kind fromJsonName(String name) {
            for (Kind kind : values()) {
                if (kind.jsonName.equals(name)) {
                    return kind;
                }
            }
            return null;
        }
    }
}
