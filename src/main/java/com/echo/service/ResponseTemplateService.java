package com.echo.service;

import com.echo.core.util.InterruptibleCharSequence;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.jknack.handlebars.Context;
import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Helper;
import com.github.jknack.handlebars.Options;
import com.github.jknack.handlebars.Template;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import jakarta.annotation.PreDestroy;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.IOException;
import java.io.StringReader;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Response Template Service - WireMock 風格的回應模板引擎
 * 
 * 支援：
 * - {{request.path}}, {{request.pathSegments.[n]}}
 * - {{request.method}}
 * - {{request.query.xxx}}
 * - {{request.headers.xxx}}
 * - {{request.body}}
 * - {{now}}, {{now format='xxx'}}
 * - {{randomValue type='UUID'}}, {{randomValue length=n type='ALPHANUMERIC'}}
 * - Faker helpers: {{randomFirstName}}, {{randomLastName}}, {{randomFullName}},
 *   {{randomEmail}}, {{randomPhoneNumber}}, {{randomCity}}, {{randomCountry}},
 *   {{randomStreetAddress}}, {{randomInt min=0 max=100}}
 */
@Service
public class ResponseTemplateService {

    private static final Logger log = LoggerFactory.getLogger(ResponseTemplateService.class);
    private static final String ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final String NUMERIC = "0123456789";
    private static final String ALPHABETIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int MAX_RANDOM_LENGTH = 10_000;
    private static final int DEFAULT_TEMPLATE_CACHE_MIB = 16;
    private static final long MEBIBYTE = 1024L * 1024L;

    // Faker data arrays
    private static final String[] FIRST_NAMES = {
        "James", "Mary", "John", "Patricia", "Robert", "Jennifer", "Michael", "Linda",
        "William", "Elizabeth", "David", "Barbara", "Richard", "Susan", "Joseph", "Jessica",
        "Thomas", "Sarah", "Charles", "Karen", "Daniel", "Emily", "Matthew", "Donna",
        "Anthony", "Michelle", "Mark", "Laura", "Steven", "Ashley"
    };
    private static final String[] LAST_NAMES = {
        "Smith", "Johnson", "Williams", "Brown", "Jones", "Garcia", "Miller", "Davis",
        "Rodriguez", "Martinez", "Hernandez", "Lopez", "Gonzalez", "Wilson", "Anderson",
        "Thomas", "Taylor", "Moore", "Jackson", "Martin", "Lee", "Perez", "Thompson",
        "White", "Harris", "Sanchez", "Clark", "Ramirez", "Lewis", "Robinson"
    };
    private static final String[] CITIES = {
        "New York", "Los Angeles", "Chicago", "Houston", "Phoenix", "Philadelphia",
        "San Antonio", "San Diego", "Dallas", "San Jose", "Austin", "Jacksonville",
        "Fort Worth", "Columbus", "Charlotte", "Indianapolis", "San Francisco",
        "Seattle", "Denver", "Boston", "Nashville", "Portland", "Las Vegas", "Memphis"
    };
    private static final String[] COUNTRIES = {
        "United States", "United Kingdom", "Canada", "Australia", "Germany", "France",
        "Japan", "South Korea", "Brazil", "India", "Mexico", "Italy", "Spain",
        "Netherlands", "Sweden", "Norway", "Denmark", "Finland", "Switzerland",
        "New Zealand", "Singapore", "Ireland", "Belgium", "Austria"
    };
    private static final String[] STREETS = {
        "Main St", "Oak Ave", "Cedar Ln", "Elm St", "Pine Rd", "Maple Dr",
        "Washington Blvd", "Park Ave", "Lake St", "Hill Rd", "River Dr",
        "Sunset Blvd", "Broadway", "Highland Ave", "Forest Ln", "Spring St",
        "Valley Rd", "Church St", "Mill Rd", "Academy St"
    };
    private static final String[] EMAIL_DOMAINS = {
        "example.com", "test.com", "demo.org", "sample.net", "mock.io"
    };

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

    /** 每個執行緒最多保留的已編譯 XPath 數；XPathExpression 不可跨執行緒共用。 */
    private static final int MAX_COMPILED_XPATHS = 64;

    /**
     * 只保留這個長度以內的運算式。模板裡寫死的 XPath 都很短；由請求內容組出的長運算式不保留，
     * 避免每個執行緒長期留住請求大小的字串。
     */
    static final int MAX_CACHED_XPATH_LENGTH = 256;

    private static final ThreadLocal<Map<String, XPathExpression>> COMPILED_XPATHS = ThreadLocal.withInitial(() ->
        new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, XPathExpression> eldest) {
                return size() > MAX_COMPILED_XPATHS;
            }
        });

    /** 模板裡的 XPath 字串固定，編譯一次後在同一執行緒重用。 */
    private static XPathExpression compiledXPath(String path) throws XPathExpressionException {
        Map<String, XPathExpression> compiled = COMPILED_XPATHS.get();
        XPathExpression expression = compiled.get(path);
        if (expression == null) {
            XPath xPath = XPATH.get();
            xPath.reset();
            expression = xPath.compile(path);
            if (path.length() <= MAX_CACHED_XPATH_LENGTH) {
                compiled.put(path, expression);
            }
        }
        return expression;
    }

    /** 測試用：目前執行緒保留的已編譯 XPath 數。 */
    static int compiledXPathCountForCurrentThread() {
        return COMPILED_XPATHS.get().size();
    }

    private final Handlebars handlebars;
    private final Cache<String, Template> templateCache;
    private final long templateCacheMaxWeightBytes;

    public ResponseTemplateService() {
        this(DEFAULT_TEMPLATE_CACHE_MIB);
    }

    @Autowired
    public ResponseTemplateService(
            @Value("${echo.cache.template.max-weight-mb:16}") int templateCacheMaxWeightMb) {
        this.handlebars = new Handlebars();
        this.templateCacheMaxWeightBytes = saturatingMebibytes(templateCacheMaxWeightMb);
        this.templateCache = Caffeine.newBuilder()
                .maximumWeight(templateCacheMaxWeightBytes)
                .weigher((String source, Template compiled) -> estimateTemplateWeight(source))
                .expireAfterAccess(1, TimeUnit.HOURS)
                .recordStats()
                .build();
        registerHelpers();
    }

    private static long saturatingMebibytes(int configuredMib) {
        return Math.max(1L, configuredMib) * MEBIBYTE;
    }

    /** Source characters plus a conservative allowance for the compiled Handlebars tree. */
    static int estimateTemplateWeight(String source) {
        if (source == null) return 1;
        long estimatedBytes = 1_024L + (long) source.length() * 6L;
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, estimatedBytes));
    }

    long templateCacheMaxWeightBytes() {
        return templateCacheMaxWeightBytes;
    }

    long templateCacheEstimatedSize() {
        templateCache.cleanUp();
        return templateCache.estimatedSize();
    }

    public Map<String, Object> getTemplateCacheStats() {
        Map<String, Object> result = new HashMap<>();
        result.put("entries", templateCache.estimatedSize());
        templateCache.policy().eviction().ifPresent(eviction -> {
            result.put("weightedSize", eviction.weightedSize().orElse(0L));
            result.put("maximumWeight", eviction.getMaximum());
        });
        var stats = templateCache.stats();
        result.put("requestCount", stats.requestCount());
        result.put("evictionCount", stats.evictionCount());
        return result;
    }

    @PreDestroy
    public void cleanup() {
        DOC_BUILDER.remove();
        XPATH.remove();
    }

    private void registerHelpers() {
        // {{now}} 和 {{now format='xxx'}}
        handlebars.registerHelper("now", (Helper<Object>) (context, options) -> {
            String format = options.hash("format", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
            Date now = new Date();
            if ("epoch".equals(format)) {
                return String.valueOf(now.getTime());
            }
            if ("unix".equals(format)) {
                return String.valueOf(now.getTime() / 1000);
            }
            try {
                SimpleDateFormat sdf = new SimpleDateFormat(format);
                sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
                return sdf.format(now);
            } catch (Exception e) {
                return now.toString();
            }
        });

        // {{randomValue type='UUID'}} 或 {{randomValue length=10 type='ALPHANUMERIC'}}
        handlebars.registerHelper("randomValue", (Helper<Object>) (context, options) -> {
            String type = options.hash("type", "ALPHANUMERIC");
            int length = options.hash("length", 8);
            length = Math.min(length, 10000);

            return switch (type.toUpperCase(Locale.ROOT)) {
                case "UUID" -> UUID.randomUUID().toString();
                case "NUMERIC" -> generateRandom(NUMERIC, length);
                case "ALPHABETIC" -> generateRandom(ALPHABETIC, length);
                default -> generateRandom(ALPHANUMERIC, length);
            };
        });

        // Phase 2: 比較運算子
        
        // {{#if (eq a b)}}
        handlebars.registerHelper("eq", (Helper<Object>) (a, options) -> {
            Object b = options.param(0, null);
            return Objects.equals(toString(a), toString(b));
        });

        // {{#if (ne a b)}}
        handlebars.registerHelper("ne", (Helper<Object>) (a, options) -> {
            Object b = options.param(0, null);
            return !Objects.equals(toString(a), toString(b));
        });

        // {{#if (gt a b)}}
        handlebars.registerHelper("gt", (Helper<Object>) (a, options) -> {
            Object b = options.param(0, null);
            return toNumber(a) > toNumber(b);
        });

        // {{#if (lt a b)}}
        handlebars.registerHelper("lt", (Helper<Object>) (a, options) -> {
            Object b = options.param(0, null);
            return toNumber(a) < toNumber(b);
        });

        // {{#if (contains str substr)}}
        handlebars.registerHelper("contains", (Helper<Object>) (str, options) -> {
            String substr = options.param(0, "");
            return toString(str).contains(substr);
        });

        // {{#if (matches str regex)}}
        handlebars.registerHelper("matches", (Helper<Object>) (str, options) -> {
            String regex = options.param(0, "");
            String input = toString(str);
            if (regex.length() > 200 || input.length() > 10000) {
                return false;
            }
            try {
                CharSequence wrapped = new InterruptibleCharSequence(input, 1000);
                return java.util.regex.Pattern.compile(".*" + regex + ".*")
                        .matcher(wrapped).matches();
            } catch (InterruptibleCharSequence.RegexTimeoutException e) {
                return false;
            } catch (Exception e) {
                return false;
            }
        });

        // Phase 2: 字串處理
        
        // {{#each (split str delimiter)}}
        handlebars.registerHelper("split", (Helper<Object>) (str, options) -> {
            String delimiter = options.param(0, ",");
            String s = toString(str);
            if (s.isEmpty()) {
                return Collections.emptyList();
            }
            return Arrays.asList(s.split(java.util.regex.Pattern.quote(delimiter)));
        });

        // {{size collection}}
        handlebars.registerHelper("size", (Helper<Object>) (obj, options) -> {
            if (obj instanceof Collection<?> c) {
                return c.size();
            }
            if (obj instanceof String s) {
                return s.length();
            }
            return 0;
        });

        // Phase 2b: JSONPath / XPath

        // {{jsonPath json '$.path'}}
        handlebars.registerHelper("jsonPath", (Helper<Object>) (json, options) -> {
            String path = options.param(0, "$");
            String jsonStr = toString(json);
            if (jsonStr.isEmpty()) {
                return "";
            }
            try {
                // 同一次渲染中相同內容只解析一次（見 ParsedBodies）；append() 會修改文件，改用獨立的解析結果
                DocumentContext document = path.contains("append(")
                        ? JsonPath.parse(jsonStr) : parsed(options).json(jsonStr);
                if (document == null) {
                    return "";
                }
                Object result = document.read(path);
                if (result instanceof List<?> list) {
                    return list;
                }
                return result != null ? result.toString() : "";
            } catch (PathNotFoundException e) {
                return "";
            } catch (Exception e) {
                return "";
            }
        });

        // {{xPath xml '//path'}}
        handlebars.registerHelper("xPath", (Helper<Object>) (xml, options) -> {
            String path = options.param(0, "");
            String xmlStr = toString(xml);
            if (xmlStr.isEmpty() || path.isEmpty()) {
                return "";
            }
            try {
                // 同一次渲染中相同內容只解析一次（見 ParsedBodies）
                Document doc = parsed(options).xml(xmlStr);
                if (doc == null) {
                    return "";
                }
                String result = compiledXPath(path).evaluate(doc);
                return result != null ? result : "";
            } catch (Exception e) {
                return "";
            }
        });

        // Phase 1-4: Faker helpers

        // {{randomFirstName}}
        handlebars.registerHelper("randomFirstName", (Helper<Object>) (context, options) ->
            randomPick(FIRST_NAMES)
        );

        // {{randomLastName}}
        handlebars.registerHelper("randomLastName", (Helper<Object>) (context, options) ->
            randomPick(LAST_NAMES)
        );

        // {{randomFullName}}
        handlebars.registerHelper("randomFullName", (Helper<Object>) (context, options) ->
            randomPick(FIRST_NAMES) + " " + randomPick(LAST_NAMES)
        );

        // {{randomEmail}}
        handlebars.registerHelper("randomEmail", (Helper<Object>) (context, options) -> {
            String first = randomPick(FIRST_NAMES).toLowerCase(Locale.ROOT);
            String last = randomPick(LAST_NAMES).toLowerCase(Locale.ROOT);
            String domain = randomPick(EMAIL_DOMAINS);
            return first + "." + last + "@" + domain;
        });

        // {{randomPhoneNumber}}
        handlebars.registerHelper("randomPhoneNumber", (Helper<Object>) (context, options) -> {
            ThreadLocalRandom rng = ThreadLocalRandom.current();
            return String.format("(%03d) %03d-%04d",
                    rng.nextInt(200, 1000),
                    rng.nextInt(200, 1000),
                    rng.nextInt(0, 10000));
        });

        // {{randomCity}}
        handlebars.registerHelper("randomCity", (Helper<Object>) (context, options) ->
            randomPick(CITIES)
        );

        // {{randomCountry}}
        handlebars.registerHelper("randomCountry", (Helper<Object>) (context, options) ->
            randomPick(COUNTRIES)
        );

        // {{randomStreetAddress}}
        handlebars.registerHelper("randomStreetAddress", (Helper<Object>) (context, options) -> {
            int number = ThreadLocalRandom.current().nextInt(1, 10000);
            return number + " " + randomPick(STREETS);
        });

        // {{randomInt min=0 max=100}}
        handlebars.registerHelper("randomInt", (Helper<Object>) (context, options) -> {
            int min = toInt(options.hash("min", 0));
            int max = toInt(options.hash("max", 100));
            if (min > max) {
                int temp = min;
                min = max;
                max = temp;
            }
            if (min == max) {
                return min;
            }
            // 用 long 避免 max + 1 溢位，確保均勻分佈
            return (int) ThreadLocalRandom.current().nextLong(min, (long) max + 1);
        });
    }

    private String toString(Object obj) {
        return obj == null ? "" : obj.toString();
    }

    private double toNumber(Object obj) {
        if (obj == null) {
            return 0;
        }
        try {
            return Double.parseDouble(obj.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private int toInt(Object obj) {
        if (obj == null) {
            return 0;
        }
        try {
            return (int) Double.parseDouble(obj.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String randomPick(String[] array) {
        return array[ThreadLocalRandom.current().nextInt(array.length)];
    }

    private String generateRandom(String chars, int length) {
        int safeLength = Math.max(0, Math.min(length, MAX_RANDOM_LENGTH));
        StringBuilder sb = new StringBuilder(safeLength);
        for (int i = 0; i < safeLength; i++) {
            sb.append(chars.charAt(ThreadLocalRandom.current().nextInt(chars.length())));
        }
        return sb.toString();
    }

    /**
     * 渲染模板
     */
    public String render(String templateStr, TemplateContext context) {
        if (templateStr == null || templateStr.isEmpty()) {
            return "";
        }

        // 快速檢查是否包含模板語法
        if (!templateStr.contains("{{")) {
            return templateStr;
        }

        try {
            Template template = templateCache.get(templateStr, t -> {
                try {
                    return handlebars.compileInline(t);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to compile template", e);
                }
            });

            Context handlebarsContext = Context.newBuilder(buildModel(context)).build();
            handlebarsContext.data(PARSED_BODIES, new ParsedBodies());
            try {
                return template.apply(handlebarsContext);
            } finally {
                handlebarsContext.destroy();
            }
        } catch (Exception e) {
            log.warn("Template rendering failed: {}", e.getMessage());
            return templateStr; // 失敗時返回原始字串
        }
    }

    private Map<String, Object> buildModel(TemplateContext context) {
        Map<String, Object> request = new HashMap<>();

        // path
        request.put("path", context.path());

        // pathSegments
        if (context.path() != null) {
            String[] segments = context.path().split("/");
            List<String> segmentList = new ArrayList<>();
            for (String seg : segments) {
                if (!seg.isEmpty()) {
                    segmentList.add(seg);
                }
            }
            request.put("pathSegments", segmentList);
        }

        // method
        request.put("method", context.method());

        // query
        request.put("query", context.query() != null ? context.query() : Map.of());

        // headers
        request.put("headers", context.headers() != null ? context.headers() : Map.of());

        // body
        request.put("body", context.body() != null ? context.body() : "");

        return Map.of("request", request);
    }

    /** 一次渲染的解析結果放在 Handlebars context data 的這個 key。 */
    private static final String PARSED_BODIES = "echo.parsedBodies";

    private static ParsedBodies parsed(Options options) {
        ParsedBodies parsed = options.data(PARSED_BODIES);
        // 不經 render() 的呼叫（理論上不會發生）退回每次解析
        return parsed != null ? parsed : new ParsedBodies();
    }

    /**
     * 同一次渲染中已解析過的 XML / JSON 內容。模板裡多個 {@code xPath} 或 {@code jsonPath}
     * 讀同一份內容時只解析一次；渲染結束即丟棄，不跨請求保留。渲染期間會同時留住已解析的內容
     * （最多 4 份 XML 與 4 份 JSON），原本則是每次用完即丟，因此單次渲染的記憶體高峰可能略高。
     * 解析失敗也會記住，之後同一內容的呼叫直接回傳空字串，與原本的結果一致。
     * 只記住最近幾份不同內容，避免模板對大量不同字串呼叫時累積。
     */
    static final class ParsedBodies {
        private static final int MAX_ENTRIES = 4;
        private static final Object FAILED = new Object();
        private final Map<String, Object> xml = new LinkedHashMap<>();
        private final Map<String, Object> json = new LinkedHashMap<>();

        Document xml(String source) {
            Object cached = lookup(xml, source, () -> {
                DocumentBuilder builder = DOC_BUILDER.get();
                builder.reset();
                return builder.parse(new InputSource(new StringReader(source)));
            });
            return cached instanceof Document document ? document : null;
        }

        DocumentContext json(String source) {
            Object cached = lookup(json, source, () -> JsonPath.parse(source));
            return cached instanceof DocumentContext document ? document : null;
        }

        private interface Parser {
            Object parse() throws Exception;
        }

        private static Object lookup(Map<String, Object> cache, String source, Parser parser) {
            Object cached = cache.get(source);
            if (cached != null) {
                return cached;
            }
            Object value;
            try {
                value = parser.parse();
            } catch (Exception e) {
                value = FAILED;
            }
            if (cache.size() >= MAX_ENTRIES) {
                cache.remove(cache.keySet().iterator().next());
            }
            cache.put(source, value);
            return value;
        }
    }

    /**
     * 檢查字串是否包含模板語法
     */
    public boolean hasTemplate(String str) {
        return str != null && str.contains("{{");
    }

    /**
     * 各協定共用的渲染入口：只在內容含模板語法時才建立上下文並渲染；沒有 <code>{{</code> 時原樣回傳，
     * 不做任何額外工作。新增協定時只要提供 {@link TemplateContext}，不需要另外支援模板。
     * 經由 {@link #hasTemplate} 與 {@link #render} 實作，service 為 null 時不渲染。
     */
    public static String renderIfTemplate(ResponseTemplateService service, String templateStr,
                                          Supplier<TemplateContext> context) {
        if (service == null || !service.hasTemplate(templateStr)) {
            return templateStr;
        }
        return service.render(templateStr, context.get());
    }

    /**
     * 模板上下文：{@code request.path / method / query / headers / body}。
     * 各協定把自己的請求轉成這五項（JMS 的訊息 ID、correlation ID 與屬性放在 headers）。
     */
    public record TemplateContext(
            String path,
            String method,
            Map<String, String> query,
            Map<String, String> headers,
            String body
    ) {
        /** 由原始 query string 建立；query 只在需要渲染時才解析。 */
        public static TemplateContext of(String path, String method, String queryString,
                                         Map<String, String> headers, String body) {
            return new TemplateContext(path, method, parseQuery(queryString), headers, body);
        }

        static Map<String, String> parseQuery(String queryString) {
            Map<String, String> params = new HashMap<>();
            if (queryString == null || queryString.isEmpty()) {
                return params;
            }
            for (String pair : queryString.split("&")) {
                int idx = pair.indexOf('=');
                if (idx > 0) {
                    params.put(pair.substring(0, idx), pair.substring(idx + 1));
                }
            }
            return params;
        }
    }
}
