package com.echo.core.match;

import com.echo.core.util.InterruptibleCharSequence;
import com.fasterxml.jackson.databind.JsonNode;
import com.jayway.jsonpath.JsonPath;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 舊版字串條件片段的評估，逐行對應 {@code com.echo.service.ConditionMatcher} 的行為
 * （運算子解析、執行期 body 型別派送、各分支在欄位不存在時的結果、actual 值的描述文字）。
 * <p>
 * 刻意保留舊版的不一致之處（例如 JSONPath 欄位不存在時 {@code !=} 也不符合），由差異測試確保等價。
 * 唯一刻意的差異：期望值只有一個 {@code "} 時，舊版會丟出例外（HTTP 回 500），這裡視為字面值比對。
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
    /** 比對用的解析結果；JSON 點路徑會先把 {@code ==} 換成 {@code =}，其他分支不換。 */
    private final Parsed parsed;
    private final Parsed parsedWithoutDoubleEquals;
    private final JsonPath jsonPath;

    LegacyConditionMatcher(MatchNode.LegacySource source, String condition, MatcherLimits limits) {
        this.source = source;
        this.condition = condition;
        this.cacheKey = source.jsonName() + ":" + condition;
        this.limits = limits;
        this.parsed = Parsed.of(condition, limits);
        this.parsedWithoutDoubleEquals = Parsed.of(condition.replace("==", "="), limits);
        this.jsonPath = source == MatchNode.LegacySource.BODY && condition.startsWith("$.")
                ? compileJsonPath(parsed.field) : null;
    }

    @Override
    public boolean matches(EvalContext context) {
        return context.memoized(cacheKey, () -> evaluate(context.request()));
    }

    @Override
    public MatchExplanation explain(EvalContext context) {
        RequestView request = context.request();
        boolean passed = matches(context);
        Parsed described = source == MatchNode.LegacySource.BODY ? parsedWithoutDoubleEquals : parsed;
        String actual = described.field + "=" + actualValue(request, described);
        return new MatchExplanation("legacy." + source.jsonName(), passed, condition, actual, List.of());
    }

    @Override
    public String cacheKey() {
        return cacheKey;
    }

    private boolean evaluate(RequestView request) {
        return switch (source) {
            case BODY -> matchBody(request.body());
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

    private boolean matchBody(BodyView body) {
        BodyType type = BodyType.of(body);
        if (type == BodyType.EMPTY || type == BodyType.TOO_LARGE) {
            return false;
        }
        if (condition.startsWith("$.")) {
            return matchJsonPath(body.json());
        }
        if (condition.startsWith("//")) {
            return type == BodyType.XML && matchXmlNodes(parsed, body.xml());
        }
        if (type == BodyType.JSON) {
            return matchJsonField(body.json());
        }
        if (type == BodyType.XML) {
            return matchXmlNodes(parsed, body.xml());
        }
        return body.raw().trim().contains(condition);
    }

    private boolean matchJsonPath(JsonNode json) {
        JsonPathResult result = readJsonPath(json);
        if (!result.found) {
            return false;
        }
        if (result.value != null && result.value.length() > limits.maxFieldValueLength()) {
            return false;
        }
        return parsed.test(result.value);
    }

    private JsonPathResult readJsonPath(JsonNode json) {
        if (json == null || jsonPath == null) {
            return JsonPathResult.MISSING;
        }
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

    private boolean matchXmlNodes(Parsed cond, Document doc) {
        if (doc == null) {
            return false;
        }
        try {
            NodeList nodes = findXmlNodes(cond.field, doc);
            for (int i = 0; i < nodes.getLength(); i++) {
                String text = nodes.item(i).getTextContent().trim();
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

    // ==================== actual value（匹配鏈說明） ====================

    private String actualValue(RequestView request, Parsed described) {
        return switch (source) {
            case BODY -> bodyActual(request.body(), described);
            case QUERY -> queryActual(request.queryString(), described.field);
            case HEADER -> headerActual(request.headers(), described.field);
        };
    }

    private String bodyActual(BodyView body, Parsed described) {
        BodyType type = BodyType.of(body);
        if (type == BodyType.EMPTY || type == BodyType.TOO_LARGE) {
            return "null";
        }
        try {
            if (condition.startsWith("$.")) {
                JsonPathResult result = readJsonPath(body.json());
                if (!result.found) {
                    return "not found";
                }
                return result.value != null ? result.value : "null";
            }
            if (condition.startsWith("//")) {
                return type == BodyType.XML ? xmlActual(described.field, body.xml()) : "not found";
            }
            if (type == BodyType.JSON) {
                return jsonFieldActual(described.field, body.json());
            }
            if (type == BodyType.XML) {
                return xmlActual(described.field, body.xml());
            }
        } catch (RuntimeException e) {
            // 與舊版相同：取值失敗時顯示 not found
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

    private static String xmlActual(String field, Document doc) {
        try {
            NodeList nodes = findXmlNodes(field, doc);
            if (nodes.getLength() > 0) {
                return nodes.item(0).getTextContent().trim();
            }
        } catch (XPathExpressionException | RuntimeException e) {
            // 與舊版相同：取值失敗時顯示 not found
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

    private static NodeList findXmlNodes(String field, Document doc) throws XPathExpressionException {
        String simpleName = simpleElementName(field);
        if (simpleName != null) {
            NodeList nodes = doc.getElementsByTagName(simpleName);
            if (nodes.getLength() > 0) {
                return nodes;
            }
        }
        String xpath = field.startsWith("/")
                ? namespaceAgnosticXPath(field)
                : "//*[local-name()='" + field + "']";
        return (NodeList) compiledXPath(xpath).evaluate(doc, XPathConstants.NODESET);
    }

    /**
     * 每個執行緒各自快取編譯後的 XPath（XPathExpression 不是執行緒安全的）。
     * 舊版以跨執行緒共用的快取避免重複編譯；少了快取時 SOAP 類條件會慢約 15%。
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

    // ==================== 解析與比對 ====================

    private static String stripBodyPrefix(String field) {
        return field.startsWith("body.") ? field.substring(5) : field;
    }

    private static JsonPath compileJsonPath(String path) {
        try {
            return JsonPath.compile(path);
        } catch (RuntimeException e) {
            // InvalidPathException 等：舊版在評估時才編譯，失敗視為欄位不存在
            return null;
        }
    }

    private enum Op { EQUAL, NOT_EQUAL, CONTAINS, MATCHES }

    /** 舊版 {@code parseCondition} + {@code matchValue}：以第一個 {@code =} 前一個字元決定運算子。 */
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
            int eqIdx = condition.indexOf('=');
            if (eqIdx <= 0) {
                return new Parsed(condition, "", Op.EQUAL, limits);
            }
            String value = condition.substring(eqIdx + 1).trim();
            return switch (condition.charAt(eqIdx - 1)) {
                case '~' -> new Parsed(condition.substring(0, eqIdx - 1).trim(), value, Op.MATCHES, limits);
                case '*' -> new Parsed(condition.substring(0, eqIdx - 1).trim(), value, Op.CONTAINS, limits);
                case '!' -> new Parsed(condition.substring(0, eqIdx - 1).trim(), value, Op.NOT_EQUAL, limits);
                default -> new Parsed(condition.substring(0, eqIdx).trim(), value, Op.EQUAL, limits);
            };
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

        /** 舊版在比對時才編譯，過長或語法錯誤都視為不符合。 */
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

    /** 依存取順序淘汰的小型快取。 */
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

    private record JsonPathResult(boolean found, String value) {
        static final JsonPathResult MISSING = new JsonPathResult(false, null);
    }

    /** 對應舊版 {@code PreparedBody.BodyType}。 */
    private enum BodyType {
        EMPTY, TOO_LARGE, JSON, XML, PLAIN;

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
