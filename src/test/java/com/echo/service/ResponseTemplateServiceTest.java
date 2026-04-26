package com.echo.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Response Template Service 單元測試
 * Phase 1: 基礎模板功能
 */
class ResponseTemplateServiceTest {

    private ResponseTemplateService templateService;

    @BeforeEach
    void setUp() {
        templateService = new ResponseTemplateService();
    }

    // === Request Path ===

    @Test
    @DisplayName("應該能取得完整路徑")
    void shouldGetFullPath() {
        var context = buildContext("/api/users/123", "GET", null, null, null);
        String result = templateService.render("Path: {{request.path}}", context);
        assertEquals("Path: /api/users/123", result);
    }

    @Test
    @DisplayName("應該能取得路徑片段")
    void shouldGetPathSegments() {
        var context = buildContext("/api/users/123", "GET", null, null, null);
        String result = templateService.render("{{request.pathSegments.[0]}}/{{request.pathSegments.[2]}}", context);
        assertEquals("api/123", result);
    }

    // === Request Method ===

    @Test
    @DisplayName("應該能取得 HTTP 方法")
    void shouldGetMethod() {
        var context = buildContext("/test", "POST", null, null, null);
        String result = templateService.render("Method: {{request.method}}", context);
        assertEquals("Method: POST", result);
    }

    // === Query Parameters ===

    @Test
    @DisplayName("應該能取得 Query 參數")
    void shouldGetQueryParam() {
        Map<String, String> query = Map.of("name", "John", "age", "30");
        var context = buildContext("/test", "GET", query, null, null);
        String result = templateService.render("Name: {{request.query.name}}, Age: {{request.query.age}}", context);
        assertEquals("Name: John, Age: 30", result);
    }

    @Test
    @DisplayName("Query 參數不存在時應該為空")
    void shouldHandleMissingQueryParam() {
        var context = buildContext("/test", "GET", Map.of(), null, null);
        String result = templateService.render("Name: {{request.query.name}}", context);
        assertEquals("Name: ", result);
    }

    // === Headers ===

    @Test
    @DisplayName("應該能取得 Header")
    void shouldGetHeader() {
        Map<String, String> headers = Map.of("Authorization", "Bearer token123", "X-Request-Id", "req-456");
        var context = buildContext("/test", "GET", null, headers, null);
        String result = templateService.render("Auth: {{request.headers.Authorization}}", context);
        assertEquals("Auth: Bearer token123", result);
    }

    @Test
    @DisplayName("Header 不存在時應該為空")
    void shouldHandleMissingHeader() {
        var context = buildContext("/test", "GET", null, Map.of(), null);
        String result = templateService.render("Token: {{request.headers.X-Token}}", context);
        assertEquals("Token: ", result);
    }

    // === Request Body ===

    @Test
    @DisplayName("應該能取得 Request Body")
    void shouldGetBody() {
        String body = "{\"user\":\"test\"}";
        var context = buildContext("/test", "POST", null, null, body);
        // 使用 triple braces 避免 HTML escape
        String result = templateService.render("Body: {{{request.body}}}", context);
        assertEquals("Body: {\"user\":\"test\"}", result);
    }

    // === Now (Date/Time) ===

    @Test
    @DisplayName("應該能產生當前時間")
    void shouldGenerateNow() {
        var context = buildContext("/test", "GET", null, null, null);
        String result = templateService.render("{{now}}", context);
        assertNotNull(result);
        assertFalse(result.contains("{{"));
    }

    @Test
    @DisplayName("應該能格式化當前時間")
    void shouldFormatNow() {
        var context = buildContext("/test", "GET", null, null, null);
        String result = templateService.render("{{now format='yyyy-MM-dd'}}", context);
        assertTrue(result.matches("\\d{4}-\\d{2}-\\d{2}"));
    }

    @Test
    @DisplayName("應該能產生 epoch 時間")
    void shouldGenerateEpoch() {
        var context = buildContext("/test", "GET", null, null, null);
        String result = templateService.render("{{now format='epoch'}}", context);
        assertTrue(result.matches("\\d+"));
        assertTrue(Long.parseLong(result) > 1700000000000L);
    }

    // === Random Value ===

    @Test
    @DisplayName("應該能產生 UUID")
    void shouldGenerateUUID() {
        var context = buildContext("/test", "GET", null, null, null);
        String result = templateService.render("{{randomValue type='UUID'}}", context);
        assertTrue(result.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
    }

    @Test
    @DisplayName("應該能產生指定長度的隨機字串")
    void shouldGenerateRandomString() {
        var context = buildContext("/test", "GET", null, null, null);
        String result = templateService.render("{{randomValue length=10 type='ALPHANUMERIC'}}", context);
        assertEquals(10, result.length());
        assertTrue(result.matches("[A-Za-z0-9]+"));
    }

    @Test
    @DisplayName("應該能產生隨機數字")
    void shouldGenerateRandomNumeric() {
        var context = buildContext("/test", "GET", null, null, null);
        String result = templateService.render("{{randomValue length=6 type='NUMERIC'}}", context);
        assertEquals(6, result.length());
        assertTrue(result.matches("[0-9]+"));
    }

    // === 複合測試 ===

    @Test
    @DisplayName("應該能處理複合模板")
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

    // === 無模板語法 ===

    @Test
    @DisplayName("無模板語法時應該原樣返回")
    void shouldReturnAsIsWhenNoTemplate() {
        var context = buildContext("/test", "GET", null, null, null);
        String result = templateService.render("Plain text response", context);
        assertEquals("Plain text response", result);
    }

    @Test
    @DisplayName("空字串應該返回空字串")
    void shouldHandleEmptyString() {
        var context = buildContext("/test", "GET", null, null, null);
        String result = templateService.render("", context);
        assertEquals("", result);
    }

    @Test
    @DisplayName("null 應該返回空字串")
    void shouldHandleNull() {
        var context = buildContext("/test", "GET", null, null, null);
        String result = templateService.render(null, context);
        assertEquals("", result);
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
    // Phase 2: 條件與迴圈
    // ========================================

    // === 條件判斷 {{#if}} ===

    @Test
    @DisplayName("if - 條件為真時應該渲染內容")
    void shouldRenderIfTrue() {
        Map<String, String> query = Map.of("debug", "true");
        var context = buildContext("/test", "GET", query, null, null);
        String result = templateService.render("{{#if request.query.debug}}DEBUG MODE{{/if}}", context);
        assertEquals("DEBUG MODE", result);
    }

    @Test
    @DisplayName("if - 條件為假時不應該渲染內容")
    void shouldNotRenderIfFalse() {
        var context = buildContext("/test", "GET", Map.of(), null, null);
        String result = templateService.render("{{#if request.query.debug}}DEBUG MODE{{/if}}", context);
        assertEquals("", result);
    }

    @Test
    @DisplayName("if/else - 應該渲染 else 區塊")
    void shouldRenderElse() {
        var context = buildContext("/test", "GET", Map.of(), null, null);
        String result = templateService.render("{{#if request.query.name}}Hello {{request.query.name}}{{else}}Hello Guest{{/if}}", context);
        assertEquals("Hello Guest", result);
    }

    @Test
    @DisplayName("unless - 條件為假時應該渲染內容")
    void shouldRenderUnless() {
        var context = buildContext("/test", "GET", Map.of(), null, null);
        String result = templateService.render("{{#unless request.query.auth}}Unauthorized{{/unless}}", context);
        assertEquals("Unauthorized", result);
    }

    // === 比較運算子 ===

    @Test
    @DisplayName("eq - 相等比較")
    void shouldCompareEqual() {
        Map<String, String> query = Map.of("status", "active");
        var context = buildContext("/test", "GET", query, null, null);
        String result = templateService.render("{{#if (eq request.query.status 'active')}}ACTIVE{{else}}INACTIVE{{/if}}", context);
        assertEquals("ACTIVE", result);
    }

    @Test
    @DisplayName("ne - 不相等比較")
    void shouldCompareNotEqual() {
        Map<String, String> query = Map.of("status", "pending");
        var context = buildContext("/test", "GET", query, null, null);
        String result = templateService.render("{{#if (ne request.query.status 'active')}}NOT ACTIVE{{/if}}", context);
        assertEquals("NOT ACTIVE", result);
    }

    @Test
    @DisplayName("gt - 大於比較")
    void shouldCompareGreaterThan() {
        Map<String, String> query = Map.of("count", "15");
        var context = buildContext("/test", "GET", query, null, null);
        String result = templateService.render("{{#if (gt request.query.count 10)}}MANY{{else}}FEW{{/if}}", context);
        assertEquals("MANY", result);
    }

    @Test
    @DisplayName("lt - 小於比較")
    void shouldCompareLessThan() {
        Map<String, String> query = Map.of("count", "5");
        var context = buildContext("/test", "GET", query, null, null);
        String result = templateService.render("{{#if (lt request.query.count 10)}}FEW{{else}}MANY{{/if}}", context);
        assertEquals("FEW", result);
    }

    @Test
    @DisplayName("contains - 包含比較")
    void shouldCompareContains() {
        Map<String, String> query = Map.of("name", "John Doe");
        var context = buildContext("/test", "GET", query, null, null);
        String result = templateService.render("{{#if (contains request.query.name 'John')}}FOUND{{/if}}", context);
        assertEquals("FOUND", result);
    }

    @Test
    @DisplayName("matches - 正則比較")
    void shouldCompareMatches() {
        var context = buildContext("/api/v2/users", "GET", null, null, null);
        String result = templateService.render("{{#if (matches request.path '/api/v[0-9]+/')}}VERSIONED API{{/if}}", context);
        assertEquals("VERSIONED API", result);
    }

    // === 迴圈 {{#each}} ===

    @Test
    @DisplayName("each - 應該能迭代 Query 參數陣列")
    void shouldIterateQueryArray() {
        // 模擬 ?ids=1&ids=2&ids=3 的情況，用逗號分隔
        Map<String, String> query = Map.of("ids", "1,2,3");
        var context = buildContext("/test", "GET", query, null, null);
        String result = templateService.render("{{#each (split request.query.ids ',')}}[{{this}}]{{/each}}", context);
        assertEquals("[1][2][3]", result);
    }

    @Test
    @DisplayName("each - 應該能使用 @index")
    void shouldAccessIndex() {
        Map<String, String> query = Map.of("items", "a,b,c");
        var context = buildContext("/test", "GET", query, null, null);
        String result = templateService.render("{{#each (split request.query.items ',')}}{{@index}}:{{this}} {{/each}}", context);
        assertEquals("0:a 1:b 2:c ", result);
    }

    @Test
    @DisplayName("each - 應該能使用 @first 和 @last")
    void shouldAccessFirstLast() {
        Map<String, String> query = Map.of("items", "x,y,z");
        var context = buildContext("/test", "GET", query, null, null);
        String result = templateService.render("{{#each (split request.query.items ',')}}{{this}}{{#unless @last}},{{/unless}}{{/each}}", context);
        assertEquals("x,y,z", result);
    }

    // === 字串處理 Helper ===

    @Test
    @DisplayName("split - 應該能分割字串")
    void shouldSplitString() {
        Map<String, String> query = Map.of("tags", "java,spring,boot");
        var context = buildContext("/test", "GET", query, null, null);
        String result = templateService.render("{{#each (split request.query.tags ',')}}{{this}} {{/each}}", context);
        assertEquals("java spring boot ", result);
    }

    @Test
    @DisplayName("size - 應該能取得陣列大小")
    void shouldGetSize() {
        Map<String, String> query = Map.of("items", "a,b,c,d,e");
        var context = buildContext("/test", "GET", query, null, null);
        String result = templateService.render("Count: {{size (split request.query.items ',')}}", context);
        assertEquals("Count: 5", result);
    }

    // === 複合條件測試 ===

    @Test
    @DisplayName("複合條件 - 根據方法和參數產生不同回應")
    void shouldHandleComplexCondition() {
        var context = buildContext("/api/users", "POST", Map.of(), null, null);
        String template = "{{#if (eq request.method 'POST')}}Created{{else if (eq request.method 'GET')}}Retrieved{{else}}Unknown{{/if}}";
        String result = templateService.render(template, context);
        assertEquals("Created", result);
    }

    @Test
    @DisplayName("複合模板 - 條件 + 迴圈 + 動態值")
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
    @DisplayName("jsonPath - 應該能取得簡單欄位")
    void shouldGetJsonPathSimpleField() {
        String body = "{\"name\":\"Alice\",\"age\":25}";
        var context = buildContext("/test", "POST", null, null, body);
        String result = templateService.render("Name: {{jsonPath request.body '$.name'}}", context);
        assertEquals("Name: Alice", result);
    }

    @Test
    @DisplayName("jsonPath - 應該能取得巢狀欄位")
    void shouldGetJsonPathNestedField() {
        String body = "{\"user\":{\"profile\":{\"name\":\"Bob\"}}}";
        var context = buildContext("/test", "POST", null, null, body);
        String result = templateService.render("{{jsonPath request.body '$.user.profile.name'}}", context);
        assertEquals("Bob", result);
    }

    @Test
    @DisplayName("jsonPath - 應該能取得陣列元素")
    void shouldGetJsonPathArrayElement() {
        String body = "{\"items\":[{\"id\":1},{\"id\":2},{\"id\":3}]}";
        var context = buildContext("/test", "POST", null, null, body);
        String result = templateService.render("First: {{jsonPath request.body '$.items[0].id'}}", context);
        assertEquals("First: 1", result);
    }

    @Test
    @DisplayName("jsonPath - 應該能取得陣列")
    void shouldGetJsonPathArray() {
        String body = "{\"tags\":[\"java\",\"spring\",\"boot\"]}";
        var context = buildContext("/test", "POST", null, null, body);
        String result = templateService.render("{{#each (jsonPath request.body '$.tags')}}{{this}} {{/each}}", context);
        assertEquals("java spring boot ", result);
    }

    @Test
    @DisplayName("jsonPath - 路徑不存在時應該為空")
    void shouldHandleMissingJsonPath() {
        String body = "{\"name\":\"Alice\"}";
        var context = buildContext("/test", "POST", null, null, body);
        String result = templateService.render("Age: {{jsonPath request.body '$.age'}}", context);
        assertEquals("Age: ", result);
    }

    @Test
    @DisplayName("jsonPath - 無效 JSON 應該為空")
    void shouldHandleInvalidJson() {
        String body = "not a json";
        var context = buildContext("/test", "POST", null, null, body);
        String result = templateService.render("{{jsonPath request.body '$.name'}}", context);
        assertEquals("", result);
    }

    @Test
    @DisplayName("jsonPath - 搭配 size 取得陣列大小")
    void shouldGetJsonPathArraySize() {
        String body = "{\"items\":[1,2,3,4,5]}";
        var context = buildContext("/test", "POST", null, null, body);
        String result = templateService.render("Count: {{size (jsonPath request.body '$.items')}}", context);
        assertEquals("Count: 5", result);
    }

    // === XPath ===

    @Test
    @DisplayName("xPath - 應該能取得元素文字")
    void shouldGetXPathText() {
        String body = "<root><name>Alice</name></root>";
        var context = buildContext("/test", "POST", null, null, body);
        String result = templateService.render("Name: {{xPath request.body '//name/text()'}}", context);
        assertEquals("Name: Alice", result);
    }

    @Test
    @DisplayName("xPath - 應該能取得巢狀元素")
    void shouldGetXPathNestedElement() {
        String body = "<order><customer><name>Bob</name></customer></order>";
        var context = buildContext("/test", "POST", null, null, body);
        String result = templateService.render("{{xPath request.body '//customer/name/text()'}}", context);
        assertEquals("Bob", result);
    }

    @Test
    @DisplayName("xPath - 應該能取得屬性")
    void shouldGetXPathAttribute() {
        String body = "<item id=\"123\" status=\"active\"/>";
        var context = buildContext("/test", "POST", null, null, body);
        String result = templateService.render("ID: {{xPath request.body '//item/@id'}}", context);
        assertEquals("ID: 123", result);
    }

    @Test
    @DisplayName("xPath - 路徑不存在時應該為空")
    void shouldHandleMissingXPath() {
        String body = "<root><name>Alice</name></root>";
        var context = buildContext("/test", "POST", null, null, body);
        String result = templateService.render("Age: {{xPath request.body '//age/text()'}}", context);
        assertEquals("Age: ", result);
    }

    @Test
    @DisplayName("xPath - 無效 XML 應該為空")
    void shouldHandleInvalidXml() {
        String body = "not xml";
        var context = buildContext("/test", "POST", null, null, body);
        String result = templateService.render("{{xPath request.body '//name'}}", context);
        assertEquals("", result);
    }

    // === 複合測試 ===

    @Test
    @DisplayName("jsonPath + 條件 - 根據 JSON 欄位判斷")
    void shouldUseJsonPathInCondition() {
        String body = "{\"status\":\"active\",\"count\":10}";
        var context = buildContext("/test", "POST", null, null, body);
        String result = templateService.render("{{#if (eq (jsonPath request.body '$.status') 'active')}}ACTIVE{{else}}INACTIVE{{/if}}", context);
        assertEquals("ACTIVE", result);
    }

    @Test
    @DisplayName("jsonPath + 迴圈 - 迭代 JSON 陣列")
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
    @DisplayName("randomFirstName - 應該產生非空名字")
    void shouldGenerateRandomFirstName() {
        var context = buildContext("/test", "GET", null, null, null);
        String result = templateService.render("{{randomFirstName}}", context);
        assertNotNull(result);
        assertFalse(result.isEmpty());
        assertFalse(result.contains("{{"));
    }

    // === randomLastName ===

    @Test
    @DisplayName("randomLastName - 應該產生非空姓氏")
    void shouldGenerateRandomLastName() {
        var context = buildContext("/test", "GET", null, null, null);
        String result = templateService.render("{{randomLastName}}", context);
        assertNotNull(result);
        assertFalse(result.isEmpty());
        assertFalse(result.contains("{{"));
    }

    // === randomFullName ===

    @Test
    @DisplayName("randomFullName - 應該產生包含空格的全名")
    void shouldGenerateRandomFullName() {
        var context = buildContext("/test", "GET", null, null, null);
        String result = templateService.render("{{randomFullName}}", context);
        assertNotNull(result);
        assertTrue(result.contains(" "), "Full name should contain a space");
    }

    // === randomEmail ===

    @Test
    @DisplayName("randomEmail - 應該產生有效格式的 email")
    void shouldGenerateRandomEmail() {
        var context = buildContext("/test", "GET", null, null, null);
        String result = templateService.render("{{randomEmail}}", context);
        assertTrue(result.matches("[a-z]+\\.[a-z]+@[a-z]+\\.[a-z]+"), "Should match email format: " + result);
    }

    // === randomPhoneNumber ===

    @Test
    @DisplayName("randomPhoneNumber - 應該產生格式化電話號碼")
    void shouldGenerateRandomPhoneNumber() {
        var context = buildContext("/test", "GET", null, null, null);
        String result = templateService.render("{{randomPhoneNumber}}", context);
        assertTrue(result.matches("\\(\\d{3}\\) \\d{3}-\\d{4}"), "Should match phone format: " + result);
    }

    // === randomCity ===

    @Test
    @DisplayName("randomCity - 應該產生非空城市名")
    void shouldGenerateRandomCity() {
        var context = buildContext("/test", "GET", null, null, null);
        String result = templateService.render("{{randomCity}}", context);
        assertNotNull(result);
        assertFalse(result.isEmpty());
    }

    // === randomCountry ===

    @Test
    @DisplayName("randomCountry - 應該產生非空國家名")
    void shouldGenerateRandomCountry() {
        var context = buildContext("/test", "GET", null, null, null);
        String result = templateService.render("{{randomCountry}}", context);
        assertNotNull(result);
        assertFalse(result.isEmpty());
    }

    // === randomStreetAddress ===

    @Test
    @DisplayName("randomStreetAddress - 應該產生門牌號碼 + 街道名")
    void shouldGenerateRandomStreetAddress() {
        var context = buildContext("/test", "GET", null, null, null);
        String result = templateService.render("{{randomStreetAddress}}", context);
        assertTrue(result.matches("\\d+ .+"), "Should match address format: " + result);
    }

    // === randomInt ===

    @Test
    @DisplayName("randomInt - 應該產生指定範圍內的整數")
    void shouldGenerateRandomInt() {
        var context = buildContext("/test", "GET", null, null, null);
        String result = templateService.render("{{randomInt min=10 max=20}}", context);
        int value = Integer.parseInt(result);
        assertTrue(value >= 10 && value <= 20, "Value should be 10-20: " + value);
    }

    @Test
    @DisplayName("randomInt - 預設範圍 0~100")
    void shouldGenerateRandomIntDefault() {
        var context = buildContext("/test", "GET", null, null, null);
        String result = templateService.render("{{randomInt}}", context);
        int value = Integer.parseInt(result);
        assertTrue(value >= 0 && value <= 100, "Value should be 0-100: " + value);
    }

    @Test
    @DisplayName("randomInt - min > max 時應自動交換")
    void shouldSwapMinMaxWhenReversed() {
        var context = buildContext("/test", "GET", null, null, null);
        String result = templateService.render("{{randomInt min=50 max=10}}", context);
        int value = Integer.parseInt(result);
        assertTrue(value >= 10 && value <= 50, "Value should be 10-50 after swap: " + value);
    }

    @Test
    @DisplayName("randomInt - min == max 時應回傳該值")
    void shouldReturnExactValueWhenMinEqualsMax() {
        var context = buildContext("/test", "GET", null, null, null);
        String result = templateService.render("{{randomInt min=42 max=42}}", context);
        assertEquals("42", result);
    }

    @Test
    @DisplayName("randomInt - 支援負數範圍")
    void shouldSupportNegativeRange() {
        var context = buildContext("/test", "GET", null, null, null);
        String result = templateService.render("{{randomInt min=-10 max=-1}}", context);
        int value = Integer.parseInt(result);
        assertTrue(value >= -10 && value <= -1, "Value should be -10 to -1: " + value);
    }

    // === Faker 複合測試 ===

    @Test
    @DisplayName("Faker - 多次呼叫應產生不同結果（隨機性驗證）")
    void shouldGenerateDifferentValuesOnMultipleCalls() {
        var context = buildContext("/test", "GET", null, null, null);
        Set<String> results = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            results.add(templateService.render("{{randomFullName}}", context));
        }
        // 30 個名字 × 30 個姓氏 = 900 種組合，20 次至少應該有 2 種不同結果
        assertTrue(results.size() > 1, "Should generate different names across calls, got: " + results);
    }

    @Test
    @DisplayName("Faker + 條件 - faker 搭配 if/eq 使用")
    void shouldUseFakerWithCondition() {
        var context = buildContext("/test", "POST", null, null, null);
        String template = "{{#if (eq request.method 'POST')}}Created by {{randomFirstName}}{{/if}}";
        String result = templateService.render(template, context);
        assertTrue(result.startsWith("Created by "), "Should start with 'Created by ': " + result);
        assertTrue(result.length() > "Created by ".length(), "Should have a name after prefix");
    }

    @Test
    @DisplayName("Faker + each 迴圈 - 每次迭代應產生值")
    void shouldUseFakerInEachLoop() {
        Map<String, String> query = Map.of("ids", "1,2,3");
        var context = buildContext("/test", "GET", query, null, null);
        String template = "{{#each (split request.query.ids ',')}}{{randomFirstName}} {{/each}}";
        String result = templateService.render(template, context);
        // 應該有 3 個名字，以空格分隔
        String[] parts = result.trim().split("\\s+");
        assertEquals(3, parts.length, "Should have 3 names: " + result);
        for (String part : parts) {
            assertFalse(part.isEmpty(), "Each name should be non-empty");
            assertFalse(part.contains("{{"), "Should not contain template syntax");
        }
    }

    @Test
    @DisplayName("randomInt - Integer.MAX_VALUE 邊界不應溢位")
    void shouldHandleIntMaxValue() {
        var context = buildContext("/test", "GET", null, null, null);
        // 使用接近 MAX_VALUE 的範圍
        String result = templateService.render("{{randomInt min=2147483600 max=2147483647}}", context);
        long value = Long.parseLong(result);
        assertTrue(value >= 2147483600L && value <= 2147483647L,
                "Value should be in range [2147483600, 2147483647]: " + value);
    }

    @Test
    @DisplayName("Faker 複合模板 - 產生完整使用者資料")
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
}
