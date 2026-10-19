package com.echo.core.match;

import java.util.Objects;

/**
 * The value method of Body.
 *
 * @param kind       Value method
 * @param expression JSONPath, XPath or point path; null for {@link Kind#RAW}
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
        /** Jayway JSONPath, for example {@code $.order.items[*].sku}. */
        JSON_PATH("jsonPath"),
        /** XPath 1.0, such as {@code //Order/Status}. */
        XPATH("xpath"),
        /** JSON point path, for example {@code order.items[0].sku}. */
        FIELD("field"),
        /** The entire body original text. */
        RAW("raw");

        private final String jsonName;

        Kind(String jsonName) {
            this.jsonName = jsonName;
        }

        /** JSON The key name used in the representation. */
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
