package com.echo.core.match;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MatcherEvaluationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ORDER_JSON = """
            {"order": {"id": "A-1", "amount": 1000.50, "vip": true, "note": null,
                       "items": [{"sku": "S1", "qty": 2}, {"sku": "S2", "qty": 5}]}}""";
    private static final String ORDER_XML = """
            <Order xmlns="urn:example:order">
              <Status>OPEN</Status>
              <Item sku="S1">2</Item>
              <Item sku="S2">5</Item>
            </Order>""";

    private final MatcherCompiler compiler = MatcherCompiler.withDefaults();

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text.replace('\'', '"'));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static RequestView request(Map<String, String> headers, String query, String body) {
        return RequestView.of("POST", "/orders", query, headers, BodyView.of(body, MatcherLimits.DEFAULT));
    }

    private static RequestView body(String body) {
        return request(Map.of(), null, body);
    }

    private boolean matches(String spec, RequestView request) {
        return compiler.compile(json(spec)).matches(new EvalContext(request));
    }

    @Nested
    class Sources {

        @Test
        void headerNameIsCaseInsensitive() {
            RequestView request = request(Map.of("X-Tenant", "acme"), null, null);

            assertThat(matches("{'source':'header','name':'x-tenant','op':'equals','value':'acme'}", request))
                    .isTrue();
        }

        @Test
        void missingHeaderFollowsOperatorDefaults() {
            RequestView request = request(Map.of(), null, null);

            assertThat(matches("{'source':'header','name':'a','op':'equals','value':'x'}", request)).isFalse();
            assertThat(matches("{'source':'header','name':'a','op':'notEquals','value':'x'}", request)).isTrue();
            assertThat(matches("{'source':'header','name':'a','op':'exists'}", request)).isFalse();
            assertThat(matches("{'source':'header','name':'a','op':'absent'}", request)).isTrue();
        }

        @Test
        void queryDecodesAndCollectsRepeatedParameters() {
            RequestView request = request(Map.of(), "name=John%20Doe&tag=a&tag=b&flag", null);

            assertThat(matches("{'source':'query','name':'name','op':'equals','value':'John Doe'}", request))
                    .isTrue();
            assertThat(matches("{'source':'query','name':'tag','op':'equals','value':'b'}", request)).isTrue();
            assertThat(matches("{'source':'query','name':'tag','op':'equals','value':'b','quantifier':'all'}",
                    request)).isFalse();
            assertThat(matches("{'source':'query','name':'tag','op':'in','value':['a','b'],'quantifier':'all'}",
                    request)).isTrue();
            assertThat(matches("{'source':'query','name':'flag','op':'equals','value':''}", request)).isTrue();
        }

        @Test
        void methodAndPath() {
            RequestView request = body(null);

            assertThat(matches("{'source':'method','op':'equals','value':'POST'}", request)).isTrue();
            assertThat(matches("{'source':'path','op':'matches','value':'/orders(/.*)?'}", request)).isTrue();
        }

        @Test
        void rawBody() {
            assertThat(matches("{'source':'body','selector':{'raw':true},'op':'contains','value':'A-1'}",
                    body(ORDER_JSON))).isTrue();
            assertThat(matches("{'source':'body','selector':{'raw':true},'op':'absent'}", body(null))).isTrue();
        }

        @Test
        void jsonPathSelectsSingleAndMultipleValues() {
            RequestView request = body(ORDER_JSON);

            assertThat(matches("{'source':'body','selector':{'jsonPath':'$.order.amount'},'op':'gte','value':1000.5}",
                    request)).isTrue();
            assertThat(matches("{'source':'body','selector':{'jsonPath':'$.order.items[*].sku'},"
                    + "'op':'equals','value':'S2'}", request)).isTrue();
            assertThat(matches("{'source':'body','selector':{'jsonPath':'$.order.items[*].qty'},"
                    + "'op':'gt','value':1,'quantifier':'all'}", request)).isTrue();
            assertThat(matches("{'source':'body','selector':{'jsonPath':'$.order.items[*].qty'},"
                    + "'op':'gt','value':3,'quantifier':'all'}", request)).isFalse();
            assertThat(matches("{'source':'body','selector':{'jsonPath':'$.order.missing'},'op':'absent'}",
                    request)).isTrue();
            assertThat(matches("{'source':'body','selector':{'jsonPath':'$.order.items[?(@.qty > 9)].sku'},"
                    + "'op':'absent'}", request)).isTrue();
        }

        @Test
        void jsonNullIsPresentWithNullValue() {
            RequestView request = body(ORDER_JSON);

            assertThat(matches("{'source':'body','selector':{'field':'order.note'},'op':'exists'}", request))
                    .isTrue();
            assertThat(matches("{'source':'body','selector':{'field':'order.note'},'op':'equals','value':null}",
                    request)).isTrue();
            assertThat(matches("{'source':'body','selector':{'field':'order.note'},'op':'contains','value':'x'}",
                    request)).isFalse();
        }

        @Test
        void fieldPathSupportsIndexes() {
            RequestView request = body(ORDER_JSON);

            assertThat(matches("{'source':'body','selector':{'field':'order.items[1].sku'},'op':'equals','value':'S2'}",
                    request)).isTrue();
            assertThat(matches("{'source':'body','selector':{'field':'order.items[7].sku'},'op':'absent'}", request))
                    .isTrue();
            assertThat(matches("{'source':'body','selector':{'field':'order.vip'},'op':'equals','value':true}",
                    request)).isTrue();
        }

        @Test
        void jsonSelectorsTreatNonJsonBodyAsMissing() {
            assertThat(matches("{'source':'body','selector':{'field':'order.id'},'op':'absent'}", body(ORDER_XML)))
                    .isTrue();
            assertThat(matches("{'source':'body','selector':{'jsonPath':'$.order'},'op':'absent'}", body("plain")))
                    .isTrue();
        }

        @Test
        void xpathMatchesDefaultNamespaceAndScalarResults() {
            RequestView request = body(ORDER_XML);

            assertThat(matches("{'source':'body','selector':{'xpath':'//Status'},'op':'equals','value':'OPEN'}",
                    request)).isTrue();
            assertThat(matches("{'source':'body','selector':{'xpath':'//Item'},'op':'equals','value':'5'}",
                    request)).isTrue();
            assertThat(matches("{'source':'body','selector':{'xpath':'//Item/@sku'},'op':'in','value':['S1','S2'],"
                    + "'quantifier':'all'}", request)).isTrue();
            assertThat(matches("{'source':'body','selector':{'xpath':'count(//Item)'},'op':'equals','value':'2'}",
                    request)).isTrue();
            assertThat(matches("{'source':'body','selector':{'xpath':'//Missing'},'op':'absent'}", request)).isTrue();
            assertThat(matches("{'source':'body','selector':{'xpath':'//Status'},'op':'absent'}", body(ORDER_JSON)))
                    .isTrue();
        }
    }

    @Nested
    class Operators {

        private final RequestView request = request(
                Map.of("X-Name", "Alice", "X-Count", "10", "X-Json", "{\"b\":[2,1],\"a\":1.0}"), null, null);

        @ParameterizedTest
        @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
                "{'op':'equals','value':'Alice'}                                   | X-Name  | true",
                "{'op':'equals','value':'alice'}                                   | X-Name  | false",
                "{'op':'equals','value':'alice','options':{'caseInsensitive':true}} | X-Name | true",
                "{'op':'notEquals','value':'Bob'}                                  | X-Name  | true",
                "{'op':'contains','value':'lic'}                                   | X-Name  | true",
                "{'op':'contains','value':'LIC','options':{'caseInsensitive':true}} | X-Name | true",
                "{'op':'matches','value':'Al.*'}                                   | X-Name  | true",
                "{'op':'matches','value':'lic'}                                    | X-Name  | false",
                "{'op':'gt','value':9}                                             | X-Count | true",
                "{'op':'gt','value':'10'}                                          | X-Count | false",
                "{'op':'gte','value':10.0}                                         | X-Count | true",
                "{'op':'lt','value':10.5}                                          | X-Count | true",
                "{'op':'lte','value':9}                                            | X-Count | false",
                "{'op':'gt','value':1}                                             | X-Name  | false",
                "{'op':'in','value':['Bob','Alice']}                               | X-Name  | true",
                "{'op':'in','value':['ALICE'],'options':{'caseInsensitive':true}}  | X-Name  | true",
                "{'op':'in','value':['Bob']}                                       | X-Name  | false",
                "{'op':'equals','value':10}                                        | X-Count | true",
                "{'op':'equalToJson','value':{'a':1,'b':[2,1]}}                    | X-Json  | true",
                "{'op':'equalToJson','value':{'a':1,'b':[1,2]}}                    | X-Json  | false",
                "{'op':'equalToJson','value':{'a':1,'b':[1,2]},'options':{'ignoreArrayOrder':true}} | X-Json | true",
                "{'op':'equalToJson','value':{'a':1}}                              | X-Json  | false",
                "{'op':'equalToJson','value':{'a':1},'options':{'ignoreExtraElements':true}} | X-Json | true",
                "{'op':'equalToJson','value':{'a':1}}                              | X-Name  | false"
        })
        void headerOperators(String opSpec, String header, boolean expected) {
            String spec = opSpec.replaceFirst("\\{", "{'source':'header','name':'" + header + "',");

            assertThat(matches(spec, request)).isEqualTo(expected);
        }

        @Test
        void equalToJsonUsesMaximumMatchingForUnorderedArrays() {
            // 貪婪配對會把 {"a":1} 配給第一個元素而失敗；正確答案是可以配對。
            RequestView request = body("{\"items\":[{\"a\":1,\"b\":2},{\"a\":1}]}");

            assertThat(matches("{'source':'body','selector':{'field':'items'},'op':'equalToJson',"
                    + "'value':[{'a':1},{'a':1,'b':2}],"
                    + "'options':{'ignoreArrayOrder':true,'ignoreExtraElements':true}}", request)).isTrue();
        }

        @Test
        void equalToJsonRejectsOversizedUnorderedArrays() {
            int size = JsonComparator.MAX_UNORDERED_ELEMENTS + 1;
            String array = "[" + "1,".repeat(size - 1) + "1]";

            assertThat(matches("{'source':'body','selector':{'raw':true},'op':'equalToJson','value':" + array
                    + ",'options':{'ignoreArrayOrder':true}}", body(array))).isFalse();
            assertThat(matches("{'source':'body','selector':{'raw':true},'op':'equalToJson','value':" + array + "}",
                    body(array))).isTrue();
        }

        @Test
        void regexTimeoutAndInputLimitFailClosed() {
            MatcherLimits limits = new MatcherLimits(MatcherLimits.DEFAULT.maxBodyChars(),
                    MatcherLimits.DEFAULT.maxFieldValueLength(), 200, 30, 50);
            MatcherCompiler limited = new MatcherCompiler(MatcherRegistry.defaults(), limits);
            CompiledMatcher evil = limited.compile(json("{'source':'header','name':'v','op':'matches','value':'(.*a){25}'}"));
            CompiledMatcher any = limited.compile(json("{'source':'header','name':'v','op':'matches','value':'.*'}"));

            assertThat(evil.matches(new EvalContext(request(Map.of("v", "a".repeat(25) + "b"), null, null))))
                    .isFalse();
            assertThat(any.matches(new EvalContext(request(Map.of("v", "x".repeat(31)), null, null)))).isFalse();
            assertThat(any.matches(new EvalContext(request(Map.of("v", "x".repeat(30)), null, null)))).isTrue();
        }
    }

    @Nested
    class MissingAndLimits {

        @Test
        void onMissingOverridesOperatorDefault() {
            RequestView request = request(Map.of(), null, null);

            assertThat(matches("{'source':'header','name':'a','op':'equals','value':'x','onMissing':'pass'}", request))
                    .isTrue();
            assertThat(matches("{'source':'header','name':'a','op':'notEquals','value':'x','onMissing':'fail'}",
                    request)).isFalse();
        }

        @Test
        void tooLargeBodyFailsEveryBodyCondition() {
            MatcherLimits limits = new MatcherLimits(10, 10, 200, 10_000, 1000);
            MatcherCompiler limited = new MatcherCompiler(MatcherRegistry.defaults(), limits);
            RequestView request = RequestView.of("POST", "/", null, Map.of(), BodyView.of("{\"a\":\"12345\"}", limits));

            for (String op : new String[] {
                    "'op':'exists'", "'op':'absent'", "'op':'notEquals','value':'x'", "'op':'equals','value':'12345'"}) {
                CompiledMatcher matcher = limited.compile(json(
                        "{'source':'body','selector':{'field':'a'}," + op + "}"));
                assertThat(matcher.matches(new EvalContext(request))).as(op).isFalse();
            }
        }

        @Test
        void oversizedValuesAreSkipped() {
            MatcherCompiler limited = new MatcherCompiler(MatcherRegistry.defaults(),
                    MatcherLimits.DEFAULT.withMaxFieldValueLength(3));
            RequestView request = body("{\"tags\":[\"ok\",\"too-long\"],\"big\":\"too-long\"}");

            assertThat(limited.compile(json("{'source':'body','selector':{'jsonPath':'$.tags[*]'},"
                    + "'op':'equals','value':'ok','quantifier':'all'}")).matches(new EvalContext(request))).isTrue();
            assertThat(limited.compile(json("{'source':'body','selector':{'field':'big'},"
                    + "'op':'notEquals','value':'x'}")).matches(new EvalContext(request))).isFalse();
        }
    }

    @Nested
    class Composites {

        @Test
        void emptyCompositesHaveIdentityValues() {
            CompiledMatcher all = compiler.compile(json("{'all':[]}"));
            CompiledMatcher any = compiler.compile(json("{'any':[]}"));

            assertThat(all.isUnconditional()).isTrue();
            assertThat(all.matches(new EvalContext(body(null)))).isTrue();
            assertThat(any.isUnconditional()).isFalse();
            assertThat(any.matches(new EvalContext(body(null)))).isFalse();
        }

        @Test
        void combinesAllAnyAndNot() {
            String spec = """
                    {'all': [
                      {'source':'header','name':'X-Tenant','op':'equals','value':'acme'},
                      {'any': [
                        {'source':'body','selector':{'jsonPath':'$.order.amount'},'op':'gte','value':5000},
                        {'source':'body','selector':{'field':'order.vip'},'op':'equals','value':true}
                      ]},
                      {'not': {'source':'query','name':'dryRun','op':'exists'}}
                    ]}""";

            assertThat(matches(spec, request(Map.of("X-Tenant", "acme"), null, ORDER_JSON))).isTrue();
            assertThat(matches(spec, request(Map.of("X-Tenant", "acme"), "dryRun", ORDER_JSON))).isFalse();
            assertThat(matches(spec, request(Map.of("X-Tenant", "other"), null, ORDER_JSON))).isFalse();
            assertThat(matches(spec, request(Map.of("X-Tenant", "acme"), null, "{\"order\":{\"amount\":1}}")))
                    .isFalse();
        }

        @Test
        void leafResultsAreMemoizedAcrossMatchersInOneRequest() {
            AtomicInteger calls = new AtomicInteger();
            MatcherRegistry registry = MatcherRegistry.builder().withDefaults()
                    .register(countingSource(calls)).build();
            MatcherCompiler counting = new MatcherCompiler(registry, MatcherLimits.DEFAULT);
            String leaf = "{'source':'counted','op':'equals','value':'v'}";
            CompiledMatcher first = counting.compile(json("{'all':[" + leaf + "," + leaf + "]}"));
            CompiledMatcher second = counting.compile(json("{'any':[" + leaf + "]}"));
            EvalContext context = new EvalContext(body(null));

            assertThat(first.matches(context)).isTrue();
            assertThat(second.matches(context)).isTrue();
            assertThat(second.explain(context).passed()).isTrue();
            assertThat(calls).hasValue(2); // explain 需要實際值，會再取一次；判斷結果仍沿用暫存
            assertThat(context.outcomesSnapshot()).containsEntry(
                    MatchSpecCodec.toJson(MatchSpecCodec.parse(json(leaf))).toString(), true);
        }
    }

    @Nested
    class Explanation {

        @Test
        void explainsEveryNodeWithExpectedAndActualValues() {
            CompiledMatcher matcher = compiler.compile(json("""
                    {'all': [
                      {'source':'header','name':'X-Tenant','op':'equals','value':'acme'},
                      {'source':'body','selector':{'jsonPath':'$.order.items[*].sku'},'op':'equals','value':'S9'},
                      {'source':'query','name':'page','op':'exists'}
                    ]}"""));

            MatchExplanation explanation = matcher.explain(
                    new EvalContext(request(Map.of("X-Tenant", "acme"), null, ORDER_JSON)));

            assertThat(explanation.node()).isEqualTo("all");
            assertThat(explanation.passed()).isFalse();
            assertThat(explanation.score()).isEqualTo("1/3");
            assertThat(explanation.children()).extracting(MatchExplanation::expected).containsExactly(
                    "header[X-Tenant] equals \"acme\"",
                    "body(jsonPath: $.order.items[*].sku) equals \"S9\"",
                    "query[page] exists");
            assertThat(explanation.children()).extracting(MatchExplanation::actual).containsExactly(
                    "\"acme\"", "[\"S1\", \"S2\"]", "missing");
        }

        @Test
        void leafExplanationCountsAsSingleCondition() {
            MatchExplanation explanation = compiler.compile(json("{'source':'method','op':'equals','value':'GET'}"))
                    .explain(new EvalContext(body(null)));

            assertThat(explanation.node()).isEqualTo("leaf");
            assertThat(explanation.score()).isEqualTo("0/1");
            assertThat(explanation.actual()).isEqualTo("\"POST\"");
        }
    }

    @Nested
    class Extension {

        @Test
        void registersCustomOperatorAndSource() {
            PredicateFactory startsWith = new PredicateFactory() {
                @Override
                public String op() {
                    return "startsWith";
                }

                @Override
                public CompiledPredicate compile(JsonNode expected, Map<String, JsonNode> options,
                                                 MatcherLimits limits) {
                    String prefix = expected.asText();
                    return actual -> actual.text() != null && actual.text().startsWith(prefix);
                }
            };
            Map<String, String> properties = new HashMap<>();
            properties.put("ServiceName", "OrderService.Create");
            ValueSourceFactory jmsProperty = new ValueSourceFactory() {
                @Override
                public String source() {
                    return "jms.property";
                }

                @Override
                public boolean requiresName() {
                    return true;
                }

                @Override
                public Set<Selector.Kind> supportedSelectors() {
                    return Set.of();
                }

                @Override
                public ValueExtractor create(String name, Selector selector, MatcherLimits limits) {
                    return request -> properties.containsKey(name)
                            ? Extracted.of(Value.ofText(properties.get(name))) : Extracted.missing();
                }
            };
            MatcherCompiler extended = new MatcherCompiler(
                    MatcherRegistry.builder().withDefaults().register(startsWith).register(jmsProperty).build(),
                    MatcherLimits.DEFAULT);

            CompiledMatcher matcher = extended.compile(json(
                    "{'source':'jms.property','name':'ServiceName','op':'startsWith','value':'OrderService.'}"));

            assertThat(matcher.matches(new EvalContext(body(null)))).isTrue();
        }

        @Test
        void rejectsDuplicateRegistration() {
            assertThatThrownBy(() -> MatcherRegistry.builder().withDefaults()
                    .register(BuiltinPredicates.all().get(0)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("equals");
        }
    }

    private static ValueSourceFactory countingSource(AtomicInteger calls) {
        return new ValueSourceFactory() {
            @Override
            public String source() {
                return "counted";
            }

            @Override
            public boolean requiresName() {
                return false;
            }

            @Override
            public Set<Selector.Kind> supportedSelectors() {
                return Set.of();
            }

            @Override
            public ValueExtractor create(String name, Selector selector, MatcherLimits limits) {
                return request -> {
                    calls.incrementAndGet();
                    return Extracted.of(Value.ofText("v"));
                };
            }
        };
    }
}
