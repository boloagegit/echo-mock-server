package com.echo.service;

import com.echo.core.match.MatchExplanation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Description of tree alignment of matching chains (detailTree): structure, actual values and upper size limit. */
class MatchExplanationJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BODY = "{\"customer\":{\"type\":\"vip\"},\"amount\":1500}";
    private static final Map<String, String> HEADERS = Map.of("X-Region", "JP");

    private final ConditionMatcher matcher = new ConditionMatcher();

    private JsonNode tree(String matchSpec, String body, String query, String header) throws Exception {
        ConditionMatcher.PreparedBody prepared = matcher.prepareBody(BODY);
        String json = MatchExplanationJson.toJson(matcher.explainPrepared(matchSpec, body, query, header,
                prepared, "channel=web", HEADERS));
        return MAPPER.readTree(json);
    }

    @Test
    void advancedConditionsKeepTheirGroupsAndShowEachActualValue() throws Exception {
        JsonNode tree = tree("""
                {"all": [
                  {"source": "body", "selector": {"jsonPath": "$.customer.type"}, "op": "equals", "value": "vip"},
                  {"source": "header", "name": "X-Region", "op": "equals", "value": "TW"},
                  {"any": [
                    {"source": "query", "name": "channel", "op": "equals", "value": "app"},
                    {"source": "body", "selector": {"jsonPath": "$.amount"}, "op": "gte", "value": 1000}
                  ]},
                  {"not": {"source": "header", "name": "X-Test", "op": "exists"}}
                ]}""", null, null, null);

        assertThat(tree.get("node").asText()).isEqualTo("all");
        assertThat(tree.get("passed").asBoolean()).isFalse();
        JsonNode children = tree.get("children");
        assertThat(children).hasSize(4);

        JsonNode type = children.get(0);
        assertThat(type.get("node").asText()).isEqualTo("leaf");
        assertThat(type.get("passed").asBoolean()).isTrue();
        assertThat(type.get("spec").get("selector").get("jsonPath").asText()).isEqualTo("$.customer.type");
        assertThat(type.get("actual").asText()).isEqualTo("\"vip\"");

        JsonNode region = children.get(1);
        assertThat(region.get("passed").asBoolean()).isFalse();
        assertThat(region.get("spec").get("name").asText()).isEqualTo("X-Region");
        assertThat(region.get("spec").get("value").asText()).isEqualTo("TW");
        assertThat(region.get("actual").asText()).isEqualTo("\"JP\"");

        JsonNode any = children.get(2);
        assertThat(any.get("node").asText()).isEqualTo("any");
        assertThat(any.get("passed").asBoolean()).isTrue();
        assertThat(any.get("children")).hasSize(2);

        JsonNode not = children.get(3);
        assertThat(not.get("node").asText()).isEqualTo("not");
        assertThat(not.get("passed").asBoolean()).isTrue();
        JsonNode testHeader = not.get("children").get(0);
        assertThat(testHeader.get("passed").asBoolean()).isFalse();
        assertThat(testHeader.get("missing").asBoolean()).isTrue();
        assertThat(testHeader.has("actual")).isFalse();
    }

    @Test
    void legacyConditionsShowTheValueWithoutTheFieldName() throws Exception {
        JsonNode tree = tree(null, "customer.type=vip;customer.level=gold", "channel=web", "X-Region=TW");

        assertThat(tree.get("node").asText()).isEqualTo("all");
        JsonNode children = tree.get("children");
        assertThat(children).hasSize(4);

        JsonNode type = children.get(0);
        assertThat(type.get("node").asText()).isEqualTo("legacy");
        assertThat(type.get("source").asText()).isEqualTo("body");
        assertThat(type.get("condition").asText()).isEqualTo("customer.type=vip");
        assertThat(type.get("passed").asBoolean()).isTrue();
        assertThat(type.get("actual").asText()).isEqualTo("vip");

        JsonNode level = children.get(1);
        assertThat(level.get("passed").asBoolean()).isFalse();
        assertThat(level.get("missing").asBoolean()).isTrue();

        assertThat(children.get(2).get("source").asText()).isEqualTo("query");
        assertThat(children.get(2).get("actual").asText()).isEqualTo("web");
        assertThat(children.get(3).get("source").asText()).isEqualTo("header");
        assertThat(children.get(3).get("passed").asBoolean()).isFalse();
        assertThat(children.get(3).get("actual").asText()).isEqualTo("JP");
    }

    @Test
    void valuesThatCannotBeReadAreMarkedUnavailable() throws Exception {
        // body is not parsed when it exceeds the upper limit of size, and the body condition cannot obtain a value.
        JsonNode tree = MAPPER.readTree(MatchExplanationJson.toJson(
                new MatchExplanation("leaf", false, "body(jsonPath: $.a) equals \"b\"", "unavailable", List.of())));

        assertThat(tree.get("unavailable").asBoolean()).isTrue();
        assertThat(tree.has("actual")).isFalse();
        assertThat(tree.get("description").asText()).isEqualTo("body(jsonPath: $.a) equals \"b\"");
    }

    @Test
    void largeTreesAndValuesAreCapped() throws Exception {
        String leaves = IntStream.range(0, MatchExplanationJson.MAX_NODES + 50)
                .mapToObj(i -> "{\"source\":\"header\",\"name\":\"H" + i + "\",\"op\":\"exists\"}")
                .collect(Collectors.joining(","));
        JsonNode many = tree("{\"any\":[" + leaves + "]}", null, null, null);
        assertThat(many.get("truncated").asBoolean()).isTrue();
        assertThat(many.get("children").size()).isBetween(1, MatchExplanationJson.MAX_NODES - 1);

        String longList = IntStream.range(0, 200).mapToObj(i -> "\"value-" + i + "\"")
                .collect(Collectors.joining(","));
        JsonNode bigValue = tree("{\"source\":\"header\",\"name\":\"X-Region\",\"op\":\"in\",\"value\":["
                + longList + "]}", null, null, null);
        assertThat(bigValue.get("valueOmitted").asBoolean()).isTrue();
        assertThat(bigValue.get("spec").has("value")).isFalse();
        assertThat(bigValue.get("spec").get("op").asText()).isEqualTo("in");

        String longLeaves = IntStream.range(0, 100)
                .mapToObj(i -> "{\"source\":\"header\",\"name\":\"H" + i + "\",\"op\":\"equals\",\"value\":\""
                        + "v".repeat(200) + "\"}")
                .collect(Collectors.joining(","));
        String bigTree = MatchExplanationJson.toJson(matcher.explainPrepared("{\"any\":[" + longLeaves + "]}",
                null, null, null, matcher.prepareBody(BODY), null, HEADERS));
        assertThat(MAPPER.readTree(bigTree).get("truncated").asBoolean()).isTrue();
        assertThat(bigTree.length()).isLessThan(MatchExplanationJson.MAX_TREE_CHARS + 1_000);

        String longCondition = "customer.type=" + "x".repeat(1_000);
        JsonNode longText = tree(null, longCondition, null, null);
        assertThat(longText.get("children").get(0).get("condition").asText())
                .hasSize(MatchExplanationJson.MAX_TEXT_LENGTH + 1).endsWith("…");
    }
}
