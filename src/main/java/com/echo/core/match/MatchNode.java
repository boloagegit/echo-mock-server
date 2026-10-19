package com.echo.core.match;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Match condition tree not yet compiled.
 * <p>
 * {@link MatchSpecCodec#parse(JsonNode)} is generated from JSON and then handed over to {@link MatcherCompiler} for compilation.
 * All nodes are immutable objects.
 */
public sealed interface MatchNode
        permits MatchNode.All, MatchNode.Any, MatchNode.Not, MatchNode.Leaf, MatchNode.Legacy {

    /** All child nodes must be consistent to be eligible; if there are no child nodes, it is considered unconditional (always consistent). */
    record All(List<MatchNode> children) implements MatchNode {
        public All {
            children = List.copyOf(children);
        }
    }

    /** If any child node is consistent, it is consistent; if there are no child nodes, it is never consistent. */
    record Any(List<MatchNode> children) implements MatchNode {
        public Any {
            children = List.copyOf(children);
        }
    }

    /** Compliant only when child nodes do not comply. */
    record Not(MatchNode child) implements MatchNode {
        public Not {
            Objects.requireNonNull(child, "child");
        }
    }

    /**
     * Leaf node: Get the value from the requested {@code source}, and then judge it based on {@code op}.
     *
     * @param source    Value source, such as body, query, header
     * @param name      The name of the named source such as query/header; null when not required
     * @param selector  The value method of body; when not needed, it is null
     * @param op        Operator name, such as equals, matches
     * @param value     Expected value; exists/absent, etc. when the expected value is not required, it is null
     * @param quantifier How to judge when multiple values are obtained
     * @param onMissing How to judge when the value cannot be obtained
     * @param options   Operator options, such as caseInsensitive
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

    /**
     * A single fragment of the old version of the string condition (a segment cut by {@code ;}) is evaluated as it is with the semantics of {@code ConditionMatcher}.
     * <p>
     * The operator parsing and body type (JSON/XML/plain text) in the old semantics are determined during execution and cannot be converted losslessly
     * {@link Leaf}, so it is an independent node. See {@link LegacyConditions}.
     *
     * @param source    Which old field does the fragment come from?
     * @param condition Fragment with leading and trailing whitespace removed, without {@code ;}
     */
    record Legacy(LegacySource source, String condition) implements MatchNode {
        public Legacy {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(condition, "condition");
            if (condition.isEmpty() || !condition.equals(condition.trim()) || condition.indexOf(';') >= 0) {
                throw new IllegalArgumentException("Legacy condition must be a single trimmed fragment");
            }
        }
    }

    /** Legacy condition field. */
    enum LegacySource {
        BODY("body", "bodyCondition"),
        QUERY("query", "queryCondition"),
        HEADER("header", "headerCondition");

        private final String jsonName;
        private final String fieldName;

        LegacySource(String jsonName, String fieldName) {
            this.jsonName = jsonName;
            this.fieldName = fieldName;
        }

        /** The name in the JSON representation, which is also the prefix for the legacy staging key (e.g. {@code body:}). */
        public String jsonName() {
            return jsonName;
        }

        /** The name used by legacy rule fields and match chains, such as {@code bodyCondition}. */
        public String fieldName() {
            return fieldName;
        }

        static LegacySource fromJsonName(String name) {
            for (LegacySource source : values()) {
                if (source.jsonName.equals(name)) {
                    return source;
                }
            }
            return null;
        }
    }

    /** Judgment method when multiple values are obtained (for example, XPath hits multiple nodes). */
    enum Quantifier {
        /** Any value can match (default, consistent with the old version XML conditional behavior). */
        ANY,
        /** All values must match. */
        ALL
    }

    /** How to judge when the value cannot be obtained. */
    enum OnMissing {
        /** Determined by the operator: notEquals and absent are regarded as consistent, and the others are regarded as inconsistent. */
        AUTO,
        PASS,
        FAIL
    }
}
