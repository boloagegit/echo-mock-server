package com.echo.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

import com.echo.core.match.JsonInputReaders;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;

class SelectiveJsonProjectionTest {
    private static final ObjectMapper M = new ObjectMapper();

    private static String nested(String value) {
        return "{\"group\":{\"target\":\""
                + value
                + "\",\"other\":123,\"padding\":\""
                + "x".repeat(65536)
                + "\"}}";
    }

    private static void equivalent(
            ObjectMapper mapper,
            String input,
            com.echo.core.match.BodyRequirements.JsonProjection plan) {
        JsonNode expected = null, actual = null;
        Exception first = null, second = null;
        try {
            expected = mapper.readTree(input);
        } catch (Exception e) {
            first = e;
        }
        try {
            actual = SelectiveJsonProjection.project(mapper, input, plan);
            if (actual == null) actual = mapper.readTree(input);
        } catch (Exception e) {
            second = e;
        }
        assertEquals(
                first == null, second == null, input.substring(0, Math.min(input.length(), 100)));
        if (first == null)
            assertEquals(
                    expected.path("group").path("target"), actual.path("group").path("target"));
    }

    @Test
    void nestedSelectionPreservesPublicTreeAndUnplannedFields() throws Exception {
        ConditionMatcher matcher = new ConditionMatcher();
        var first = matcher.conditions("$.group.target=yes", null, null);
        var later = matcher.conditions("$.group.other=123", null, null);
        var prepared = matcher.prepareBody(nested("yes"), List.of(first));
        assertTrue(matcher.matchesPrepared(first, prepared, null, Map.of()));
        var field = prepared.getClass().getDeclaredField("projection");
        field.setAccessible(true);
        JsonNode projected = (JsonNode) field.get(prepared);
        assertNotNull(projected);
        assertFalse(projected.path("group").has("padding"));
        assertEquals(
                65536, prepared.getJsonNode().path("group").path("padding").textValue().length());
        assertTrue(matcher.matchesPrepared(later, prepared, null, Map.of()));
        assertEquals(
                matcher.explainPrepared(later, matcher.prepareBody(nested("yes")), null, Map.of()),
                matcher.explainPrepared(later, prepared, null, Map.of()));
    }

    @Test
    void wholeParentAndDescendantPlansMergeSafely() {
        ConditionMatcher matcher = new ConditionMatcher();
        var first = matcher.conditions("$.group.target=yes", null, null);
        var whole = matcher.conditions("$.group*=padding", null, null);
        for (var planned : List.of(List.of(first), List.of(first, whole))) {
            var prepared = matcher.prepareBody(nested("yes"), planned);
            assertTrue(matcher.matchesPrepared(first, prepared, null, Map.of()));
            assertEquals(
                    matcher.explainPrepared(
                            whole, matcher.prepareBody(nested("yes")), null, Map.of()),
                    matcher.explainPrepared(whole, prepared, null, Map.of()));
        }
    }

    @Test
    void manyPresentFieldsAndDeepPositivePathsRemainAvailable() throws Exception {
        ConditionMatcher matcher = new ConditionMatcher();
        for (int count : new int[] {1, 10, 16, 17, 100}) {
            ObjectNode root = (ObjectNode) M.readTree(nested("yes"));
            ObjectNode group = (ObjectNode) root.get("group");
            List<ConditionMatcher.RuleConditions> rules = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                group.put("f" + i, "yes");
                rules.add(matcher.conditions("$.group.f" + i + "=yes", null, null));
            }
            String input = M.writeValueAsString(root);
            var prepared = matcher.prepareBody(input, rules);
            for (var rule : rules)
                assertTrue(matcher.matchesPrepared(rule, prepared, null, Map.of()));
        }
        for (int depth : new int[] {1, 8, 16, 17, 21}) {
            ObjectNode root = M.createObjectNode(), node = root;
            for (int i = 0; i < depth; i++) node = node.putObject("a");
            node.put("target", "yes");
            node.put("padding", "x".repeat(65536));
            var rule = matcher.conditions("$." + "a.".repeat(depth) + "target=yes", null, null);
            String input = M.writeValueAsString(root);
            assertTrue(
                    matcher.matchesPrepared(
                            rule, matcher.prepareBody(input, List.of(rule)), null, Map.of()));
        }
    }

    @Test
    void invalidValuesDuplicatesTypesAndTrailingRootsFollowJackson() {
        ConditionMatcher matcher = new ConditionMatcher();
        var plan =
                matcher.conditions("$.group.target=yes", null, null)
                        .bodyRequirements()
                        .jsonProjection();
        for (String input :
                List.of(
                        "{\"group\":{\"target\":\"yes\",\"unused\":\"bad\\q\"}}",
                        "{\"group\":{\"target\":\"yes\",\"unused\":[1,]}}",
                        "{\"group\":{\"target\":\"yes\",\"unused\":01}}",
                        "{\"group\":{\"target\":\"yes\"},\"group\":{}}",
                        "{\"group\":null}",
                        "{\"group\":[{\"target\":1}]}",
                        "{\"group\":{\"target\":-0.0}}",
                        "{\"group\":{\"target\":123456789012345678901234567890}}",
                        "{\"group\":{\"target\":\"yes\"}} trailing",
                        "{\"group\":{\"target\":\"yes\"}}{}")) equivalent(M, input, plan);
    }

    @Test
    void nestedMutationAndNumericDifferentials() {
        ConditionMatcher matcher = new ConditionMatcher();
        var plan =
                matcher.conditions("$.group.target=yes", null, null)
                        .bodyRequirements()
                        .jsonProjection();
        Random random = new Random(812381);
        String seed =
                "{\"group\":{\"target\":\"中文\\n😀\",\"unused\":[-0.0,1e300,123456789012345678901234567890]}}";
        String edits = "{}[],:\\\"q01 \n";
        for (int i = 0; i < 1500; i++) {
            int at = random.nextInt(seed.length() + 1);
            equivalent(
                    M,
                    seed.substring(0, at)
                            + edits.charAt(random.nextInt(edits.length()))
                            + seed.substring(at),
                    plan);
        }
        for (int i = 0; i < 3000; i++) {
            String number =
                    switch (i % 6) {
                        case 0 -> Long.toString(random.nextLong());
                        case 1 -> Double.toString(Double.longBitsToDouble(random.nextLong()));
                        case 2 -> new java.math.BigInteger(1500, random).toString();
                        case 3 ->
                                "-0." + "0".repeat(random.nextInt(1100)) + "1e" + random.nextInt();
                        case 4 -> "1e" + "9".repeat(1 + random.nextInt(1100));
                        default -> "0" + Math.abs((long) random.nextInt());
                    };
            equivalent(M, "{\"group\":{\"target\":\"yes\",\"unused\":" + number + "}}", plan);
        }
    }

    @Test
    void allResourceConstraintsStillApplyToDiscardedValues() {
        ConditionMatcher matcher = new ConditionMatcher();
        var plan =
                matcher.conditions("$.group.target=yes", null, null)
                        .bodyRequirements()
                        .jsonProjection();
        for (StreamReadConstraints limits :
                List.of(
                        StreamReadConstraints.builder().maxStringLength(5).build(),
                        StreamReadConstraints.builder().maxNameLength(5).build(),
                        StreamReadConstraints.builder().maxNumberLength(5).build(),
                        StreamReadConstraints.builder().maxNestingDepth(3).build(),
                        StreamReadConstraints.builder().maxDocumentLength(25).build(),
                        StreamReadConstraints.builder().maxTokenCount(7).build())) {
            ObjectMapper mapper = new ObjectMapper();
            mapper.getFactory().setStreamReadConstraints(limits);
            for (String input :
                    List.of(
                            "{\"group\":{\"target\":\"yes\",\"unused\":\"123456789\"}}",
                            "{\"group\":{\"target\":\"yes\",\"longignoredname\":123456789}}",
                            "{\"group\":{\"target\":\"yes\",\"unused\":[[[[1,2,3]]]]}}"))
                equivalent(mapper, input, plan);
        }
    }

    @Test
    void logicalGroupsAndRootReferencedFiltersRemainEquivalent() throws Exception {
        ConditionMatcher matcher = new ConditionMatcher();
        for (String spec :
                List.of(
                        "{\"all\":[{\"source\":\"body\",\"selector\":{\"jsonPath\":\"$.group.target\"},\"op\":\"equals\",\"value\":\"yes\"},{\"not\":{\"source\":\"body\",\"selector\":{\"jsonPath\":\"$.group.other\"},\"op\":\"equals\",\"value\":0}}]}",
                        "{\"any\":[{\"source\":\"body\",\"selector\":{\"jsonPath\":\"$.missing\"},\"op\":\"equals\",\"value\":0},{\"source\":\"body\",\"selector\":{\"jsonPath\":\"$.group.target\"},\"op\":\"equals\",\"value\":\"yes\"}]}",
                        "{\"source\":\"body\",\"selector\":{\"jsonPath\":\"$.*\"},\"op\":\"contains\",\"value\":\"yes\"}",
                        "{\"source\":\"body\",\"selector\":{\"jsonPath\":\"$.group.items[?(@.id == $.other)].id\"},\"op\":\"equals\",\"value\":2}")) {
            var rule = matcher.conditions(spec, null, null, null);
            String input = nested("yes");
            var prepared = matcher.prepareBody(input, List.of(rule));
            assertEquals(
                    matcher.explainPrepared(rule, matcher.prepareBody(input), null, Map.of()),
                    matcher.explainPrepared(rule, prepared, null, Map.of()));
        }
    }

    @Test
    void unsupportedDeserializersIncludingNullModuleIdsFallBack() throws Exception {
        var module =
                new com.fasterxml.jackson.databind.module.SimpleModule() {
                    @Override
                    public Object getTypeId() {
                        return null;
                    }
                };
        module.addDeserializer(
                JsonNode.class,
                new com.fasterxml.jackson.databind.JsonDeserializer<JsonNode>() {
                    @Override
                    public JsonNode deserialize(
                            com.fasterxml.jackson.core.JsonParser parser,
                            com.fasterxml.jackson.databind.DeserializationContext context)
                            throws java.io.IOException {
                        parser.skipChildren();
                        return com.fasterxml.jackson.databind.node.TextNode.valueOf("custom");
                    }
                });
        ObjectMapper mapper = new ObjectMapper().registerModule(module);
        assertTrue(mapper.getRegisteredModuleIds().isEmpty());
        assertNull(SelectiveJsonProjection.project(mapper, "{\"a\":1}", Set.of("a")));
        ObjectMapper strict =
                new ObjectMapper()
                        .enable(
                                com.fasterxml.jackson.databind.DeserializationFeature
                                        .FAIL_ON_TRAILING_TOKENS);
        assertNull(SelectiveJsonProjection.project(strict, "{\"a\":1} trailing", Set.of("a")));
    }

    @Test
    void longNamesAreIsolatedWithoutLimitsOrSharedFactoryMutation() throws Exception {
        ObjectMapper shared = new ObjectMapper();
        String name = "k" + "中文".repeat(1000);
        ObjectNode root = M.createObjectNode();
        root.put(name, 1);
        root.put("target", "yes");
        String input = M.writeValueAsString(root);
        ObjectMapper safe = JsonInputReaders.reader(shared, input);
        assertNotSame(shared, safe);
        assertTrue(shared.getFactory().isEnabled(JsonFactory.Feature.CANONICALIZE_FIELD_NAMES));
        assertFalse(safe.getFactory().isEnabled(JsonFactory.Feature.INTERN_FIELD_NAMES));
        assertEquals(root, safe.readTree(input));
        assertSame(shared, JsonInputReaders.reader(shared, nested("yes")));
        String bad = "{\"" + "x".repeat(50000) + "\" ? 1}";
        assertNotSame(shared, JsonInputReaders.reader(shared, bad));
        String excessive = "{\"" + "x".repeat(50001) + "\":1}";
        assertThrows(
                com.fasterxml.jackson.core.JsonProcessingException.class,
                () -> JsonInputReaders.reader(shared, excessive).readTree(excessive));
    }

    @Test
    void isolatedReaderPreservesEscapesAndCustomConstraints() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.getFactory()
                .setStreamReadConstraints(
                        StreamReadConstraints.builder().maxNameLength(2048).build());
        String key = "quote\\\"" + "x".repeat(1500);
        ObjectNode root = M.createObjectNode();
        root.put(key, "中文😀");
        String input = M.writeValueAsString(root);
        assertEquals(
                mapper.readTree(input), JsonInputReaders.reader(mapper, input).readTree(input));
    }

    @Test
    void invalidProjectionPreservesLaterRulesExplanationAndPublicTree() throws Exception {
        ConditionMatcher matcher = new ConditionMatcher();
        var first = matcher.conditions("$.group.target=yes", null, null);
        var later = matcher.conditions("$.group.other=123", null, null);
        var negated =
                matcher.conditions(
                        "{\"not\":{\"source\":\"body\",\"selector\":{\"jsonPath\":\"$.group.target\"},\"op\":\"equals\",\"value\":\"yes\"}}",
                        null,
                        null,
                        null);
        for (String suffix : List.of("\"bad\\q\"}}", "[1,]}}", "[1,2")) {
            String input =
                    "{\"group\":{\"target\":\"yes\",\"padding\":\""
                            + "x".repeat(65536)
                            + "\",\"unused\":"
                            + suffix;
            var planned = matcher.prepareBody(input, List.of(first));
            for (var rule : List.of(first, later, negated)) {
                var baseline = matcher.prepareBody(input);
                assertEquals(
                        matcher.matchesPrepared(rule, baseline, null, Map.of()),
                        matcher.matchesPrepared(rule, planned, null, Map.of()));
                assertEquals(
                        matcher.explainPrepared(rule, matcher.prepareBody(input), null, Map.of()),
                        matcher.explainPrepared(rule, planned, null, Map.of()));
            }
            assertNull(planned.getJsonNode());
        }
    }

    @Test
    void rejectedProjectionDoesNotParseTheSameInvalidBodyAgain() {
        ConditionMatcher matcher = new ConditionMatcher();
        var rule = matcher.conditions("$.group.target=yes", null, null);
        for (String suffix : List.of("\"bad\\q\"}}", "[1,]}}", "[1,2")) {
            String input =
                    "{\"group\":{\"target\":\"yes\",\"padding\":\""
                            + "x".repeat(65536)
                            + "\",\"unused\":"
                            + suffix;
            var prepared = matcher.prepareBody(input, List.of(rule));
            try (var fullTree = mockStatic(BoundedJsonInputMapper.class, CALLS_REAL_METHODS)) {
                assertFalse(matcher.matchesPrepared(rule, prepared, null, Map.of()));
                assertNull(prepared.getJsonNode());
                fullTree.verifyNoInteractions();
            }
        }
    }

    @Test
    void concurrentRequestsDoNotShareValuesOrMapperConfiguration() throws Exception {
        ConditionMatcher matcher = new ConditionMatcher();
        var rule = matcher.conditions("$.group.target=yes", null, null);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int i = 0; i < 128; i++) {
                boolean hit = i % 2 == 0;
                tasks.add(
                        () ->
                                matcher.matchesPrepared(
                                                rule,
                                                matcher.prepareBody(
                                                        nested(hit ? "yes" : "bad"), List.of(rule)),
                                                null,
                                                Map.of())
                                        == hit);
            }
            for (Future<Boolean> result : executor.invokeAll(tasks)) assertTrue(result.get());
        } finally {
            executor.shutdownNow();
        }
    }
}
