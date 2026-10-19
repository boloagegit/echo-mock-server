package com.echo.service;

import com.echo.core.match.InvalidMatchSpecException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MatchSpecsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> HTTP = Set.of("body", "query", "header");
    private static final Set<String> JMS = Set.of("body");

    private static JsonNode json(String text) throws Exception {
        return MAPPER.readTree(text);
    }

    @Test
    void advancedSpecIsStoredInCanonicalForm() throws Exception {
        MatchSpecs.Normalized normalized = MatchSpecs.normalize(json("""
                {"any": [
                  {"value": 1000, "op": "gte", "selector": {"jsonPath": "$.amount"}, "source": "body"},
                  {"source": "query", "name": "vip", "op": "exists", "onMissing": "auto"}
                ]}"""), HTTP);

        assertThat(normalized.advanced()).isTrue();
        assertThat(normalized.bodyCondition()).isNull();
        assertThat(normalized.queryCondition()).isNull();
        assertThat(normalized.headerCondition()).isNull();
        // Field order fixed, default value omitted
        assertThat(normalized.matchSpec()).isEqualTo(
                "{\"any\":[{\"source\":\"body\",\"selector\":{\"jsonPath\":\"$.amount\"},\"op\":\"gte\",\"value\":1000},"
                        + "{\"source\":\"query\",\"name\":\"vip\",\"op\":\"exists\"}]}");
    }

    @Test
    void legacyExpressibleSpecCollapsesToLegacyFields() throws Exception {
        MatchSpecs.Normalized normalized = MatchSpecs.normalize(json("""
                {"all": [
                  {"legacy": {"source": "body", "condition": "user.type=vip"}},
                  {"legacy": {"source": "query", "condition": "a=1"}},
                  {"legacy": {"source": "body", "condition": "x!=y"}},
                  {"legacy": {"source": "header", "condition": "X-Env=prod"}}
                ]}"""), HTTP);

        assertThat(normalized.advanced()).isFalse();
        assertThat(normalized.matchSpec()).isNull();
        assertThat(normalized.bodyCondition()).isEqualTo("user.type=vip;x!=y");
        assertThat(normalized.queryCondition()).isEqualTo("a=1");
        assertThat(normalized.headerCondition()).isEqualTo("X-Env=prod");
    }

    @Test
    void emptyAllClearsConditions() throws Exception {
        MatchSpecs.Normalized normalized = MatchSpecs.normalize(json("{\"all\": []}"), HTTP);

        assertThat(normalized.advanced()).isFalse();
        assertThat(normalized.bodyCondition()).isNull();
        assertThat(normalized.queryCondition()).isNull();
        assertThat(normalized.headerCondition()).isNull();
    }

    @Test
    void unknownOperatorIsRejectedWithItsPath() throws Exception {
        assertThatThrownBy(() -> MatchSpecs.normalize(json("""
                {"all": [{"source": "header", "name": "X", "op": "equalz", "value": "a"}]}"""), HTTP))
                .isInstanceOf(InvalidMatchSpecException.class)
                .hasMessageStartingWith("$.all[0].op: unknown op 'equalz'");
    }

    @Test
    void sourcesAreRestrictedPerProtocol() throws Exception {
        assertThatThrownBy(() -> MatchSpecs.normalize(json("""
                {"not": {"source": "header", "name": "X", "op": "exists"}}"""), JMS))
                .isInstanceOf(InvalidMatchSpecException.class)
                .hasMessageContaining("$.not.source")
                .hasMessageContaining("source 'header' is not available");

        // method/path has been determined by routing and is not open as a condition source
        assertThatThrownBy(() -> MatchSpecs.normalize(json("""
                {"source": "method", "op": "equals", "value": "GET"}"""), HTTP))
                .hasMessageContaining("source 'method' is not available");

        // Older clips are subject to the same restrictions
        assertThatThrownBy(() -> MatchSpecs.normalize(json("""
                {"any": [{"legacy": {"source": "query", "condition": "a=1"}}]}"""), JMS))
                .hasMessageContaining("$.any[0].legacy.source");
    }

    @Test
    void depthNodeCountAndInListAreBounded() throws Exception {
        String leaf = "{\"source\":\"query\",\"name\":\"a\",\"op\":\"exists\"}";
        String deep = leaf;
        for (int i = 0; i <= MatchSpecs.MAX_DEPTH; i++) {
            deep = "{\"not\":" + deep + "}";
        }
        String tooDeep = deep;
        assertThatThrownBy(() -> MatchSpecs.normalize(json(tooDeep), HTTP))
                .hasMessageContaining("nested deeper than " + MatchSpecs.MAX_DEPTH);

        ArrayNode many = MAPPER.createArrayNode();
        for (int i = 0; i < MatchSpecs.MAX_NODES; i++) {
            many.add(json(leaf));
        }
        ObjectNode wide = MAPPER.createObjectNode();
        wide.set("any", many);
        assertThatThrownBy(() -> MatchSpecs.normalize(wide, HTTP))
                .hasMessageContaining("more than " + MatchSpecs.MAX_NODES + " nodes");

        ArrayNode values = MAPPER.createArrayNode();
        for (int i = 0; i <= MatchSpecs.MAX_IN_VALUES; i++) {
            values.add("v" + i);
        }
        ObjectNode in = (ObjectNode) json("{\"source\":\"query\",\"name\":\"a\",\"op\":\"in\"}");
        in.set("value", values);
        assertThatThrownBy(() -> MatchSpecs.normalize(in, HTTP))
                .hasMessageContaining("$.value: has more than " + MatchSpecs.MAX_IN_VALUES + " values");
    }

    @Test
    void oversizedSpecIsRejected() throws Exception {
        ObjectNode leaf = (ObjectNode) json("{\"source\":\"query\",\"name\":\"a\",\"op\":\"equals\"}");
        leaf.put("value", "x".repeat(MatchSpecs.MAX_SPEC_CHARS));
        assertThatThrownBy(() -> MatchSpecs.normalize(leaf, HTTP))
                .hasMessageContaining("longer than " + MatchSpecs.MAX_SPEC_CHARS);
    }

    @Test
    void nullOrNonObjectSpecIsRejected() throws Exception {
        assertThatThrownBy(() -> MatchSpecs.normalize(null, HTTP)).hasMessageContaining("expected an object");
        assertThatThrownBy(() -> MatchSpecs.normalize(json("[1]"), HTTP)).hasMessageContaining("expected an object");
    }

    @Test
    void conditionCountCountsLeavesAndLegacyFragments() throws Exception {
        assertThat(MatchSpecs.conditionCount(json("""
                {"all": [
                  {"source": "query", "name": "a", "op": "exists"},
                  {"any": [{"legacy": {"source": "body", "condition": "x=1"}},
                           {"not": {"source": "header", "name": "H", "op": "absent"}}]}
                ]}"""))).isEqualTo(3);
        assertThat(MatchSpecs.conditionCount((JsonNode) null)).isZero();
        assertThat(MatchSpecs.conditionCount("not json")).isZero();
    }
}
