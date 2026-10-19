package com.echo.core.match;

import com.echo.core.util.InterruptibleCharSequence;
import com.fasterxml.jackson.databind.JsonNode;
import com.jayway.jsonpath.JsonPath;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Evaluate legacy string-condition fragments, mirroring each behavior of {@code com.echo.service.ConditionMatcher}
 * including operator parsing, runtime body-type dispatch, missing-field branches, and actual-value descriptions.
 * <p>
 * Intentionally preserve legacy inconsistencies, such as {@code !=} not matching a missing JSONPath field; differential tests enforce equivalence.
 * One intentional difference: a lone {@code "} expected value previously threw an exception and returned HTTP 500; compare it literally here.
 */
final class LegacyConditionMatcher implements CompiledMatcher {

    private static final ThreadLocal<XPath> XPATH =
            ThreadLocal.withInitial(() -> XPathFactory.newInstance().newXPath());
    private static final int XPATH_CACHE_SIZE = 512;
    private static final ThreadLocal<Map<String, XPathExpression>> XPATH_CACHE =
            ThreadLocal.withInitial(() -> new LruCache<>(XPATH_CACHE_SIZE));

    private final MatchNode.LegacySource source;
    private final String condition;
    private final String cacheKey;
    private final MatcherLimits limits;
    /**
     * Parsed condition. JSON dot paths and XML element paths normalize {@code ==} to {@code =}; XML matches streaming scans, see section 7.2.9.
     * JSONPath, query, header will not be replaced.
     */
    private final Parsed parsed;
    private final Parsed parsedWithoutDoubleEquals;
    private final JsonPath jsonPath;
    /** Simple XML-body path for streaming; null if the expression requires general XPath evaluation. */
    private final XmlPath xmlPath;
    private final BodyRequirements bodyRequirements;

    LegacyConditionMatcher(MatchNode.LegacySource source, String condition, MatcherLimits limits) {
        this.source = source;
        this.condition = condition;
        this.cacheKey = source.jsonName() + ":" + condition;
        this.limits = limits;
        this.parsed = Parsed.of(condition, limits);
        this.parsedWithoutDoubleEquals = Parsed.of(condition.replace("==", "="), limits);
        this.jsonPath = source == MatchNode.LegacySource.BODY && condition.startsWith("$.")
                ? compileJsonPath(parsed.field) : null;
        this.xmlPath = source == MatchNode.LegacySource.BODY && jsonPath == null && !condition.startsWith("$.")
                ? XmlPath.parse(parsedWithoutDoubleEquals.field).orElse(null) : null;
        this.bodyRequirements = requirements();
    }

    private BodyRequirements requirements() {
        if (source != MatchNode.LegacySource.BODY) {
            return BodyRequirements.NONE;
        }
        if (condition.startsWith("$.")) {
            return BodyRequirements.BODY_WITHOUT_XML;
        }
        // Use the same condition as an XML path for XML bodies or a JSON dot path for JSON bodies.
        return xmlPath != null ? BodyRequirements.streamable(new LegacyStreamCheck()) : BodyRequirements.FULL;
    }

    @Override
    public BodyRequirements bodyRequirements() {
        return bodyRequirements;
    }

    /** Like {@link #matchXmlNodes}: match if any value within the length limit satisfies the condition. */
    private final class LegacyStreamCheck implements BodyRequirements.StreamCheck {

        @Override
        public XmlPath path() {
            return xmlPath;
        }

        @Override
        public String cacheKey() {
            return cacheKey;
        }

        @Override
        public BodyRequirements.Probe newProbe() {
            return new BodyRequirements.Probe() {
                private boolean matched;

                @Override
                public boolean offer(String value) {
                    if (value.length() <= limits.maxFieldValueLength() && parsedWithoutDoubleEquals.test(value)) {
                        matched = true;
                    }
                    return matched;
                }

                @Override
                public boolean result() {
                    return matched;
                }
            };
        }
    }

    @Override
    public boolean matches(EvalContext context) {
        return context.memoized(cacheKey, () -> evaluate(context));
    }

    @Override
    public MatchExplanation explain(EvalContext context) {
        boolean passed = matches(context);
        Parsed described = source == MatchNode.LegacySource.BODY ? parsedWithoutDoubleEquals : parsed;
        String actual = described.field + "=" + actualValue(context, described);
        return new MatchExplanation(LegacyConditions.LEGACY_NODE_PREFIX + source.jsonName(),
                passed, condition, actual, List.of());
    }

    @Override
    public String cacheKey() {
        return cacheKey;
    }

    private boolean evaluate(EvalContext context) {
        RequestView request = context.request();
        return switch (source) {
            case BODY -> matchBody(context);
            case QUERY -> matchQuery(request.queryString());
            case HEADER -> matchHeader(request.headers());
        };
    }

    // ==================== query / header ====================

    private boolean matchQuery(String queryString) {
        if (queryString == null || queryString.isBlank()) {
            return parsed.test(null);
        }
        for (String param : queryString.split("&")) {
            int idx = param.indexOf('=');
            if (idx > 0 && parsed.field.equals(param.substring(0, idx))) {
                return parsed.test(param.substring(idx + 1));
            }
        }
        return parsed.test(null);
    }

    private boolean matchHeader(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return false;
        }
        return parsed.test(firstHeader(headers, parsed.field));
    }

    private static String firstHeader(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    // ==================== body ====================

    private boolean matchBody(EvalContext context) {
        BodyView body = context.request().body();
        // JSONPath applies only to JSON; check it first to avoid parsing XML just to determine the body type, since streaming XML has no DOM.
        if (condition.startsWith("$.")) {
            return !BodyType.emptyOrTooLarge(body) && matchJsonPath(context);
        }
        BodyType type = BodyType.of(body);
        if (type == BodyType.EMPTY || type == BodyType.TOO_LARGE) {
            return false;
        }
        if (condition.startsWith("//")) {
            return type == BodyType.XML && matchXmlNodes(context, parsedWithoutDoubleEquals, body);
        }
        if (type == BodyType.JSON) {
            return matchJsonField(body.json());
        }
        if (type == BodyType.XML) {
            return matchXmlNodes(context, parsedWithoutDoubleEquals, body);
        }
        return body.raw().trim().contains(condition);
    }

    private boolean matchJsonPath(EvalContext context) {
        JsonPathResult result = readJsonPath(context);
        if (!result.found) {
            return false;
        }
        if (result.value != null && result.value.length() > limits.maxFieldValueLength()) {
            return false;
        }
        return parsed.test(result.value);
    }

    /** Same as the old version: the same JSONPath is only evaluated once in the same request, and the results are shared across conditions. */
    private JsonPathResult readJsonPath(EvalContext context) {
        JsonNode json = context.request().body().json();
        if (json == null || jsonPath == null) {
            return JsonPathResult.MISSING;
        }
        return context.cachedValue(new JsonPathKey(parsed.field), () -> evaluateJsonPath(json));
    }

    private JsonPathResult evaluateJsonPath(JsonNode json) {
        try {
            Object value = jsonPath.read(json, BuiltinSources.JSON_PATH_CONFIGURATION);
            if (value instanceof JsonNode node) {
                if (node.isNull()) {
                    return new JsonPathResult(true, null);
                }
                return new JsonPathResult(true, node.isTextual() ? node.asText() : node.toString());
            }
            return new JsonPathResult(true, value != null ? value.toString() : null);
        } catch (RuntimeException e) {
            return JsonPathResult.MISSING;
        }
    }

    private boolean matchJsonField(JsonNode root) {
        Parsed cond = parsedWithoutDoubleEquals;
        boolean missingResult = cond.op == Op.NOT_EQUAL;
        try {
            JsonNode node = root;
            for (String field : stripBodyPrefix(cond.field).split("\\.")) {
                if (node == null) {
                    return missingResult;
                }
                if (field.contains("[")) {
                    int bs = field.indexOf('[');
                    int be = field.indexOf(']');
                    node = node.get(field.substring(0, bs));
                    if (node == null || !node.isArray()) {
                        return missingResult;
                    }
                    node = node.get(Integer.parseInt(field.substring(bs + 1, be)));
                } else {
                    node = node.get(field);
                }
            }
            if (node == null) {
                return missingResult;
            }
            String actual = node.isTextual() ? node.asText() : node.toString();
            if (actual.length() > limits.maxFieldValueLength()) {
                return false;
            }
            return cond.test(actual);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean matchXmlNodes(EvalContext context, Parsed cond, BodyView body) {
        try {
            List<String> values = xmlValues(context, cond.field, body);
            if (values == null) {
                return false;
            }
            for (String text : values) {
                if (text.length() > limits.maxFieldValueLength()) {
                    continue;
                }
                if (cond.test(text)) {
                    return true;
                }
            }
            return false;
        } catch (XPathExpressionException | RuntimeException e) {
            return false;
        }
    }

    // ==================== actual value (matching chain description) ====================

    private String actualValue(EvalContext context, Parsed described) {
        RequestView request = context.request();
        return switch (source) {
            case BODY -> bodyActual(context, described);
            case QUERY -> queryActual(request.queryString(), described.field);
            case HEADER -> headerActual(request.headers(), described.field);
        };
    }

    private String bodyActual(EvalContext context, Parsed described) {
        BodyView body = context.request().body();
        BodyType type = BodyType.of(body);
        if (type == BodyType.EMPTY || type == BodyType.TOO_LARGE) {
            return "null";
        }
        try {
            if (condition.startsWith("$.")) {
                JsonPathResult result = readJsonPath(context);
                if (!result.found) {
                    return "not found";
                }
                return result.value != null ? result.value : "null";
            }
            if (condition.startsWith("//")) {
                return type == BodyType.XML ? xmlActual(context, described.field, body) : "not found";
            }
            if (type == BodyType.JSON) {
                return jsonFieldActual(described.field, body.json());
            }
            if (type == BodyType.XML) {
                return xmlActual(context, described.field, body);
            }
        } catch (RuntimeException e) {
            // Preserve legacy behavior: display not found if value extraction fails.
        }
        return "not found";
    }

    private static String jsonFieldActual(String fieldPath, JsonNode root) {
        JsonNode node = root;
        for (String field : stripBodyPrefix(fieldPath).split("\\.")) {
            if (node == null) {
                return "not found";
            }
            if (field.contains("[")) {
                int bs = field.indexOf('[');
                int be = field.indexOf(']');
                node = node.get(field.substring(0, bs));
                if (node == null || !node.isArray()) {
                    return "not found";
                }
                node = node.get(Integer.parseInt(field.substring(bs + 1, be)));
            } else {
                node = node.get(field);
            }
        }
        if (node == null) {
            return "null";
        }
        return node.isTextual() ? node.asText() : node.toString();
    }

    private String xmlActual(EvalContext context, String field, BodyView body) {
        try {
            List<String> values = xmlValues(context, field, body);
            if (values != null && !values.isEmpty()) {
                return values.get(0);
            }
        } catch (XPathExpressionException | RuntimeException e) {
            // Preserve legacy behavior: display not found if value extraction fails.
        }
        return "not found";
    }

    private static String queryActual(String queryString, String field) {
        if (queryString == null || queryString.isBlank()) {
            return "null";
        }
        for (String param : queryString.split("&")) {
            int idx = param.indexOf('=');
            if (idx > 0 && field.equals(param.substring(0, idx))) {
                return param.substring(idx + 1);
            }
        }
        return "not found";
    }

    private static String headerActual(Map<String, String> headers, String field) {
        if (headers == null || headers.isEmpty()) {
            return "null";
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(field)) {
                return String.valueOf(entry.getValue());
            }
        }
        return "not found";
    }

    // ==================== XML lookup ====================

    /**
     * XML-body field values, trimmed and in document order; null for non-XML bodies.
     * Simple {@link XmlPath} expressions match local names, as streaming scans do. Cache results on the body within the request;
     * other expressions, including predicates, functions, and prefixed names, still use XPath and evaluate each field once per request.
     * Cache lookup failures too and rethrow the same exception, preserving legacy caller behavior.
     */
    private List<String> xmlValues(EvalContext context, String field, BodyView body)
            throws XPathExpressionException {
        if (xmlPath != null) {
            return body.xmlValues(xmlPath);
        }
        Document doc = body.xml();
        if (doc == null) {
            return null;
        }
        XmlLookup lookup = context.cachedValue(new XmlLookupKey(field), () -> {
            try {
                return new XmlLookup(texts(findXmlNodes(field, doc)));
            } catch (XPathExpressionException | RuntimeException e) {
                return XmlLookup.FAILED;
            }
        });
        if (lookup.values() == null) {
            throw new XPathExpressionException("XML lookup failed for " + field);
        }
        return lookup.values();
    }

    private static List<String> texts(NodeList nodes) {
        List<String> texts = new ArrayList<>(nodes.getLength());
        for (int i = 0; i < nodes.getLength(); i++) {
            texts.add(nodes.item(i).getTextContent().trim());
        }
        return texts;
    }

    private static NodeList findXmlNodes(String field, Document doc) throws XPathExpressionException {
        String simpleName = simpleElementName(field);
        if (simpleName != null) {
            NodeList nodes = doc.getElementsByTagName(simpleName);
            if (nodes.getLength() > 0) {
                return nodes;
            }
        }
        if (!field.startsWith("/") && field.indexOf('\'') < 0) {
            return elementsByLocalName(doc, field);
        }
        String xpath = field.startsWith("/")
                ? namespaceAgnosticXPath(field)
                : "//*[local-name()='" + field + "']";
        return (NodeList) compiledXPath(xpath).evaluate(doc, XPathConstants.NODESET);
    }

    /**
     * Equivalent to XPath {@code //*[local-name()='name']} in document order, but traverses the DOM directly, roughly an order of magnitude faster.
     * <p>
     * Older SOAP-like conditions (e.g. {@code CustomerId=...} vs. {@code <ns:CustomerId>}) end up here every time.
     * For a non-namespace-aware DOM, take the part after {@code :} in both local and node names, matching JDK XPath {@code local-name()}.
     * A name containing {@code '} makes the XPath literal invalid; keep the original XPath path to preserve that result.
     */
    private static NodeList elementsByLocalName(Document doc, String name) {
        NodeList all = doc.getElementsByTagName("*");
        List<Node> matches = new ArrayList<>();
        for (int i = 0; i < all.getLength(); i++) {
            Node node = all.item(i);
            if (name.equals(localName(node))) {
                matches.add(node);
            }
        }
        return new ListNodeList(matches);
    }

    private static String localName(Node node) {
        String localName = node.getLocalName();
        if (localName != null) {
            return localName;
        }
        String qualifiedName = node.getNodeName();
        int colon = qualifiedName.indexOf(':');
        return colon < 0 ? qualifiedName : qualifiedName.substring(colon + 1);
    }

    private record ListNodeList(List<Node> nodes) implements NodeList {

        ListNodeList {
            nodes = List.copyOf(nodes);
        }

        @Override
        public Node item(int index) {
            return index >= 0 && index < nodes.size() ? nodes.get(index) : null;
        }

        @Override
        public int getLength() {
            return nodes.size();
        }
    }

    /**
     * Each thread caches compiled XPath (XPathExpression is not thread-safe).
     * Legacy code shared a compiled-expression cache across threads; removing caching makes SOAP-style conditions roughly 15% slower.
     */
    private static XPathExpression compiledXPath(String xpath) throws XPathExpressionException {
        Map<String, XPathExpression> cache = XPATH_CACHE.get();
        XPathExpression compiled = cache.get(xpath);
        if (compiled == null) {
            XPath evaluator = XPATH.get();
            evaluator.reset();
            compiled = evaluator.compile(xpath);
            cache.put(xpath, compiled);
        }
        return compiled;
    }

    private static String simpleElementName(String field) {
        String name = field.startsWith("//") ? field.substring(2) : field;
        if (name.isEmpty() || name.contains("/") || name.contains("@")
                || name.contains("[") || name.contains("(") || name.contains(":")) {
            return null;
        }
        return name;
    }

    private static String namespaceAgnosticXPath(String xpath) {
        if (xpath.trim().isEmpty() || xpath.contains("local-name()")
                || xpath.contains("@") || xpath.contains("(") || xpath.contains("[")) {
            return xpath;
        }
        if (xpath.startsWith("//")) {
            String[] parts = xpath.substring(2).split("/");
            StringBuilder result = new StringBuilder("//*[local-name()='").append(escapeXml(parts[0])).append("']");
            for (int i = 1; i < parts.length; i++) {
                if (!parts[i].isEmpty()) {
                    result.append("/*[local-name()='").append(escapeXml(parts[i])).append("']");
                }
            }
            return result.toString();
        }
        if (xpath.startsWith("/")) {
            StringBuilder result = new StringBuilder();
            for (String part : xpath.substring(1).split("/")) {
                if (!part.isEmpty()) {
                    result.append("/*[local-name()='").append(escapeXml(part)).append("']");
                }
            }
            return result.toString();
        }
        return xpath;
    }

    private static String escapeXml(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    // ==================== Analysis and comparison ====================

    private static String stripBodyPrefix(String field) {
        return field.startsWith("body.") ? field.substring(5) : field;
    }

    private static JsonPath compileJsonPath(String path) {
        try {
            return JsonPath.compile(path);
        } catch (RuntimeException e) {
            // Legacy code compiles only during evaluation; failures such as InvalidPathException mean the field is missing.
            return null;
        }
    }

    private enum Op { EQUAL, NOT_EQUAL, CONTAINS, MATCHES }

    /** Legacy {@code parseCondition} + {@code matchValue}: determine the operator from the character before the first {@code =}. */
    private static final class Parsed {

        private final String field;
        private final String expected;
        private final Op op;
        private final Pattern pattern;
        private final MatcherLimits limits;

        private Parsed(String field, String value, Op op, MatcherLimits limits) {
            this.field = field;
            this.op = op;
            this.limits = limits;
            this.expected = value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                    ? value.substring(1, value.length() - 1) : value;
            this.pattern = op == Op.MATCHES ? compileRegex(expected, limits) : null;
        }

        static Parsed of(String condition, MatcherLimits limits) {
            return LegacyConditions.parts(condition)
                    .map(parts -> new Parsed(parts.field(), parts.value(), switch (parts.operator()) {
                        case "~=" -> Op.MATCHES;
                        case "*=" -> Op.CONTAINS;
                        case "!=" -> Op.NOT_EQUAL;
                        default -> Op.EQUAL;
                    }, limits))
                    .orElseGet(() -> new Parsed(condition, "", Op.EQUAL, limits));
        }

        boolean test(String actual) {
            if (actual == null) {
                return op == Op.NOT_EQUAL;
            }
            return switch (op) {
                case EQUAL -> expected.equals(actual);
                case NOT_EQUAL -> !expected.equals(actual);
                case CONTAINS -> actual.contains(expected);
                case MATCHES -> regexMatches(actual);
            };
        }

        private boolean regexMatches(String input) {
            if (pattern == null || input.length() > limits.maxRegexInputLength()) {
                return false;
            }
            try {
                return pattern.matcher(new InterruptibleCharSequence(input, limits.regexTimeoutMs())).matches();
            } catch (InterruptibleCharSequence.RegexTimeoutException e) {
                return false;
            }
        }

        /** Legacy code compiles only during matching; excessive length or invalid syntax means no match. */
        private static Pattern compileRegex(String regex, MatcherLimits limits) {
            if (regex.length() > limits.maxRegexPatternLength()) {
                return null;
            }
            try {
                return Pattern.compile(regex);
            } catch (PatternSyntaxException e) {
                return null;
            }
        }
    }

    /** Small access-order eviction cache. */
    private static final class LruCache<K, V> extends LinkedHashMap<K, V> {

        private static final long serialVersionUID = 1L;

        private final int maxSize;

        LruCache(int maxSize) {
            super(16, 0.75f, true);
            this.maxSize = maxSize;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
            return size() > maxSize;
        }
    }

    /** key of {@link EvalContext#cachedValue}; the same path string corresponds to the same result. */
    private record JsonPathKey(String path) {
    }

    private record XmlLookupKey(String field) {
    }

    /** {@code values} is null, which means the search failed (XPath syntax error, etc.). */
    private record XmlLookup(List<String> values) {
        static final XmlLookup FAILED = new XmlLookup(null);
    }

    private record JsonPathResult(boolean found, String value) {
        static final JsonPathResult MISSING = new JsonPathResult(false, null);
    }

    /** Corresponds to the old version {@code PreparedBody.BodyType}. */
    private enum BodyType {
        EMPTY, TOO_LARGE, JSON, XML, PLAIN;

        static boolean emptyOrTooLarge(BodyView body) {
            return body.raw() == null || body.raw().isBlank() || body.tooLarge();
        }

        static BodyType of(BodyView body) {
            String raw = body.raw();
            if (raw == null || raw.isBlank()) {
                return EMPTY;
            }
            if (body.tooLarge()) {
                return TOO_LARGE;
            }
            String trimmed = raw.trim();
            if (trimmed.startsWith("<")) {
                return body.xml() != null ? XML : PLAIN;
            }
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                return body.json() != null ? JSON : PLAIN;
            }
            return PLAIN;
        }
    }
}
