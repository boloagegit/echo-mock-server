package com.echo.pipeline;

import com.echo.entity.HttpRule;
import com.echo.entity.Protocol;
import com.echo.service.ConditionMatcher;
import com.echo.service.MatchChainEntry;
import com.echo.service.MatchResult;
import com.echo.service.RequestLogService;
import com.echo.service.RequestLogUnavailableException;
import com.echo.service.RuleService;
import com.echo.service.ScenarioService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AbstractMockPipeline 單元測試
 * <p>
 * 使用具體測試子類別 (TestPipeline) 驗證 Template Method 主流程、
 * 共用方法 (resolveResponseBody, applyDelay, recordLog) 及錯誤處理。
 */
@ExtendWith(MockitoExtension.class)
class AbstractMockPipelineTest {

    @Mock
    private ConditionMatcher conditionMatcher;

    @Mock
    private RuleService ruleService;

    @Mock
    private RequestLogService requestLogService;

    @Mock
    private ScenarioService scenarioService;

    private TestPipeline pipeline;

    // ==================== Test Subclass ====================

    /**
     * 具體測試子類別，繼承 AbstractMockPipeline<HttpRule>，
     * 所有抽象方法以可控行為實作。
     */
    static class TestPipeline extends AbstractMockPipeline<HttpRule> {

        List<HttpRule> candidateRules = Collections.emptyList();
        MockResponse buildResponseResult;
        MockResponse forwardResult;
        boolean shouldForwardResult = false;
        MockResponse handleNoMatchResult;

        // 追蹤呼叫順序
        final List<String> callOrder = new ArrayList<>();

        TestPipeline(ConditionMatcher conditionMatcher,
                     RuleService ruleService,
                     RequestLogService requestLogService,
                     ScenarioService scenarioService) {
            super(conditionMatcher, ruleService, requestLogService, scenarioService);
        }

        @Override
        protected List<HttpRule> findCandidateRules(MockRequest request) {
            callOrder.add("findCandidateRules");
            return candidateRules;
        }

        @Override
        protected MockResponse buildResponse(HttpRule rule, MockRequest request, String responseBody) {
            callOrder.add("buildResponse");
            return buildResponseResult;
        }

        @Override
        protected MockResponse forward(MockRequest request) {
            callOrder.add("forward");
            return forwardResult;
        }

        @Override
        protected boolean shouldForward(MockRequest request) {
            callOrder.add("shouldForward");
            return shouldForwardResult;
        }

        @Override
        protected MockResponse handleNoMatch(MockRequest request) {
            callOrder.add("handleNoMatch");
            return handleNoMatchResult;
        }

        @Override
        protected boolean hasCondition(HttpRule rule) {
            return rule.getBodyCondition() != null && !rule.getBodyCondition().isBlank();
        }

        @Override
        protected ConditionSet extractConditions(HttpRule rule) {
            return ConditionSet.builder()
                    .bodyCondition(rule.getBodyCondition())
                    .queryCondition(rule.getQueryCondition())
                    .headerCondition(rule.getHeaderCondition())
                    .build();
        }

        @Override
        protected MatchChainEntry createMatchChainEntry(HttpRule rule, String reason) {
            return MatchChainEntry.fromHttp(rule, reason);
        }
    }

    // ==================== Setup ====================

    @BeforeEach
    void setUp() {
        pipeline = new TestPipeline(conditionMatcher, ruleService, requestLogService, scenarioService);
    }

    private MockRequest defaultRequest() {
        return MockRequest.builder()
                .protocol(Protocol.HTTP)
                .method("POST")
                .path("/api/test")
                .body("{\"key\":\"value\"}")
                .clientIp("127.0.0.1")
                .targetHost("default")
                .build();
    }

    private HttpRule enabledRuleWithCondition(String id, String bodyCondition, Long responseId) {
        return HttpRule.builder()
                .id(id)
                .enabled(true)
                .bodyCondition(bodyCondition)
                .responseId(responseId)
                .matchKey("/api/test")
                .method("POST")
                .httpStatus(200)
                .priority(0)
                .delayMs(100L)
                .build();
    }

    // ==================== 1. execute() step execution order ====================

    @Nested
    @DisplayName("execute() 步驟執行順序")
    class ExecuteStepOrder {

        @Test
        @DisplayName("匹配成功：findCandidateRules → matchRule → buildResponse → recordLog")
        void matchedFlow() {
            HttpRule rule = enabledRuleWithCondition("rule-1", "key=value", 1L);
            pipeline.candidateRules = List.of(rule);
            pipeline.buildResponseResult = MockResponse.builder()
                    .status(200).body("OK").matched(true).forwarded(false).build();

            ConditionMatcher.PreparedBody prepared = ConditionMatcher.PreparedBody.empty();
            when(conditionMatcher.prepareBody(anyString())).thenReturn(prepared);
            when(conditionMatcher.matchesPrepared(anyString(), isNull(), isNull(),
                    any(), isNull(), isNull())).thenReturn(true);
            when(ruleService.findResponseBodyById(1L)).thenReturn(Optional.of("response body"));

            PipelineResult result = pipeline.execute(defaultRequest());

            assertThat(result.isMatched()).isTrue();
            assertThat(result.getRuleId()).isEqualTo("rule-1");

            // 驗證呼叫順序
            assertThat(pipeline.callOrder).containsExactly(
                    "findCandidateRules", "buildResponse");

            // 驗證 recordLog 被呼叫
            verify(requestLogService).record(
                    eq("rule-1"), eq(Protocol.HTTP), eq("POST"), eq("/api/test"),
                    eq(true), anyInt(), eq("127.0.0.1"),
                    any(), any(), any(), any(), eq(200), anyInt(),
                    eq("{\"key\":\"value\"}"), eq("OK"),
                    anyList(), any(), any(), any(),
                    any(), any(), any(), any());
        }

        @Test
        @DisplayName("未匹配且需轉發：findCandidateRules → matchRule → shouldForward → forward → recordLog")
        void notMatchedForwardFlow() {
            pipeline.candidateRules = Collections.emptyList();
            pipeline.shouldForwardResult = true;
            pipeline.forwardResult = MockResponse.builder()
                    .status(200).body("forwarded").matched(false).forwarded(true).build();

            MockRequest request = MockRequest.builder()
                    .protocol(Protocol.HTTP)
                    .method("GET")
                    .path("/api/proxy")
                    .body("{}")
                    .clientIp("10.0.0.1")
                    .targetHost("remote-host")
                    .build();

            PipelineResult result = pipeline.execute(request);

            assertThat(result.isMatched()).isFalse();
            assertThat(result.getResponse().isForwarded()).isTrue();

            assertThat(pipeline.callOrder).containsExactly(
                    "findCandidateRules", "shouldForward", "forward");

            verify(requestLogService).record(
                    isNull(), eq(Protocol.HTTP), eq("GET"), eq("/api/proxy"),
                    eq(false), anyInt(), eq("10.0.0.1"),
                    any(), eq("remote-host"), eq(200), isNull(), eq(200), anyInt(),
                    eq("{}"), eq("forwarded"),
                    anyList(), any(), any(), any(),
                    any(), any(), any(), any());
        }

        @Test
        @DisplayName("未匹配且不轉發：findCandidateRules → matchRule → shouldForward → handleNoMatch → recordLog")
        void notMatchedNoForwardFlow() {
            pipeline.candidateRules = Collections.emptyList();
            pipeline.shouldForwardResult = false;
            pipeline.handleNoMatchResult = MockResponse.builder()
                    .status(404).body("Not Found").matched(false).forwarded(false).build();

            PipelineResult result = pipeline.execute(defaultRequest());

            assertThat(result.isMatched()).isFalse();
            assertThat(result.getResponse().getStatus()).isEqualTo(404);

            assertThat(pipeline.callOrder).containsExactly(
                    "findCandidateRules", "shouldForward", "handleNoMatch");

            verify(requestLogService).record(
                    isNull(), eq(Protocol.HTTP), eq("POST"), eq("/api/test"),
                    eq(false), anyInt(), eq("127.0.0.1"),
                    any(), isNull(), isNull(), isNull(), eq(404), anyInt(),
                    eq("{\"key\":\"value\"}"), eq("Not Found"),
                    anyList(), any(), any(), any(),
                    any(), any(), any(), any());
        }
    }

    // ==================== 2. resolveResponseBody() ====================

    @Nested
    @DisplayName("resolveResponseBody()")
    class ResolveResponseBody {

        @Test
        @DisplayName("正常查詢回傳 body")
        void normalQueryReturnsBody() {
            when(ruleService.findResponseBodyById(42L)).thenReturn(Optional.of("hello world"));

            String body = pipeline.resolveResponseBody(42L);

            assertThat(body).isEqualTo("hello world");
            verify(ruleService).findResponseBodyById(42L);
        }

        @Test
        @DisplayName("找不到 Response 回傳空字串")
        void missingResponseReturnsEmpty() {
            when(ruleService.findResponseBodyById(99L)).thenReturn(Optional.empty());

            String body = pipeline.resolveResponseBody(99L);

            assertThat(body).isEmpty();
        }

        @Test
        @DisplayName("responseId 為 null 回傳空字串")
        void nullResponseIdReturnsEmpty() {
            String body = pipeline.resolveResponseBody(null);

            assertThat(body).isEmpty();
            verify(ruleService, never()).findResponseBodyById(any());
        }
    }

    // ==================== 3. applyDelay() ====================

    @Nested
    @DisplayName("applyDelay()")
    class ApplyDelay {

        @Test
        @DisplayName("delayMs > 0 時執行延遲")
        void positiveDelayIsApplied() {
            long before = System.currentTimeMillis();
            pipeline.applyDelay(50);
            long elapsed = System.currentTimeMillis() - before;

            // 至少等了 30ms（允許一些計時誤差）
            assertThat(elapsed).isGreaterThanOrEqualTo(30);
        }

        @Test
        @DisplayName("delayMs = 0 時不延遲")
        void zeroDelayNoSleep() {
            long before = System.currentTimeMillis();
            pipeline.applyDelay(0);
            long elapsed = System.currentTimeMillis() - before;

            assertThat(elapsed).isLessThan(50);
        }

        @Test
        @DisplayName("delayMs < 0 時不延遲")
        void negativeDelayNoSleep() {
            long before = System.currentTimeMillis();
            pipeline.applyDelay(-100);
            long elapsed = System.currentTimeMillis() - before;

            assertThat(elapsed).isLessThan(50);
        }
    }

    // ==================== 4. recordLog() ====================

    @Nested
    @DisplayName("recordLog()")
    class RecordLog {

        @Test
        @DisplayName("呼叫 RequestLogService.record() 並傳入正確參數")
        void callsRequestLogServiceWithCorrectParams() {
            pipeline.recordLog(
                    "rule-abc", Protocol.HTTP, "POST", "/api/endpoint",
                    true, 150, "192.168.1.1",
                    "[{\"ruleId\":\"rule-abc\"}]", "target.host",
                    200, null, 200, 10,
                    "{\"req\":1}", "{\"res\":2}",
                    Collections.emptyList(), null, null, null,
                    null, null, null, null);

            verify(requestLogService).record(
                    eq("rule-abc"),
                    eq(Protocol.HTTP),
                    eq("POST"),
                    eq("/api/endpoint"),
                    eq(true),
                    eq(150),
                    eq("192.168.1.1"),
                    eq("[{\"ruleId\":\"rule-abc\"}]"),
                    eq("target.host"),
                    eq(200),
                    isNull(),
                    eq(200),
                    eq(10),
                    eq("{\"req\":1}"),
                    eq("{\"res\":2}"),
                    eq(Collections.emptyList()),
                    isNull(),
                    isNull(),
                    isNull(),
                    isNull(),
                    isNull(),
                    isNull(),
                    isNull());
        }

        @Test
        @DisplayName("未匹配時 ruleId 為 null")
        void noMatchRuleIdIsNull() {
            pipeline.recordLog(
                    null, Protocol.HTTP, "GET", "/api/miss",
                    false, 50, "10.0.0.1",
                    null, null,
                    null, null, 404, 5,
                    null, null,
                    Collections.emptyList(), null, null, null,
                    null, null, null, null);

            verify(requestLogService).record(
                    isNull(),
                    eq(Protocol.HTTP),
                    eq("GET"),
                    eq("/api/miss"),
                    eq(false),
                    eq(50),
                    eq("10.0.0.1"),
                    isNull(),
                    isNull(),
                    isNull(),
                    isNull(),
                    eq(404),
                    eq(5),
                    isNull(),
                    isNull(),
                    eq(Collections.emptyList()),
                    isNull(),
                    isNull(),
                    isNull(),
                    isNull(),
                    isNull(),
                    isNull(),
                    isNull());
        }
    }

    // ==================== 5. PipelineResult 正確性 ====================

    @Nested
    @DisplayName("PipelineResult 包含正確值")
    class PipelineResultValues {

        @Test
        @DisplayName("匹配成功時 PipelineResult 包含 ruleId、matched、delayMs 等")
        void matchedResultContainsCorrectValues() {
            HttpRule rule = enabledRuleWithCondition("rule-x", "field=val", 5L);
            rule.setDelayMs(200L);
            pipeline.candidateRules = List.of(rule);
            pipeline.buildResponseResult = MockResponse.builder()
                    .status(200).body("resp").matched(true).forwarded(false).build();

            ConditionMatcher.PreparedBody prepared = ConditionMatcher.PreparedBody.empty();
            when(conditionMatcher.prepareBody(anyString())).thenReturn(prepared);
            when(conditionMatcher.matchesPrepared(anyString(), isNull(), isNull(),
                    any(), isNull(), isNull())).thenReturn(true);
            when(ruleService.findResponseBodyById(5L)).thenReturn(Optional.of("resp body"));

            PipelineResult result = pipeline.execute(defaultRequest());

            assertThat(result.getRuleId()).isEqualTo("rule-x");
            assertThat(result.isMatched()).isTrue();
            assertThat(result.getMatchTimeMs()).isGreaterThanOrEqualTo(0);
            assertThat(result.getResponseTimeMs()).isGreaterThanOrEqualTo(0);
            assertThat(result.getMatchChainJson()).isNotNull();
            assertThat(result.getDelayMs()).isEqualTo(200L);
            assertThat(result.getResponse()).isNotNull();
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("未匹配時 PipelineResult ruleId 為 null、matched 為 false、delayMs 為 0")
        void notMatchedResultValues() {
            pipeline.candidateRules = Collections.emptyList();
            pipeline.shouldForwardResult = false;
            pipeline.handleNoMatchResult = MockResponse.builder()
                    .status(404).body("").matched(false).forwarded(false).build();

            PipelineResult result = pipeline.execute(defaultRequest());

            assertThat(result.getRuleId()).isNull();
            assertThat(result.isMatched()).isFalse();
            assertThat(result.getDelayMs()).isEqualTo(0);
            assertThat(result.getMatchTimeMs()).isGreaterThanOrEqualTo(0);
            assertThat(result.getResponseTimeMs()).isGreaterThanOrEqualTo(0);
            verify(conditionMatcher, never()).prepareBody(anyString());
        }
    }

    // ==================== 6. Error handling ====================

    @Nested
    @DisplayName("錯誤處理")
    class ErrorHandling {

        @Test
        @DisplayName("findCandidateRules 拋出例外時回傳 500 error response")
        void findCandidateRulesThrowsReturns500() {
            // 建立一個會在 findCandidateRules 拋出例外的 pipeline
            TestPipeline errorPipeline = new TestPipeline(conditionMatcher, ruleService, requestLogService, scenarioService) {
                @Override
                protected List<HttpRule> findCandidateRules(MockRequest request) {
                    callOrder.add("findCandidateRules");
                    throw new RuntimeException("DB connection failed");
                }
            };

            PipelineResult result = errorPipeline.execute(defaultRequest());

            assertThat(result.getResponse().getStatus()).isEqualTo(500);
            assertThat(result.getResponse().getBody()).contains("Pipeline error");
            assertThat(result.getResponse().getBody()).contains("DB connection failed");
            assertThat(result.isMatched()).isFalse();
            assertThat(result.getMatchTimeMs()).isEqualTo(0);
            assertThat(result.getDelayMs()).isEqualTo(0);
            assertThat(result.getResponseTimeMs()).isGreaterThanOrEqualTo(0);
        }

        @Test
        @DisplayName("durable request log 無法接受時回傳 503，不假裝成功")
        void durableLogUnavailableReturns503() {
            pipeline.candidateRules = Collections.emptyList();
            pipeline.shouldForwardResult = false;
            pipeline.handleNoMatchResult = MockResponse.builder()
                    .status(404).body("not found").matched(false).forwarded(false).build();
            doThrow(new RequestLogUnavailableException("spool full"))
                    .when(requestLogService).record(
                            ArgumentMatchers.<String>any(), ArgumentMatchers.<Protocol>any(),
                            ArgumentMatchers.<String>any(), ArgumentMatchers.<String>any(),
                            anyBoolean(), anyInt(), ArgumentMatchers.<String>any(),
                            ArgumentMatchers.<String>any(), ArgumentMatchers.<String>any(),
                            ArgumentMatchers.<Integer>any(), ArgumentMatchers.<String>any(),
                            ArgumentMatchers.<Integer>any(), ArgumentMatchers.<Integer>any(),
                            ArgumentMatchers.<String>any(), ArgumentMatchers.<String>any(),
                            ArgumentMatchers.<HttpRule>anyList(),
                            ArgumentMatchers.<ConditionMatcher.PreparedBody>any(),
                            ArgumentMatchers.<String>any(), ArgumentMatchers.<Map<String, String>>any(),
                            ArgumentMatchers.nullable(String.class), ArgumentMatchers.nullable(String.class),
                            ArgumentMatchers.nullable(String.class), ArgumentMatchers.nullable(String.class));

            PipelineResult result = pipeline.execute(defaultRequest());

            assertThat(result.getResponse().getStatus()).isEqualTo(503);
            assertThat(result.getResponse().getBody())
                    .isEqualTo("Request logging is temporarily unavailable");
            assertThat(result.isMatched()).isFalse();
        }
    }
}
