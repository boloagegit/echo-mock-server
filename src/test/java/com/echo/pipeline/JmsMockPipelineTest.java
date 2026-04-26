package com.echo.pipeline;

import com.echo.config.JmsProperties;
import com.echo.entity.JmsRule;
import com.echo.entity.Protocol;
import com.echo.jms.JmsTargetForwarder;
import com.echo.service.ConditionMatcher;
import com.echo.service.JmsRuleService;
import com.echo.service.RequestLogService;
import com.echo.service.RuleService;
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

    private JmsMockPipeline pipeline;

    @BeforeEach
    void setUp() {
        pipeline = new JmsMockPipeline(
                conditionMatcher, ruleService, requestLogService,
                jmsRuleService, targetForwarder, jmsProperties, null);
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

    private JmsProperties.Target buildTarget(boolean enabled, String serverUrl) {
        JmsProperties.Target target = new JmsProperties.Target();
        target.setEnabled(enabled);
        target.setServerUrl(serverUrl);
        target.setQueue("TARGET.REQUEST");
        return target;
    }

    // ==================== 1. Normal match response ====================

    @Nested
    @DisplayName("正常匹配回應")
    class NormalMatchResponse {

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
            when(conditionMatcher.prepareBody(anyString())).thenReturn(prepared);
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
            JmsProperties.Target target = buildTarget(true, "tcp://target-host:61616");
            when(jmsProperties.getTarget()).thenReturn(target);
            when(jmsProperties.getQueue()).thenReturn("ORDER.REQUEST");

            when(jmsRuleService.findPreparedJmsRules("ORDER.REQUEST"))
                    .thenReturn(Collections.emptyList());

            ConditionMatcher.PreparedBody prepared = ConditionMatcher.PreparedBody.empty();
            when(conditionMatcher.prepareBody(anyString())).thenReturn(prepared);

            String forwardedBody = "<Response>Forwarded OK</Response>";
            when(targetForwarder.forward(anyString(), isNull()))
                    .thenReturn(forwardedBody);

            PipelineResult result = pipeline.execute(defaultRequest());

            assertThat(result.isMatched()).isFalse();

            MockResponse response = result.getResponse();
            assertThat(response.isForwarded()).isTrue();
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getBody()).isEqualTo(forwardedBody);
            assertThat(response.getProxyError()).isNull();
        }
    }

    // ==================== 3. JMS forwarding failure (error in response) ====================

    @Nested
    @DisplayName("JMS 轉發失敗（回應含 <error>）")
    class JmsForwardingFailure {

        @Test
        @DisplayName("轉發回應包含 <error> 時設定 proxyError")
        void forwardWithErrorResponse() {
            JmsProperties.Target target = buildTarget(true, "tcp://target-host:61616");
            when(jmsProperties.getTarget()).thenReturn(target);
            when(jmsProperties.getQueue()).thenReturn("ORDER.REQUEST");

            when(jmsRuleService.findPreparedJmsRules("ORDER.REQUEST"))
                    .thenReturn(Collections.emptyList());

            ConditionMatcher.PreparedBody prepared = ConditionMatcher.PreparedBody.empty();
            when(conditionMatcher.prepareBody(anyString())).thenReturn(prepared);

            String errorBody = "<error>JMS forward error: Connection refused</error>";
            when(targetForwarder.forward(anyString(), isNull()))
                    .thenReturn(errorBody);

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
            JmsProperties.Target target = buildTarget(false, null);
            when(jmsProperties.getTarget()).thenReturn(target);
            when(jmsProperties.getQueue()).thenReturn("ORDER.REQUEST");

            when(jmsRuleService.findPreparedJmsRules("ORDER.REQUEST"))
                    .thenReturn(Collections.emptyList());

            ConditionMatcher.PreparedBody prepared = ConditionMatcher.PreparedBody.empty();
            when(conditionMatcher.prepareBody(anyString())).thenReturn(prepared);

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
        @DisplayName("target.enabled=true, serverUrl=\"tcp://host\" → true")
        void enabledWithServerUrlReturnsTrue() {
            JmsProperties.Target target = buildTarget(true, "tcp://host:61616");
            when(jmsProperties.getTarget()).thenReturn(target);

            MockRequest request = MockRequest.builder()
                    .protocol(Protocol.JMS)
                    .path("QUEUE")
                    .build();

            assertThat(pipeline.shouldForward(request)).isTrue();
        }

        @Test
        @DisplayName("target.enabled=false → false")
        void disabledReturnsFalse() {
            JmsProperties.Target target = buildTarget(false, "tcp://host:61616");
            when(jmsProperties.getTarget()).thenReturn(target);

            MockRequest request = MockRequest.builder()
                    .protocol(Protocol.JMS)
                    .path("QUEUE")
                    .build();

            assertThat(pipeline.shouldForward(request)).isFalse();
        }

        @Test
        @DisplayName("target.enabled=true, serverUrl=null → false")
        void enabledWithNullServerUrlReturnsFalse() {
            JmsProperties.Target target = buildTarget(true, null);
            when(jmsProperties.getTarget()).thenReturn(target);

            MockRequest request = MockRequest.builder()
                    .protocol(Protocol.JMS)
                    .path("QUEUE")
                    .build();

            assertThat(pipeline.shouldForward(request)).isFalse();
        }

        @Test
        @DisplayName("target.enabled=true, serverUrl=\"\" → false")
        void enabledWithEmptyServerUrlReturnsFalse() {
            JmsProperties.Target target = buildTarget(true, "");
            when(jmsProperties.getTarget()).thenReturn(target);

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
            when(conditionMatcher.prepareBody(anyString())).thenReturn(prepared);
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
            JmsProperties.Target target = buildTarget(false, null);
            when(jmsProperties.getTarget()).thenReturn(target);
            when(jmsProperties.getQueue()).thenReturn("ORDER.REQUEST");

            when(jmsRuleService.findPreparedJmsRules("ORDER.REQUEST"))
                    .thenReturn(Collections.emptyList());

            ConditionMatcher.PreparedBody prepared = ConditionMatcher.PreparedBody.empty();
            when(conditionMatcher.prepareBody(anyString())).thenReturn(prepared);

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
