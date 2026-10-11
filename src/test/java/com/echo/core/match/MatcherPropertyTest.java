package com.echo.core.match;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 組合節點必須遵守布林代數的基本定律，避免巢狀條件出現意外結果。 */
class MatcherPropertyTest {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final List<String> VALUES = List.of("", "a", "ab", "b", "10", "9");

    private final MatcherCompiler compiler = MatcherCompiler.withDefaults();

    @Property(tries = 300)
    void doubleNegationIsIdentity(@ForAll("leaves") JsonNode leaf, @ForAll("headers") Map<String, String> headers) {
        assertThat(eval(not(not(leaf)), headers)).isEqualTo(eval(leaf, headers));
    }

    @Property(tries = 300)
    void deMorganHolds(@ForAll("leaves") JsonNode a, @ForAll("leaves") JsonNode b,
                       @ForAll("headers") Map<String, String> headers) {
        assertThat(eval(not(group("all", a, b)), headers))
                .isEqualTo(eval(group("any", not(a), not(b)), headers));
        assertThat(eval(not(group("any", a, b)), headers))
                .isEqualTo(eval(group("all", not(a), not(b)), headers));
    }

    @Property(tries = 300)
    void singleChildGroupsAreIdentity(@ForAll("leaves") JsonNode leaf, @ForAll("headers") Map<String, String> headers) {
        boolean expected = eval(leaf, headers);
        assertThat(eval(group("all", leaf), headers)).isEqualTo(expected);
        assertThat(eval(group("any", leaf), headers)).isEqualTo(expected);
    }

    @Property(tries = 300)
    void explainAgreesWithMatches(@ForAll("leaves") JsonNode a, @ForAll("leaves") JsonNode b,
                                  @ForAll("headers") Map<String, String> headers) {
        CompiledMatcher matcher = compiler.compile(group("all", a, not(b)));
        RequestView request = RequestView.of("GET", "/", null, headers, BodyView.empty());

        assertThat(matcher.explain(new EvalContext(request)).passed())
                .isEqualTo(matcher.matches(new EvalContext(request)));
    }

    @Provide
    Arbitrary<JsonNode> leaves() {
        Arbitrary<String> names = Arbitraries.of("h1", "h2");
        Arbitrary<String> ops = Arbitraries.of("equals", "notEquals", "contains", "exists", "absent", "gt", "in");
        Arbitrary<String> values = Arbitraries.of(VALUES);
        return Combinators.combine(names, ops, values).as((name, op, value) -> {
            ObjectNode leaf = NODES.objectNode().put("source", "header").put("name", name).put("op", op);
            if ("in".equals(op)) {
                leaf.putArray("value").add(value).add("b");
            } else if ("gt".equals(op)) {
                leaf.put("value", 9);
            } else if (!"exists".equals(op) && !"absent".equals(op)) {
                leaf.put("value", value);
            }
            return leaf;
        });
    }

    @Provide
    Arbitrary<Map<String, String>> headers() {
        Arbitrary<String> value = Arbitraries.of(VALUES).injectNull(0.3);
        return Combinators.combine(value, value).as((h1, h2) -> {
            Map<String, String> headers = new HashMap<>();
            if (h1 != null) {
                headers.put("h1", h1);
            }
            if (h2 != null) {
                headers.put("h2", h2);
            }
            return headers;
        });
    }

    private boolean eval(JsonNode spec, Map<String, String> headers) {
        RequestView request = RequestView.of("GET", "/", null, headers, BodyView.empty());
        return compiler.compile(spec).matches(new EvalContext(request));
    }

    private static JsonNode not(JsonNode child) {
        ObjectNode node = NODES.objectNode();
        node.set("not", child);
        return node;
    }

    private static JsonNode group(String kind, JsonNode... children) {
        ObjectNode node = NODES.objectNode();
        node.putArray(kind).addAll(List.of(children));
        return node;
    }
}
