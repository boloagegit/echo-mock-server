package com.echo.core.match;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import java.util.*;
import java.util.concurrent.*;

/** General XPath extraction is bounded and shared only within one request. */
class XPathExtractionSharingTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PATH = "//Order/Type[contains(.,'V') or contains(.,'R')]";

    private static final class ObservedBody implements BodyView {
        final BodyView delegate;
        int xmlCalls;

        ObservedBody(String xml) {
            delegate = BodyView.of(xml, MatcherLimits.DEFAULT);
        }

        @Override
        public String raw() {
            return delegate.raw();
        }

        @Override
        public boolean tooLarge() {
            return delegate.tooLarge();
        }

        @Override
        public JsonNode json() {
            return delegate.json();
        }

        @Override
        public Document xml() {
            xmlCalls++;
            return delegate.xml();
        }

        EvalContext context() {
            return new EvalContext(RequestView.of("POST", "/test", null, Map.of(), this));
        }
    }

    private static CompiledMatcher rule(String path, String value) throws Exception {
        return rule(path, value, Map.of(), MatcherLimits.DEFAULT);
    }

    private static CompiledMatcher rule(
            String path, String value, Map<String, Object> extra, MatcherLimits limits)
            throws Exception {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("source", "body");
        spec.put("selector", Map.of("xpath", path));
        spec.put("op", "equals");
        spec.put("value", value);
        spec.putAll(extra);
        JsonNode json = JSON.valueToTree(spec);
        return new MatcherCompiler(MatcherRegistry.defaults(), limits).compile(json);
    }

    @Test
    void eightDifferentRulesAndTheirExplanationsShareOneExtraction() throws Exception {
        var body = new ObservedBody("<Order><Type> VIP </Type></Order>");
        var ctx = body.context();
        for (String value : List.of("A", "B", "C", "D", "E", "F", "G", "VIP")) {
            var m = rule(PATH, value);
            boolean expected = value.equals("VIP");
            assertEquals(expected, m.matches(ctx));
            var e = m.explain(ctx);
            assertEquals(expected, e.passed());
            assertEquals("\"VIP\"", e.actual());
        }
        assertEquals(1, body.xmlCalls);
    }

    @Test
    void predicatesQuantifiersOptionsAndFieldLimitsRemainIndependent() throws Exception {
        var body = new ObservedBody("<Order><Type>VIP</Type><Type>REG</Type></Order>");
        var ctx = body.context();
        assertTrue(rule(PATH, "VIP").matches(ctx));
        assertFalse(
                rule(PATH, "VIP", Map.of("quantifier", "all"), MatcherLimits.DEFAULT).matches(ctx));
        assertTrue(
                rule(
                                PATH,
                                "vip",
                                Map.of("options", Map.of("caseInsensitive", true)),
                                MatcherLimits.DEFAULT)
                        .matches(ctx));
        assertFalse(rule(PATH, "vip").matches(ctx));
        assertFalse(
                rule(PATH, "REG", Map.of(), MatcherLimits.DEFAULT.withMaxFieldValueLength(2))
                        .matches(ctx));
        assertEquals(1, body.xmlCalls);
    }

    @Test
    void differentXPathExpressionsDoNotCollide() throws Exception {
        var body = new ObservedBody("<Order><Type>VIP</Type><Other>REG</Other></Order>");
        var ctx = body.context();
        assertTrue(rule(PATH, "VIP").matches(ctx));
        assertTrue(rule("//Order/Other[contains(.,'R')]", "REG").matches(ctx));
        assertEquals(2, body.xmlCalls);
    }

    @Test
    void anotherRequestReevaluatesTheSameCompiledMatcher() throws Exception {
        var m = rule(PATH, "VIP");
        var first = new ObservedBody("<Order><Type>VIP</Type></Order>");
        var second = new ObservedBody("<Order><Type>REG</Type></Order>");
        assertTrue(m.matches(first.context()));
        assertFalse(m.matches(second.context()));
        assertEquals(1, first.xmlCalls);
        assertEquals(1, second.xmlCalls);
    }

    @Test
    void missingValuesShareExtractionButKeepOnMissingPolicies() throws Exception {
        var body = new ObservedBody("<Order/>");
        var ctx = body.context();
        assertFalse(rule(PATH, "VIP").matches(ctx));
        assertTrue(
                rule(PATH, "VIP", Map.of("onMissing", "pass"), MatcherLimits.DEFAULT).matches(ctx));
        assertEquals("missing", rule(PATH, "REG").explain(ctx).actual());
        assertEquals(1, body.xmlCalls);
    }

    @Test
    void scalarAndNamespaceLocalNameValuesAreShared() throws Exception {
        var body =
                new ObservedBody(
                        "<s:Order"
                            + " xmlns:s='urn:x'><s:Type>VIP</s:Type><s:Type>REG</s:Type></s:Order>");
        var ctx = body.context();
        String nodes = "//*[local-name()='Type']";
        assertTrue(rule(nodes, "VIP").matches(ctx));
        assertTrue(rule(nodes, "REG").matches(ctx));
        assertTrue(rule("count(" + nodes + ")", "2").matches(ctx));
        assertFalse(rule("count(" + nodes + ")", "0").matches(ctx));
        assertTrue(rule("string(" + nodes + ")", "VIP").matches(ctx));
        assertFalse(rule("string(" + nodes + ")", "REG").matches(ctx));
        assertTrue(rule("boolean(" + nodes + ")", "true").matches(ctx));
        assertFalse(rule("boolean(" + nodes + ")", "false").matches(ctx));
        assertEquals(4, body.xmlCalls);
    }

    @Test
    void entryLimitBypassesAdditionalResultsWithoutChangingAnswers() throws Exception {
        var body = new ObservedBody("<Order><Type>VIP</Type></Order>");
        var ctx = body.context();
        var rules = new ArrayList<CompiledMatcher>();
        for (int i = 0; i < 129; i++) {
            var m = rule("//Order/Type[position()<" + (i + 2) + "]", "VIP");
            rules.add(m);
            assertTrue(m.matches(ctx));
        }
        assertEquals(129, body.xmlCalls);
        assertTrue(rules.get(0).explain(ctx).passed());
        assertEquals(129, body.xmlCalls);
        assertTrue(rules.get(128).explain(ctx).passed());
        assertEquals(130, body.xmlCalls);
    }

    @Test
    void oversizedValueBypassesStorageAndDoesNotConsumeAllTheBudget() throws Exception {
        var body =
                new ObservedBody(
                        "<Order><Type>"
                                + "x".repeat(300_000)
                                + "</Type><Small>VIP</Small></Order>");
        var ctx = body.context();
        var large = rule("//Order/Type[starts-with(.,'x')]", "not-equal");
        assertFalse(large.matches(ctx));
        assertFalse(large.explain(ctx).passed());
        assertEquals(2, body.xmlCalls);
        var small = rule("//Order/Small[contains(.,'V')]", "VIP");
        assertTrue(small.matches(ctx));
        assertTrue(small.explain(ctx).passed());
        assertEquals(3, body.xmlCalls);
    }

    @Test
    void cumulativeWeightLimitBypassesNewEntriesAndKeepsExistingEntries() throws Exception {
        var body = new ObservedBody("<Order><Type>" + "x".repeat(100_000) + "</Type></Order>");
        var ctx = body.context();
        var rules = new ArrayList<CompiledMatcher>();
        for (int i = 0; i < 4; i++) {
            var m =
                    rule(
                            "//Order/Type[starts-with(.,'x')][position()<" + (i + 2) + "]",
                            "different");
            rules.add(m);
            assertFalse(m.matches(ctx));
        }
        assertEquals(4, body.xmlCalls);
        rules.get(0).explain(ctx);
        assertEquals(4, body.xmlCalls);
        rules.get(2).explain(ctx);
        assertEquals(5, body.xmlCalls);
    }

    @Test
    void malformedTailAndDoctypeCannotBecomeSuccessfulFieldMatches() throws Exception {
        var m = rule(PATH, "VIP");
        for (String xml :
                List.of(
                        "<Order><Type>VIP</Type></Order>bad",
                        "<Order><Type>VIP</Type>",
                        "<!DOCTYPE Order [<!ENTITY x SYSTEM"
                            + " 'file:///nonexistent-xpath-sharing-test'>]><Order><Type>&x;</Type></Order>")) {
            var body = new ObservedBody(xml);
            var ctx = body.context();
            assertFalse(m.matches(ctx));
            assertFalse(m.explain(ctx).passed());
            assertEquals(1, body.xmlCalls);
        }
    }

    @Test
    void compiledRulesDoNotShareValuesAcrossConcurrentRequests() throws Exception {
        var vip = rule(PATH, "VIP");
        var reg = rule(PATH, "REG");
        var pool = Executors.newFixedThreadPool(4);
        try {
            var jobs = new ArrayList<Future<?>>();
            for (int i = 0; i < 200; i++) {
                final boolean isVip = i % 2 == 0;
                jobs.add(
                        pool.submit(
                                () -> {
                                    var body =
                                            new ObservedBody(
                                                    "<Order><Type>"
                                                            + (isVip ? "VIP" : "REG")
                                                            + "</Type></Order>");
                                    var ctx = body.context();
                                    assertEquals(isVip, vip.matches(ctx));
                                    assertEquals(!isVip, reg.matches(ctx));
                                    assertEquals(isVip, vip.explain(ctx).passed());
                                    assertEquals(1, body.xmlCalls);
                                }));
            }
            for (var job : jobs) {
                job.get(20, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}
