package com.echo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SseEventsContentValidator.validate() 單元測試。
 * 使用真實 ObjectMapper（不 mock）。
 */
class SseEventsContentValidatorTest {

    private SseEventsContentValidator validator;

    @BeforeEach
    void setUp() {
        validator = new SseEventsContentValidator(new ObjectMapper());
    }

    // --- 合法輸入 ---

    @Test
    void validate_singleEvent_doesNotThrow() {
        String body = "[{\"data\":\"hello\"}]";
        assertThatCode(() -> validator.validate(body)).doesNotThrowAnyException();
    }

    @Test
    void validate_eventWithTypeNormal_doesNotThrow() {
        String body = "[{\"data\":\"hello\",\"type\":\"normal\"}]";
        assertThatCode(() -> validator.validate(body)).doesNotThrowAnyException();
    }

    @Test
    void validate_eventWithTypeError_doesNotThrow() {
        String body = "[{\"data\":\"{\\\"code\\\":500}\",\"type\":\"error\"}]";
        assertThatCode(() -> validator.validate(body)).doesNotThrowAnyException();
    }

    @Test
    void validate_eventWithTypeAbort_doesNotThrow() {
        String body = "[{\"data\":\"bye\",\"type\":\"abort\"}]";
        assertThatCode(() -> validator.validate(body)).doesNotThrowAnyException();
    }

    // --- 非法輸入 ---

    @Test
    void validate_null_throwsIllegalArgument() {
        assertThatThrownBy(() -> validator.validate(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validate_emptyString_throwsIllegalArgument() {
        assertThatThrownBy(() -> validator.validate(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validate_blankString_throwsIllegalArgument() {
        assertThatThrownBy(() -> validator.validate("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validate_nonJsonArray_throwsIllegalArgument() {
        assertThatThrownBy(() -> validator.validate("{\"data\":\"x\"}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validate_emptyArray_throwsIllegalArgument() {
        assertThatThrownBy(() -> validator.validate("[]"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validate_emptyData_throwsWithEventIndex() {
        String body = "[{\"data\":\"\"}]";
        assertThatThrownBy(() -> validator.validate(body))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1");
    }

    @Test
    void validate_nullData_throwsWithEventIndex() {
        String body = "[{\"event\":\"test\"}]";
        assertThatThrownBy(() -> validator.validate(body))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1");
    }

    @Test
    void validate_unknownType_throwsIllegalArgument() {
        String body = "[{\"data\":\"hello\",\"type\":\"unknown\"}]";
        assertThatThrownBy(() -> validator.validate(body))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validate_secondEventEmptyData_throwsWithIndex2() {
        String body = "[{\"data\":\"ok\"},{\"data\":\"\"}]";
        assertThatThrownBy(() -> validator.validate(body))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2");
    }
}
