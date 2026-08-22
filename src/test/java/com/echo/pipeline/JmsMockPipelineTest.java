package com.echo.pipeline;

import com.echo.config.JmsProperties;
import com.echo.entity.JmsRule;
import com.echo.entity.JmsForwardTargetMode;
import com.echo.entity.JmsRuleAction;
import com.echo.entity.Protocol;
import com.echo.jms.JmsTargetForwarder;
import com.echo.service.ConditionMatcher;
import com.echo.service.JmsRuleService;
import com.echo.service.RequestLogService;
import com.echo.service.RuleService;
import com.echo.service.ScenarioService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * JmsMockPipeline 單元測試
 * <p>
 * 驗證 JMS 協定 pipeline 的各項行為：匹配回應、JMS 轉發（成功與失敗）、
 * 無匹配處理、shouldForward 決策、日誌記錄。
 */
@ExtendWith(MockitoExtension.class)
class JmsMockPipelineTest {

    @Mock
    private ConditionMatcher conditionMatcher;

    @Mock
    private RuleService ruleService;

    @Mock
    private RequestLogService requestLogService;

    @Mock
    private JmsRuleService jmsRuleService;

    @Mock
    private JmsTargetForwarder targetForwarder;

    @Mock
    private JmsProperties jmsProperties;

    @Mock
    private ScenarioService scenarioService;

    private JmsMockPipeline pipeline;

    @BeforeEach
    void setUp() {
        pipeline = new JmsMockPipeline(
                conditionMatcher, ruleService, requestLogService,
                jmsRuleService, targetForwarder, jmsProperties, scenarioService);
    }

    // ==================== Helpers ====================

    private MockRequest defaultRequest() {
        return MockRequest.builder()
                .protocol(Protocol.JMS)
                .path("ORDER.REQUEST")
                .body("<Order><ServiceName>CreateOrder</ServiceName></Order>")
                .clientIp("JMS")
                .build();
    }

    private JmsRule buildRule(String id, String queueName, String bodyCondition,
                              Long responseId) {
        return JmsRule.builder()
                .id(id)
                .enabled(true)
                .queueName(queueName)
                .bodyCondition(bodyCondition)
                .responseId(responseId)
                .priority(0)
                .delayMs(0L)
                .build();
    }

    // ==================== 1. Normal match response ====================

    @Nested
    @DisplayName("正常匹配回應")
    class NormalMatchResponse {

        @Test
        @DisplayName("無 body 條件時不解析 XML DOM")
        void unconditionalRuleSkipsBodyParsing() {
            JmsRule rule = buildRule("jms-fallback", "ORDER.REQUEST", null, 99L);

            when(jmsProperties.getQueue()).thenReturn("ORDER.REQUEST");
            when(jmsRuleService.findPreparedJmsRules("ORDER.REQUEST"))
                    .thenReturn(List.of(rule));
            when(ruleService.findResponseBodyById(99L)).thenReturn(Optional.of("<Response>OK</Response>"));

            PipelineResult result = pipeline.execute(defaultRequest());

            assertThat(result.isMatched()).isTrue();
            verify(conditionMatcher, never()).prepareBody(anyString());
        }

        @Test
        @DisplayName("匹配成功時回傳 status=200、正確 body、matched=true")
        void matchedReturnsCorrectResponse() {
            JmsRule rule = buildRule("jms-rule-1", "ORDER.REQUEST",
                    "ServiceName=CreateOrder", 100L);
            String responseBody = "<Response>OK</Response>";

            when(jmsProperties.getQueue()).thenReturn("ORDER.REQUEST");
            when(jmsRuleService.findPreparedJmsRules("ORDER.REQUEST"))
                    .thenReturn(List.of(rule));

            ConditionMatcher.PreparedBody prepared = ConditionMatcher.PreparedBody.empty();
            when(conditionMatcher.prepareBodyForConditions(anyString(), anyList()))
                    .thenReturn(prepared);
            when(conditionMatcher.matchesPrepared(
                    eq("ServiceName=CreateOrder"), isNull(), isNull(),
                    any(), isNull(), isNull()))
                    .thenReturn(true);
            when(ruleService.findResponseBodyById(100L))
                    .thenReturn(Optional.of(responseBody));

            PipelineResult result = pipeline.execute(defaultRequest());

            assertThat(result.isMatched()).isTrue();
            assertThat(result.getRuleId()).isEqualTo("jms-rule-1");

            MockResponse response = result.getResponse();
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getBody()).isEqualTo(responseBody);
            assertThat(response.isMatched()).isTrue();
            assertThat(response.isForwarded()).isFalse();
            assertThat(response.getProxyError()).isNull();
        }
    }

    // ==================== 2. JMS forwarding success ====================

    @Nested
    @DisplayName("JMS 轉發成功")
    class JmsForwardingSuccess {

        @Test
        @DisplayName("無匹配且 target 啟用時轉發成功，forwarded=true")
        void forwardSuccess() {
            when(targetForwarder.hasActiveTarget()).thenReturn(true);
            when(jmsProperties.getQueue()).thenReturn("ORDER.REQUEST");

            when(jmsRuleService.findPreparedJmsRules("ORDER.REQUEST"))
                    .thenReturn(Collections.emptyList());

            String forwardedBody = "<Response>Forwarded OK</Response>";
            when(targetForwarder.forwardWithMetadata(anyString(), isNull()))
                    .thenReturn(new JmsTargetForwarder.ForwardResult(
                            forwardedBody, "Primary | tcp://broker:61616 | TARGET.REQUEST"));

            PipelineResult result = pipeline.execute(defaultRequest());

            assertThat(result.isMatched()).isFalse();

            MockResponse response = result.getResponse();
            assertThat(response.isForwarded()).isTrue();
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getBody()).isEqualTo(forwardedBody);
            assertThat(response.getForwardTarget())
                    .isEqualTo("Primary | tcp://broker:61616 | TARGET.REQUEST");
            assertThat(response.getProxyError()).isNull();
        }


        @Test
        @DisplayName("規則命中時使用該規則指定的 JMS 連線")
        void matchedRuleUsesSelectedConnection() {
            JmsRule rule = buildRule("jms-forward-rule", "ORDER.REQUEST", null, null);
            rule.setAction(JmsRuleAction.FORWARD);
            rule.setForwardTargetMode(JmsForwardTargetMode.CONNECTION);
            rule.setJmsTargetConnectionId("7");
            when(jmsProperties.getQueue()).thenReturn("ORDER.REQUEST");
            when(jmsRuleService.findPreparedJmsRules("ORDER.REQUEST"))
                    .thenReturn(List.of(rule));
            when(targetForwarder.forwardWithMetadata(anyString(), isNull(), eq("7"), eq(false)))
                    .thenReturn(new JmsTargetForwarder.ForwardResult(
                            "<Response>Selected target</Response>",
                            "Selected | tcp://broker:61616 | SELECTED.REQUEST"));

            PipelineResult result = pipeline.execute(defaultRequest());

            assertThat(result.isMatched()).isTrue();
            assertThat(result.getResponse().isForwarded()).isTrue();
            assertThat(result.getResponse().getBody()).isEqualTo("<Response>Selected target</Response>");
            verify(targetForwarder).forwardWithMetadata(anyString(), isNull(), eq("7"), eq(false));
            verify(ruleService, never()).findResponseBodyById(anyLong());
        }
    }

    // ==================== 3. JMS forwarding failure (error in response) ====================

    @Nested
    @DisplayName("JMS 轉發失敗（回應含 <error>）")
    class JmsForwardingFailure {

        @Test
        @DisplayName("轉發回應包含 <error> 時設定 proxyError")
        void forwardWithErrorResponse() {
            when(targetForwarder.hasActiveTarget()).thenReturn(true);
            when(jmsProperties.getQueue()).thenReturn("ORDER.REQUEST");

            when(jmsRuleService.findPreparedJmsRules("ORDER.REQUEST"))
                    .thenReturn(Collections.emptyList());

            String errorBody = "<error>JMS forward error: Connection refused</error>";
            when(targetForwarder.forwardWithMetadata(anyString(), isNull()))
                    .thenReturn(new JmsTargetForwarder.ForwardResult(
                            errorBody, "Primary | tcp://broker:61616 | TARGET.REQUEST"));

            PipelineResult result = pipeline.execute(defaultRequest());

            MockResponse response = result.getResponse();
            assertThat(response.isForwarded()).isTrue();
            assertThat(response.getBody()).isEqualTo(errorBody);
            assertThat(response.getProxyError()).isEqualTo(errorBody);
        }
    }

    // ==================== 4. No match and no forward target → error reply ====================

    @Nested
    @DisplayName("無匹配且不轉發 → error reply")
    class NoMatchNoForward {

        @Test
        @DisplayName("空候選清單且 shouldForward=false 時回傳 error XML")
        void noMatchReturnsErrorReply() {
            when(jmsProperties.getQueue()).thenReturn("ORDER.REQUEST");

            when(jmsRuleService.findPreparedJmsRules("ORDER.REQUEST"))
                    .thenReturn(Collections.emptyList());

            PipelineResult result = pipeline.execute(defaultRequest());

            assertThat(result.isMatched()).isFalse();

            MockResponse response = result.getResponse();
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getBody()).contains("<error>");
            assertThat(response.getBody()).contains("ORDER.REQUEST");
            assertThat(response.isMatched()).isFalse();
            assertThat(response.isForwarded()).isFalse();
        }
    }

    // ==================== 5. shouldForward() decision logic ====================

    @Nested
    @DisplayName("shouldForward() 決策邏輯")
    class ShouldForwardDecision {

        @Test
        @DisplayName("存在有效預設轉發連線 → true")
        void enabledWithServerUrlReturnsTrue() {
            when(targetForwarder.hasActiveTarget()).thenReturn(true);

            MockRequest request = MockRequest.builder()
                    .protocol(Protocol.JMS)
                    .path("QUEUE")
                    .build();

            assertThat(pipeline.shouldForward(request)).isTrue();
        }

        @Test
        @DisplayName("沒有有效預設轉發連線 → false")
        void disabledReturnsFalse() {
            when(targetForwarder.hasActiveTarget()).thenReturn(false);

            MockRequest request = MockRequest.builder()
                    .protocol(Protocol.JMS)
                    .path("QUEUE")
                    .build();

            assertThat(pipeline.shouldForward(request)).isFalse();
        }

        @Test
        @DisplayName("預設解析器回傳 false 時不轉發")
        void enabledWithNullServerUrlReturnsFalse() {
            when(targetForwarder.hasActiveTarget()).thenReturn(false);

            MockRequest request = MockRequest.builder()
                    .protocol(Protocol.JMS)
                    .path("QUEUE")
                    .build();

            assertThat(pipeline.shouldForward(request)).isFalse();
        }

        @Test
        @DisplayName("無可用連線時保持 false")
        void enabledWithEmptyServerUrlReturnsFalse() {
            when(targetForwarder.hasActiveTarget()).thenReturn(false);

            MockRequest request = MockRequest.builder()
                    .protocol(Protocol.JMS)
                    .path("QUEUE")
                    .build();

            assertThat(pipeline.shouldForward(request)).isFalse();
        }
    }

    // ==================== 6. RequestLogService.record() called with correct params ====================

    @Nested
    @DisplayName("RequestLogService.record() 參數正確性")
    class RecordLogVerification {

        @Test
        @DisplayName("匹配成功時 record() 傳入正確的 ruleId、protocol、matched 等參數")
        void recordCalledOnMatch() {
            JmsRule rule = buildRule("jms-log-rule", "ORDER.REQUEST",
                    "ServiceName=CreateOrder", 200L);

            when(jmsProperties.getQueue()).thenReturn("ORDER.REQUEST");
            when(jmsRuleService.findPreparedJmsRules("ORDER.REQUEST"))
                    .thenReturn(List.of(rule));

            ConditionMatcher.PreparedBody prepared = ConditionMatcher.PreparedBody.empty();
            when(conditionMatcher.prepareBodyForConditions(anyString(), anyList()))
                    .thenReturn(prepared);
            when(conditionMatcher.matchesPrepared(
                    eq("ServiceName=CreateOrder"), isNull(), isNull(),
                    any(), isNull(), isNull()))
                    .thenReturn(true);

            String responseBody = "<Response>Logged</Response>";
            when(ruleService.findResponseBodyById(200L))
                    .thenReturn(Optional.of(responseBody));

            pipeline.execute(defaultRequest());

            verify(requestLogService).record(
                    eq("jms-log-rule"),       // ruleId
                    eq(Protocol.JMS),         // protocol
                    isNull(),                 // method (JMS has no method)
                    eq("ORDER.REQUEST"),      // endpoint
                    eq(true),                 // matched
                    anyInt(),                 // responseTimeMs
                    eq("JMS"),                // clientIp
                    any(),                    // matchChainJson
                    isNull(),                 // targetHost (matched, but JMS request has no targetHost)
                    eq(false),                // forwarded
                    isNull(),                 // forwardTarget
                    any(),                    // proxyStatus
                    isNull(),                 // proxyError
                    eq(200),                  // responseStatus
                    anyInt(),                 // matchTimeMs
                    eq("<Order><ServiceName>CreateOrder</ServiceName></Order>"), // requestBody
                    eq(responseBody),         // responseBody
                    anyList(),                // candidates
                    any(),                    // preparedBody
                    any(),                    // queryString
                    any(),                    // headers
                    any(),                    // faultType
                    any(),                    // scenarioName
                    any(),                    // scenarioFromState
                    any()                     // scenarioToState
            );
        }

        @Test
        @DisplayName("無匹配且不轉發時 record() 傳入 ruleId=null、matched=false")
        void recordCalledOnNoMatch() {
            when(jmsProperties.getQueue()).thenReturn("ORDER.REQUEST");

            when(jmsRuleService.findPreparedJmsRules("ORDER.REQUEST"))
                    .thenReturn(Collections.emptyList());

            pipeline.execute(defaultRequest());

            verify(requestLogService).record(
                    isNull(),                 // ruleId = null (no match)
                    eq(Protocol.JMS),         // protocol
                    isNull(),                 // method
                    eq("ORDER.REQUEST"),      // endpoint
                    eq(false),                // matched = false
                    anyInt(),                 // responseTimeMs
                    eq("JMS"),                // clientIp
                    any(),                    // matchChainJson
                    isNull(),                 // targetHost = null (handleNoMatch path)
                    eq(false),                // forwarded
                    isNull(),                 // forwardTarget
                    isNull(),                 // proxyStatus
                    isNull(),                 // proxyError
                    eq(200),                  // responseStatus
                    anyInt(),                 // matchTimeMs
                    eq("<Order><ServiceName>CreateOrder</ServiceName></Order>"), // requestBody
                    any(),                    // responseBody (error message)
                    anyList(),                // candidates
                    any(),                    // preparedBody
                    any(),                    // queryString
                    any(),                    // headers
                    any(),                    // faultType
                    any(),                    // scenarioName
                    any(),                    // scenarioFromState
                    any()                     // scenarioToState
            );
        }
    }
}
