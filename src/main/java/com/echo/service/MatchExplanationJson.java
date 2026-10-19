package com.echo.service;

import com.echo.core.match.LegacyConditions;
import com.echo.core.match.MatchExplanation;
import com.echo.core.match.MatchNode;
import com.echo.core.match.MatchSpecCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Optional;

/**
 * Convert {@link MatchExplanation} to {@code detailTree} JSON of the matching chain, and display the "comparison process" of the request record in a tree shape.
 * <ul>
 *   <li>group: {@code {"node":"all|any|not","passed":true,"children":[...]}}</li>
 *   <li>structured condition: {@code {"node":"leaf","passed":false,"spec":{...},"actual":"\"JP\""}}</li>
 *   <li>Old format condition: {@code {"node":"legacy","source":"body","passed":true,"condition":"a=b","actual":"b"}}</li>
 * </ul>
 * When the value cannot be obtained, it is represented by {@code "missing":true} (when the structured condition cannot be obtained in this way, it is represented by {@code "unavailable":true}), without {@code actual}.
 * Records will be stored in the database or memory (memory mode retains 10,000 records by default), so the size is limited: number of nodes, approximate number of words in the entire tree,
 * There is an upper limit for the text length and the expected value length. If it exceeds the upper limit, it will be marked with {@code truncated} and {@code valueOmitted}.
 */
public final class MatchExplanationJson {

    static final int MAX_NODES = 200;
    /** Approximate character-count limit for the whole tree; typical rule trees contain fewer than 1,000 characters. */
    static final int MAX_TREE_CHARS = 8_000;
    static final int MAX_TEXT_LENGTH = 300;
    static final int MAX_SPEC_LENGTH = 1_000;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String LEAF_MISSING = "missing";
    private static final String LEAF_UNAVAILABLE = "unavailable";

    private MatchExplanationJson() {
    }

    public static String toJson(MatchExplanation explanation) {
        return write(explanation, new Budget()).toString();
    }

    /** Number of nodes and approximate word count written. */
    private static final class Budget {
        private int nodes;
        private int chars;

        private boolean exhausted() {
            return nodes >= MAX_NODES || chars >= MAX_TREE_CHARS;
        }
    }

    private static ObjectNode write(MatchExplanation explanation, Budget budget) {
        budget.nodes++;
        ObjectNode json = MAPPER.createObjectNode();
        Optional<MatchNode.LegacySource> legacy = LegacyConditions.sourceOf(explanation);
        if (legacy.isPresent()) {
            json.put("node", "legacy");
            json.put("source", legacy.get().jsonName());
            json.put("passed", explanation.passed());
            json.put("condition", truncate(explanation.expected()));
            writeLegacyActual(explanation.actual(), json);
            budget.chars += json.toString().length();
            return json;
        }
        if (explanation.isCondition()) {
            json.put("node", "leaf");
            json.put("passed", explanation.passed());
            writeSpec(explanation, json);
            writeLeafActual(explanation.actual(), json);
            budget.chars += json.toString().length();
            return json;
        }
        json.put("node", explanation.node());
        json.put("passed", explanation.passed());
        budget.chars += 40;
        ArrayNode children = json.putArray("children");
        for (MatchExplanation child : explanation.children()) {
            if (budget.exhausted()) {
                json.put("truncated", true);
                break;
            }
            children.add(write(child, budget));
        }
        return json;
    }

    private static void writeSpec(MatchExplanation explanation, ObjectNode json) {
        MatchNode.Leaf leaf = explanation.leaf();
        if (leaf == null) {
            // The externally registered node has no structured conditions and can only display description text.
            json.put("description", truncate(explanation.expected()));
            return;
        }
        ObjectNode spec = MatchSpecCodec.toJson(leaf);
        if (spec.has("value") && spec.toString().length() > MAX_SPEC_LENGTH) {
            spec.remove("value");
            json.put("valueOmitted", true);
        }
        json.set("spec", spec);
    }

    /** The actual value of the structured condition is already a summary for display (the string is enclosed in quotes, and multiple values are a list). When the value cannot be obtained, it is missing/unavailable. */
    private static void writeLeafActual(String actual, ObjectNode json) {
        if (actual == null || LEAF_MISSING.equals(actual)) {
            json.put("missing", true);
        } else if (LEAF_UNAVAILABLE.equals(actual)) {
            json.put("unavailable", true);
        } else {
            json.put("actual", truncate(actual));
        }
    }

    /** The actual value of the old format condition is {@code field=value}; the field does not contain =. When the value cannot be obtained, the old version displays null or not found. */
    private static void writeLegacyActual(String actual, ObjectNode json) {
        String value = actual == null ? null : actual.substring(actual.indexOf('=') + 1);
        if (value == null || "null".equals(value) || "not found".equals(value)) {
            json.put("missing", true);
        } else {
            json.put("actual", truncate(value));
        }
    }

    private static String truncate(String text) {
        if (text == null || text.length() <= MAX_TEXT_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_TEXT_LENGTH) + "…";
    }
}
