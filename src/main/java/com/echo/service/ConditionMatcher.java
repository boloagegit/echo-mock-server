package com.echo.service;

import java.util.Collection;
import javax.xml.stream.XMLStreamException;
import com.echo.core.match.XmlStreamScan;
import com.echo.core.match.XmlPath;
import com.echo.core.match.BodyRequirements;
import com.echo.core.match.BodyView;
import com.echo.core.match.CompiledMatcher;
import com.echo.core.match.EvalContext;
import com.echo.core.match.LegacyConditions;
import com.echo.core.match.MatchExplanation;
import com.echo.core.match.MatchNode;
import com.echo.core.match.MatcherCompiler;
import com.echo.core.match.MatcherLimits;
import com.echo.core.match.MatcherRegistry;
import com.echo.core.match.RequestView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * Condition matching service
 * <p>
 * Supports request content matching in multiple formats, similar to WireMock style:
 *
 * <h3>Supported operators</h3>
 * <ul>
 *   <li>{@code field=value} - equal to (default)</li>
 *   <li>{@code field~=pattern} - Regular expression matching</li>
 *   <li>{@code field*=substring} - contains the substring</li>
 *   <li>{@code field!=value} - not equal to</li>
 * </ul>
 *
 * <h3>Supported path formats</h3>
 * <ul>
 *   <li>{@code $.jsonPath} - JSON Path expression</li>
 *   <li>{@code //xpath} - XPath expression</li>
 *   <li>{@code ?param} - Query Parameter</li>
 *   <li>{@code field.nested} - JSON field path</li>
 * </ul>
 *
 * Conditions are always evaluated by the {@code com.echo.core.match} engine: the old string conditions are converted into compatible nodes by {@link LegacyConditions}.
 * Compile structured conditions ({@code matchSpec}) directly. This class handles request-level work: body pre-parsing, JMS streaming-XML precomputation,
 * sharing compiled conditions and adapting engine explanations to the matching-chain format.
 */
@Service
@Slf4j
public class ConditionMatcher {

    private static final int MAX_JSON_SIZE = 10 * 1024 * 1024; // 10MB

    private static final int DEFAULT_MAX_FIELD_VALUE_LENGTH = 2 * 1024 * 1024;

    /** Maximum field-value length; skip matching oversized values, such as base64 payloads, to prevent excessive matching cost. */
    private final int maxFieldValueLength;

    private final MatcherCompiler compiler;
    /**
     * Condition strings map to {@link RuleConditions} through weak references; rule snapshots ({@code HttpRuleSnapshot}/{@code JmsQueueSnapshot})
     * Active conditions have strong references, so they cannot be reclaimed and need no capacity limit; after snapshot eviction, GC reclaims conditions no longer in use.
     * This replaces the former 10,000-entry LRU cache, avoiding repeated recompilation when the condition count exceeds that limit.
     */
    private final Cache<ConditionKey, RuleConditions> conditionRegistry = Caffeine.newBuilder()
            .weakValues()
            .build();

    /** Default constructor, use 2MB limit (for testing and non-Spring environments) */
    public ConditionMatcher() {
        this(DEFAULT_MAX_FIELD_VALUE_LENGTH);
    }

    @Autowired
    public ConditionMatcher(@Value("${echo.matcher.max-field-value-length:2097152}") int maxFieldValueLength) {
        this.maxFieldValueLength = maxFieldValueLength;
        this.compiler = new MatcherCompiler(MatcherRegistry.defaults(),
                MatcherLimits.DEFAULT.withMaxFieldValueLength(maxFieldValueLength));
    }

    private record ConditionKey(String matchSpec, String body, String query, String header) {
    }

    /**
     * One rule's conditions, either structured or the three legacy strings; compile on first evaluation and reuse thereafter.
     * The caller, usually a rule snapshot, retains this object to keep compiled results alive for the snapshot's lifetime.
     */
    public static final class RuleConditions {

        private final ConditionKey key;
        private final MatcherCompiler compiler;
        private volatile CompiledMatcher compiled;

        private RuleConditions(ConditionKey key, MatcherCompiler compiler) {
            this.key = key;
            this.compiler = compiler;
        }

        /** Body requirements for these conditions; see {@link BodyRequirements}. */
        public BodyRequirements bodyRequirements() {
            return compiled().bodyRequirements();
        }

        private CompiledMatcher compiled() {
            CompiledMatcher result = compiled;
            if (result == null) {
                // Concurrent compilation may duplicate work once; results are identical and immutable, so no lock is needed.
                result = key.matchSpec() != null
                        ? compileStored(key.matchSpec())
                        : compiler.compile(LegacyConditions.toMatchNode(key.body(), key.query(), key.header()));
                compiled = result;
            }
            return result;
        }

        /**
         * Conditions are validated when saved; if direct database edits introduce invalid conditions, treat the rule as never matching
         * rather than failing every request with an exception.
         */
        private CompiledMatcher compileStored(String matchSpec) {
            try {
                return compiler.compile(STORED_SPEC_READER.readTree(matchSpec));
            } catch (JsonProcessingException | IllegalArgumentException e) {
                log.warn("Rule matchSpec cannot be compiled; the rule will never match: {}", e.getMessage());
                return compiler.compile(new MatchNode.Any(List.of()));
            }
        }
    }

    private static final ObjectMapper STORED_SPEC_READER = new ObjectMapper();

    /**
     * Get a common object for a set of conditions; the same condition returns the same object during use.
     * Rule snapshots must hold the returned value, keeping compiled results alive for the snapshot's lifetime.
     */
    public RuleConditions conditions(String matchSpec, String bodyCondition, String queryCondition,
                                     String headerCondition) {
        String spec = hasMatchSpec(matchSpec) ? matchSpec : null;
        return conditionRegistry.get(new ConditionKey(spec, bodyCondition, queryCondition, headerCondition),
                key -> new RuleConditions(key, compiler));
    }

    public RuleConditions conditions(String bodyCondition, String queryCondition, String headerCondition) {
        return conditions(null, bodyCondition, queryCondition, headerCondition);
    }

    private CompiledMatcher compiled(String matchSpec, String bodyCondition, String queryCondition,
                                     String headerCondition) {
        return conditions(matchSpec, bodyCondition, queryCondition, headerCondition).compiled();
    }

    private static boolean hasMatchSpec(String matchSpec) {
        return matchSpec != null && !matchSpec.isBlank();
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final ThreadLocal<DocumentBuilder> DOC_BUILDER = ThreadLocal.withInitial(() -> {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create DocumentBuilder", e);
        }
    });

    @PreDestroy
    public void cleanup() {
        DOC_BUILDER.remove();
    }

    // ==================== Prepared Body ====================

    /**
     * Request-confined body container with shared parsing and predicate results.
     * <p>
     * JSON is parsed only on first access; RAW conditions never need a tree.
     * Subsequent rules reuse successful or failed parsing, just like lazy XML.
     */
    public static class PreparedBody {
        private final String raw;
        private final Document xmlDoc;
        private JsonNode jsonNode;
        private final ObjectMapper jsonReader;
        private boolean jsonParsed;
        private final BodyType type;
        private final Map<String, Boolean> matchOutcomes = new HashMap<>();
        /** Matching-engine evaluation state; see {@link #evalContext(String, Map)}. */
        private EvalContext compiledContext;
        private String contextQueryString;
        private Map<String, String> contextHeaders;
        private Document lazyXmlDoc;
        private boolean lazyXmlParsed;
        private Map<XmlPath, List<String>> xmlValues;

        enum BodyType { XML, JSON, PLAIN, EMPTY, TOO_LARGE }

        private PreparedBody(String raw, Document xmlDoc, JsonNode jsonNode, BodyType type) {
            this(raw, xmlDoc, jsonNode, type, null);
        }

        private PreparedBody(String raw, Document xmlDoc, JsonNode jsonNode, BodyType type,
                             ObjectMapper jsonReader) {
            this.raw = raw;
            this.xmlDoc = xmlDoc;
            this.jsonNode = jsonNode;
            this.type = type;
            this.jsonReader = jsonReader;
        }

        public static PreparedBody empty() {
            return new PreparedBody(null, null, null, BodyType.EMPTY);
        }

        public static PreparedBody tooLarge() {
            return new PreparedBody(null, null, null, BodyType.TOO_LARGE);
        }

        /**
         * Keep the original content for request logging, but do not create JSON tree or XML DOM.
         * Only used if the candidate rule has no body condition at all.
         */
        public static PreparedBody rawOnly(String raw) {
            if (raw == null || raw.isBlank()) {
                return empty();
            }
            return new PreparedBody(raw, null, null, BodyType.PLAIN);
        }

        private static PreparedBody streamingXml(String raw, Map<String, Boolean> outcomes) {
            PreparedBody prepared = new PreparedBody(raw, null, null, BodyType.XML);
            prepared.matchOutcomes.putAll(outcomes);
            return prepared;
        }

        /** Obtain the parsed XML Document, if not XML it is null */
        public Document getXmlDoc() {
            return xmlDoc;
        }

        /** Obtain the parsed JSON node, if it is not JSON, it is null */
        public JsonNode getJsonNode() {
            if (jsonReader != null && !jsonParsed) {
                jsonParsed = true;
                try {
                    jsonNode = jsonReader.readTree(raw);
                } catch (Exception e) {
                    // Retain the existing warning format and the terminal failed result.
                    log.warn("Failed to prepare body: {}", e.getMessage());
                }
            }
            return jsonNode;
        }

        /** Get the original body string */
        public String getRaw() {
            return raw;
        }

        /** Lightweight request-time predicate results; never contains parsed body objects. */
        public Map<String, Boolean> getMatchOutcomesSnapshot() {
            return Map.copyOf(matchOutcomes);
        }

        private void restoreMatchOutcomes(Map<String, Boolean> outcomes) {
            if (outcomes != null && !outcomes.isEmpty()) {
                matchOutcomes.putAll(outcomes);
            }
        }

        /** Number of JSONPath evaluations for this request, stored in the matching-engine evaluation state. */
        int cachedJsonPathCount() {
            return compiledContext == null ? 0 : compiledContext.cachedValueCount();
        }

        /**
         * Matching-engine evaluation state. Query parameters and headers stay unchanged within a request, so reuse the same context;
         * share cached outcomes directly with {@link #matchOutcomes}, reusing JMS streaming-XML results and outcomes restored for background analysis.
         */
        @SuppressWarnings("ReferenceEquality") // The same request passes the same headers object, so an identity check is sufficient.
        private EvalContext evalContext(String queryString, Map<String, String> headers) {
            if (compiledContext == null || !Objects.equals(contextQueryString, queryString)
                    || contextHeaders != headers) {
                contextQueryString = queryString;
                contextHeaders = headers;
                compiledContext = new EvalContext(
                        new PreparedRequestView(queryString, headers, new PreparedBodyView()), matchOutcomes);
            }
            return compiledContext;
        }

        /** Expose parsed content to the matching engine without parsing again. */
        private final class PreparedBodyView implements BodyView {

            @Override
            public String raw() {
                return raw;
            }

            @Override
            public boolean tooLarge() {
                return type == BodyType.TOO_LARGE;
            }

            @Override
            public JsonNode json() {
                return getJsonNode();
            }

            /** Streaming XML has no DOM; parse one only when needed, such as for matching-chain explanations, and attempt it only once even on failure. */
            @Override
            public Document xml() {
                if (xmlDoc != null) {
                    return xmlDoc;
                }
                if (type == BodyType.XML && !lazyXmlParsed && raw != null) {
                    lazyXmlParsed = true;
                    lazyXmlDoc = parseXmlQuietly(raw);
                }
                return lazyXmlDoc;
            }

            /** Evaluate each path once per request and share its result across rules. */
            @Override
            public List<String> xmlValues(XmlPath path) {
                Document doc = xml();
                if (doc == null) {
                    return null;
                }
                if (xmlValues == null) {
                    xmlValues = new HashMap<>();
                }
                return xmlValues.computeIfAbsent(path, ignored -> path.select(doc));
            }
        }
    }

    /** Use the caller's header map without copying, preserving the legacy first-header-in-iteration-order behavior. */
    private record PreparedRequestView(String queryString, Map<String, String> headers, BodyView body)
            implements RequestView {

        @Override
        public String method() {
            return null;
        }

        @Override
        public String path() {
            return null;
        }
    }

    private static Document parseXmlQuietly(String xml) {
        try {
            DocumentBuilder builder = DOC_BUILDER.get();
            builder.reset();
            return builder.parse(new InputSource(new StringReader(xml)));
        } catch (SAXException | IOException e) {
            return null;
        }
    }

    /**
     * Pre-parse body and return PreparedBody for batch matching.
     */
    public PreparedBody prepareBody(String body) {
        if (body == null || body.isBlank()) {
            return PreparedBody.empty();
        }
        if (body.length() > MAX_JSON_SIZE) {
            log.warn("Body too large for prepare: {} bytes", body.length());
            return PreparedBody.tooLarge();
        }
        String trimmed = body.trim();
        try {
            if (trimmed.startsWith("<")) {
                DocumentBuilder builder = DOC_BUILDER.get();
                builder.reset();
                Document doc = builder.parse(new InputSource(new StringReader(trimmed)));
                return new PreparedBody(trimmed, doc, null, PreparedBody.BodyType.XML);
            }
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                return new PreparedBody(trimmed, null, null, PreparedBody.BodyType.JSON, objectMapper);
            }
        } catch (Exception e) {
            log.warn("Failed to prepare body: {}", e.getMessage());
        }
        return new PreparedBody(trimmed, null, null, PreparedBody.BodyType.PLAIN);
    }

    /**
     * Prepare the body for candidate requirements (section 4.5): skip parsing if no condition reads it; for XML that requires no DOM,
     * evaluate all streamable conditions in one pass without building a DOM. Otherwise parse normally and share the result across rules.
     */
    public PreparedBody prepareBody(String body, Collection<RuleConditions> candidates) {
        List<BodyRequirements> requirements = new ArrayList<>(candidates.size());
        for (RuleConditions conditions : candidates) {
            requirements.add(conditions.bodyRequirements());
        }
        return prepareBody(body, BodyRequirements.merge(requirements));
    }

    /** Same as above, the requirements have been merged. */
    public PreparedBody prepareBody(String body, BodyRequirements requirements) {
        if (!requirements.usesBody()) {
            return PreparedBody.rawOnly(body);
        }
        if (body == null || body.isBlank()) {
            return PreparedBody.empty();
        }
        if (body.length() > MAX_JSON_SIZE) {
            log.warn("Body too large for prepare: {} bytes", body.length());
            return PreparedBody.tooLarge();
        }
        String trimmed = body.trim();
        if (!trimmed.startsWith("<") || requirements.needsXmlDom()) {
            return prepareBody(body);
        }
        try {
            return PreparedBody.streamingXml(trimmed,
                    XmlStreamScan.evaluate(trimmed, requirements, maxFieldValueLength));
        } catch (XMLStreamException | RuntimeException e) {
            // If streaming fails, for example due to invalid XML or undeclared prefixes, fall back to normal parsing to classify XML versus plain text.
            return prepareBody(body);
        }
    }

    /** Rebuilds parsed body state while reusing predicates already evaluated on the request path. */
    public PreparedBody prepareBody(String body, Map<String, Boolean> matchOutcomes) {
        PreparedBody prepared = prepareBody(body);
        prepared.restoreMatchOutcomes(matchOutcomes);
        return prepared;
    }

    // ==================== Public Matching API ====================

    /**
     * Fast Boolean matching (use pre-parsed body, do not create detail objects)
     * <p>
     * Used for rule matching loops, with the best performance.
     */
    public boolean matchesPrepared(String bodyCondition, String queryCondition, String headerCondition,
                                   PreparedBody prepared, String queryString, Map<String, String> headers) {
        return matchesPrepared(null, bodyCondition, queryCondition, headerCondition, prepared, queryString, headers);
    }

    /**
     * Same as {@link #matchesPrepared(String, String, String, PreparedBody, String, Map)}, but also supports structured conditions;
     * When {@code matchSpec} is present, it takes precedence over all three legacy fields.
     */
    public boolean matchesPrepared(String matchSpec, String bodyCondition, String queryCondition,
                                   String headerCondition, PreparedBody prepared, String queryString,
                                   Map<String, String> headers) {
        return compiled(matchSpec, bodyCondition, queryCondition, headerCondition)
                .matches(evalContext(prepared, queryString, headers));
    }

    /**
     * Structured matching (using pre-parsed body, returning PASS/FAIL details for each condition)
     * <p>
     * Used for match chain analysis, providing detailed information required for near-miss detection.
     */
    public ConditionDetail matchesPreparedWithDetail(String bodyCondition, String queryCondition, String headerCondition,
                                                     PreparedBody prepared, String queryString, Map<String, String> headers) {
        return matchesPreparedWithDetail(null, bodyCondition, queryCondition, headerCondition,
                prepared, queryString, headers);
    }

    /** Same as above, in addition, structured conditions are supported; when there is {@code matchSpec}, it shall prevail. */
    public ConditionDetail matchesPreparedWithDetail(String matchSpec, String bodyCondition, String queryCondition,
                                                     String headerCondition, PreparedBody prepared,
                                                     String queryString, Map<String, String> headers) {
        return toConditionDetail(explainPrepared(matchSpec, bodyCondition, queryCondition, headerCondition,
                prepared, queryString, headers));
    }

    /**
     * Tree-shaped explanation for matching-chain display: each group's outcome and the actual values extracted.
     * Please use {@link #toConditionDetail(MatchExplanation)} to flatten the result into an item-by-item result.
     */
    public MatchExplanation explainPrepared(String matchSpec, String bodyCondition, String queryCondition,
                                            String headerCondition, PreparedBody prepared,
                                            String queryString, Map<String, String> headers) {
        return compiled(matchSpec, bodyCondition, queryCondition, headerCondition)
                .explain(evalContext(prepared, queryString, headers));
    }

    private static EvalContext evalContext(PreparedBody prepared, String queryString, Map<String, String> headers) {
        return (prepared != null ? prepared : PreparedBody.empty()).evalContext(queryString, headers);
    }

    /**
     * The description text of the old version of the condition is exactly the same as the original implementation (see test LegacyConditionOracle);
     * Flatten each structured condition leaf into one entry with type {@code matchSpec},
     * The whole tree's outcome, including any/not groups, determines the overall match.
     */
    public static ConditionDetail toConditionDetail(MatchExplanation explanation) {
        List<ConditionResult> results = new ArrayList<>(explanation.children().size());
        addConditionResults(explanation, false, results);
        return ConditionDetail.builder()
                .overallMatch(explanation.passed())
                .results(results)
                .build();
    }

    /** Present descendants of {@code not} as passing only when their condition fails, keeping each PASS/FAIL consistent with its contribution to the whole. */
    private static void addConditionResults(MatchExplanation explanation, boolean negated,
                                            List<ConditionResult> results) {
        if (explanation.isCondition()) {
            addConditionResult(explanation, negated, results);
            return;
        }
        boolean childNegated = "not".equals(explanation.node()) != negated;
        for (MatchExplanation child : explanation.children()) {
            addConditionResults(child, childNegated, results);
        }
    }

    private static void addConditionResult(MatchExplanation explanation, boolean negated,
                                           List<ConditionResult> results) {
        Optional<MatchNode.LegacySource> legacy = LegacyConditions.sourceOf(explanation);
        if (legacy.isPresent() && !negated) {
            results.add(ConditionResult.builder()
                    .type(legacy.get().fieldName())
                    .condition(explanation.expected())
                    .passed(explanation.passed())
                    .detail(LegacyConditions.legacyDetail(explanation).orElseThrow())
                    .build());
            return;
        }
        String condition = negated ? "not " + explanation.expected() : explanation.expected();
        boolean passed = explanation.passed() != negated;
        String detail = passed
                ? MATCH_SPEC_TYPE + " PASS (" + condition + ")"
                : MATCH_SPEC_TYPE + " FAIL (expected " + condition + ", actual " + explanation.actual() + ")";
        results.add(ConditionResult.builder()
                .type(MATCH_SPEC_TYPE)
                .condition(condition)
                .passed(passed)
                .detail(detail)
                .build());
    }

    /** The type name of the structured condition in the match chain. */
    public static final String MATCH_SPEC_TYPE = "matchSpec";

    /**
     * Simple matching (automatically parses body)
     */
    public boolean matches(String bodyCondition, String queryCondition, String headerCondition,
                          String body, String queryString, Map<String, String> headers) {
        PreparedBody prepared = prepareBody(body);
        return matchesPrepared(bodyCondition, queryCondition, headerCondition, prepared, queryString, headers);
    }

    public boolean matches(String bodyCondition, String queryCondition, String body, String queryString) {
        return matches(bodyCondition, queryCondition, null, body, queryString, null);
    }

    /** Single condition string: The beginning of {@code ?} is the query condition, and the rest are body conditions. */
    public boolean matches(String condition, String body, String queryString) {
        if (condition == null || condition.isBlank()) {
            return true;
        }
        StringJoiner bodyConditions = new StringJoiner(";");
        StringJoiner queryConditions = new StringJoiner(";");
        for (String cond : condition.split(";")) {
            String c = cond.trim();
            if (c.startsWith("?")) {
                queryConditions.add(c.substring(1));
            } else if (!c.isEmpty()) {
                bodyConditions.add(c);
            }
        }
        return matches(bodyConditions.toString(), queryConditions.toString(), body, queryString);
    }

    public boolean matches(String condition, String body) {
        return matches(condition, body, null);
    }

    // ==================== Structured Match Result Classes ====================

    /**
     * Structured match results, including PASS/FAIL details for all conditions
     */
    @Getter
    @Builder
    public static class ConditionDetail {
        private final boolean overallMatch;
        private final List<ConditionResult> results;

        public int passedCount() {
            return (int) results.stream().filter(ConditionResult::isPassed).count();
        }

        public int totalCount() {
            return results.size();
        }

        public String score() {
            return passedCount() + "/" + totalCount();
        }
    }

    /**
     * Match result of single condition
     */
    @Getter
    @Builder
    public static class ConditionResult {
        private final String type;       // "bodyCondition", "queryCondition", "headerCondition"
        private final String condition;  // Original condition string
        private final boolean passed;
        private final String detail;     // e.g., "bodyCondition PASS (user.type=vip)"
    }
}
