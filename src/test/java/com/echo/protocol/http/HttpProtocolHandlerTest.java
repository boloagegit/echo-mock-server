package com.echo.protocol.http;

import com.echo.dto.RuleDto;
import com.echo.entity.BaseRule;
import com.echo.entity.HttpRule;
import com.echo.entity.Protocol;
import com.echo.entity.Response;
import com.echo.repository.HttpRuleRepository;
import net.jqwik.api.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class HttpProtocolHandlerTest {

    @Mock
    private HttpRuleRepository repository;

    private HttpProtocolHandler handler;

    @BeforeEach
    void setUp() {
        handler = new HttpProtocolHandler(repository);
    }

    // --- toDto tests ---

    @Test
    void toDto_sseEnabledTrue() {
        HttpRule rule = buildRule(true);
        RuleDto dto = handler.toDto(rule, null, false);
        assertThat(dto.getSseEnabled()).isTrue();
    }

    @Test
    void toDto_sseEnabledFalse() {
        HttpRule rule = buildRule(false);
        RuleDto dto = handler.toDto(rule, null, false);
        assertThat(dto.getSseEnabled()).isFalse();
    }

    @Test
    void toDto_sseEnabledDefaultIsFalse() {
        HttpRule rule = HttpRule.builder().matchKey("/test").httpStatus(200).build();
        RuleDto dto = handler.toDto(rule, null, false);
        assertThat(dto.getSseEnabled()).isFalse();
    }

    // --- fromDto tests ---

    @Test
    void fromDto_sseEnabledTrue() {
        RuleDto dto = buildDto(true);
        HttpRule rule = (HttpRule) handler.fromDto(dto);
        assertThat(rule.getSseEnabled()).isTrue();
    }

    @Test
    void fromDto_sseEnabledNull_defaultsToFalse() {
        RuleDto dto = buildDto(null);
        HttpRule rule = (HttpRule) handler.fromDto(dto);
        assertThat(rule.getSseEnabled()).isFalse();
    }

    // --- round-trip test ---

    @Test
    void fromDto_toDto_roundTrip_sseEnabledTrue() {
        RuleDto original = buildDto(true);
        BaseRule rule = handler.fromDto(original);
        RuleDto result = handler.toDto(rule, null, false);
        assertThat(result.getSseEnabled()).isEqualTo(original.getSseEnabled());
    }

    @Test
    void fromDto_toDto_roundTrip_sseEnabledFalse() {
        RuleDto original = buildDto(false);
        BaseRule rule = handler.fromDto(original);
        RuleDto result = handler.toDto(rule, null, false);
        assertThat(result.getSseEnabled()).isEqualTo(original.getSseEnabled());
    }

    @Test
    void fromDto_toDto_roundTrip_sseEnabledNull_becomeFalse() {
        RuleDto original = buildDto(null);
        BaseRule rule = handler.fromDto(original);
        RuleDto result = handler.toDto(rule, null, false);
        assertThat(result.getSseEnabled()).isFalse();
    }

    // --- Property 1: DTO mapping round-trip consistency ---

    /**
     * Validates: Requirements 2.2, 2.3, 2.4
     *
     * For any RuleDto with an sseEnabled value, performing fromDto() then toDto()
     * should preserve the sseEnabled value. When the original value is null,
     * the round-trip result should be false.
     */
    @Property
    void dtoMappingRoundTripConsistency(@ForAll("sseEnabledValues") Boolean sseEnabled) {
        HttpProtocolHandler h = new HttpProtocolHandler(null);
        RuleDto original = buildDto(sseEnabled);
        BaseRule rule = h.fromDto(original);
        RuleDto result = h.toDto(rule, null, false);

        if (sseEnabled == null) {
            assertThat(result.getSseEnabled()).isFalse();
        } else {
            assertThat(result.getSseEnabled()).isEqualTo(sseEnabled);
        }
    }

    @Provide
    Arbitrary<Boolean> sseEnabledValues() {
        return Arbitraries.of(true, false, null);
    }

    // --- sseLoopEnabled toDto tests ---

    @Test
    void toDto_sseLoopEnabledTrue_mappedCorrectly() {
        HttpRule rule = HttpRule.builder()
                .matchKey("/test").httpStatus(200)
                .sseEnabled(true).sseLoopEnabled(true)
                .build();
        RuleDto dto = handler.toDto(rule, null, false);
        assertThat(dto.getSseLoopEnabled()).isTrue();
    }

    @Test
    void toDto_sseLoopEnabledFalse_mappedCorrectly() {
        HttpRule rule = HttpRule.builder()
                .matchKey("/test").httpStatus(200)
                .sseEnabled(true).sseLoopEnabled(false)
                .build();
        RuleDto dto = handler.toDto(rule, null, false);
        assertThat(dto.getSseLoopEnabled()).isFalse();
    }

    // --- responseContentType toDto tests ---

    @Test
    void toDto_httpSseEnabled_responseContentTypeIsSseEvents() {
        HttpRule rule = HttpRule.builder()
                .matchKey("/test").httpStatus(200)
                .sseEnabled(true)
                .build();
        RuleDto dto = handler.toDto(rule, null, false);
        assertThat(dto.getResponseContentType()).isEqualTo("SSE_EVENTS");
    }

    @Test
    void toDto_httpSseDisabled_responseContentTypeIsText() {
        HttpRule rule = HttpRule.builder()
                .matchKey("/test").httpStatus(200)
                .sseEnabled(false)
                .build();
        RuleDto dto = handler.toDto(rule, null, false);
        assertThat(dto.getResponseContentType()).isEqualTo("TEXT");
    }

    // --- sseLoopEnabled fromDto tests ---

    @Test
    void fromDto_sseLoopEnabledNull_defaultsToFalse() {
        RuleDto dto = RuleDto.builder()
                .protocol(Protocol.HTTP).matchKey("/test").status(200)
                .sseLoopEnabled(null)
                .build();
        HttpRule rule = (HttpRule) handler.fromDto(dto);
        assertThat(rule.getSseLoopEnabled()).isFalse();
    }

    @Test
    void fromDto_sseLoopEnabledTrue_mappedCorrectly() {
        RuleDto dto = RuleDto.builder()
                .protocol(Protocol.HTTP).matchKey("/test").status(200)
                .sseLoopEnabled(true)
                .build();
        HttpRule rule = (HttpRule) handler.fromDto(dto);
        assertThat(rule.getSseLoopEnabled()).isTrue();
    }

    // --- sseLoopEnabled round-trip ---

    @Test
    void fromDto_toDto_roundTrip_sseLoopEnabledTrue() {
        RuleDto original = RuleDto.builder()
                .protocol(Protocol.HTTP).matchKey("/test").status(200)
                .sseEnabled(true).sseLoopEnabled(true)
                .build();
        BaseRule rule = handler.fromDto(original);
        RuleDto result = handler.toDto(rule, null, false);
        assertThat(result.getSseLoopEnabled()).isTrue();
    }

    @Test
    void fromDto_toDto_roundTrip_sseLoopEnabledNull_becomesFalse() {
        RuleDto original = RuleDto.builder()
                .protocol(Protocol.HTTP).matchKey("/test").status(200)
                .sseLoopEnabled(null)
                .build();
        BaseRule rule = handler.fromDto(original);
        RuleDto result = handler.toDto(rule, null, false);
        assertThat(result.getSseLoopEnabled()).isFalse();
    }

    // --- helpers ---

    private HttpRule buildRule(Boolean sseEnabled) {
        return HttpRule.builder()
                .matchKey("/test")
                .httpStatus(200)
                .sseEnabled(sseEnabled)
                .build();
    }

    private RuleDto buildDto(Boolean sseEnabled) {
        return RuleDto.builder()
                .protocol(Protocol.HTTP)
                .matchKey("/test")
                .status(200)
                .sseEnabled(sseEnabled)
                .build();
    }
}
