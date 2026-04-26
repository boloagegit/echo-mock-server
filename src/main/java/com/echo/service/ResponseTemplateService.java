package com.echo.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Helper;
import com.github.jknack.handlebars.Template;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import jakarta.annotation.PreDestroy;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathFactory;
import java.io.IOException;
import java.io.StringReader;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

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

    private final Handlebars handlebars;
    private final Cache<String, Template> templateCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterAccess(1, TimeUnit.HOURS)
            .build();

    public ResponseTemplateService() {
        this.handlebars = new Handlebars();
        registerHelpers();
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
            return toString(str).matches(".*" + regex + ".*");
        });

        // Phase 2: 字串處理
        
        // {{#each (split str delimiter)}}
        handlebars.registerHelper("split", (Helper<Object>) (str, options) -> {
            String delimiter = options.param(0, ",");
            String s = toString(str);
            if (s.isEmpty()) {
                return Collections.emptyList();
            }
            return Arrays.asList(s.split(delimiter));
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
                Object result = JsonPath.read(jsonStr, path);
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
                DocumentBuilder builder = DOC_BUILDER.get();
                builder.reset();
                Document doc = builder.parse(new InputSource(new StringReader(xmlStr)));
                XPath xPath = XPATH.get();
                xPath.reset();
                String result = xPath.evaluate(path, doc);
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

            Map<String, Object> model = buildModel(context);
            return template.apply(model);
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

    /**
     * 檢查字串是否包含模板語法
     */
    public boolean hasTemplate(String str) {
        return str != null && str.contains("{{");
    }

    /**
     * 模板上下文
     */
    public record TemplateContext(
            String path,
            String method,
            Map<String, String> query,
            Map<String, String> headers,
            String body
    ) {}
}
