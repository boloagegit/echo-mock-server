package com.echo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathFactory;
import jakarta.annotation.PreDestroy;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 條件匹配服務
 * <p>
 * 支援多種格式的請求內容匹配，類似 WireMock 風格：
 *
 * <h3>支援的運算子</h3>
 * <ul>
 *   <li>{@code field=value} - 等於（預設）</li>
 *   <li>{@code field~=pattern} - 正規表達式匹配</li>
 *   <li>{@code field*=substring} - 包含子字串</li>
 *   <li>{@code field!=value} - 不等於</li>
 * </ul>
 *
 * <h3>支援的路徑格式</h3>
 * <ul>
 *   <li>{@code $.jsonPath} - JSON Path 表達式</li>
 *   <li>{@code //xpath} - XPath 表達式</li>
 *   <li>{@code ?param} - Query 參數</li>
 *   <li>{@code field.nested} - JSON 欄位路徑</li>
 * </ul>
 */
@Service
@Slf4j
public class ConditionMatcher {

    private static final int MAX_XML_SIZE = 10 * 1024 * 1024; // 10MB
    private static final int MAX_JSON_SIZE = 10 * 1024 * 1024; // 10MB
    private static final int MAX_REGEX_INPUT_LENGTH = 10000;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Cache<String, Pattern> patternCache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterAccess(1, TimeUnit.HOURS)
            .build();

    private final Cache<String, XPathExpression> xpathExprCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterAccess(1, TimeUnit.HOURS)
            .build();

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

    private static final ThreadLocal<XPath> XPATH = ThreadLocal.withInitial(() ->
        XPathFactory.newInstance().newXPath()
    );

    @PreDestroy
    public void cleanup() {
        DOC_BUILDER.remove();
        XPATH.remove();
        patternCache.invalidateAll();
        xpathExprCache.invalidateAll();
    }

    private NodeList evaluateXPath(String xpathExpr, Document doc) throws javax.xml.xpath.XPathExpressionException {
        XPathExpression compiled = xpathExprCache.getIfPresent(xpathExpr);
        if (compiled == null) {
            XPath xPath = XPATH.get();
            xPath.reset();
            compiled = xPath.compile(xpathExpr);
            xpathExprCache.put(xpathExpr, compiled);
        }
        return (NodeList) compiled.evaluate(doc, XPathConstants.NODESET);
    }

    private static String extractSimpleElementName(String field) {
        String name = field.startsWith("//") ? field.substring(2) : field;
        if (name.isEmpty() || name.contains("/") || name.contains("@") ||
            name.contains("[") || name.contains("(") || name.contains(":")) {
            return null;
        }
        return name;
    }

    private NodeList findXmlNodes(String field, Document doc) throws javax.xml.xpath.XPathExpressionException {
        String simpleName = extractSimpleElementName(field);
        if (simpleName != null) {
            NodeList nodes = doc.getElementsByTagName(simpleName);
            if (nodes.getLength() > 0) {
                return nodes;
            }
        }
        String xpath = field.startsWith("/")
                ? convertToNamespaceAgnosticXPath(field)
                : "//*[local-name()='" + field + "']";
        return evaluateXPath(xpath, doc);
    }

    // ==================== Prepared Body ====================

    /**
     * 預先解析的 Body 容器
     * <p>
     * 在批次匹配（多條規則比對同一個 body）時，先 parse 一次 body，
     * 後續每條規則直接使用已 parse 的物件，避免重複 parse。
     */
    public static class PreparedBody {
        private final String raw;
        private final Document xmlDoc;
        private final JsonNode jsonNode;
        private final BodyType type;

        enum BodyType { XML, JSON, PLAIN, EMPTY, TOO_LARGE }

        private PreparedBody(String raw, Document xmlDoc, JsonNode jsonNode, BodyType type) {
            this.raw = raw;
            this.xmlDoc = xmlDoc;
            this.jsonNode = jsonNode;
            this.type = type;
        }

        public static PreparedBody empty() {
            return new PreparedBody(null, null, null, BodyType.EMPTY);
        }

        public static PreparedBody tooLarge() {
            return new PreparedBody(null, null, null, BodyType.TOO_LARGE);
        }

        /** 取得已解析的 XML Document，若非 XML 則為 null */
        public Document getXmlDoc() {
            return xmlDoc;
        }

        /** 取得已解析的 JSON 節點，若非 JSON 則為 null */
        public JsonNode getJsonNode() {
            return jsonNode;
        }

        /** 取得原始 body 字串 */
        public String getRaw() {
            return raw;
        }
    }

    /**
     * 預先解析 body，回傳 PreparedBody 供批次匹配使用
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
                JsonNode node = objectMapper.readTree(trimmed);
                return new PreparedBody(trimmed, null, node, PreparedBody.BodyType.JSON);
            }
        } catch (Exception e) {
            log.warn("Failed to prepare body: {}", e.getMessage());
        }
        return new PreparedBody(trimmed, null, null, PreparedBody.BodyType.PLAIN);
    }

    // ==================== Public Matching API ====================

    /**
     * 快速布林匹配（使用預先解析的 body，不建立 detail 物件）
     * <p>
     * 用於規則匹配迴圈，效能最佳。
     */
    public boolean matchesPrepared(String bodyCondition, String queryCondition, String headerCondition,
                                   PreparedBody prepared, String queryString, Map<String, String> headers) {
        if (bodyCondition != null && !bodyCondition.isBlank()) {
            for (String c : bodyCondition.split(";")) {
                String trimmed = c.trim();
                if (!trimmed.isEmpty() && !matchBodyPrepared(trimmed, prepared)) {
                    return false;
                }
            }
        }
        if (queryCondition != null && !queryCondition.isBlank()) {
            for (String c : queryCondition.split(";")) {
                String trimmed = c.trim();
                if (!trimmed.isEmpty() && !matchQueryParam(trimmed, queryString)) {
                    return false;
                }
            }
        }
        if (headerCondition != null && !headerCondition.isBlank()) {
            for (String c : headerCondition.split(";")) {
                String trimmed = c.trim();
                if (!trimmed.isEmpty() && !matchHeader(trimmed, headers)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 結構化匹配（使用預先解析的 body，回傳每個條件的 PASS/FAIL 細節）
     * <p>
     * 用於匹配鏈分析，提供 near-miss 偵測所需的詳細資訊。
     */
    public ConditionDetail matchesPreparedWithDetail(String bodyCondition, String queryCondition, String headerCondition,
                                                     PreparedBody prepared, String queryString, Map<String, String> headers) {
        List<ConditionResult> results = new ArrayList<>();

        evaluateConditions(bodyCondition, "bodyCondition", results,
                c -> matchBodyPrepared(c, prepared),
                c -> buildBodyDetail(c, prepared),
                c -> "bodyCondition PASS (" + c + ")");

        evaluateConditions(queryCondition, "queryCondition", results,
                c -> matchQueryParam(c, queryString),
                c -> buildQueryDetail(c, queryString),
                c -> "queryCondition PASS (" + c + ")");

        evaluateConditions(headerCondition, "headerCondition", results,
                c -> matchHeader(c, headers),
                c -> buildHeaderDetail(c, headers),
                c -> "headerCondition PASS (" + c + ")");

        boolean overallMatch = results.stream().allMatch(ConditionResult::isPassed);
        return ConditionDetail.builder()
                .overallMatch(overallMatch)
                .results(results)
                .build();
    }

    /**
     * 拆分條件字串並逐一評估，將結果加入 results 列表。
     */
    private void evaluateConditions(String conditionStr, String type, List<ConditionResult> results,
                                    java.util.function.Predicate<String> matcher,
                                    java.util.function.Function<String, String> failDetailBuilder,
                                    java.util.function.Function<String, String> passDetailBuilder) {
        if (conditionStr == null || conditionStr.isBlank()) {
            return;
        }
        for (String c : conditionStr.split(";")) {
            String trimmed = c.trim();
            if (!trimmed.isEmpty()) {
                boolean passed = matcher.test(trimmed);
                String detail = passed ? passDetailBuilder.apply(trimmed) : failDetailBuilder.apply(trimmed);
                results.add(ConditionResult.builder()
                        .type(type)
                        .condition(trimmed)
                        .passed(passed)
                        .detail(detail)
                        .build());
            }
        }
    }

    // ==================== Detail Building Helpers ====================

    private String buildBodyDetail(String condition, PreparedBody prepared) {
        var parsed = parseCondition(condition.replace("==", "="));
        String actual = extractBodyActualValue(condition, parsed, prepared);
        return "bodyCondition FAIL (expected " + condition + ", actual " + parsed.field() + "=" + actual + ")";
    }

    private String extractBodyActualValue(String condition, ParsedCondition parsed, PreparedBody prepared) {
        if (prepared.type == PreparedBody.BodyType.EMPTY || prepared.type == PreparedBody.BodyType.TOO_LARGE) {
            return "null";
        }
        try {
            if (condition.startsWith("$.")) {
                return extractJsonPathActual(parsed.field(), prepared.raw);
            }
            if (condition.startsWith("//")) {
                if (prepared.xmlDoc != null) {
                    return extractXmlActual(parsed.field(), prepared.xmlDoc);
                }
                return "not found";
            }
            if (prepared.jsonNode != null) {
                return extractJsonActual(parsed.field(), prepared.jsonNode);
            }
            if (prepared.xmlDoc != null) {
                return extractXmlActual(parsed.field(), prepared.xmlDoc);
            }
        } catch (Exception e) {
            // fall through
        }
        return "not found";
    }

    private String extractJsonActual(String fieldPath, JsonNode root) {
        String path = fieldPath.startsWith("body.") ? fieldPath.substring(5) : fieldPath;
        JsonNode node = root;
        for (String field : path.split("\\.")) {
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

    private String extractJsonPathActual(String jsonPath, String body) {
        try {
            Object result = JsonPath.read(body, jsonPath);
            return result != null ? result.toString() : "null";
        } catch (PathNotFoundException e) {
            return "not found";
        } catch (Exception e) {
            return "not found";
        }
    }

    private String extractXmlActual(String field, Document doc) {
        try {
            NodeList nodes = findXmlNodes(field, doc);
            if (nodes.getLength() > 0) {
                return nodes.item(0).getTextContent().trim();
            }
        } catch (Exception e) {
            // fall through
        }
        return "not found";
    }

    private String buildQueryDetail(String condition, String queryString) {
        var parsed = parseCondition(condition);
        String actual = extractQueryActual(parsed.field(), queryString);
        return "queryCondition FAIL (expected " + condition + ", actual " + parsed.field() + "=" + actual + ")";
    }

    private String extractQueryActual(String field, String queryString) {
        if (queryString == null || queryString.isBlank()) {
            return "null";
        }
        String[] params = queryString.split("&");
        for (String param : params) {
            int idx = param.indexOf('=');
            if (idx > 0 && field.equals(param.substring(0, idx))) {
                return param.substring(idx + 1);
            }
        }
        return "not found";
    }

    private String buildHeaderDetail(String condition, Map<String, String> headers) {
        var parsed = parseCondition(condition);
        String actual = extractHeaderActual(parsed.field(), headers);
        return "headerCondition FAIL (expected " + condition + ", actual " + parsed.field() + "=" + actual + ")";
    }

    private String extractHeaderActual(String field, Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return "null";
        }
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(field)) {
                return e.getValue();
            }
        }
        return "not found";
    }

    /**
     * 簡易匹配（自動解析 body）
     */
    public boolean matches(String bodyCondition, String queryCondition, String headerCondition,
                          String body, String queryString, Map<String, String> headers) {
        PreparedBody prepared = prepareBody(body);
        return matchesPrepared(bodyCondition, queryCondition, headerCondition, prepared, queryString, headers);
    }

    public boolean matches(String bodyCondition, String queryCondition, String body, String queryString) {
        return matches(bodyCondition, queryCondition, null, body, queryString, null);
    }

    public boolean matches(String condition, String body, String queryString) {
        if (condition == null || condition.isBlank()) {
            return true;
        }
        String[] conditions = condition.split(";");
        for (String cond : conditions) {
            String c = cond.trim();
            if (c.isEmpty()) {
                continue;
            }
            if (c.startsWith("?")) {
                if (!matchQueryParam(c.substring(1), queryString)) {
                    return false;
                }
            } else {
                PreparedBody prepared = prepareBody(body);
                if (!matchBodyPrepared(c, prepared)) {
                    return false;
                }
            }
        }
        return true;
    }

    public boolean matches(String condition, String body) {
        return matches(condition, body, null);
    }

    // ==================== Private Matching Methods ====================

    private boolean matchHeader(String condition, Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return false;
        }
        var parsed = parseCondition(condition);
        String actualValue = headers.entrySet().stream()
            .filter(e -> e.getKey().equalsIgnoreCase(parsed.field))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElse(null);
        return matchValue(parsed, actualValue);
    }

    private boolean matchQueryParam(String condition, String queryString) {
        var parsed = parseCondition(condition);
        if (queryString == null || queryString.isBlank()) {
            return matchValue(parsed, null);
        }
        String[] params = queryString.split("&");
        for (String param : params) {
            int idx = param.indexOf('=');
            if (idx > 0 && parsed.field.equals(param.substring(0, idx))) {
                return matchValue(parsed, param.substring(idx + 1));
            }
        }
        return matchValue(parsed, null);
    }

    private boolean matchBodyPrepared(String condition, PreparedBody prepared) {
        if (prepared.type == PreparedBody.BodyType.EMPTY || prepared.type == PreparedBody.BodyType.TOO_LARGE) {
            return false;
        }
        if (condition.startsWith("$.")) {
            return matchJsonPath(condition, prepared.raw);
        }
        if (condition.startsWith("//")) {
            if (prepared.xmlDoc != null) {
                return matchXPathPrepared(condition, prepared.xmlDoc);
            }
            return matchXPath(condition, prepared.raw);
        }
        if (prepared.jsonNode != null) {
            return matchJsonPrepared(condition, prepared.jsonNode);
        }
        if (prepared.xmlDoc != null) {
            return matchXmlPrepared(condition, prepared.xmlDoc);
        }
        return prepared.raw != null && prepared.raw.contains(condition);
    }

    private boolean matchXPathPrepared(String condition, Document doc) {
        var parsed = parseCondition(condition);
        try {
            NodeList nodes = findXmlNodes(parsed.field(), doc);
            for (int i = 0; i < nodes.getLength(); i++) {
                if (matchValue(parsed, nodes.item(i).getTextContent().trim())) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean matchXmlPrepared(String condition, Document doc) {
        var parsed = parseCondition(condition);
        try {
            NodeList nodes = findXmlNodes(parsed.field(), doc);
            for (int i = 0; i < nodes.getLength(); i++) {
                if (matchValue(parsed, nodes.item(i).getTextContent().trim())) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean matchJsonPrepared(String condition, JsonNode root) {
        var parsed = parseCondition(condition.replace("==", "="));
        try {
            String path = parsed.field().startsWith("body.") ? parsed.field().substring(5) : parsed.field();
            JsonNode node = root;
            for (String field : path.split("\\.")) {
                if (node == null) {
                    return parsed.op() == Op.NOT_EQUAL;
                }
                if (field.contains("[")) {
                    int bs = field.indexOf('['), be = field.indexOf(']');
                    node = node.get(field.substring(0, bs));
                    if (node == null || !node.isArray()) {
                        return parsed.op() == Op.NOT_EQUAL;
                    }
                    node = node.get(Integer.parseInt(field.substring(bs + 1, be)));
                } else {
                    node = node.get(field);
                }
            }
            if (node == null) {
                return parsed.op() == Op.NOT_EQUAL;
            }
            String actual = node.isTextual() ? node.asText() : node.toString();
            return matchValue(parsed, actual);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean matchJsonPath(String condition, String body) {
        try {
            var parsed = parseCondition(condition);
            Object result = JsonPath.read(body, parsed.field);
            String actual = result != null ? result.toString() : null;
            return matchValue(parsed, actual);
        } catch (PathNotFoundException e) {
            return false;
        } catch (Exception e) {
            log.warn("JsonPath match failed: {}", e.getMessage());
            return false;
        }
    }

    private boolean matchXPath(String condition, String body) {
        if (body.length() > MAX_XML_SIZE) {
            log.warn("XML too large for processing: {} bytes", body.length());
            return false;
        }
        try {
            var parsed = parseCondition(condition);
            DocumentBuilder builder = DOC_BUILDER.get();
            builder.reset();
            Document doc = builder.parse(new InputSource(new StringReader(body)));
            NodeList nodes = findXmlNodes(parsed.field, doc);
            for (int i = 0; i < nodes.getLength(); i++) {
                if (matchValue(parsed, nodes.item(i).getTextContent().trim())) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            log.warn("XPath match failed: {}", e.getMessage());
            return false;
        }
    }

    // ==================== Condition Parsing ====================

    private ParsedCondition parseCondition(String condition) {
        int eqIdx = condition.indexOf('=');
        if (eqIdx <= 0) {
            return new ParsedCondition(condition, "", Op.EQUAL);
        }
        char prev = condition.charAt(eqIdx - 1);
        if (prev == '~') {
            return new ParsedCondition(
                condition.substring(0, eqIdx - 1).trim(),
                condition.substring(eqIdx + 1).trim(), Op.MATCHES);
        }
        if (prev == '*') {
            return new ParsedCondition(
                condition.substring(0, eqIdx - 1).trim(),
                condition.substring(eqIdx + 1).trim(), Op.CONTAINS);
        }
        if (prev == '!') {
            return new ParsedCondition(
                condition.substring(0, eqIdx - 1).trim(),
                condition.substring(eqIdx + 1).trim(), Op.NOT_EQUAL);
        }
        return new ParsedCondition(
            condition.substring(0, eqIdx).trim(),
            condition.substring(eqIdx + 1).trim(), Op.EQUAL);
    }

    private boolean matchValue(ParsedCondition cond, String actual) {
        if (actual == null) {
            return cond.op == Op.NOT_EQUAL;
        }
        String expected = cond.value;
        if (expected.startsWith("\"") && expected.endsWith("\"")) {
            expected = expected.substring(1, expected.length() - 1);
        }
        return switch (cond.op) {
            case EQUAL -> expected.equals(actual);
            case NOT_EQUAL -> !expected.equals(actual);
            case CONTAINS -> actual.contains(expected);
            case MATCHES -> safeRegexMatch(expected, actual);
        };
    }

    private boolean safeRegexMatch(String pattern, String input) {
        if (input.length() > MAX_REGEX_INPUT_LENGTH) {
            log.warn("Input too long for regex match: {} chars", input.length());
            return false;
        }
        try {
            Pattern compiledPattern = patternCache.getIfPresent(pattern);
            if (compiledPattern == null) {
                try {
                    compiledPattern = Pattern.compile(pattern);
                    patternCache.put(pattern, compiledPattern);
                } catch (Exception e) {
                    log.warn("Invalid regex pattern: {}", pattern);
                    return false;
                }
            }
            return compiledPattern.matcher(input).matches();
        } catch (Exception e) {
            log.warn("Regex match failed: {}", e.getMessage());
            return false;
        }
    }

    // ==================== XPath Utilities ====================

    private String convertToNamespaceAgnosticXPath(String xpath) {
        if (xpath == null || xpath.trim().isEmpty()) {
            return xpath;
        }
        if (xpath.contains("local-name()")) {
            return xpath;
        }
        if (xpath.contains("@") || xpath.contains("(") || xpath.contains("[")) {
            return xpath;
        }
        if (xpath.startsWith("//")) {
            String path = xpath.substring(2);
            String[] parts = path.split("/");
            StringBuilder result = new StringBuilder("//*[local-name()='").append(escapeXmlAttribute(parts[0])).append("']");
            for (int i = 1; i < parts.length; i++) {
                if (!parts[i].isEmpty()) {
                    result.append("/*[local-name()='").append(escapeXmlAttribute(parts[i])).append("']");
                }
            }
            return result.toString();
        }
        if (xpath.startsWith("/")) {
            String[] parts = xpath.substring(1).split("/");
            StringBuilder result = new StringBuilder();
            for (String part : parts) {
                if (!part.isEmpty()) {
                    result.append("/*[local-name()='").append(escapeXmlAttribute(part)).append("']");
                }
            }
            return result.toString();
        }
        return xpath;
    }

    private String escapeXmlAttribute(String str) {
        if (str == null) {
            return null;
        }
        return str.replace("&", "&amp;")
                  .replace("<", "&lt;")
                  .replace(">", "&gt;")
                  .replace("\"", "&quot;")
                  .replace("'", "&apos;");
    }

    private enum Op { EQUAL, NOT_EQUAL, CONTAINS, MATCHES }
    private record ParsedCondition(String field, String value, Op op) {}

    // ==================== Structured Match Result Classes ====================

    /**
     * 結構化匹配結果，包含所有條件的 PASS/FAIL 細節
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
     * 單一條件的匹配結果
     */
    @Getter
    @Builder
    public static class ConditionResult {
        private final String type;       // "bodyCondition", "queryCondition", "headerCondition"
        private final String condition;  // 原始條件字串
        private final boolean passed;
        private final String detail;     // e.g., "bodyCondition PASS (user.type=vip)"
    }
}
