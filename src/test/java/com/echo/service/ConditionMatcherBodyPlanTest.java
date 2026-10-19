package com.echo.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Prepare body as required by candidate rule conditions (§4.5, §7.2.9): no parsing, streaming scan, or full parsing. */
class ConditionMatcherBodyPlanTest {

    private static final String XML = "<Order><Header><ServiceName>Pay</ServiceName></Header>"
            + "<Item sku=\"S1\">2</Item><Item sku=\"S2\">5</Item></Order>";
    private static final String SIMPLE_XPATH_SPEC = """
            {"source": "body", "selector": {"xpath": "//Item/@sku"}, "op": "equals", "value": "S2"}""";
    private static final String COMPLEX_XPATH_SPEC = """
            {"source": "body", "selector": {"xpath": "//Item[2]"}, "op": "equals", "value": "5"}""";

    private final ConditionMatcher matcher = new ConditionMatcher();

    private ConditionMatcher.PreparedBody prepare(String body, ConditionMatcher.RuleConditions... conditions) {
        return matcher.prepareBody(body, List.of(conditions));
    }

    @Test
    void conditionsThatDoNotReadTheBodySkipParsing() {
        ConditionMatcher.PreparedBody prepared = prepare(XML, matcher.conditions(null, "page=1", "X-Type=vip"));

        assertThat(prepared.getXmlDoc()).isNull();
        assertThat(prepared.getJsonNode()).isNull();
        assertThat(prepared.getRaw()).isEqualTo(XML);
    }

    @Test
    void streamableLegacyAndSimpleXPathConditionsScanXmlWithoutDom() {
        ConditionMatcher.RuleConditions legacy = matcher.conditions("ServiceName=Pay", null, null);
        ConditionMatcher.RuleConditions advanced = matcher.conditions(SIMPLE_XPATH_SPEC, null, null, null);

        ConditionMatcher.PreparedBody prepared = prepare(XML, legacy, advanced);

        assertThat(prepared.getXmlDoc()).isNull();
        // The result has been calculated by stream scanning and can be used directly during comparison.
        assertThat(prepared.getMatchOutcomesSnapshot()).containsEntry("body:ServiceName=Pay", true).hasSize(2);
        assertThat(matcher.matchesPrepared("ServiceName=Pay", null, null, prepared, null, null)).isTrue();
        assertThat(matcher.matchesPrepared(SIMPLE_XPATH_SPEC, null, null, null, prepared, null, null)).isTrue();
        assertThat(prepared.getXmlDoc()).as("matching used the streamed outcomes").isNull();
    }

    @Test
    void anyConditionNeedingDomBuildsOneSharedDom() {
        ConditionMatcher.PreparedBody prepared = prepare(XML, matcher.conditions("ServiceName=Pay", null, null),
                matcher.conditions(COMPLEX_XPATH_SPEC, null, null, null));

        assertThat(prepared.getXmlDoc()).isNotNull();
        assertThat(matcher.matchesPrepared(COMPLEX_XPATH_SPEC, null, null, null, prepared, null, null)).isTrue();
        assertThat(matcher.matchesPrepared("ServiceName=Pay", null, null, prepared, null, null)).isTrue();
    }

    @Test
    void xmlThatCannotBeStreamedFallsBackToNormalParsing() {
        // namespace prefix is not announced: StAX cannot be processed, DOM (regardless of namespace) can be processed
        String unbound = "<ns:Order><ns:Status>OPEN</ns:Status></ns:Order>";
        ConditionMatcher.PreparedBody prepared = prepare(unbound, matcher.conditions("Status=OPEN", null, null));

        assertThat(prepared.getXmlDoc()).isNotNull();
        assertThat(matcher.matchesPrepared("Status=OPEN", null, null, prepared, null, null)).isTrue();

        String notXml = "<<not xml";
        ConditionMatcher.PreparedBody plain = prepare(notXml, matcher.conditions("<<not", null, null));
        assertThat(matcher.matchesPrepared("<<not", null, null, plain, null, null)).isTrue();
    }

    @Test
    void jsonBodiesAreParsedAsJson() {
        ConditionMatcher.PreparedBody prepared = prepare("{\"user\":{\"type\":\"vip\"}}",
                matcher.conditions("user.type=vip", null, null));

        assertThat(prepared.getJsonNode()).isNotNull();
        assertThat(matcher.matchesPrepared("user.type=vip", null, null, prepared, null, null)).isTrue();
    }

    @Test
    void streamedBodyStillExplainsWithTheSameActualValues() {
        ConditionMatcher.RuleConditions legacy = matcher.conditions("Item=5", null, null);
        ConditionMatcher.PreparedBody streamed = prepare(XML, legacy);
        ConditionMatcher.PreparedBody parsed = matcher.prepareBody(XML);

        ConditionMatcher.ConditionDetail fromStream = matcher.matchesPreparedWithDetail("Item=5", null, null,
                streamed, null, Map.of());
        ConditionMatcher.ConditionDetail fromDom = matcher.matchesPreparedWithDetail("Item=5", null, null,
                parsed, null, Map.of());

        assertThat(fromStream.isOverallMatch()).isTrue().isEqualTo(fromDom.isOverallMatch());
        assertThat(fromStream.getResults()).usingRecursiveFieldByFieldElementComparator()
                .isEqualTo(fromDom.getResults());
    }
}
