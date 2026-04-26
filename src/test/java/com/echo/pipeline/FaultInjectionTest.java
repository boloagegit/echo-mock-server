package com.echo.pipeline;

import com.echo.dto.RuleDto;
import com.echo.entity.BaseRule;
import com.echo.entity.FaultType;
import com.echo.entity.HttpRule;
import com.echo.entity.Protocol;
import com.echo.entity.Response;
import com.echo.protocol.http.HttpProtocolHandler;
import com.echo.repository.HttpRuleRepository;
import com.echo.service.ConditionMatcher;
import com.echo.service.MatchChainEntry;
import com.echo.service.RequestLogService;
import com.echo.service.RuleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 故障注入（Fault Injection）完整測試
 * <p>
 * 涵蓋 Pipeline 層、ProtocolHandler 層、Entity 預設值三個面向，
 * 確保 faultType 在整個生命週期中行為正確。
 */
@ExtendWith(MockitoExtension.class)
class FaultInjectionTest {

    // ==================== Pipeline Layer ====================

    @Nested
    @DisplayName("Pipeline 層故障注入")
    class PipelineLayer {

        @Mock
        private ConditionMatcher conditionMatcher;

        @Mock
        private RuleService ruleService;

        @Mock
        private RequestLogService requestLogService;

        private TestPipeline pipeline;

        /**
         * 測試用 Pipeline 子類別，繼承 AbstractMockPipeline<HttpRule>，
         * 所有抽象方法以可控行為實作。
         */
        static class TestPipeline extends AbstractMockPipeline<HttpRule> {

            List<HttpRule> candidateRules = Collections.emptyList();

            TestPipeline(ConditionMatcher conditionMatcher,
                         RuleService ruleService,
                         RequestLogService requestLogService) {
                super(conditionMatcher, ruleService, requestLogService, null);
            }

            @Override
            protected List<HttpRule> findCandidateRules(MockRequest request) {
                return candidateRules;
            }

            @Override
            protected MockResponse buildResponse(HttpRule rule, MockRequest request, String responseBody) {
                return MockResponse.builder()
                        .status(rule.getHttpStatus())
                        .body(responseBody)
                        .matched(true)
                        .forwarded(false)
                        .build();
            }

            @Override
            protected MockResponse forward(MockRequest request) {
                return MockResponse.builder()
                        .status(200)
                        .body("forwarded")
                        .matched(false)
                        .forwarded(true)
                        .build();
            }

            @Override
            protected boolean shouldForward(MockRequest request) {
                return false;
            }

            @Override
            protected MockResponse handleNoMatch(MockRequest request) {
                return MockResponse.builder()
                        .status(404)
                        .body("Not Found")
                        .matched(false)
                        .forwarded(false)
                        .build();
            }

            @Override
            protected boolean hasCondition(HttpRule rule) {
                return false;
            }

            @Override
            protected ConditionSet extractConditions(HttpRule rule) {
                return ConditionSet.builder().build();
            }

            @Override
            protected MatchChainEntry createMatchChainEntry(HttpRule rule, String reason) {
                return MatchChainEntry.fromHttp(rule, reason);
            }
        }

        @BeforeEach
        void setUp() {
            pipeline = new TestPipeline(conditionMatcher, ruleService, requestLogService);
            lenient().when(conditionMatcher.prepareBody(null))
                    .thenReturn(ConditionMatcher.PreparedBody.empty());
            lenient().when(conditionMatcher.prepareBody(""))
                    .thenReturn(ConditionMatcher.PreparedBody.empty());
        }

        private MockRequest defaultRequest() {
            return MockRequest.builder()
                    .protocol(Protocol.HTTP)
                    .method("GET")
                    .path("/api/test")
                    .clientIp("127.0.0.1")
                    .build();
        }

        private HttpRule ruleWithFault(FaultType faultType, int httpStatus, Long responseId) {
            return HttpRule.builder()
                    .id("rule-1")
                    .matchKey("/api/test")
                    .method("GET")
                    .httpStatus(httpStatus)
                    .priority(0)
                    .enabled(true)
                    .isProtected(false)
                    .delayMs(0L)
                    .responseId(responseId)
                    .faultType(faultType)
                    .build();
        }

        // ---- Test 1 ----
        @Test
        @DisplayName("faultType=NONE → 正常回應，faultType 為 null")
        void faultTypeNone_normalResponse() {
            HttpRule rule = ruleWithFault(FaultType.NONE, 200, 1L);
            pipeline.candidateRules = List.of(rule);
            when(ruleService.findResponseBodyById(1L)).thenReturn(Optional.of("hello"));

            PipelineResult result = pipeline.execute(defaultRequest());

            assertThat(result.getFaultType()).isNull();
            assertThat(result.getResponse().getBody()).isEqualTo("hello");
            assertThat(result.isMatched()).isTrue();
        }

        // ---- Test 2 ----
        @Test
        @DisplayName("faultType=EMPTY_RESPONSE → body 清空，status 保留")
        void faultTypeEmptyResponse_bodyCleared() {
            HttpRule rule = ruleWithFault(FaultType.EMPTY_RESPONSE, 200, 1L);
            pipeline.candidateRules = List.of(rule);
            when(ruleService.findResponseBodyById(1L)).thenReturn(Optional.of("should-be-cleared"));

            PipelineResult result = pipeline.execute(defaultRequest());

            assertThat(result.getFaultType()).isEqualTo("EMPTY_RESPONSE");
            assertThat(result.getResponse().getBody()).isEmpty();
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(result.isMatched()).isTrue();
        }

        // ---- Test 3 ----
        @Test
        @DisplayName("faultType=CONNECTION_RESET → faultType 傳播，body 不變")
        void faultTypeConnectionReset_propagated() {
            HttpRule rule = ruleWithFault(FaultType.CONNECTION_RESET, 200, 1L);
            pipeline.candidateRules = List.of(rule);
            when(ruleService.findResponseBodyById(1L)).thenReturn(Optional.of("original-body"));

            PipelineResult result = pipeline.execute(defaultRequest());

            assertThat(result.getFaultType()).isEqualTo("CONNECTION_RESET");
            assertThat(result.getResponse().getBody()).isEqualTo("original-body");
            assertThat(result.isMatched()).isTrue();
        }

        // ---- Test 4 ----
        @Test
        @DisplayName("faultType=null（舊規則向後相容）→ 視為 NONE")
        void faultTypeNull_backwardCompat() {
            HttpRule rule = ruleWithFault(null, 200, 1L);
            pipeline.candidateRules = List.of(rule);
            when(ruleService.findResponseBodyById(1L)).thenReturn(Optional.of("body"));

            PipelineResult result = pipeline.execute(defaultRequest());

            assertThat(result.getFaultType()).isNull();
            assertThat(result.getResponse().getBody()).isEqualTo("body");
        }

        // ---- Test 5 ----
        @Test
        @DisplayName("無匹配 → faultType 為 null")
        void noMatch_faultTypeNull() {
            pipeline.candidateRules = Collections.emptyList();

            PipelineResult result = pipeline.execute(defaultRequest());

            assertThat(result.getFaultType()).isNull();
            assertThat(result.isMatched()).isFalse();
            assertThat(result.getResponse().getStatus()).isEqualTo(404);
        }

        // ---- Test 6 ----
        @Test
        @DisplayName("EMPTY_RESPONSE 保留 httpStatus（如 503）")
        void emptyResponse_preservesHttpStatus() {
            HttpRule rule = ruleWithFault(FaultType.EMPTY_RESPONSE, 503, 1L);
            pipeline.candidateRules = List.of(rule);
            when(ruleService.findResponseBodyById(1L)).thenReturn(Optional.of("will-be-cleared"));

            PipelineResult result = pipeline.execute(defaultRequest());

            assertThat(result.getFaultType()).isEqualTo("EMPTY_RESPONSE");
            assertThat(result.getResponse().getBody()).isEmpty();
            assertThat(result.getResponse().getStatus()).isEqualTo(503);
        }
    }

    // ==================== Protocol Handler Layer ====================

    @Nested
    @DisplayName("HttpProtocolHandler 故障注入轉換")
    class ProtocolHandlerLayer {

        @Mock
        private HttpRuleRepository repository;

        private HttpProtocolHandler handler;

        @BeforeEach
        void setUp() {
            handler = new HttpProtocolHandler(repository);
        }

        // ---- Test 7 ----
        @Test
        @DisplayName("toDto: faultType=NONE → 字串 \"NONE\"")
        void toDto_faultTypeNone_returnsNoneString() {
            HttpRule rule = HttpRule.builder()
                    .id("r1")
                    .matchKey("/api/test")
                    .httpStatus(200)
                    .priority(0)
                    .enabled(true)
                    .isProtected(false)
                    .delayMs(0L)
                    .faultType(FaultType.NONE)
                    .sseEnabled(false)
                    .sseLoopEnabled(false)
                    .build();

            RuleDto dto = handler.toDto(rule, null, false);

            assertThat(dto.getFaultType()).isEqualTo("NONE");
        }

        // ---- Test 8 ----
        @Test
        @DisplayName("toDto: faultType=null → 字串 \"NONE\"")
        void toDto_faultTypeNull_returnsNoneString() {
            HttpRule rule = HttpRule.builder()
                    .id("r1")
                    .matchKey("/api/test")
                    .httpStatus(200)
                    .priority(0)
                    .enabled(true)
                    .isProtected(false)
                    .delayMs(0L)
                    .sseEnabled(false)
                    .sseLoopEnabled(false)
                    .build();
            // 強制設為 null 繞過 @Builder.Default
            rule.setFaultType(null);

            RuleDto dto = handler.toDto(rule, null, false);

            assertThat(dto.getFaultType()).isEqualTo("NONE");
        }

        // ---- Test 9 ----
        @Test
        @DisplayName("fromDto: faultType=null → FaultType.NONE")
        void fromDto_faultTypeNull_returnsNone() {
            RuleDto dto = RuleDto.builder()
                    .matchKey("/api/test")
                    .method("GET")
                    .faultType(null)
                    .build();

            BaseRule rule = handler.fromDto(dto);

            assertThat(rule.getFaultType()).isEqualTo(FaultType.NONE);
        }

        // ---- Test 10 ----
        @Test
        @DisplayName("fromDto: faultType=\"INVALID\" → FaultType.NONE")
        void fromDto_faultTypeInvalid_returnsNone() {
            RuleDto dto = RuleDto.builder()
                    .matchKey("/api/test")
                    .method("GET")
                    .faultType("INVALID")
                    .build();

            BaseRule rule = handler.fromDto(dto);

            assertThat(rule.getFaultType()).isEqualTo(FaultType.NONE);
        }

        // ---- Test 11 ----
        @Test
        @DisplayName("fromDto: faultType=\"CONNECTION_RESET\" → FaultType.CONNECTION_RESET")
        void fromDto_connectionReset() {
            RuleDto dto = RuleDto.builder()
                    .matchKey("/api/test")
                    .method("GET")
                    .faultType("CONNECTION_RESET")
                    .build();

            BaseRule rule = handler.fromDto(dto);

            assertThat(rule.getFaultType()).isEqualTo(FaultType.CONNECTION_RESET);
        }

        // ---- Test 12 ----
        @Test
        @DisplayName("fromDto: faultType=\"EMPTY_RESPONSE\" → FaultType.EMPTY_RESPONSE")
        void fromDto_emptyResponse() {
            RuleDto dto = RuleDto.builder()
                    .matchKey("/api/test")
                    .method("GET")
                    .faultType("EMPTY_RESPONSE")
                    .build();

            BaseRule rule = handler.fromDto(dto);

            assertThat(rule.getFaultType()).isEqualTo(FaultType.EMPTY_RESPONSE);
        }
    }

    // ==================== Entity Default Layer ====================

    @Nested
    @DisplayName("Entity 預設值")
    class EntityDefaults {

        // ---- Test 13 ----
        @Test
        @DisplayName("HttpRule @Builder.Default → FaultType.NONE")
        void builderDefault_faultTypeNone() {
            HttpRule rule = HttpRule.builder()
                    .matchKey("/api/test")
                    .httpStatus(200)
                    .build();

            assertThat(rule.getFaultType()).isEqualTo(FaultType.NONE);
        }

        // ---- Test 14 ----
        @Test
        @DisplayName("BaseRule @PrePersist null → FaultType.NONE")
        void prePersist_nullFaultType_setsNone() throws Exception {
            HttpRule rule = HttpRule.builder()
                    .matchKey("/api/test")
                    .httpStatus(200)
                    .build();
            // 強制設為 null 模擬舊資料
            rule.setFaultType(null);
            assertThat(rule.getFaultType()).isNull();

            // 透過反射觸發 @PrePersist（protected method 在不同 package 無法直接呼叫）
            java.lang.reflect.Method onCreate = HttpRule.class.getDeclaredMethod("onCreate");
            onCreate.setAccessible(true);
            onCreate.invoke(rule);

            assertThat(rule.getFaultType()).isEqualTo(FaultType.NONE);
        }
    }
}
