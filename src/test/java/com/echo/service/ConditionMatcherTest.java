package com.echo.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConditionMatcherTest {

    private final ConditionMatcher matcher = new ConditionMatcher();

    // ========== 基本測試 ==========

    @Test
    void matches_shouldReturnTrue_whenNoCondition() {
        assertThat(matcher.matches(null, "{\"any\":\"body\"}")).isTrue();
        assertThat(matcher.matches("", "{\"any\":\"body\"}")).isTrue();
        assertThat(matcher.matches("   ", "{\"any\":\"body\"}")).isTrue();
    }

    @Test
    void matches_shouldReturnFalse_whenConditionButNoBody() {
        assertThat(matcher.matches("custId=123", null)).isFalse();
        assertThat(matcher.matches("custId=123", "")).isFalse();
        assertThat(matcher.matches("custId=123", "   ")).isFalse();
    }

    // ========== JSON 測試 ==========

    @Test
    void json_shouldMatchSimpleField() {
        String body = "{\"custId\":\"K113113114\",\"name\":\"Test\"}";
        
        assertThat(matcher.matches("custId=K113113114", body)).isTrue();
        assertThat(matcher.matches("custId=WRONG", body)).isFalse();
    }

    @Test
    void json_shouldMatchNestedField() {
        String body = "{\"order\":{\"customer\":{\"id\":\"VIP001\"}}}";
        
        assertThat(matcher.matches("order.customer.id=VIP001", body)).isTrue();
        assertThat(matcher.matches("order.customer.id=WRONG", body)).isFalse();
    }

    @Test
    void json_shouldMatchArrayIndex() {
        String body = "{\"items\":[{\"id\":\"A\"},{\"id\":\"B\"}]}";
        
        assertThat(matcher.matches("items[0].id=A", body)).isTrue();
        assertThat(matcher.matches("items[1].id=B", body)).isTrue();
        assertThat(matcher.matches("items[0].id=B", body)).isFalse();
    }

    @Test
    void json_shouldMatchMultipleConditions() {
        String body = "{\"custId\":\"K123\",\"status\":\"ACTIVE\",\"type\":\"VIP\"}";
        
        assertThat(matcher.matches("custId=K123;status=ACTIVE", body)).isTrue();
        assertThat(matcher.matches("custId=K123;status=INACTIVE", body)).isFalse();
    }

    @Test
    void json_shouldMatchNumericValue() {
        String body = "{\"amount\":100,\"count\":5}";
        
        assertThat(matcher.matches("amount=100", body)).isTrue();
        assertThat(matcher.matches("count=5", body)).isTrue();
    }

    @Test
    void json_shouldReturnFalse_whenFieldNotExists() {
        String body = "{\"custId\":\"K123\"}";
        assertThat(matcher.matches("nonexistent=value", body)).isFalse();
    }

    @Test
    void json_shouldReturnFalse_whenInvalidJson() {
        assertThat(matcher.matches("field=value", "{invalid json}")).isFalse();
    }

    @Test
    void json_shouldReturnFalse_whenInvalidConditionFormat() {
        String body = "{\"custId\":\"K123\"}";
        assertThat(matcher.matches("invalidcondition", body)).isFalse();
        assertThat(matcher.matches("=value", body)).isFalse();
    }

    @Test
    void json_shouldMatchArrayBody() {
        String body = "[{\"id\":\"A\"},{\"id\":\"B\"}]";
        // 陣列根節點需要用索引存取
        assertThat(matcher.matches("[0].id=A", body)).isFalse(); // 不支援根陣列索引
    }

    @Test
    void json_shouldReturnFalse_whenArrayIndexOutOfBounds() {
        String body = "{\"items\":[{\"id\":\"A\"}]}";
        assertThat(matcher.matches("items[5].id=A", body)).isFalse();
    }

    @Test
    void json_shouldReturnFalse_whenNestedPathNotExists() {
        String body = "{\"order\":{}}";
        assertThat(matcher.matches("order.customer.id=VIP001", body)).isFalse();
    }

    // ========== XML (JMS) 測試 ==========

    @Test
    void xml_shouldMatchSimpleElement() {
        String xml = "<request><custId>K113113114</custId><status>OK</status></request>";
        
        assertThat(matcher.matches("custId=K113113114", xml)).isTrue();
        assertThat(matcher.matches("status=OK", xml)).isTrue();
        assertThat(matcher.matches("custId=WRONG", xml)).isFalse();
    }

    @Test
    void xml_shouldMatchWithXPath() {
        String xml = "<request><custId>K113113114</custId></request>";
        
        assertThat(matcher.matches("//custId=K113113114", xml)).isTrue();
        assertThat(matcher.matches("/request/custId=K113113114", xml)).isTrue();
    }

    @Test
    void xml_shouldMatchWithNamespace() {
        String xml = "<ns:request xmlns:ns=\"http://example.com\"><ns:custId>K113113114</ns:custId></ns:request>";
        
        assertThat(matcher.matches("custId=K113113114", xml)).isTrue();
        assertThat(matcher.matches("//custId=K113113114", xml)).isTrue();
        assertThat(matcher.matches("/request/custId=K113113114", xml)).isTrue();
    }

    @Test
    void xml_shouldMatchNestedElement() {
        String xml = """
            <order>
                <customer>
                    <id>VIP001</id>
                    <name>Test</name>
                </customer>
            </order>
            """;
        
        assertThat(matcher.matches("//customer/id=VIP001", xml)).isTrue();
        assertThat(matcher.matches("/order/customer/id=VIP001", xml)).isTrue();
        assertThat(matcher.matches("id=VIP001", xml)).isTrue();
    }

    @Test
    void xml_shouldMatchMultipleConditions() {
        String xml = "<req><custId>K123</custId><status>ACTIVE</status></req>";
        
        assertThat(matcher.matches("custId=K123;status=ACTIVE", xml)).isTrue();
        assertThat(matcher.matches("custId=K123;status=INACTIVE", xml)).isFalse();
    }

    @Test
    void xml_shouldMatchXmlMessage() {
        String xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                <soap:Body>
                    <OrderRequest>
                        <Header>
                            <TransactionId>TXN123456</TransactionId>
                            <SourceSystem>CRM</SourceSystem>
                        </Header>
                        <Body>
                            <CustomerId>K113113114</CustomerId>
                            <OrderType>VIP</OrderType>
                            <Amount>50000</Amount>
                        </Body>
                    </OrderRequest>
                </soap:Body>
            </soap:Envelope>
            """;
        
        assertThat(matcher.matches("CustomerId=K113113114", xml)).isTrue();
        assertThat(matcher.matches("OrderType=VIP", xml)).isTrue();
        assertThat(matcher.matches("//CustomerId=K113113114", xml)).isTrue();
        assertThat(matcher.matches("CustomerId=K113113114;OrderType=VIP", xml)).isTrue();
    }

    @Test
    void xml_shouldMatchWithWhitespace() {
        String xml = """
            <request>
                <custId>  K123  </custId>
            </request>
            """;
        
        assertThat(matcher.matches("custId=K123", xml)).isTrue();
    }

    @Test
    void xml_shouldReturnFalse_whenElementNotExists() {
        String xml = "<request><custId>K123</custId></request>";
        assertThat(matcher.matches("nonexistent=value", xml)).isFalse();
    }

    @Test
    void xml_shouldReturnFalse_whenInvalidConditionFormat() {
        String xml = "<request><custId>K123</custId></request>";
        assertThat(matcher.matches("invalidcondition", xml)).isFalse();
    }

    // ========== 純文字測試 ==========

    @Test
    void plainText_shouldMatchContains() {
        String body = "This is a plain text message with keyword";
        assertThat(matcher.matches("keyword", body)).isTrue();
        assertThat(matcher.matches("notfound", body)).isFalse();
    }

    // ========== 分開的 body/query 條件測試 ==========

    @Test
    void matchesSeparate_shouldMatchBodyCondition() {
        String body = "{\"custId\":\"K123\"}";
        assertThat(matcher.matches("custId=K123", null, body, null)).isTrue();
        assertThat(matcher.matches("custId=WRONG", null, body, null)).isFalse();
    }

    @Test
    void matchesSeparate_shouldMatchQueryCondition() {
        assertThat(matcher.matches(null, "id=123", null, "id=123&type=vip")).isTrue();
        assertThat(matcher.matches(null, "id=999", null, "id=123")).isFalse();
    }

    @Test
    void matchesSeparate_shouldMatchBothConditions() {
        String body = "{\"custId\":\"K123\"}";
        String query = "status=active";
        assertThat(matcher.matches("custId=K123", "status=active", body, query)).isTrue();
        assertThat(matcher.matches("custId=K123", "status=inactive", body, query)).isFalse();
    }

    @Test
    void matchesSeparate_shouldReturnTrue_whenBothNull() {
        assertThat(matcher.matches(null, null, "{}", "a=1")).isTrue();
    }

    @Test
    void matchesSeparate_shouldReturnFalse_whenQueryNotMatch() {
        assertThat(matcher.matches(null, "missing=value", null, "other=1")).isFalse();
    }

    // ========== Query Parameter 測試 ==========

    @Test
    void queryParam_shouldMatchMultiple() {
        assertThat(matcher.matches("?id=1;?type=vip", "{}", "id=1&type=vip")).isTrue();
        assertThat(matcher.matches("?id=1;?type=vip", "{}", "id=1&type=normal")).isFalse();
    }

    @Test
    void queryParam_shouldReturnFalse_whenQueryStringNull() {
        assertThat(matcher.matches("?id=1", "{}", null)).isFalse();
    }

    // ========== WireMock 風格 Operators 測試 ==========

    @Test
    void operator_contains_shouldMatch() {
        String body = "{\"name\":\"John Smith\"}";
        assertThat(matcher.matches("name*=Smith", body)).isTrue();
        assertThat(matcher.matches("name*=John", body)).isTrue();
        assertThat(matcher.matches("name*=Jane", body)).isFalse();
    }

    @Test
    void operator_matches_shouldMatchRegex() {
        String body = "{\"email\":\"test@example.com\"}";
        assertThat(matcher.matches("email~=.*@example\\.com", body)).isTrue();
        assertThat(matcher.matches("email~=test@.*", body)).isTrue();
        assertThat(matcher.matches("email~=.*@other\\.com", body)).isFalse();
    }

    @Test
    void operator_notEqual_shouldMatch() {
        String body = "{\"status\":\"active\"}";
        assertThat(matcher.matches("status!=inactive", body)).isTrue();
        assertThat(matcher.matches("status!=active", body)).isFalse();
    }

    @Test
    void jsonPath_shouldMatch() {
        String body = "{\"store\":{\"book\":[{\"title\":\"A\"},{\"title\":\"B\"}]}}";
        assertThat(matcher.matches("$.store.book[0].title=A", body)).isTrue();
        assertThat(matcher.matches("$.store.book[1].title=B", body)).isTrue();
        assertThat(matcher.matches("$.store.book[0].title=X", body)).isFalse();
    }

    @Test
    void jsonPath_withOperators_shouldMatch() {
        String body = "{\"name\":\"John Smith\",\"age\":30}";
        assertThat(matcher.matches("$.name*=Smith", body)).isTrue();
        assertThat(matcher.matches("$.name~=John.*", body)).isTrue();
        assertThat(matcher.matches("$.age!=25", body)).isTrue();
    }

    @Test
    void xpath_withOperators_shouldMatch() {
        String xml = "<user><name>John Smith</name><status>active</status></user>";
        assertThat(matcher.matches("//name*=Smith", xml)).isTrue();
        assertThat(matcher.matches("//name~=John.*", xml)).isTrue();
        assertThat(matcher.matches("//status!=inactive", xml)).isTrue();
    }

    @Test
    void queryParam_withOperators_shouldMatch() {
        assertThat(matcher.matches("?name*=Smith", "{}", "name=John%20Smith")).isTrue();
        assertThat(matcher.matches("?id~=\\d+", "{}", "id=12345")).isTrue();
        assertThat(matcher.matches("?status!=inactive", "{}", "status=active")).isTrue();
    }

    // ========== Size Limits 測試 ==========

    @Test
    void sizeLimit_shouldRejectLargeXml_whenExceeds10MB() {
        StringBuilder largeXml = new StringBuilder("<root>");
        // 建立超過 10MB 的 XML
        for (int i = 0; i < 1024 * 1024; i++) {
            largeXml.append("<item>data</item>");
        }
        largeXml.append("</root>");
        
        assertThat(matcher.matches("item=data", largeXml.toString())).isFalse();
    }

    @Test
    void sizeLimit_shouldRejectLargeJson_whenExceeds10MB() {
        StringBuilder largeJson = new StringBuilder("{\"items\":[");
        // 建立超過 10MB 的 JSON
        for (int i = 0; i < 1024 * 1024; i++) {
            if (i > 0) largeJson.append(",");
            largeJson.append("\"item").append(i).append("\"");
        }
        largeJson.append("]}");
        
        assertThat(matcher.matches("items[0]=item0", largeJson.toString())).isFalse();
    }

    @Test
    void sizeLimit_shouldAcceptNormalSize_whenUnder10MB() {
        String normalXml = "<root><item>data</item></root>";
        String normalJson = "{\"item\":\"data\"}";
        
        assertThat(matcher.matches("item=data", normalXml)).isTrue();
        assertThat(matcher.matches("item=data", normalJson)).isTrue();
    }

    // ========== Pattern Cache 測試 ==========

    @Test
    void patternCache_shouldReuseCompiledPattern_whenSameRegex() {
        String body1 = "{\"email\":\"user1@test.com\"}";
        String body2 = "{\"email\":\"user2@test.com\"}";
        String regex = "email~=.*@test\\.com";
        
        // 第一次使用會編譯 pattern
        assertThat(matcher.matches(regex, body1)).isTrue();
        // 第二次使用應該重用快取的 pattern
        assertThat(matcher.matches(regex, body2)).isTrue();
    }

    @Test
    void patternCache_shouldCacheDifferentPatterns_whenDifferentRegex() {
        String body = "{\"email\":\"test@example.com\",\"phone\":\"123-456-7890\"}";
        
        assertThat(matcher.matches("email~=.*@example\\.com", body)).isTrue();
        assertThat(matcher.matches("phone~=\\d{3}-\\d{3}-\\d{4}", body)).isTrue();
        // 兩個不同的 regex 都應該被快取
        assertThat(matcher.matches("email~=.*@example\\.com", body)).isTrue();
    }

    @Test
    void patternCache_shouldHandleInvalidRegex_whenCaching() {
        String body = "{\"text\":\"sample\"}";
        String invalidRegex = "text~=[invalid(regex";
        
        // 無效的 regex 不應該被快取，且應該返回 false
        assertThat(matcher.matches(invalidRegex, body)).isFalse();
        assertThat(matcher.matches(invalidRegex, body)).isFalse();
    }

    // ========== parseCondition 運算子解析測試 ==========

    @Test
    void parseCondition_scenarioA_valueContainsNotEqual() {
        // 場景 A: value 含 != 不應被誤判
        // condition: status=active!=true → field=status, op=EQUAL, value=active!=true
        String body = "{\"status\":\"active!=true\"}";
        assertThat(matcher.matches("status=active!=true", body)).isTrue();
    }

    @Test
    void parseCondition_scenarioB_valueContainsTildeEqual() {
        // 場景 B: value 含 ~= 不應被誤判
        // condition: regex=abc~=def → field=regex, op=EQUAL, value=abc~=def
        String body = "{\"regex\":\"abc~=def\"}";
        assertThat(matcher.matches("regex=abc~=def", body)).isTrue();
    }

    @Test
    void parseCondition_scenarioC_jsonPathNormal() {
        // 場景 C: JsonPath 正常解析
        String body = "{\"data\":{\"items\":[{\"name\":\"test\"}]}}";
        assertThat(matcher.matches("$.data.items[0].name=test", body)).isTrue();
    }

    @Test
    void parseCondition_scenarioD_xpathNotEqual() {
        // 場景 D: XPath 搭配 != 運算子
        String xml = "<root><element>value</element></root>";
        assertThat(matcher.matches("//element!=wrong", xml)).isTrue();
        assertThat(matcher.matches("//element!=value", xml)).isFalse();
    }

    @Test
    void parseCondition_scenarioE_valueContainsStarEqual() {
        // 場景 E: value 含 *= 不應被誤判
        // condition: field=abc*=def → field=field, op=EQUAL, value=abc*=def
        String body = "{\"field\":\"abc*=def\"}";
        assertThat(matcher.matches("field=abc*=def", body)).isTrue();
    }

    @Test
    void parseCondition_scenarioF_doubleEqualsSyntax() {
        // 場景 F: == 語法（matchJson 會先 replace == → =）
        String body = "{\"type\":\"ORDER\"}";
        assertThat(matcher.matches("type == ORDER", body)).isTrue();
        // body.type prefix 會被自動去掉，所以也能匹配
        assertThat(matcher.matches("body.type == \"ORDER\"", body)).isTrue();
    }

    @Test
    void parseCondition_scenarioG_headerWithOperator() {
        // 場景 G: Header 條件搭配運算子
        java.util.Map<String, String> headers = java.util.Map.of("Content-Type", "application/json");
        assertThat(matcher.matches(null, null, "Content-Type!=text/xml", null, null, headers)).isTrue();
        assertThat(matcher.matches(null, null, "Content-Type!=application/json", null, null, headers)).isFalse();
        assertThat(matcher.matches(null, null, "Content-Type*=json", null, null, headers)).isTrue();
    }

    @Test
    void parseCondition_scenarioH_emptyValue() {
        // 場景 H: value 為空
        String body = "{\"field\":\"\"}";
        assertThat(matcher.matches("field=", body)).isTrue();
    }

    @Test
    void parseCondition_scenarioI_jsonPathWithContains() {
        // 場景 I: JsonPath 搭配 *= 運算子
        String body = "{\"user\":{\"name\":\"John Smith\"}}";
        assertThat(matcher.matches("$.user.name*=Smith", body)).isTrue();
        assertThat(matcher.matches("$.user.name*=Jane", body)).isFalse();
    }
}
