package com.echo.service;

import com.echo.service.ConditionMatcher.PreparedBody;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConditionMatcherExtendedTest {

    private final ConditionMatcher matcher = new ConditionMatcher();

    @Test
    void prepareBody_tooLarge_shouldNotMatch() {
        String huge = "{" + "\"x\":\"" + "a".repeat(11 * 1024 * 1024) + "\"}";
        PreparedBody prepared = matcher.prepareBody(huge);

        boolean result = matcher.matchesPrepared(
                "x=value", null, null, prepared, null, null);

        assertThat(result).isFalse();
    }

    @Test
    void jsonPrepared_bodyPrefix_shouldBeStripped() {
        String json = "{\"type\":\"ORDER\"}";
        PreparedBody prepared = matcher.prepareBody(json);

        assertThat(matcher.matchesPrepared("body.type=ORDER", null, null, prepared, null, null)).isTrue();
    }

    @Test
    void jsonPrepared_arrayIndex_shouldWork() {
        String json = "{\"items\":[{\"id\":\"A\"},{\"id\":\"B\"}]}";
        PreparedBody prepared = matcher.prepareBody(json);

        assertThat(matcher.matchesPrepared("items[0].id=A", null, null, prepared, null, null)).isTrue();
    }

    @Test
    void jsonPrepared_arrayIndex_notArray_shouldFail() {
        String json = "{\"items\":\"not-array\"}";
        PreparedBody prepared = matcher.prepareBody(json);

        assertThat(matcher.matchesPrepared("items[0].id=A", null, null, prepared, null, null)).isFalse();
    }

    @Test
    void jsonPrepared_notEqual_fieldNotFound_shouldMatch() {
        String json = "{\"name\":\"test\"}";
        PreparedBody prepared = matcher.prepareBody(json);

        assertThat(matcher.matchesPrepared("missing!=anything", null, null, prepared, null, null)).isTrue();
    }

    @Test
    void xmlPrepared_elementNotFound_shouldFail() {
        String xml = "<root><name>test</name></root>";
        PreparedBody prepared = matcher.prepareBody(xml);

        assertThat(matcher.matchesPrepared("nonexistent=value", null, null, prepared, null, null)).isFalse();
    }

    @Test
    void xpathPrepared_notFound_shouldFail() {
        String xml = "<root><name>test</name></root>";
        PreparedBody prepared = matcher.prepareBody(xml);

        assertThat(matcher.matchesPrepared("//nonexistent=value", null, null, prepared, null, null)).isFalse();
    }

    @Test
    void xpath_xmlTooLarge_shouldFail() {
        StringBuilder largeXml = new StringBuilder("<root>");
        for (int i = 0; i < 800_000; i++) {
            largeXml.append("<item>x</item>");
        }
        largeXml.append("</root>");

        assertThat(largeXml.length()).isGreaterThan(10 * 1024 * 1024);
        assertThat(matcher.matches("//item=x", largeXml.toString())).isFalse();
    }

    @Test
    void jsonPrepared_doubleEquals_shouldBeNormalized() {
        String json = "{\"type\":\"ORDER\"}";
        PreparedBody prepared = matcher.prepareBody(json);

        assertThat(matcher.matchesPrepared("type==ORDER", null, null, prepared, null, null)).isTrue();
    }

    @Test
    void jsonPathPrepared_shouldMatch() {
        String json = "{\"user\":{\"name\":\"Alice\"}}";
        PreparedBody prepared = matcher.prepareBody(json);

        assertThat(matcher.matchesPrepared("$.user.name=Alice", null, null, prepared, null, null)).isTrue();
    }

    @Test
    void jsonPathPrepared_pathNotFound_shouldFail() {
        String json = "{\"user\":{\"name\":\"Alice\"}}";
        PreparedBody prepared = matcher.prepareBody(json);

        assertThat(matcher.matchesPrepared("$.user.age=30", null, null, prepared, null, null)).isFalse();
    }

    @Test
    void queryPrepared_notEqual_nullParam_shouldMatch() {
        assertThat(matcher.matchesPrepared(
                null, "missing!=value", null,
                matcher.prepareBody(null), null, null)).isTrue();
    }

    @Test
    void queryPrepared_notEqual_nullQueryString_shouldMatch() {
        assertThat(matcher.matchesPrepared(
                null, "status!=active", null,
                matcher.prepareBody(null), null, null)).isTrue();
    }

    @Test
    void headerPrepared_caseInsensitive_shouldMatch() {
        assertThat(matcher.matchesPrepared(
                null, null, "content-type=application/json",
                matcher.prepareBody(null), null,
                Map.of("Content-Type", "application/json"))).isTrue();
    }

    @Test
    void plainTextPrepared_shouldMatchContains() {
        PreparedBody prepared = matcher.prepareBody("hello world message");

        assertThat(matcher.matchesPrepared("world", null, null, prepared, null, null)).isTrue();
    }

    @Test
    void plainTextPrepared_shouldFailWhenNotContains() {
        PreparedBody prepared = matcher.prepareBody("hello world");

        assertThat(matcher.matchesPrepared("goodbye", null, null, prepared, null, null)).isFalse();
    }

    @Test
    void multipleHeaderConditions_shouldEarlyExit() {
        assertThat(matcher.matchesPrepared(
                null, null, "Accept=text/html;X-Custom=value",
                matcher.prepareBody(null), null,
                Map.of("Accept", "application/json"))).isFalse();
    }

    @Test
    void multipleQueryConditions_shouldEarlyExit() {
        assertThat(matcher.matchesPrepared(
                null, "page=1;size=wrong", null,
                matcher.prepareBody(null), "page=1&size=10", null)).isFalse();
    }

    @Test
    void xml_namespaceAgnostic_absolutePath_shouldMatch() {
        String xml = "<ns:root xmlns:ns=\"http://example.com\"><ns:child>value</ns:child></ns:root>";
        assertThat(matcher.matches("/root/child=value", xml)).isTrue();
    }

    @Test
    void xml_namespaceAgnostic_alreadyConverted_shouldNotReconvert() {
        String xml = "<root><child>value</child></root>";
        assertThat(matcher.matches("//child=value", xml)).isTrue();
    }

    @Test
    void matchBody_plainText_shouldContains() {
        assertThat(matcher.matches("keyword", "some keyword here")).isTrue();
        assertThat(matcher.matches("missing", "some keyword here")).isFalse();
    }

    @Test
    void regex_inputTooLong_shouldReturnFalse() {
        String longValue = "a".repeat(20000);
        String body = "{\"field\":\"" + longValue + "\"}";
        assertThat(matcher.matches("field~=a+", body)).isFalse();
    }

    @Test
    void cleanup_shouldNotThrow() {
        matcher.cleanup();
    }

    // ========== maxFieldValueLength 防呆測試 ==========

    @Test
    void jsonPrepared_fieldValueTooLarge_shouldReturnFalse() {
        // 使用小閾值的 matcher 來測試
        ConditionMatcher smallMatcher = new ConditionMatcher(100);
        String largeValue = "x".repeat(200);
        String json = "{\"userId\":\"U001\",\"image\":\"" + largeValue + "\"}";
        PreparedBody prepared = smallMatcher.prepareBody(json);

        // image 欄位超過 100 字元，應該跳過
        assertThat(smallMatcher.matchesPrepared("image=something", null, null, prepared, null, null)).isFalse();
        // userId 欄位正常大小，應該正常匹配
        assertThat(smallMatcher.matchesPrepared("userId=U001", null, null, prepared, null, null)).isTrue();
    }

    @Test
    void jsonPath_fieldValueTooLarge_shouldReturnFalse() {
        ConditionMatcher smallMatcher = new ConditionMatcher(100);
        String largeValue = "x".repeat(200);
        String json = "{\"data\":{\"image\":\"" + largeValue + "\",\"id\":\"A1\"}}";
        PreparedBody prepared = smallMatcher.prepareBody(json);

        // JsonPath 取到的值超過閾值
        assertThat(smallMatcher.matchesPrepared("$.data.image=something", null, null, prepared, null, null)).isFalse();
        // 正常欄位
        assertThat(smallMatcher.matchesPrepared("$.data.id=A1", null, null, prepared, null, null)).isTrue();
    }

    @Test
    void xmlPrepared_nodeValueTooLarge_shouldReturnFalse() {
        ConditionMatcher smallMatcher = new ConditionMatcher(100);
        String largeValue = "x".repeat(200);
        String xml = "<root><image>" + largeValue + "</image><id>A1</id></root>";
        PreparedBody prepared = smallMatcher.prepareBody(xml);

        // XML 節點值超過閾值
        assertThat(smallMatcher.matchesPrepared("image=something", null, null, prepared, null, null)).isFalse();
        // 正常節點
        assertThat(smallMatcher.matchesPrepared("id=A1", null, null, prepared, null, null)).isTrue();
    }

    @Test
    void xpathPrepared_nodeValueTooLarge_shouldReturnFalse() {
        ConditionMatcher smallMatcher = new ConditionMatcher(100);
        String largeValue = "x".repeat(200);
        String xml = "<root><image>" + largeValue + "</image><id>A1</id></root>";
        PreparedBody prepared = smallMatcher.prepareBody(xml);

        // XPath 取到的值超過閾值
        assertThat(smallMatcher.matchesPrepared("//image=something", null, null, prepared, null, null)).isFalse();
        // 正常 XPath
        assertThat(smallMatcher.matchesPrepared("//id=A1", null, null, prepared, null, null)).isTrue();
    }

    @Test
    void defaultMatcher_2MB_shouldAllowNormalFields() {
        // 預設 matcher (2MB 閾值) 對正常欄位不受影響
        String json = "{\"userId\":\"U001\",\"name\":\"test\"}";
        PreparedBody prepared = matcher.prepareBody(json);

        assertThat(matcher.matchesPrepared("userId=U001", null, null, prepared, null, null)).isTrue();
        assertThat(matcher.matchesPrepared("name=test", null, null, prepared, null, null)).isTrue();
    }
}
