package com.echo.core.match;

import com.echo.service.ConditionMatcher;
import com.echo.service.LegacyConditionOracle;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Difference test: The original implementation of the old version comparison ({@link LegacyConditionOracle}) is the standard answer, the old syntax compatibility layer of the new engine must be in
 * Whether it is met, the description text of each condition, and the score are completely consistent with the temporary storage key.
 * <p>
 * The only deliberate difference is that the old version will throw an exception when the expected value is only one {@code "}, the generator does not produce this value
 * (see {@link LegacyConditionMatcher}).
 */
class LegacyDifferentialTest {

    private static final String JSON_USER = """
            {"user":{"type":"vip","name":"Alice Smith","age":30,"tags":["a","b"],"active":true,"note":null},\
            "items":[{"sku":"S1","qty":2},{"sku":"S2","qty":5}],"code":"A=B","empty":"","n":1.50}""";
    private static final String XML_ORDER = """
            <Order><Status>OPEN</Status><Item sku="S1">2</Item><Item sku="S2">5</Item>\
            <Customer><Type>vip</Type></Customer></Order>""";
    private static final String XML_SOAP = """
            <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"><soap:Body>\
            <ns:Req xmlns:ns="urn:x"><ns:ServiceName>OrderService</ns:ServiceName><ns:Type>vip</ns:Type>\
            </ns:Req></soap:Body></soap:Envelope>""";

    private static final List<String> BODIES = List.of(
            JSON_USER,
            "  " + JSON_USER + "\n",
            "[{\"sku\":\"S1\"},{\"sku\":\"S2\"}]",
            "{\"a\":{\"b\":{\"c\":\"deep\"}},\"type\":\"vip\",\"s\":\"x y\"}",
            "{ \"type\" : \"normal\" }",
            "[]",
            "{bad json",
            XML_ORDER,
            XML_SOAP,
            "<Root xmlns=\"urn:default\"><Status>CLOSED</Status><Type>vip</Type></Root>",
            "<a:Root xmlns:a=\"u1\" xmlns:b=\"u2\"><a:Item>1</a:Item><b:Item>2</b:Item><Item>3</Item>"
                    + "<c:d:Item xmlns:c=\"u3\">4</c:d:Item><b:Type> vip </b:Type></a:Root>",
            "<?xml version=\"1.0\"?><a><b>1</b><b>2</b></a>",
            "<broken><xml>",
            "hello world",
            "type=vip&user.type=vip",
            "",
            "   ");

    private static final List<String> BODY_FIELDS = List.of(
            "user.type", "body.user.type", "user.age", "user.tags", "user.tags[0]", "user.tags[9]",
            "items[1].sku", "items[x].sku", "items[0", "user.note", "user", "code", "n", "a.b.c", "type", "missing",
            "$.user.type", "$.items[*].sku", "$..sku", "$.user.note", "$.missing", "$[0].sku", "$.n",
            "$[?(@.sku=='S1')].sku", "$.[", "//Status", "Status", "/Order/Status", "//Item/@sku", "Item",
            "//Customer/Type", "ServiceName", "ns:ServiceName", "//ServiceName", "/Envelope/Body/Req/Type",
            "//*[local-name()='Type']", "Type", "b", "//b", "hello", "world", "x y", "it's",
            "d:Item", "a:Item", "Root", "//Item", "/Root/Item");
    private static final List<String> OPS = List.of("=", "!=", "*=", "~=", "==", "!==", " = ", "");
    private static final List<String> VALUES = List.of(
            "vip", "\"vip\"", "normal", "OPEN", "CLOSED", "S1", "S2", "5", "2", "30", "1.5", "1.50", "true",
            "null", "", "\"\"", "Alice Smith", "Ali", "v.p", ".*", "[", "deep", "OrderService", "a", "B",
            "[\"a\",\"b\"]", "x", "1", "3", "4");
    private static final List<String> RAW_FRAGMENTS = List.of(
            "hello", "world", "=vip", "==vip", "type=vip", "user.type=vip", "~=v.*", "a=b=c");

    private static final List<String> QUERY_STRINGS = List.of(
            "", "type=vip", "type=vip&a=1", "a=1&a=2", "flag", "=x", "type=", "type=v%20p", "a=1&type=normal");
    private static final List<String> QUERY_FIELDS = List.of("type", "a", "flag", "x", "");

    private static final List<Map<String, String>> HEADERS = List.of(
            Map.of(),
            Map.of("X-Type", "vip"),
            Map.of("x-type", "normal", "Content-Type", "application/json"),
            Map.of("Authorization", "Bearer abc", "X-Type", "VIP"));
    private static final List<String> HEADER_FIELDS = List.of("X-Type", "x-type", "content-type", "Missing");
    private static final List<String> HEADER_VALUES =
            List.of("vip", "VIP", "normal", "application/json", "json", "Bearer.*", "");

    // ==================== Random difference test ====================

    @Property(tries = 4000)
    void legacyLayerMatchesConditionMatcher(@ForAll("bodyConditions") String bodyCondition,
                                            @ForAll("queryConditions") String queryCondition,
                                            @ForAll("headerConditions") String headerCondition,
                                            @ForAll("requests") Tuple.Tuple3<String, String, Map<String, String>> request) {
        assertEquivalent(new Case(bodyCondition, queryCondition, headerCondition,
                request.get1(), request.get2(), request.get3()), MatcherLimits.DEFAULT.maxFieldValueLength());
    }

    @Property(tries = 1000)
    void legacyLayerHonorsFieldValueLimit(@ForAll("bodyConditions") String bodyCondition,
                                          @ForAll("requests") Tuple.Tuple3<String, String, Map<String, String>> request) {
        assertEquivalent(new Case(bodyCondition, null, null, request.get1(), request.get2(), request.get3()), 3);
    }

    @Provide
    Arbitrary<String> bodyConditions() {
        Arbitrary<String> structured = Combinators.combine(
                Arbitraries.of(BODY_FIELDS), Arbitraries.of(OPS), Arbitraries.of(VALUES))
                .as((field, op, value) -> op.isEmpty() ? field : field + op + value);
        return joinedFragments(Arbitraries.oneOf(structured, Arbitraries.of(RAW_FRAGMENTS)));
    }

    @Provide
    Arbitrary<String> queryConditions() {
        return joinedFragments(Combinators.combine(
                Arbitraries.of(QUERY_FIELDS), Arbitraries.of(OPS), Arbitraries.of(List.of("vip", "1", "2", "", "v.*")))
                .as((field, op, value) -> op.isEmpty() ? field : field + op + value));
    }

    @Provide
    Arbitrary<String> headerConditions() {
        return joinedFragments(Combinators.combine(
                Arbitraries.of(HEADER_FIELDS), Arbitraries.of(OPS), Arbitraries.of(HEADER_VALUES))
                .as((field, op, value) -> op.isEmpty() ? field : field + op + value));
    }

    @Provide
    Arbitrary<Tuple.Tuple3<String, String, Map<String, String>>> requests() {
        return Combinators.combine(
                Arbitraries.of(BODIES).injectNull(0.05),
                Arbitraries.of(QUERY_STRINGS).injectNull(0.1),
                Arbitraries.of(HEADERS))
                .as(Tuple::of);
    }

    /** 0~3 fragments, concatenated with {@code ;}, {@code " ; "} or {@code ;;}; null and blank strings will also be generated. */
    private static Arbitrary<String> joinedFragments(Arbitrary<String> fragment) {
        Arbitrary<String> joined = Combinators.combine(
                fragment.list().ofMinSize(1).ofMaxSize(3), Arbitraries.of(";", " ; ", ";;"))
                .as((parts, separator) -> String.join(separator, parts));
        return Arbitraries.frequencyOf(
                Tuple.of(1, Arbitraries.of("", " ", ";")),
                Tuple.of(8, joined)).injectNull(0.1);
    }

    // ==================== Specified edge case ====================

    @ParameterizedTest
    @MethodSource("edgeCases")
    void legacyLayerMatchesConditionMatcherOnEdgeCases(Case edgeCase) {
        assertEquivalent(edgeCase, MatcherLimits.DEFAULT.maxFieldValueLength());
    }

    static Stream<Case> edgeCases() {
        Map<String, String> none = Map.of();
        return Stream.of(
                // When the JSONPath field does not exist, != does not match either; point path does
                body("$.user.missing!=x", JSON_USER),
                body("user.missing!=x", JSON_USER),
                body("$.user.note!=x", JSON_USER),
                body("user.note=null", JSON_USER),
                // == is replaced only on point path branches
                body("$.user.type==vip", JSON_USER),
                body("user.type==vip", JSON_USER),
                // The same condition takes different branches according to the body type
                body("//Status=OPEN", JSON_USER),
                body("Type=vip", XML_SOAP),
                body("Type=vip", JSON_USER),
                body("ns:ServiceName=OrderService", XML_SOAP),
                body("/Envelope/Body/Req/Type=vip", XML_SOAP),
                body("//Item/@sku=S2", XML_ORDER),
                body("Item=5", XML_ORDER),
                // Pure text body Use the entire condition to make contains
                body("a=b", "x a=b y"),
                body("hello", "hello world"),
                body("type=vip", "   "),
                // Quotation marks and regular expressions
                body("user.type=\"vip\"", JSON_USER),
                body("user.type=\"\"", JSON_USER),
                body("user.name~=Alice.*", JSON_USER),
                body("user.name~=" + "a".repeat(201), JSON_USER),
                body("user.name~=(", JSON_USER),
                body("user.name~=(.*a){25}", "{\"user\":{\"name\":\"" + "a".repeat(25) + "b\"}}"),
                // query/header missing
                new Case(null, "type!=vip", null, null, null, none),
                new Case(null, "type!=vip", null, null, "", none),
                new Case(null, null, "X-Type!=vip", null, null, none),
                new Case(null, null, "X-Type!=vip", null, null, Map.of("Other", "1")),
                new Case(null, "type=v%20p", null, null, "type=v%20p", none),
                // Mixing three conditions
                new Case("user.type=vip;$.items[*].sku=S1", "a=1", "x-type=vip",
                        JSON_USER, "a=1&a=2", Map.of("X-Type", "vip")));
    }

    @Test
    void tooLargeBodyFailsLikeConditionMatcher() {
        String huge = "{\"a\":\"" + "x".repeat(MatcherLimits.DEFAULT.maxBodyChars()) + "\"}";

        assertEquivalent(new Case("a!=y;$.a!=y;a", "q!=1", null, huge, null, Map.of()),
                MatcherLimits.DEFAULT.maxFieldValueLength());
    }

    private static Case body(String condition, String requestBody) {
        return new Case(condition, null, null, requestBody, null, Map.of());
    }

    // ==================== Comparison ====================

    record Case(String bodyCondition, String queryCondition, String headerCondition,
                String requestBody, String queryString, Map<String, String> headers) {
    }

    private static void assertEquivalent(Case c, int maxFieldValueLength) {
        LegacyConditionOracle oracle = new LegacyConditionOracle(maxFieldValueLength);
        MatcherCompiler compiler = new MatcherCompiler(MatcherRegistry.defaults(),
                MatcherLimits.DEFAULT.withMaxFieldValueLength(maxFieldValueLength));
        Map<String, String> headers = new LinkedHashMap<>(c.headers());

        boolean expectedMatch = oracle.matchesPrepared(c.bodyCondition(), c.queryCondition(), c.headerCondition(),
                oracle.prepareBody(c.requestBody()), c.queryString(), headers);
        LegacyConditionOracle.PreparedBody detailBody = oracle.prepareBody(c.requestBody());
        ConditionMatcher.ConditionDetail expectedDetail = oracle.matchesPreparedWithDetail(
                c.bodyCondition(), c.queryCondition(), c.headerCondition(), detailBody, c.queryString(), headers);

        CompiledMatcher matcher = compiler.compile(
                LegacyConditions.toMatchNode(c.bodyCondition(), c.queryCondition(), c.headerCondition()));
        RequestView request = RequestView.of("POST", "/mock", c.queryString(), headers,
                BodyView.of(c.requestBody(), MatcherLimits.DEFAULT));
        EvalContext explainContext = new EvalContext(request);
        MatchExplanation explanation = matcher.explain(explainContext);

        assertThat(matcher.matches(new EvalContext(request))).as("matches %s", c).isEqualTo(expectedMatch);
        assertThat(explanation.passed()).as("explain.passed %s", c).isEqualTo(expectedDetail.isOverallMatch());
        assertThat(explanation.score()).as("score %s", c).isEqualTo(expectedDetail.score());
        assertThat(details(explanation)).as("details %s", c).isEqualTo(expectedDetail.getResults().stream()
                .map(r -> r.getType() + " | " + r.getCondition() + " | " + r.isPassed() + " | " + r.getDetail())
                .toList());
        assertThat(explainContext.outcomesSnapshot()).as("memo keys %s", c)
                .isEqualTo(detailBody.getMatchOutcomesSnapshot());
    }

    private static List<String> details(MatchExplanation explanation) {
        List<String> details = new ArrayList<>();
        for (MatchExplanation child : explanation.children()) {
            String type = MatchNode.LegacySource.valueOf(
                    child.node().substring("legacy.".length()).toUpperCase(Locale.ROOT)).fieldName();
            details.add(type + " | " + child.expected() + " | " + child.passed() + " | "
                    + LegacyConditions.legacyDetail(child).orElseThrow());
        }
        return details;
    }
}
