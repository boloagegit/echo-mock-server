package com.echo.core.match;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.InvalidPathException;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.spi.json.JacksonJsonNodeJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.namespace.QName;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 內建取值來源：{@code body}、{@code query}、{@code header}、{@code method}、{@code path}。
 */
public final class BuiltinSources {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    static final Configuration JSON_PATH_CONFIGURATION = Configuration.builder()
            .jsonProvider(new JacksonJsonNodeJsonProvider(MAPPER))
            .mappingProvider(new JacksonMappingProvider(MAPPER))
            .build();
    private static final ThreadLocal<XPath> XPATH =
            ThreadLocal.withInitial(() -> XPathFactory.newInstance().newXPath());
    private static final Pattern FIELD_SEGMENT = Pattern.compile("([^.\\[\\]]+)((?:\\[\\d+])*)");
    private static final Pattern INDEX = Pattern.compile("\\[(\\d+)]");

    private BuiltinSources() {
    }

    public static List<ValueSourceFactory> all() {
        return List.of(
                new SimpleSource("body", false, EnumSet.allOf(Selector.Kind.class),
                        (name, selector, limits) -> body(selector)),
                new SimpleSource("query", true, Set.of(),
                        (name, selector, limits) -> request -> query(request.queryString(), name)),
                new SimpleSource("header", true, Set.of(),
                        (name, selector, limits) -> request -> single(request.header(name))),
                new SimpleSource("method", false, Set.of(),
                        (name, selector, limits) -> request -> single(request.method())),
                new SimpleSource("path", false, Set.of(),
                        (name, selector, limits) -> request -> single(request.path())));
    }

    private static Extracted single(String value) {
        return value == null ? Extracted.missing() : Extracted.of(Value.ofText(value));
    }

    // ==================== query ====================

    private static Extracted query(String queryString, String name) {
        if (queryString == null || queryString.isEmpty()) {
            return Extracted.missing();
        }
        List<Value> values = new ArrayList<>();
        for (String pair : queryString.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int idx = pair.indexOf('=');
            String key = decode(idx < 0 ? pair : pair.substring(0, idx));
            if (name.equals(key)) {
                values.add(Value.ofText(idx < 0 ? "" : decode(pair.substring(idx + 1))));
            }
        }
        return Extracted.of(values);
    }

    private static String decode(String text) {
        try {
            return URLDecoder.decode(text, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return text;
        }
    }

    // ==================== body ====================

    private static ValueExtractor body(Selector selector) {
        ValueExtractor extractor = switch (selector.kind()) {
            case RAW -> request -> single(request.body().raw());
            case JSON_PATH -> jsonPath(selector.expression());
            case XPATH -> xpath(selector.expression());
            case FIELD -> field(selector.expression());
        };
        return request -> request.body().tooLarge() ? Extracted.unavailable() : extractor.extract(request);
    }

    private static ValueExtractor jsonPath(String expression) {
        JsonPath path;
        try {
            path = JsonPath.compile(expression);
        } catch (InvalidPathException e) {
            // Jayway 的訊息常是內部例外（例如 StringIndexOutOfBoundsException），對使用者沒有幫助
            throw new IllegalArgumentException("Invalid JSONPath: " + expression, e);
        }
        boolean definite = path.isDefinite();
        return request -> {
            JsonNode json = request.body().json();
            if (json == null) {
                return Extracted.missing();
            }
            JsonNode result;
            try {
                Object value = path.read(json, JSON_PATH_CONFIGURATION);
                result = value instanceof JsonNode node ? node : MAPPER.valueToTree(value);
            } catch (RuntimeException e) {
                // PathNotFoundException 以及對錯誤型別套用 filter 等，都視為欄位不存在。
                return Extracted.missing();
            }
            if (definite || !result.isArray()) {
                return Extracted.of(Value.ofJson(result));
            }
            List<Value> values = new ArrayList<>(result.size());
            result.forEach(node -> values.add(Value.ofJson(node)));
            return Extracted.of(values);
        };
    }

    private static ValueExtractor xpath(String expression) {
        try {
            XPATH.get().compile(expression);
        } catch (XPathExpressionException e) {
            throw new IllegalArgumentException("Invalid XPath: " + expression, e);
        }
        return request -> {
            Document doc = request.body().xml();
            if (doc == null) {
                return Extracted.missing();
            }
            try {
                NodeList nodes = (NodeList) evaluateXPath(expression, doc, XPathConstants.NODESET);
                List<Value> values = new ArrayList<>(nodes.getLength());
                for (int i = 0; i < nodes.getLength(); i++) {
                    values.add(Value.ofText(nodes.item(i).getTextContent().trim()));
                }
                return Extracted.of(values);
            } catch (XPathExpressionException nodeSetFailure) {
                // 例如 count(//Item)：結果不是節點集合，改取字串值。
                try {
                    return Extracted.of(Value.ofText((String) evaluateXPath(expression, doc, XPathConstants.STRING)));
                } catch (XPathExpressionException e) {
                    return Extracted.missing();
                }
            }
        };
    }

    private static Object evaluateXPath(String expression, Document doc, QName type)
            throws XPathExpressionException {
        XPath xpath = XPATH.get();
        xpath.reset();
        return xpath.compile(expression).evaluate(doc, type);
    }

    private static ValueExtractor field(String expression) {
        List<Object> steps = parseFieldPath(expression);
        return request -> {
            JsonNode node = request.body().json();
            for (Object step : steps) {
                if (node == null) {
                    return Extracted.missing();
                }
                node = step instanceof Integer index
                        ? (node.isArray() ? node.get(index) : null)
                        : node.get((String) step);
            }
            return node == null ? Extracted.missing() : Extracted.of(Value.ofJson(node));
        };
    }

    /** 把 {@code a.b[0][1].c} 拆成 {@code ["a", "b", 0, 1, "c"]}。 */
    private static List<Object> parseFieldPath(String expression) {
        List<Object> steps = new ArrayList<>();
        for (String segment : expression.split("\\.", -1)) {
            Matcher matcher = FIELD_SEGMENT.matcher(segment);
            if (!matcher.matches()) {
                throw new IllegalArgumentException("Invalid field path: " + expression);
            }
            steps.add(matcher.group(1));
            Matcher index = INDEX.matcher(matcher.group(2));
            while (index.find()) {
                try {
                    steps.add(Integer.parseInt(index.group(1)));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid field path index: " + expression, e);
                }
            }
        }
        return List.copyOf(steps);
    }

    @FunctionalInterface
    private interface ExtractorCreator {
        ValueExtractor create(String name, Selector selector, MatcherLimits limits);
    }

    private record SimpleSource(String source, boolean requiresName, Set<Selector.Kind> supportedSelectors,
                                ExtractorCreator creator) implements ValueSourceFactory {

        SimpleSource {
            supportedSelectors = supportedSelectors.isEmpty() ? Set.of() : EnumSet.copyOf(supportedSelectors);
        }

        @Override
        public Set<Selector.Kind> supportedSelectors() {
            return supportedSelectors.isEmpty() ? Set.of() : EnumSet.copyOf(supportedSelectors);
        }

        @Override
        public ValueExtractor create(String name, Selector selector, MatcherLimits limits) {
            return creator.create(name, selector, limits);
        }
    }
}
