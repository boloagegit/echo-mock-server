package com.echo.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Response Template Service unit test
 * Phase 1: Basic template function
 */
class ResponseTemplateServiceTest {

    private ResponseTemplateService templateService;

    @BeforeEach
    void setUp() {
        templateService = new ResponseTemplateService();
    }

    // === Request Path ===

    @Test
    @DisplayName("Reads the full request path")
    void shouldGetFullPath() {
        var context = buildContext("/api/users/123", "GET", null, null, null);
        String result = templateService.render("Path: {{request.path}}", context);
        assertEquals("Path: /api/users/123", result);
    }

    @Test
    @DisplayName("Reads path segments")
    void shouldGetPathSegments() {
        var context = buildContext("/api/users/123", "GET", null, null, null);
        String result = templateService.render("{{request.pathSegments.[0]}}/{{request.pathSegments.[2]}}", context);
        assertEquals("api/123", result);
    }

    // === Request Method ===

    @Test
    @DisplayName("Reads the HTTP method")
    void shouldGetMethod() {
        var context = buildContext("/test", "POST", null, null, null);
        String result = templateService.render("Method: {{request.method}}", context);
        assertEquals("Method: POST", result);
    }

    // === Query Parameters ===

    @Test
    @DisplayName("Reads query parameters")
    void shouldGetQueryParam() {
        Map<String, String> query = Map.of("name", "John", "age", "30");
        var context = buildContext("/test", "GET", query, null, null);
        String result = templateService.render("Name: {{request.query.name}}, Age: {{request.query.age}}", context);
        assertEquals("Name: John, Age: 30", result);
    }

    @Test
    @DisplayName("Missing query parameters produce empty output")
    void shouldHandleMissingQueryParam() {
        var context = buildContext("/test", "GET", Map.of(), null, null);
        String result = templateService.render("Name: {{request.query.name}}", context);
        assertEquals("Name: ", result);
    }

    // === Headers ===

    @Test
    @DisplayName("Reads request headers")
    void shouldGetHeader() {
        Map<String, String> headers = Map.of("Authorization", "Bearer token123", "X-Request-Id", "req-456");
        var context = buildContext("/test", "GET", null, headers, null);
        String result = templateService.render("Auth: {{request.headers.Authorization}}", context);
        assertEquals("Auth: Bearer token123", result);
    }

    @Test
    @DisplayName("Missing headers produce empty output")
    void shouldHandleMissingHeader() {
        var context = buildContext("/test", "GET", null, Map.of(), null);
        String result = templateService.render("Token: {{request.headers.X-Token}}", context);
        assertEquals("Token: ", result);
    }

    // === Request Body ===

    @Test
    @DisplayName("Reads the request body")
    void shouldGetBody() {
        String body = "{\"user\":\"test\"}";
        var context = buildContext("/test", "POST", null, null, body);
        // Use triple braces avoid HTML escape
        String result = templateService.render("Body: {{{request.body}}}", context);
        assertEquals("Body: {\"user\":\"test\"}", result);
    }

    // === Now (Date/Time) ===

    @Test
    @DisplayName("Generates the current time")
    void shouldGenerateNow() {
        var context = buildContext("/test", "GET", null, null, null);
        String result = templateService.render("{{now}}", context);
        assertNotNull(result);
        assertFalse(result.contains("{{"));
    }

    @Test
    @DisplayName("Formats the current time")
    void shouldFormatNow() {
        var context = buildContext("/test", "GET", null, null, null);
        String result = templateService.render("{{now format='yyyy-MM-dd'}}", context);
        assertTrue(result.matches("\\d{4}-\\d{2}-\\d{2}"));
    }

    @Test
    @DisplayName("Generates epoch time")
    void shouldGenerateEpoch() {
        var context = buildContext("/test", "GET", null, null, null);
        String result = templateService.render("{{now format='epoch'}}", context);
        assertTrue(result.matches("\\d+"));
        assertTrue(Long.parseLong(result) > 1700000000000L);
    }

    // === Random Value ===

    @Test
    @DisplayName("Generates a UUID")
    void shouldGenerateUUID() {
        var context = buildContext("/test", "GET", null, null, null);
        String result = templateService.render("{{randomValue type='UUID'}}", context);
        assertTrue(result.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
    }

    @Test
    @DisplayName("Generates a random string of the requested length")
    void shouldGenerateRandomString() {
        var context = buildContext("/test", "GET", null, null, null);
        String result = templateService.render("{{randomValue length=10 type='ALPHANUMERIC'}}", context);
        assertEquals(10, result.length());
        assertTrue(result.matches("[A-Za-z0-9]+"));
    }

    @Test
    @DisplayName("Generates a random number")
    void shouldGenerateRandomNumeric() {
        var context = buildContext("/test", "GET", null, null, null);
        String result = templateService.render("{{randomValue length=6 type='NUMERIC'}}", context);
        assertEquals(6, result.length());
        assertTrue(result.matches("[0-9]+"));
    }

    // === Composite test ===

    @Test
    @DisplayName("Renders compound templates")
    void shouldHandleComplexTemplate() {
        Map<String, String> query = Map.of("id", "123");
        Map<String, String> headers = Map.of("X-Correlation-Id", "corr-789");
        var context = buildContext("/api/orders/456", "POST", query, headers, "{\"amount\":100}");

        String template = """
            {
              "orderId": "{{request.pathSegments.[2]}}",
              "queryId": "{{request.query.id}}",
              "correlationId": "{{request.headers.X-Correlation-Id}}",
              "method": "{{request.method}}",
              "timestamp": "{{now format='yyyy-MM-dd'}}",
              "traceId": "{{randomValue type='UUID'}}"
            }
            """;

        String result = templateService.render(template, context);

        assertTrue(result.contains("\"orderId\": \"456\""));
        assertTrue(result.contains("\"queryId\": \"123\""));
        assertTrue(result.contains("\"correlationId\": \"corr-789\""));
        assertTrue(result.contains("\"method\": \"POST\""));
    }

    // === No template syntax ===

    @Test
    @DisplayName("Returns text without template syntax unchanged")
    void shouldReturnAsIsWhenNoTemplate() {
        var context = buildContext("/test", "GET", null, null, null);
        String result = templateService.render("Plain text response", context);
        assertEquals("Plain text response", result);
    }

    @Test
    @DisplayName("An empty string returns an empty string")
    void shouldHandleEmptyString() {
        var context = buildContext("/test", "GET", null, null, null);
        String result = templateService.render("", context);
        assertEquals("", result);
    }

    @Test
    @DisplayName("null returns an empty string")
    void shouldHandleNull() {
        var context = buildContext("/test", "GET", null, null, null);
        String result = templateService.render(null, context);
        assertEquals("", result);
    }

    @Test
    @DisplayName("The template cache defaults to a 16 MiB weight budget")
    void shouldUseDefaultTemplateCacheWeightBudget() {
        assertEquals(16L * 1024 * 1024, templateService.templateCacheMaxWeightBytes());
    }

    @Test
    @DisplayName("Templates exceeding the weight budget render without being cached")
    void oversizedTemplateShouldRenderWithoutBeingCached() {
        ResponseTemplateService smallCacheService = new ResponseTemplateService(1);
        String oversized = "x".repeat(175_000) + "{{request.method}}";

        String result = smallCacheService.render(
                oversized, buildContext("/test", "POST", null, null, null));

        assertTrue(result.endsWith("POST"));
        assertEquals(0, smallCacheService.templateCacheEstimatedSize());
    }

    @Test
    @DisplayName("Template weight includes source characters and compiled-structure allowance")
    void templateWeightShouldBeConservative() {
        assertTrue(ResponseTemplateService.estimateTemplateWeight("x".repeat(1_000)) >= 6_000);
    }

    @Test
    @DisplayName("Shared compiled templates keep concurrent request contexts isolated")
    void cachedTemplateShouldKeepConcurrentRequestContextsIsolated() throws Exception {
        var executor = Executors.newFixedThreadPool(8);
        try {
            java.util.List<Callable<Boolean>> tasks = new java.util.ArrayList<>();
            for (int i = 0; i < 100; i++) {
                String requestId = "request-" + i;
                tasks.add(() -> {
                    var context = buildContext("/test", "POST", Map.of("id", requestId), null, null);
                    return requestId.equals(templateService.render("{{request.query.id}}", context));
                });
            }
            assertTrue(executor.invokeAll(tasks).stream().allMatch(future -> {
                try {
                    return future.get();
                } catch (Exception e) {
                    return false;
                }
            }));
        } finally {
            executor.shutdownNow();
        }
    }

    // === Helper ===

    private ResponseTemplateService.TemplateContext buildContext(
            String path, String method,
            Map<String, String> query,
            Map<String, String> headers,
            String body) {
        return new ResponseTemplateService.TemplateContext(
                path,
                method,
                query != null ? query : new HashMap<>(),
                headers != null ? headers : new HashMap<>(),
                body
        );
    }

    // ========================================
    // Phase 2: Conditions and loops
    // ========================================

    // === Conditional judgment {{#if}} ===

    @Test
    @DisplayName("if renders its body when the condition is true")
    void shouldRenderIfTrue() {
        Map<String, String> query = Map.of("debug", "true");
        var context = buildContext("/test", "GET", query, null, null);
        String result = templateService.render("{{#if request.query.debug}}DEBUG MODE{{/if}}", context);
        assertEquals("DEBUG MODE", result);
    }

    @Test
    @DisplayName("if omits its body when the condition is false")
    void shouldNotRenderIfFalse() {
        var context = buildContext("/test", "GET", Map.of(), null, null);
        String result = templateService.render("{{#if request.query.debug}}DEBUG MODE{{/if}}", context);
        assertEquals("", result);
    }

    @Test
    @DisplayName("if/else renders the else branch")
    void shouldRenderElse() {
        var context = buildContext("/test", "GET", Map.of(), null, null);
        String result = templateService.render("{{#if request.query.name}}Hello {{request.query.name}}{{else}}Hello Guest{{/if}}", context);
        assertEquals("Hello Guest", result);
    }

    @Test
    @DisplayName("unless renders its body when the condition is false")
    void shouldRenderUnless() {
        var context = buildContext("/test", "GET", Map.of(), null, null);
        String result = templateService.render("{{#unless request.query.auth}}Unauthorized{{/unless}}", context);
        assertEquals("Unauthorized", result);
    }

    // === comparison operator ===

    @Test
    @DisplayName("eq tests equality")
    void shouldCompareEqual() {
        Map<String, String> query = Map.of("status", "active");
        var context = buildContext("/test", "GET", query, null, null);
        String result = templateService.render("{{#if (eq request.query.status 'active')}}ACTIVE{{else}}INACTIVE{{/if}}", context);
        assertEquals("ACTIVE", result);
    }

    @Test
    @DisplayName("ne tests inequality")
    void shouldCompareNotEqual() {
        Map<String, String> query = Map.of("status", "pending");
        var context = buildContext("/test", "GET", query, null, null);
        String result = templateService.render("{{#if (ne request.query.status 'active')}}NOT ACTIVE{{/if}}", context);
        assertEquals("NOT ACTIVE", result);
    }

    @Test
    @DisplayName("gt tests greater-than comparisons")
    void shouldCompareGreaterThan() {
        Map<String, String> query = Map.of("count", "15");
        var context = buildContext("/test", "GET", query, null, null);
        String result = templateService.render("{{#if (gt request.query.count 10)}}MANY{{else}}FEW{{/if}}", context);
        assertEquals("MANY", result);
    }

    @Test
    @DisplayName("lt tests less-than comparisons")
    void shouldCompareLessThan() {
        Map<String, String> query = Map.of("count", "5");
        var context = buildContext("/test", "GET", query, null, null);
        String result = templateService.render("{{#if (lt request.query.count 10)}}FEW{{else}}MANY{{/if}}", context);
        assertEquals("FEW", result);
    }

    @Test
    @DisplayName("contains tests substring membership")
    void shouldCompareContains() {
        Map<String, String> query = Map.of("name", "John Doe");
        var context = buildContext("/test", "GET", query, null, null);
        String result = templateService.render("{{#if (contains request.query.name 'John')}}FOUND{{/if}}", context);
        assertEquals("FOUND", result);
    }

    @Test
    @DisplayName("matches evaluates regular expressions")
    void shouldCompareMatches() {
        var context = buildContext("/api/v2/users", "GET", null, null, null);
        String result = templateService.render("{{#if (matches request.path '/api/v[0-9]+/')}}VERSIONED API{{/if}}", context);
        assertEquals("VERSIONED API", result);
    }

    // === Loop {{#each}} ===

    @Test
    @DisplayName("each iterates query-parameter arrays")
    void shouldIterateQueryArray() {
        // Simulate the situation of ?ids=1&ids=2&ids=3, separated by commas
        Map<String, String> query = Map.of("ids", "1,2,3");
        var context = buildContext("/test", "GET", query, null, null);
        String result = templateService.render("{{#each (split request.query.ids ',')}}[{{this}}]{{/each}}", context);
        assertEquals("[1][2][3]", result);
    }

    @Test
    @DisplayName("each supports @index")
    void shouldAccessIndex() {
        Map<String, String> query = Map.of("items", "a,b,c");
        var context = buildContext("/test", "GET", query, null, null);
        String result = templateService.render("{{#each (split request.query.items ',')}}{{@index}}:{{this}} {{/each}}", context);
        assertEquals("0:a 1:b 2:c ", result);
    }

    @Test
    @DisplayName("each supports @first and @last")
    void shouldAccessFirstLast() {
        Map<String, String> query = Map.of("items", "x,y,z");
        var context = buildContext("/test", "GET", query, null, null);
        String result = templateService.render("{{#each (split request.query.items ',')}}{{this}}{{#unless @last}},{{/unless}}{{/each}}", context);
        assertEquals("x,y,z", result);
    }

    // === String processing Helper ===

    @Test
    @DisplayName("split divides strings")
    void shouldSplitString() {
        Map<String, String> query = Map.of("tags", "java,spring,boot");
        var context = buildContext("/test", "GET", query, null, null);
        String result = templateService.render("{{#each (split request.query.tags ',')}}{{this}} {{/each}}", context);
        assertEquals("java spring boot ", result);
    }

    @Test
    @DisplayName("size returns the array length")
    void shouldGetSize() {
        Map<String, String> query = Map.of("items", "a,b,c,d,e");
        var context = buildContext("/test", "GET", query, null, null);
        String result = templateService.render("Count: {{size (split request.query.items ',')}}", context);
        assertEquals("Count: 5", result);
    }

    // === Compound condition test ===

    @Test
    @DisplayName("Compound conditions generate different replies from methods and parameters")
    void shouldHandleComplexCondition() {
        var context = buildContext("/api/users", "POST", Map.of(), null, null);
        String template = "{{#if (eq request.method 'POST')}}Created{{else if (eq request.method 'GET')}}Retrieved{{else}}Unknown{{/if}}";
        String result = templateService.render(template, context);
        assertEquals("Created", result);
    }

    @Test
    @DisplayName("Compound templates combine conditions, loops, and dynamic values")
    void shouldHandleComplexTemplate2() {
        Map<String, String> query = Map.of("ids", "101,102", "format", "json");
        var context = buildContext("/api/orders", "GET", query, null, null);

        String template = "{\"items\":[{{#each (split request.query.ids ',')}}{{this}}{{#unless @last}},{{/unless}}{{/each}}],\"format\":\"{{request.query.format}}\"}";

        String result = templateService.render(template, context);
        assertEquals("{\"items\":[101,102],\"format\":\"json\"}", result);
    }

    // ========================================
    // Phase 2b: JSONPath / XPath
    // ========================================

    // === JSONPath ===

    @Test
    @DisplayName("jsonPath reads a simple field")
    void shouldGetJsonPathSimpleField() {
        String body = "{\"name\":\"Alice\",\"age\":25}";
        var context = buildContext("/test", "POST", null, null, body);
        String result = templateService.render("Name: {{jsonPath request.body '$.name'}}", context);
        assertEquals("Name: Alice", result);
    }

    @Test
    @DisplayName("jsonPath reads nested fields")
    void shouldGetJsonPathNestedField() {
        String body = "{\"user\":{\"profile\":{\"name\":\"Bob\"}}}";
        var context = buildContext("/test", "POST", null, null, body);
        String result = templateService.render("{{jsonPath request.body '$.user.profile.name'}}", context);
        assertEquals("Bob", result);
    }

    @Test
    @DisplayName("jsonPath reads array elements")
    void shouldGetJsonPathArrayElement() {
        String body = "{\"items\":[{\"id\":1},{\"id\":2},{\"id\":3}]}";
        var context = buildContext("/test", "POST", null, null, body);
        String result = templateService.render("First: {{jsonPath request.body '$.items[0].id'}}", context);
        assertEquals("First: 1", result);
    }

    @Test
    @DisplayName("jsonPath reads arrays")
    void shouldGetJsonPathArray() {
        String body = "{\"tags\":[\"java\",\"spring\",\"boot\"]}";
        var context = buildContext("/test", "POST", null, null, body);
        String result = templateService.render("{{#each (jsonPath request.body '$.tags')}}{{this}} {{/each}}", context);
        assertEquals("java spring boot ", result);
    }

    @Test
    @DisplayName("jsonPath returns empty output for missing paths")
    void shouldHandleMissingJsonPath() {
        String body = "{\"name\":\"Alice\"}";
        var context = buildContext("/test", "POST", null, null, body);
        String result = templateService.render("Age: {{jsonPath request.body '$.age'}}", context);
        assertEquals("Age: ", result);
    }

    @Test
    @DisplayName("jsonPath returns empty output for invalid JSON")
    void shouldHandleInvalidJson() {
        String body = "not a json";
        var context = buildContext("/test", "POST", null, null, body);
        String result = templateService.render("{{jsonPath request.body '$.name'}}", context);
        assertEquals("", result);
    }

    @Test
    @DisplayName("jsonPath combines with size to return an array length")
    void shouldGetJsonPathArraySize() {
        String body = "{\"items\":[1,2,3,4,5]}";
        var context = buildContext("/test", "POST", null, null, body);
        String result = templateService.render("Count: {{size (jsonPath request.body '$.items')}}", context);
        assertEquals("Count: 5", result);
    }

    // === XPath ===

    @Test
    @DisplayName("xPath reads element text")
    void shouldGetXPathText() {
        String body = "<root><name>Alice</name></root>";
        var context = buildContext("/test", "POST", null, null, body);
        String result = templateService.render("Name: {{xPath request.body '//name/text()'}}", context);
        assertEquals("Name: Alice", result);
    }

    @Test
    @DisplayName("xPath reads nested elements")
    void shouldGetXPathNestedElement() {
        String body = "<order><customer><name>Bob</name></customer></order>";
        var context = buildContext("/test", "POST", null, null, body);
        String result = templateService.render("{{xPath request.body '//customer/name/text()'}}", context);
        assertEquals("Bob", result);
    }

    @Test
    @DisplayName("xPath reads attributes")
    void shouldGetXPathAttribute() {
        String body = "<item id=\"123\" status=\"active\"/>";
        var context = buildContext("/test", "POST", null, null, body);
        String result = templateService.render("ID: {{xPath request.body '//item/@id'}}", context);
        assertEquals("ID: 123", result);
    }

    @Test
    @DisplayName("xPath returns empty output for missing paths")
    void shouldHandleMissingXPath() {
        String body = "<root><name>Alice</name></root>";
        var context = buildContext("/test", "POST", null, null, body);
        String result = templateService.render("Age: {{xPath request.body '//age/text()'}}", context);
        assertEquals("Age: ", result);
    }

    @Test
    @DisplayName("xPath returns empty output for invalid XML")
    void shouldHandleInvalidXml() {
        String body = "not xml";
        var context = buildContext("/test", "POST", null, null, body);
        String result = templateService.render("{{xPath request.body '//name'}}", context);
        assertEquals("", result);
    }

    // === Composite test ===

    @Test
    @DisplayName("jsonPath conditions evaluate JSON fields")
    void shouldUseJsonPathInCondition() {
        String body = "{\"status\":\"active\",\"count\":10}";
        var context = buildContext("/test", "POST", null, null, body);
        String result = templateService.render("{{#if (eq (jsonPath request.body '$.status') 'active')}}ACTIVE{{else}}INACTIVE{{/if}}", context);
        assertEquals("ACTIVE", result);
    }

    @Test
    @DisplayName("jsonPath loops iterate JSON arrays")
    void shouldIterateJsonPathArray() {
        String body = "{\"users\":[{\"name\":\"A\"},{\"name\":\"B\"},{\"name\":\"C\"}]}";
        var context = buildContext("/test", "POST", null, null, body);
        String result = templateService.render("{{#each (jsonPath request.body '$.users')}}{{name}}{{#unless @last}},{{/unless}}{{/each}}", context);
        assertEquals("A,B,C", result);
    }

    // ========================================
    // Phase 1-4: Faker Helpers
    // ========================================

    // === randomFirstName ===

    @Test
    @DisplayName("randomFirstName generates a nonempty first name")
    void shouldGenerateRandomFirstName() {
        var context = buildContext("/test", "GET", null, null, null);
        String result = templateService.render("{{randomFirstName}}", context);
        assertNotNull(result);
        assertFalse(result.isEmpty());
        assertFalse(result.contains("{{"));
    }

    // === randomLastName ===

    @Test
    @DisplayName("randomLastName generates a nonempty last name")
    void shouldGenerateRandomLastName() {
        var context = buildContext("/test", "GET", null, null, null);
        String result = templateService.render("{{randomLastName}}", context);
        assertNotNull(result);
        assertFalse(result.isEmpty());
        assertFalse(result.contains("{{"));
    }

    // === randomFullName ===

    @Test
    @DisplayName("randomFullName generates a full name containing a space")
    void shouldGenerateRandomFullName() {
        var context = buildContext("/test", "GET", null, null, null);
        String result = templateService.render("{{randomFullName}}", context);
        assertNotNull(result);
        assertTrue(result.contains(" "), "Full name should contain a space");
    }

    // === randomEmail ===

    @Test
    @DisplayName("randomEmail generates a correctly formatted email address")
    void shouldGenerateRandomEmail() {
        var context = buildContext("/test", "GET", null, null, null);
        String result = templateService.render("{{randomEmail}}", context);
        assertTrue(result.matches("[a-z]+\\.[a-z]+@[a-z]+\\.[a-z]+"), "Should match email format: " + result);
    }

    // === randomPhoneNumber ===

    @Test
    @DisplayName("randomPhoneNumber generates a formatted phone number")
    void shouldGenerateRandomPhoneNumber() {
        var context = buildContext("/test", "GET", null, null, null);
        String result = templateService.render("{{randomPhoneNumber}}", context);
        assertTrue(result.matches("\\(\\d{3}\\) \\d{3}-\\d{4}"), "Should match phone format: " + result);
    }

    // === randomCity ===

    @Test
    @DisplayName("randomCity generates a nonempty city name")
    void shouldGenerateRandomCity() {
        var context = buildContext("/test", "GET", null, null, null);
        String result = templateService.render("{{randomCity}}", context);
        assertNotNull(result);
        assertFalse(result.isEmpty());
    }

    // === randomCountry ===

    @Test
    @DisplayName("randomCountry generates a nonempty country name")
    void shouldGenerateRandomCountry() {
        var context = buildContext("/test", "GET", null, null, null);
        String result = templateService.render("{{randomCountry}}", context);
        assertNotNull(result);
        assertFalse(result.isEmpty());
    }

    // === randomStreetAddress ===

    @Test
    @DisplayName("randomStreetAddress generates a street number and name")
    void shouldGenerateRandomStreetAddress() {
        var context = buildContext("/test", "GET", null, null, null);
        String result = templateService.render("{{randomStreetAddress}}", context);
        assertTrue(result.matches("\\d+ .+"), "Should match address format: " + result);
    }

    // === randomInt ===

    @Test
    @DisplayName("randomInt generates an integer within the requested range")
    void shouldGenerateRandomInt() {
        var context = buildContext("/test", "GET", null, null, null);
        String result = templateService.render("{{randomInt min=10 max=20}}", context);
        int value = Integer.parseInt(result);
        assertTrue(value >= 10 && value <= 20, "Value should be 10-20: " + value);
    }

    @Test
    @DisplayName("randomInt defaults to the range 0 through 100")
    void shouldGenerateRandomIntDefault() {
        var context = buildContext("/test", "GET", null, null, null);
        String result = templateService.render("{{randomInt}}", context);
        int value = Integer.parseInt(result);
        assertTrue(value >= 0 && value <= 100, "Value should be 0-100: " + value);
    }

    @Test
    @DisplayName("randomInt swaps min and max when min > max")
    void shouldSwapMinMaxWhenReversed() {
        var context = buildContext("/test", "GET", null, null, null);
        String result = templateService.render("{{randomInt min=50 max=10}}", context);
        int value = Integer.parseInt(result);
        assertTrue(value >= 10 && value <= 50, "Value should be 10-50 after swap: " + value);
    }

    @Test
    @DisplayName("randomInt returns the bound when min == max")
    void shouldReturnExactValueWhenMinEqualsMax() {
        var context = buildContext("/test", "GET", null, null, null);
        String result = templateService.render("{{randomInt min=42 max=42}}", context);
        assertEquals("42", result);
    }

    @Test
    @DisplayName("randomInt supports negative ranges")
    void shouldSupportNegativeRange() {
        var context = buildContext("/test", "GET", null, null, null);
        String result = templateService.render("{{randomInt min=-10 max=-1}}", context);
        int value = Integer.parseInt(result);
        assertTrue(value >= -10 && value <= -1, "Value should be -10 to -1: " + value);
    }

    // === Faker composite test ===

    @Test
    @DisplayName("Faker produces different results across repeated calls")
    void shouldGenerateDifferentValuesOnMultipleCalls() {
        var context = buildContext("/test", "GET", null, null, null);
        Set<String> results = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            results.add(templateService.render("{{randomFullName}}", context));
        }
        // 30 first names × 30 last names = 900 combinations, there should be at least 2 different results 20 times
        assertTrue(results.size() > 1, "Should generate different names across calls, got: " + results);
    }

    @Test
    @DisplayName("Faker combines with if/eq conditions")
    void shouldUseFakerWithCondition() {
        var context = buildContext("/test", "POST", null, null, null);
        String template = "{{#if (eq request.method 'POST')}}Created by {{randomFirstName}}{{/if}}";
        String result = templateService.render(template, context);
        assertTrue(result.startsWith("Created by "), "Should start with 'Created by ': " + result);
        assertTrue(result.length() > "Created by ".length(), "Should have a name after prefix");
    }

    @Test
    @DisplayName("Faker produces a value on each loop iteration")
    void shouldUseFakerInEachLoop() {
        Map<String, String> query = Map.of("ids", "1,2,3");
        var context = buildContext("/test", "GET", query, null, null);
        String template = "{{#each (split request.query.ids ',')}}{{randomFirstName}} {{/each}}";
        String result = templateService.render(template, context);
        // There should be 3 names, separated by spaces
        String[] parts = result.trim().split("\\s+");
        assertEquals(3, parts.length, "Should have 3 names: " + result);
        for (String part : parts) {
            assertFalse(part.isEmpty(), "Each name should be non-empty");
            assertFalse(part.contains("{{"), "Should not contain template syntax");
        }
    }

    @Test
    @DisplayName("randomInt does not overflow at Integer.MAX_VALUE")
    void shouldHandleIntMaxValue() {
        var context = buildContext("/test", "GET", null, null, null);
        // Using range close to MAX_VALUE
        String result = templateService.render("{{randomInt min=2147483600 max=2147483647}}", context);
        long value = Long.parseLong(result);
        assertTrue(value >= 2147483600L && value <= 2147483647L,
                "Value should be in range [2147483600, 2147483647]: " + value);
    }

    @Test
    @DisplayName("Compound Faker templates generate complete user profiles")
    void shouldGenerateFakeUserProfile() {
        var context = buildContext("/test", "GET", null, null, null);
        String template = """
            {
              "name": "{{randomFullName}}",
              "email": "{{randomEmail}}",
              "phone": "{{randomPhoneNumber}}",
              "city": "{{randomCity}}",
              "country": "{{randomCountry}}",
              "address": "{{randomStreetAddress}}",
              "age": {{randomInt min=18 max=65}}
            }
            """;
        String result = templateService.render(template, context);
        assertFalse(result.contains("{{"), "Should not contain unresolved templates");
        assertTrue(result.contains("\"name\":"));
        assertTrue(result.contains("\"email\":"));
        assertTrue(result.contains("\"phone\":"));
    }

    @Test
    @DisplayName("renderIfTemplate returns non-template text unchanged without creating a context")
    void renderIfTemplateSkipsContextWithoutTemplate() {
        java.util.concurrent.atomic.AtomicInteger built = new java.util.concurrent.atomic.AtomicInteger();

        String result = ResponseTemplateService.renderIfTemplate(templateService, "{\"plain\":true}", () -> {
            built.incrementAndGet();
            return buildContext("/x", "GET", null, null, null);
        });

        assertEquals("{\"plain\":true}", result);
        assertEquals(0, built.get());
        assertNull(ResponseTemplateService.renderIfTemplate(templateService, null, () -> null));
        assertEquals("{{x}}", ResponseTemplateService.renderIfTemplate(null, "{{x}}", () -> null));
    }

    @Test
    @DisplayName("TemplateContext.of builds query parameters from the raw query string")
    void templateContextParsesQueryString() {
        var context = ResponseTemplateService.TemplateContext.of("/p", "GET", "a=1&b=x&flag", Map.of("h", "v"), "body");

        assertEquals("1|x|v|body", templateService.render(
                "{{request.query.a}}|{{request.query.b}}|{{request.headers.h}}|{{request.body}}", context));
        assertEquals(Map.of("a", "1", "b", "x"), context.query());
    }

    // ========================================
    // The same rendering is only parsed once
    // ========================================

    @Test
    @DisplayName("Repeated xPath/jsonPath reads on the same content match individually parsed results")
    void multiplePathsOnSameBodyGiveSameResults() {
        String xml = "<o><a>1</a><b x='y'>2</b><c><d>3</d></c></o>";
        var xmlContext = buildContext("/t", "POST", null, null, xml);
        assertEquals("1|2|y|3|",
                templateService.render("{{xPath request.body '//a/text()'}}|{{xPath request.body '/o/b'}}|"
                        + "{{xPath request.body '//b/@x'}}|{{xPath request.body '//c/d/text()'}}|"
                        + "{{xPath request.body '//missing/text()'}}", xmlContext));

        String json = "{\"a\":1,\"b\":{\"c\":\"x\"},\"items\":[{\"id\":7},{\"id\":8}]}";
        var jsonContext = buildContext("/t", "POST", null, null, json);
        assertEquals("1|x|8|2|",
                templateService.render("{{jsonPath request.body '$.a'}}|{{jsonPath request.body '$.b.c'}}|"
                        + "{{jsonPath request.body '$.items[1].id'}}|{{size (jsonPath request.body '$.items')}}|"
                        + "{{jsonPath request.body '$.nope'}}", jsonContext));
    }

    @Test
    @DisplayName("ParsedBodies parses identical content once and remembers parsing failures")
    void parsedBodiesParseEachSourceOnce() {
        var parsed = new ResponseTemplateService.ParsedBodies();
        String xml = "<o><a>1</a></o>";

        assertSame(parsed.xml(xml), parsed.xml(new String(xml)));
        assertSame(parsed.json("{\"a\":1}"), parsed.json("{\"a\":1}"));
        assertNull(parsed.xml("<broken"));
        assertNull(parsed.xml("<broken"));
        assertNull(parsed.json("{broken"));
    }

    @Test
    @DisplayName("ParsedBodies retains only the most recent bodies")
    void parsedBodiesKeepOnlyRecentSources() {
        var parsed = new ResponseTemplateService.ParsedBodies();
        var first = parsed.xml("<o>0</o>");
        for (int i = 1; i <= 4; i++) {
            parsed.xml("<o>" + i + "</o>");
        }

        assertNotSame(first, parsed.xml("<o>0</o>"));
    }

    @Test
    @DisplayName("Parsed results remain request-local and use each request's content")
    void parsedBodiesAreNotSharedAcrossRenders() {
        String template = "{{xPath request.body '//id/text()'}}-{{jsonPath request.body '$.id'}}";
        assertEquals("A-", templateService.render(template, buildContext("/t", "POST", null, null, "<r><id>A</id></r>")));
        assertEquals("B-", templateService.render(template, buildContext("/t", "POST", null, null, "<r><id>B</id></r>")));
        assertEquals("-9", templateService.render(template, buildContext("/t", "POST", null, null, "{\"id\":9}")));
    }

    @Test
    @DisplayName("Concurrent xPath/jsonPath rendering returns each thread's own values")
    void concurrentRendersWithPathsStayIsolated() throws Exception {
        var executor = Executors.newFixedThreadPool(8);
        try {
            java.util.List<Callable<Boolean>> tasks = new java.util.ArrayList<>();
            for (int i = 0; i < 400; i++) {
                String id = "id-" + i;
                boolean xml = i % 2 == 0;
                tasks.add(() -> {
                    String body = xml ? "<r><id>" + id + "</id><n>" + id + "</n></r>"
                            : "{\"id\":\"" + id + "\",\"n\":\"" + id + "\"}";
                    String template = xml ? "{{xPath request.body '//id/text()'}}{{xPath request.body '/r/n'}}"
                            : "{{jsonPath request.body '$.id'}}{{jsonPath request.body '$.n'}}";
                    return (id + id).equals(templateService.render(template, buildContext("/t", "POST", null, null, body)));
                });
            }
            assertTrue(executor.invokeAll(tasks).stream().allMatch(future -> {
                try {
                    return future.get();
                } catch (Exception e) {
                    return false;
                }
            }));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("Invalid XPath expressions return an empty string without affecting other template fields")
    void invalidXPathExpressionReturnsEmpty() {
        var context = buildContext("/t", "POST", null, null, "<r><id>1</id></r>");
        assertEquals("[]1", templateService.render("[{{xPath request.body '//['}}]{{xPath request.body '//id/text()'}}", context));
    }

    @Test
    @DisplayName("jsonPath append() does not affect other field reads in the same template")
    void jsonPathAppendDoesNotLeakIntoLaterPaths() {
        var context = buildContext("/t", "POST", null, null, "{\"items\":[{\"id\":7},{\"id\":8}]}");

        String result = templateService.render("{{size (jsonPath request.body '$.items.append(9)')}}|"
                + "{{size (jsonPath request.body '$.items')}}|{{jsonPath request.body '$.items.length()'}}", context);

        assertEquals("3|2|2", result);
    }

    @Test
    @DisplayName("Long request-derived XPath expressions are not retained on threads")
    void longXPathExpressionsAreNotRetained() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            String longPath = "concat('" + "A".repeat(ResponseTemplateService.MAX_CACHED_XPATH_LENGTH) + "', //id/text())";
            var context = buildContext("/t", "POST", null, Map.of("p", longPath), "<r><id>1</id></r>");
            var counts = executor.submit(() -> {
                int before = ResponseTemplateService.compiledXPathCountForCurrentThread();
                String rendered = templateService.render("{{xPath request.body request.headers.p}}", context);
                templateService.render("{{xPath request.body '//id/text()'}}", context);
                return new Object[]{rendered, before, ResponseTemplateService.compiledXPathCountForCurrentThread()};
            }).get();

            assertTrue(((String) counts[0]).endsWith("A1"));
            assertEquals((int) counts[1] + 1, (int) counts[2], "only the short expression is kept");
        } finally {
            executor.shutdownNow();
        }
    }
}
