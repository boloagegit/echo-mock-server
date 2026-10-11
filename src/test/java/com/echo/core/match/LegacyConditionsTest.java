package com.echo.core.match;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegacyConditionsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void splitsFragmentsInLegacyEvaluationOrder() {
        MatchNode.All node = LegacyConditions.toMatchNode(" a=1 ;; b=2 ", "q=1", "X-Type=vip; ");

        assertThat(node.children()).containsExactly(
                new MatchNode.Legacy(MatchNode.LegacySource.BODY, "a=1"),
                new MatchNode.Legacy(MatchNode.LegacySource.BODY, "b=2"),
                new MatchNode.Legacy(MatchNode.LegacySource.QUERY, "q=1"),
                new MatchNode.Legacy(MatchNode.LegacySource.HEADER, "X-Type=vip"));
    }

    @Test
    void ruleWithoutConditionsIsUnconditional() {
        MatchNode.All node = LegacyConditions.toMatchNode(null, " ", ";");

        assertThat(node.children()).isEmpty();
        assertThat(MatcherCompiler.withDefaults().compile(node).isUnconditional()).isTrue();
        assertThat(LegacyConditions.toStrings(node)).contains(new LegacyConditions.Strings(null, null, null));
    }

    @Test
    void roundTripsToNormalizedStrings() {
        MatchNode.All node = LegacyConditions.toMatchNode(" a=1 ;; b=2 ", null, "X-Type=vip");

        assertThat(LegacyConditions.toStrings(node))
                .contains(new LegacyConditions.Strings("a=1;b=2", null, "X-Type=vip"));
    }

    @Test
    void treesUsingNewFeaturesCannotBeWrittenBackAsStrings() {
        MatchNode legacy = new MatchNode.Legacy(MatchNode.LegacySource.BODY, "a=1");
        MatchNode leaf = MatchSpecCodec.parse(json("{'source':'method','op':'equals','value':'GET'}"));

        assertThat(LegacyConditions.toStrings(new MatchNode.All(List.of(legacy, leaf)))).isEmpty();
        assertThat(LegacyConditions.toStrings(new MatchNode.Any(List.of(legacy)))).isEmpty();
        assertThat(LegacyConditions.toStrings(new MatchNode.Not(legacy))).isEmpty();
    }

    @Test
    void legacyFragmentsCanBeMixedWithStructuredConditions() {
        CompiledMatcher matcher = MatcherCompiler.withDefaults().compile(json("""
                {'all': [
                  {'legacy': {'source': 'body', 'condition': 'user.type=vip'}},
                  {'source': 'method', 'op': 'equals', 'value': 'POST'}
                ]}"""));
        RequestView request = RequestView.of("POST", "/", null, Map.of(),
                BodyView.of("{\"user\":{\"type\":\"vip\"}}", MatcherLimits.DEFAULT));

        assertThat(matcher.matches(new EvalContext(request))).isTrue();
    }

    @Test
    void jsonFormRoundTrips() {
        JsonNode spec = json("{'legacy': {'source': 'header', 'condition': 'X-Type~=v.*'}}");

        MatchNode node = MatchSpecCodec.parse(spec);

        assertThat(node).isEqualTo(new MatchNode.Legacy(MatchNode.LegacySource.HEADER, "X-Type~=v.*"));
        assertThat(MatchSpecCodec.toJson(node)).isEqualTo(spec);
    }

    @Test
    void rejectsMalformedLegacyJson() {
        assertThatThrownBy(() -> MatchSpecCodec.parse(json("{'legacy': {'source': 'cookie', 'condition': 'a=1'}}")))
                .isInstanceOf(InvalidMatchSpecException.class)
                .hasMessageStartingWith("$.legacy.source");
        assertThatThrownBy(() -> MatchSpecCodec.parse(json("{'legacy': {'source': 'body', 'condition': 'a=1;b=2'}}")))
                .isInstanceOf(InvalidMatchSpecException.class)
                .hasMessageStartingWith("$.legacy.condition");
        assertThatThrownBy(() -> MatchSpecCodec.parse(json("{'legacy': {'source': 'body'}}")))
                .isInstanceOf(InvalidMatchSpecException.class)
                .hasMessageStartingWith("$.legacy");
    }

    @Test
    void cacheKeysMatchLegacyPreparedBodyKeys() {
        CompiledMatcher matcher = MatcherCompiler.withDefaults()
                .compile(LegacyConditions.toMatchNode("a=1", "q=1", "h=1"));
        EvalContext context = new EvalContext(RequestView.of("GET", "/", "q=1", Map.of("h", "1"),
                BodyView.of("{\"a\":1}", MatcherLimits.DEFAULT)));

        assertThat(matcher.matches(context)).isTrue();
        assertThat(context.outcomesSnapshot()).containsOnlyKeys("body:a=1", "query:q=1", "header:h=1");
    }

    @Test
    void formatsLegacyDetailText() {
        CompiledMatcher matcher = MatcherCompiler.withDefaults()
                .compile(LegacyConditions.toMatchNode("user.type=vip", null, null));
        MatchExplanation explanation = matcher.explain(new EvalContext(RequestView.of("POST", "/", null, Map.of(),
                BodyView.of("{\"user\":{\"type\":\"normal\"}}", MatcherLimits.DEFAULT))));

        assertThat(LegacyConditions.legacyDetail(explanation.children().get(0)))
                .contains("bodyCondition FAIL (expected user.type=vip, actual user.type=normal)");
        assertThat(LegacyConditions.legacyDetail(explanation)).isEmpty();
    }

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text.replace('\'', '"'));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
