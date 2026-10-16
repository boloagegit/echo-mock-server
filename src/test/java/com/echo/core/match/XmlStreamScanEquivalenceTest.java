package com.echo.core.match;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

import javax.xml.stream.XMLStreamException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 串流掃描（{@link XmlStreamScan}）與 DOM 取值必須得到相同結果：同一組 XML 與條件，
 * 以串流預先算出的結果要等於在 DOM 上實際評估的結果（舊版條件與 XPath 結構化條件都測）。
 */
class XmlStreamScanEquivalenceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MatcherCompiler COMPILER = MatcherCompiler.withDefaults();

    private static final List<String> NAMES = List.of("Order", "Item", "Status", "a", "b");
    private static final List<String> PREFIXES = List.of("", "", "ns:", "x:");
    private static final List<String> TEXTS = List.of("OPEN", "5", "1000", "vip", "a&amp;b", "  x  ", "", "&lt;t&gt;",
            "&#65;B", "<![CDATA[ OPEN ]]>", "<!-- c -->OPEN", "1&amp;2");
    private static final List<String> PATHS = List.of("Item", "Status", "Order/Status", "//Order/Status", "/Order/Status",
            "/Order", "//Item", "//Item/@sku", "Item/@id", "//a/b", "a/b/Status", "//Status/text()", "b", "//b/@id");
    private static final List<String> LEGACY_OPS = List.of("=", "!=", "*=", "~=");
    private static final List<String> VALUES = List.of("OPEN", "5", "vip", "a&b", "x", "", "<t>", "AB", "O.*", "1&2");

    @Property(tries = 400)
    void streamingGivesTheSameResultsAsDom(@ForAll("documents") String xml, @ForAll("legacyConditions") List<String> legacy,
                                          @ForAll("xpathLeaves") List<String> leaves) {
        List<CompiledMatcher> matchers = new ArrayList<>();
        for (String condition : legacy) {
            matchers.add(legacy(condition));
        }
        for (String leaf : leaves) {
            matchers.add(COMPILER.compile(read(leaf)));
        }
        assertEquivalent(xml, matchers);
    }

    @Test
    void edgeCasesFromTheUnificationDecision() {
        String mixed = "<r xmlns:ns=\"u\"><ns:Item sku=\"S1\">5</ns:Item><Item ns:sku=\"S2\">2</Item></r>";
        String relative = "<Order><Status>OPEN</Status></Order>";
        assertEquivalent(mixed, List.of(legacy("Item=5"), legacy("//Item=5"), legacy("//Item/@sku=S2"),
                legacy("Item==5"), xpath("//Item", "equals", "\"5\""), xpath("//Item/@sku", "equals", "\"S2\"")));
        assertEquivalent(relative, List.of(legacy("Order/Status=OPEN"), legacy("Order/Status==OPEN"),
                xpath("Order/Status", "equals", "\"OPEN\"")));
        // 統一後的結果：前綴不同的同名元素都算、相對路徑是結尾符合、== 視為 =
        assertThat(domResult(mixed, legacy("Item=5"))).isTrue();
        assertThat(domResult(relative, legacy("Order/Status=OPEN"))).isTrue();
        assertThat(domResult(relative, legacy("Order/Status==OPEN"))).isTrue();
    }

    @Test
    void tooLongValuesAreSkippedTheSameWay() {
        MatcherCompiler small = new MatcherCompiler(MatcherRegistry.defaults(),
                MatcherLimits.DEFAULT.withMaxFieldValueLength(3));
        String xml = "<r><Item>long-value</Item><Item>ok</Item><Big>long-value</Big></r>";
        List<CompiledMatcher> matchers = List.of(
                small.compile(new MatchNode.Legacy(MatchNode.LegacySource.BODY, "Item=ok")),
                small.compile(new MatchNode.Legacy(MatchNode.LegacySource.BODY, "Big!=x")),
                small.compile(read("{\"source\":\"body\",\"selector\":{\"xpath\":\"//Big\"},\"op\":\"absent\"}")),
                small.compile(read("{\"source\":\"body\",\"selector\":{\"xpath\":\"//Big\"},\"op\":\"notEquals\","
                        + "\"value\":\"x\"}")),
                small.compile(read("{\"source\":\"body\",\"selector\":{\"xpath\":\"//Item\"},\"op\":\"equals\","
                        + "\"value\":\"ok\",\"quantifier\":\"all\"}")));
        assertEquivalent(xml, matchers, 3);
    }

    // ==================== 比對 ====================

    private static void assertEquivalent(String xml, List<CompiledMatcher> matchers) {
        assertEquivalent(xml, matchers, MatcherLimits.DEFAULT.maxFieldValueLength());
    }

    private static void assertEquivalent(String xml, List<CompiledMatcher> matchers, int maxValueLength) {
        List<BodyRequirements> requirements = matchers.stream().map(CompiledMatcher::bodyRequirements).toList();
        BodyRequirements merged = BodyRequirements.merge(requirements);
        assertThat(merged.needsXmlDom()).as("all conditions are streamable").isFalse();
        Map<String, Boolean> streamed;
        try {
            streamed = XmlStreamScan.evaluate(xml, merged, maxValueLength);
        } catch (XMLStreamException e) {
            return; // 串流無法處理時呼叫端改用 DOM，沒有可比較的串流結果
        }
        RequestView request = RequestView.of("POST", "/", null, Map.of(), BodyView.of(xml, MatcherLimits.DEFAULT));
        for (CompiledMatcher matcher : matchers) {
            boolean dom = matcher.matches(new EvalContext(request));
            assertThat(streamed.get(matcher.cacheKey())).as("%s on %s", matcher.cacheKey(), xml).isEqualTo(dom);
            // 預先填入串流結果後，比對直接使用該結果
            boolean prefilled = matcher.matches(new EvalContext(request, new LinkedHashMap<>(streamed)));
            assertThat(prefilled).isEqualTo(dom);
        }
    }

    private static boolean domResult(String xml, CompiledMatcher matcher) {
        return matcher.matches(new EvalContext(RequestView.of("POST", "/", null, Map.of(),
                BodyView.of(xml, MatcherLimits.DEFAULT))));
    }

    private static CompiledMatcher legacy(String condition) {
        return COMPILER.compile(new MatchNode.Legacy(MatchNode.LegacySource.BODY, condition));
    }

    private static CompiledMatcher xpath(String path, String op, String valueJson) {
        return COMPILER.compile(read("{\"source\":\"body\",\"selector\":{\"xpath\":\"" + path + "\"},\"op\":\"" + op
                + "\",\"value\":" + valueJson + "}"));
    }

    private static MatchNode read(String json) {
        try {
            return MatchSpecCodec.parse(MAPPER.readTree(json));
        } catch (Exception e) {
            throw new IllegalArgumentException(json, e);
        }
    }

    // ==================== 產生器 ====================

    @Provide
    Arbitrary<String> documents() {
        // 根元素宣告兩個前綴，所有元素與屬性的前綴都有效
        return element(3).map(root -> root.replaceFirst(">", " xmlns:ns=\"urn:ns\" xmlns:x=\"urn:x\">"));
    }

    private Arbitrary<String> element(int depth) {
        Arbitrary<String> name = Combinators.combine(Arbitraries.of(PREFIXES), Arbitraries.of(NAMES)).as(String::concat);
        Arbitrary<String> attributes = Arbitraries.of("", " id=\"5\"", " sku=\"S1\"", " ns:id=\"OPEN\"", " id=\" x \"",
                " sku=\"a&amp;b\"");
        Arbitrary<String> text = Arbitraries.of(TEXTS);
        if (depth == 0) {
            return Combinators.combine(name, attributes, text).as((n, a, t) -> "<" + n + a + ">" + t + "</" + n + ">");
        }
        Arbitrary<List<String>> children = Arbitraries.lazy(() -> element(depth - 1)).list().ofMaxSize(3);
        return Combinators.combine(name, attributes, text, children, Arbitraries.of(TEXTS))
                .as((n, a, t, kids, tail) -> "<" + n + a + ">" + t + String.join("", kids) + tail + "</" + n + ">");
    }

    @Provide
    Arbitrary<List<String>> legacyConditions() {
        return Combinators.combine(Arbitraries.of(PATHS), Arbitraries.of(LEGACY_OPS), Arbitraries.of(VALUES),
                        Arbitraries.of("", "=")).as((path, op, value, doubled) -> path + op
                        + ("=".equals(op) ? doubled : "") + value)
                .list().ofMinSize(1).ofMaxSize(4);
    }

    @Provide
    Arbitrary<List<String>> xpathLeaves() {
        Arbitrary<String> opAndValue = Arbitraries.of("\"equals\",\"value\":\"OPEN\"", "\"equals\",\"value\":\"5\"",
                "\"notEquals\",\"value\":\"x\"", "\"contains\",\"value\":\"a&b\"", "\"contains\",\"value\":\"OP\"",
                "\"gt\",\"value\":5", "\"lte\",\"value\":1000", "\"in\",\"value\":[\"vip\",\"5\"]",
                "\"matches\",\"value\":\"O.*\"");
        Arbitrary<String> withValue = Combinators.combine(Arbitraries.of(PATHS), opAndValue,
                Arbitraries.of("any", "all"), Arbitraries.of("auto", "pass", "fail"))
                .as((path, opValue, quantifier, onMissing) -> "{\"source\":\"body\",\"selector\":{\"xpath\":\"" + path
                        + "\"},\"op\":" + opValue + ",\"quantifier\":\"" + quantifier
                        + "\",\"onMissing\":\"" + onMissing + "\"}");
        Arbitrary<String> presence = Combinators.combine(Arbitraries.of(PATHS), Arbitraries.of("exists", "absent"))
                .as((path, op) -> "{\"source\":\"body\",\"selector\":{\"xpath\":\"" + path + "\"},\"op\":\"" + op + "\"}");
        return Arbitraries.oneOf(withValue, presence).list().ofMaxSize(4);
    }
}
