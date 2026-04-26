package com.echo.pipeline;

import com.echo.entity.HttpRule;
import com.echo.entity.Protocol;
import com.echo.service.ConditionMatcher;
import com.echo.service.HttpRuleService;
import com.echo.service.RequestLogService;
import com.echo.service.ResponseTemplateService;
import com.echo.service.RuleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * HttpMockPipeline 單元測試
 * <p>
 * 驗證 HTTP 協定 pipeline 的各項行為：匹配回應、模板渲染、proxy 轉發、
 * 無匹配處理、shouldForward 決策、日誌記錄。
 */
@ExtendWith(MockitoExtension.class)
class HttpMockPipelineTest {

    @Mock
    private ConditionMatcher conditionMatcher;

    @Mock
    private RuleService ruleService;

    @Mock
    private RequestLogService requestLogService;

    @Mock
    private HttpRuleService httpRuleService;

    @Mock
    private ResponseTemplateService templateService;

    @Mock
    private RestTemplate restTemplate;

    private HttpMockPipeline pipeline;

    @BeforeEach
    void setUp() {
        pipeline = new HttpMockPipeline(
                conditionMatcher, ruleService, requestLogService,
                httpRuleService, templateService, restTemplate, null);
    }

    // ==================== Helpers ====================

    private MockRequest defaultRequest() {
        return MockRequest.builder()
                .protocol(Protocol.HTTP)
                .method("POST")
                .path("/api/users")
                .queryString("page=1&size=10")
                .body("{\"name\":\"test\"}")
                .clientIp("127.0.0.1")
                .targetHost("default")
                .headers(Map.of("Content-Type", "application/json"))
                .build();
    }

    private HttpRule buildRule(String id, String bodyCondition, Long responseId,
                               Integer httpStatus, String httpHeaders) {
        return HttpRule.builder()
                .id(id)
                .enabled(true)
                .bodyCondition(bodyCondition)
                .responseId(responseId)
                .matchKey("/api/users")
                .method("POST")
                .httpStatus(httpStatus)
                .httpHeaders(httpHeaders)
                .priority(0)
                .delayMs(0L)
                .build();
    }

    // ==================== 1. Normal match with template rendering and custom headers ====================

    @Nested
    @DisplayName("正常匹配回應（含模板渲染與自訂 headers）")
    class NormalMatchWithTemplate {

        @Test
        @DisplayName("匹配成功時回傳渲染後的 body 與自訂 headers")
        void matchedWithTemplateAndHeaders() {
            HttpRule rule = buildRule("rule-1", "name=test", 10L, 201,
                    "{\"X-Custom\":\"hello\",\"X-Trace\":\"abc\"}");
            String templateBody = "Hello {{request.path}}";
            String renderedBody = "Hello /api/users";

            when(httpRuleService.findPreparedHttpRules("default", "/api/users", "POST"))
                    .thenReturn(List.of(rule));

            ConditionMatcher.PreparedBody prepared = ConditionMatcher.PreparedBody.empty();
            when(conditionMatcher.prepareBody(anyString())).thenReturn(prepared);
            when(conditionMatcher.matchesPrepared(eq("name=test"), isNull(), isNull(),
                    any(), eq("page=1&size=10"), eq(Map.of("Content-Type", "application/json"))))
                    .thenReturn(true);
            when(ruleService.findResponseBodyById(10L)).thenReturn(Optional.of(templateBody));
            when(templateService.hasTemplate(templateBody)).thenReturn(true);
            when(templateService.render(eq(templateBody), any(ResponseTemplateService.TemplateContext.class)))
                    .thenReturn(renderedBody);

            PipelineResult result = pipeline.execute(defaultRequest());

            assertThat(result.isMatched()).isTrue();
            assertThat(result.getRuleId()).isEqualTo("rule-1");

            MockResponse response = result.getResponse();
            assertThat(response.getStatus()).isEqualTo(201);
            assertThat(response.getBody()).isEqualTo(renderedBody);
            assertThat(response.getHeaders()).containsEntry("X-Custom", "hello");
            assertThat(response.getHeaders()).containsEntry("X-Trace", "abc");
            assertThat(response.isMatched()).isTrue();
            assertThat(response.isForwarded()).isFalse();
        }
    }

    // ==================== 2. Normal match without template ====================

    @Nested
    @DisplayName("正常匹配回應（無模板語法）")
    class NormalMatchWithoutTemplate {

        @Test
        @DisplayName("body 不含 {{ 時直接回傳原始 body，不呼叫模板渲染")
        void matchedWithoutTemplate() {
            HttpRule rule = buildRule("rule-2", "name=test", 20L, 200, null);
            String plainBody = "plain response body";

            when(httpRuleService.findPreparedHttpRules("default", "/api/users", "POST"))
                    .thenReturn(List.of(rule));

            ConditionMatcher.PreparedBody prepared = ConditionMatcher.PreparedBody.empty();
            when(conditionMatcher.prepareBody(anyString())).thenReturn(prepared);
            when(conditionMatcher.matchesPrepared(eq("name=test"), isNull(), isNull(),
                    any(), eq("page=1&size=10"), eq(Map.of("Content-Type", "application/json"))))
                    .thenReturn(true);
            when(ruleService.findResponseBodyById(20L)).thenReturn(Optional.of(plainBody));
            when(templateService.hasTemplate(plainBody)).thenReturn(false);

            PipelineResult result = pipeline.execute(defaultRequest());

            MockResponse response = result.getResponse();
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getBody()).isEqualTo(plainBody);
            assertThat(response.isMatched()).isTrue();

            // 不應呼叫 render
            verify(templateService, never()).render(anyString(), any());
        }
    }

    // ==================== 3. Proxy forwarding success ====================

    @Nested
    @DisplayName("Proxy 轉發成功")
    class ProxyForwardingSuccess {

        @Test
        @DisplayName("無匹配且有 targetHost 時轉發成功，回傳 forwarded=true")
        void proxyForwardSuccess() {
            MockRequest request = MockRequest.builder()
                    .protocol(Protocol.HTTP)
                    .method("GET")
                    .path("/api/data")
                    .queryString("q=hello")
                    .body("")
                    .clientIp("10.0.0.1")
                    .targetHost("backend.example.com")
                    .headers(Map.of("Accept", "application/json"))
                    .build();

            when(httpRuleService.findPreparedHttpRules("backend.example.com", "/api/data", "GET"))
                    .thenReturn(Collections.emptyList());

            ConditionMatcher.PreparedBody prepared = ConditionMatcher.PreparedBody.empty();
            when(conditionMatcher.prepareBody(anyString())).thenReturn(prepared);

            ResponseEntity<String> proxyResponse = new ResponseEntity<>("proxy body", HttpStatus.OK);
            when(restTemplate.exchange(eq("https://backend.example.com/api/data?q=hello"),
                    any(), any(), eq(String.class)))
                    .thenReturn(proxyResponse);

            PipelineResult result = pipeline.execute(request);

            assertThat(result.isMatched()).isFalse();

            MockResponse response = result.getResponse();
            assertThat(response.isForwarded()).isTrue();
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getBody()).isEqualTo("proxy body");
            assertThat(response.getProxyError()).isNull();
        }
    }

    // ==================== 4. Proxy forwarding failure ====================

    @Nested
    @DisplayName("Proxy 轉發失敗")
    class ProxyForwardingFailure {

        @Test
        @DisplayName("RestTemplate 拋出例外時回傳 502 與 proxyError")
        void proxyForwardFailure() {
            MockRequest request = MockRequest.builder()
                    .protocol(Protocol.HTTP)
                    .method("GET")
                    .path("/api/data")
                    .body("")
                    .clientIp("10.0.0.1")
                    .targetHost("unreachable.host")
                    .headers(Map.of())
                    .build();

            when(httpRuleService.findPreparedHttpRules("unreachable.host", "/api/data", "GET"))
                    .thenReturn(Collections.emptyList());

            ConditionMatcher.PreparedBody prepared = ConditionMatcher.PreparedBody.empty();
            when(conditionMatcher.prepareBody(anyString())).thenReturn(prepared);

            when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                    .thenThrow(new RestClientException("Connection refused"));

            PipelineResult result = pipeline.execute(request);

            MockResponse response = result.getResponse();
            assertThat(response.getStatus()).isEqualTo(502);
            assertThat(response.getBody()).contains("Proxy error");
            assertThat(response.isForwarded()).isTrue();
            assertThat(response.getProxyError()).isEqualTo("Connection refused");
        }
    }

    // ==================== 5. No match and no target host → 404 ====================

    @Nested
    @DisplayName("無匹配且無目標主機 → 404")
    class NoMatchNoForward {

        @Test
        @DisplayName("空候選清單且 targetHost=default 時回傳 404")
        void noMatchReturns404() {
            when(httpRuleService.findPreparedHttpRules("default", "/api/users", "POST"))
                    .thenReturn(Collections.emptyList());

            ConditionMatcher.PreparedBody prepared = ConditionMatcher.PreparedBody.empty();
            when(conditionMatcher.prepareBody(anyString())).thenReturn(prepared);

            PipelineResult result = pipeline.execute(defaultRequest());

            assertThat(result.isMatched()).isFalse();

            MockResponse response = result.getResponse();
            assertThat(response.getStatus()).isEqualTo(404);
            assertThat(response.isMatched()).isFalse();
            assertThat(response.isForwarded()).isFalse();
        }
    }

    // ==================== 6. shouldForward() decision logic ====================

    @Nested
    @DisplayName("shouldForward() 決策邏輯")
    class ShouldForwardDecision {

        @Test
        @DisplayName("targetHost = \"default\" → false")
        void defaultHostReturnsFalse() {
            MockRequest request = MockRequest.builder()
                    .targetHost("default").build();

            assertThat(pipeline.shouldForward(request)).isFalse();
        }

        @Test
        @DisplayName("targetHost = null → false")
        void nullHostReturnsFalse() {
            MockRequest request = MockRequest.builder()
                    .targetHost(null).build();

            assertThat(pipeline.shouldForward(request)).isFalse();
        }

        @Test
        @DisplayName("targetHost = \"some-host\" → true")
        void realHostReturnsTrue() {
            MockRequest request = MockRequest.builder()
                    .targetHost("some-host").build();

            assertThat(pipeline.shouldForward(request)).isTrue();
        }
    }

    // ==================== 7. RequestLogService.record() called with correct params ====================

    @Nested
    @DisplayName("RequestLogService.record() 參數正確性")
    class RecordLogVerification {

        @Test
        @DisplayName("匹配成功時 record() 傳入正確的 ruleId、protocol、matched 等參數")
        void recordCalledOnMatch() {
            HttpRule rule = buildRule("rule-log", "name=test", 30L, 200, null);
            rule.setDelayMs(0L);

            when(httpRuleService.findPreparedHttpRules("default", "/api/users", "POST"))
                    .thenReturn(List.of(rule));

            ConditionMatcher.PreparedBody prepared = ConditionMatcher.PreparedBody.empty();
            when(conditionMatcher.prepareBody(anyString())).thenReturn(prepared);
            when(conditionMatcher.matchesPrepared(eq("name=test"), isNull(), isNull(),
                    any(), eq("page=1&size=10"), eq(Map.of("Content-Type", "application/json"))))
                    .thenReturn(true);
            when(ruleService.findResponseBodyById(30L)).thenReturn(Optional.of("log body"));
            when(templateService.hasTemplate("log body")).thenReturn(false);

            pipeline.execute(defaultRequest());

            verify(requestLogService).record(
                    eq("rule-log"),          // ruleId
                    eq(Protocol.HTTP),       // protocol
                    eq("POST"),              // method
                    eq("/api/users"),        // endpoint
                    eq(true),                // matched
                    anyInt(),                // responseTimeMs
                    eq("127.0.0.1"),         // clientIp
                    any(),                   // matchChainJson
                    eq("default"),           // targetHost (matched → includes targetHost)
                    any(),                   // proxyStatus
                    isNull(),                // proxyError
                    eq(200),                 // responseStatus
                    anyInt(),                // matchTimeMs
                    eq("{\"name\":\"test\"}"), // requestBody
                    eq("log body"),          // responseBody
                    anyList(),               // candidates
                    any(),                   // preparedBody
                    any(),                   // queryString
                    any(),                   // headers
                    any(),                   // faultType
                    any(),                   // scenarioName
                    any(),                   // scenarioFromState
                    any()                    // scenarioToState
            );
        }

        @Test
        @DisplayName("無匹配且不轉發時 record() 傳入 ruleId=null、matched=false")
        void recordCalledOnNoMatch() {
            when(httpRuleService.findPreparedHttpRules("default", "/api/users", "POST"))
                    .thenReturn(Collections.emptyList());

            ConditionMatcher.PreparedBody prepared = ConditionMatcher.PreparedBody.empty();
            when(conditionMatcher.prepareBody(anyString())).thenReturn(prepared);

            pipeline.execute(defaultRequest());

            verify(requestLogService).record(
                    isNull(),                // ruleId = null (no match)
                    eq(Protocol.HTTP),       // protocol
                    eq("POST"),              // method
                    eq("/api/users"),        // endpoint
                    eq(false),               // matched = false
                    anyInt(),                // responseTimeMs
                    eq("127.0.0.1"),         // clientIp
                    any(),                   // matchChainJson
                    isNull(),                // targetHost = null (handleNoMatch path)
                    isNull(),                // proxyStatus
                    isNull(),                // proxyError
                    eq(404),                 // responseStatus
                    anyInt(),                // matchTimeMs
                    eq("{\"name\":\"test\"}"), // requestBody
                    eq("No mock rule found for: POST /api/users"), // responseBody
                    anyList(),               // candidates
                    any(),                   // preparedBody
                    any(),                   // queryString
                    any(),                   // headers
                    any(),                   // faultType
                    any(),                   // scenarioName
                    any(),                   // scenarioFromState
                    any()                    // scenarioToState
            );
        }
    }
}
