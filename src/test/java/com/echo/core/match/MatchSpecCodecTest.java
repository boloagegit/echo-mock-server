package com.echo.core.match;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MatchSpecCodecTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String text) throws Exception {
        return MAPPER.readTree(text.replace('\'', '"'));
    }

    @Test
    void parsesNestedTree() throws Exception {
        MatchNode node = MatchSpecCodec.parse(json("""
                {'all': [
                  {'source': 'header', 'name': 'X-Tenant', 'op': 'equals', 'value': 'acme',
                   'options': {'caseInsensitive': true}},
                  {'any': [
                    {'source': 'body', 'selector': {'jsonPath': '$.amount'}, 'op': 'gte', 'value': 1000},
                    {'source': 'query', 'name': 'vip', 'op': 'exists'}
                  ]},
                  {'not': {'source': 'body', 'selector': {'raw': true}, 'op': 'contains', 'value': 'CANCELLED',
                           'quantifier': 'all', 'onMissing': 'pass'}}
                ]}"""));

        assertThat(node).isInstanceOf(MatchNode.All.class);
        MatchNode.All all = (MatchNode.All) node;
        assertThat(all.children()).hasSize(3);

        MatchNode.Leaf header = (MatchNode.Leaf) all.children().get(0);
        assertThat(header.source()).isEqualTo("header");
        assertThat(header.name()).isEqualTo("X-Tenant");
        assertThat(header.value().asText()).isEqualTo("acme");
        assertThat(header.options().get("caseInsensitive").booleanValue()).isTrue();
        assertThat(header.quantifier()).isEqualTo(MatchNode.Quantifier.ANY);
        assertThat(header.onMissing()).isEqualTo(MatchNode.OnMissing.AUTO);

        MatchNode.Any any = (MatchNode.Any) all.children().get(1);
        MatchNode.Leaf amount = (MatchNode.Leaf) any.children().get(0);
        assertThat(amount.selector()).isEqualTo(new Selector(Selector.Kind.JSON_PATH, "$.amount"));
        assertThat(((MatchNode.Leaf) any.children().get(1)).value()).isNull();

        MatchNode.Leaf raw = (MatchNode.Leaf) ((MatchNode.Not) all.children().get(2)).child();
        assertThat(raw.selector()).isEqualTo(Selector.raw());
        assertThat(raw.quantifier()).isEqualTo(MatchNode.Quantifier.ALL);
        assertThat(raw.onMissing()).isEqualTo(MatchNode.OnMissing.PASS);
    }

    @Test
    void canonicalJsonRoundTripsAndOmitsDefaults() throws Exception {
        JsonNode original = json("""
                {'any': [
                  {'source': 'header', 'name': 'A', 'op': 'equals', 'value': 'x',
                   'quantifier': 'any', 'onMissing': 'auto',
                   'options': {'caseInsensitive': true}},
                  {'not': {'source': 'body', 'selector': {'xpath': '//Status'}, 'op': 'equals', 'value': 'X',
                           'quantifier': 'all'}}
                ]}""");

        JsonNode canonical = MatchSpecCodec.toJson(MatchSpecCodec.parse(original));

        assertThat(canonical).isEqualTo(json("""
                {'any': [
                  {'source': 'header', 'name': 'A', 'op': 'equals', 'value': 'x',
                   'options': {'caseInsensitive': true}},
                  {'not': {'source': 'body', 'selector': {'xpath': '//Status'}, 'op': 'equals', 'value': 'X',
                           'quantifier': 'all'}}
                ]}"""));
        assertThat(MatchSpecCodec.toJson(MatchSpecCodec.parse(canonical))).isEqualTo(canonical);
    }

    @Test
    void canonicalJsonSortsOptions() throws Exception {
        JsonNode a = MatchSpecCodec.toJson(MatchSpecCodec.parse(json(
                "{'source':'body','selector':{'raw':true},'op':'equalToJson','value':{},"
                        + "'options':{'ignoreExtraElements':true,'ignoreArrayOrder':true}}")));
        JsonNode b = MatchSpecCodec.toJson(MatchSpecCodec.parse(json(
                "{'source':'body','selector':{'raw':true},'op':'equalToJson','value':{},"
                        + "'options':{'ignoreArrayOrder':true,'ignoreExtraElements':true}}")));

        assertThat(a.toString()).isEqualTo(b.toString());
    }

    @Test
    void leafIsImmutable() throws Exception {
        MatchNode.Leaf leaf = (MatchNode.Leaf) MatchSpecCodec.parse(json(
                "{'source':'body','selector':{'raw':true},'op':'equalToJson','value':{'a':1}}"));

        ((ObjectNode) leaf.value()).put("a", 2);

        assertThat(leaf.value().get("a").intValue()).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            "[]                                                              | $",
            "{'all': {}}                                                     | $.all",
            "{'all': [], 'any': []}                                          | $",
            "{'not': {'source': 'query', 'name': 'a', 'op': 'exists'}, 'x': 1} | $",
            "{'all': [{'source': 'header', 'op': 'equals', 'oops': 1}]}       | $.all[0].oops",
            "{'all': [{'op': 'equals'}]}                                     | $.all[0].source",
            "{'source': 'header', 'name': 'a'}                               | $.op",
            "{'source': 'header', 'name': '', 'op': 'exists'}                | $.name",
            "{'source': 'header', 'name': 'a', 'op': 'exists', 'quantifier': 'some'} | $.quantifier",
            "{'source': 'header', 'name': 'a', 'op': 'exists', 'onMissing': 'maybe'} | $.onMissing",
            "{'source': 'header', 'name': 'a', 'op': 'exists', 'options': []} | $.options",
            "{'source': 'body', 'selector': {'css': 'a'}, 'op': 'exists'}     | $.selector.css",
            "{'source': 'body', 'selector': {'jsonPath': '$.a', 'xpath': '/a'}, 'op': 'exists'} | $.selector",
            "{'source': 'body', 'selector': {'raw': false}, 'op': 'exists'}   | $.selector.raw",
            "{'source': 'body', 'selector': {'jsonPath': ' '}, 'op': 'exists'} | $.selector.jsonPath"
    })
    void rejectsMalformedSpecWithPath(String spec, String expectedPath) {
        assertThatThrownBy(() -> MatchSpecCodec.parse(json(spec)))
                .isInstanceOf(InvalidMatchSpecException.class)
                .satisfies(e -> assertThat(((InvalidMatchSpecException) e).path()).isEqualTo(expectedPath));
    }
}
