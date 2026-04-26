package com.echo.pipeline;

import com.echo.entity.HttpRule;
import com.echo.entity.Protocol;
import com.echo.service.ConditionMatcher;
import com.echo.service.MatchChainEntry;
import com.echo.service.RequestLogService;
import com.echo.service.RuleService;
import net.jqwik.api.*;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Pipeline 日誌參數完整性屬性測試
 * <p>
 * Feature: protocol-pipeline-refactor, Property 5: Pipeline 日誌參數完整性
 * <p>
 * 使用 jqwik 產生隨機 pipeline 執行情境（匹配成功、轉發、無匹配），
 * 驗證 {@code recordLog()} 傳入 {@code RequestLogService.record()} 的參數正確性：
 * <ul>
 *   <li>matched=true 時 ruleId 非 null</li>
 *   <li>matched=false 時 ruleId 為 null</li>
 *   <li>protocol 與請求協定一致</li>
 *   <li>endpoint 與請求路徑一致</li>
 * </ul>
 *
 * <b>Validates: Requirements 6.5</b>
 */
class PipelineLogParameterPropertyTest {

    // ==================== Scenario Enum ====================

    enum Scenario {
        /** 候選規則中有匹配的規則 */
        MATCH_SUCCESS,
        /** 無匹配，shouldForward=true，forward 回傳回應 */
        FORWARD,
        /** 無匹配，shouldForward=false，handleNoMatch 回傳回應 */
        NO_MATCH
    }

    // ==================== Test Subclass ====================

    /**
     * 具體測試子類別，根據 scenario 控制 pipeline 行為。
     * <p>
     * - MATCH_SUCCESS: findCandidateRules 回傳含一條 enabled 無條件規則（作為 fallback 匹配）
     * - FORWARD: findCandidateRules 回傳空清單，shouldForward=true
     * - NO_MATCH: findCandidateRules 回傳空清單，shouldForward=false
     */
    static class ScenarioPipeline extends AbstractMockPipeline<HttpRule> {

        private final Scenario scenario;
        private final HttpRule matchingRule;

        ScenarioPipeline(ConditionMatcher conditionMatcher,
                         RuleService ruleService,
                         RequestLogService requestLogService,
                         Scenario scenario,
                         HttpRule matchingRule) {
            super(conditionMatcher, ruleService, requestLogService, null);
            this.scenario = scenario;
            this.matchingRule = matchingRule;
        }

        @Override
        protected List<HttpRule> findCandidateRules(MockRequest request) {
            if (scenario == Scenario.MATCH_SUCCESS && matchingRule != null) {
                return List.of(matchingRule);
            }
            return Collections.emptyList();
        }

        @Override
        protected MockResponse buildResponse(HttpRule rule, MockRequest request, String responseBody) {
            return MockResponse.builder()
                    .status(200)
                    .body("matched-body")
                    .matched(true)
                    .forwarded(false)
                    .build();
        }

        @Override
        protected MockResponse forward(MockRequest request) {
            return MockResponse.builder()
                    .status(200)
                    .body("forwarded-body")
                    .matched(false)
                    .forwarded(true)
                    .build();
        }

        @Override
        protected boolean shouldForward(MockRequest request) {
            return scenario == Scenario.FORWARD;
        }

        @Override
        protected MockResponse handleNoMatch(MockRequest request) {
            return MockResponse.builder()
                    .status(404)
                    .body("no-match")
                    .matched(false)
                    .forwarded(false)
                    .build();
        }

        @Override
        protected boolean hasCondition(HttpRule rule) {
            return rule.getBodyCondition() != null && !rule.getBodyCondition().isBlank();
        }

        @Override
        protected ConditionSet extractConditions(HttpRule rule) {
            return ConditionSet.builder()
                    .bodyCondition(rule.getBodyCondition())
                    .build();
        }

        @Override
        protected MatchChainEntry createMatchChainEntry(HttpRule rule, String reason) {
            return MatchChainEntry.fromHttp(rule, reason);
        }
    }

    // ==================== Stub ConditionMatcher ====================

    static class NoOpConditionMatcher extends ConditionMatcher {
        @Override
        public boolean matchesPrepared(String bodyCondition, String queryCondition,
                                       String headerCondition,
                                       PreparedBody prepared, String queryString,
                                       Map<String, String> headers) {
            return false;
        }

        @Override
        public PreparedBody prepareBody(String body) {
            return PreparedBody.empty();
        }
    }

    // ==================== Generators ====================

    @Provide
    Arbitrary<Scenario> scenarios() {
        return Arbitraries.of(Scenario.MATCH_SUCCESS, Scenario.FORWARD, Scenario.NO_MATCH);
    }

    @Provide
    Arbitrary<Protocol> protocols() {
        return Arbitraries.of(Protocol.HTTP, Protocol.JMS);
    }

    @Provide
    Arbitrary<String> paths() {
        return Arbitraries.oneOf(
                Arbitraries.of("/api/test", "/mock/users", "/orders/123", "/health"),
                Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(20)
                        .map(s -> "/" + s)
        );
    }

    @Provide
    Arbitrary<String> methods() {
        return Arbitraries.of("GET", "POST", "PUT", "DELETE", "PATCH", null);
    }

    // ==================== Helpers ====================

    private HttpRule createMatchingRule() {
        return HttpRule.builder()
                .id(UUID.randomUUID().toString())
                .enabled(true)
                .bodyCondition(null)
                .matchKey("/test")
                .httpStatus(200)
                .priority(0)
                .delayMs(0L)
                .build();
    }

    // ==================== Property Tests ====================

    /**
     * Feature: protocol-pipeline-refactor, Property 5: Pipeline 日誌參數完整性
     * <p>
     * 對任意 pipeline 執行情境，驗證 recordLog() 傳入 RequestLogService.record() 時：
     * matched=true → ruleId 非 null；matched=false → ruleId 為 null。
     *
     * <b>Validates: Requirements 6.5</b>
     */
    @Property(tries = 150)
    void ruleIdConsistentWithMatchedFlag(
            @ForAll("scenarios") Scenario scenario,
            @ForAll("protocols") Protocol protocol,
            @ForAll("paths") String path) {

        RequestLogService mockLogService = mock(RequestLogService.class);
        RuleService mockRuleService = mock(RuleService.class);
        when(mockRuleService.findResponseBodyById(any())).thenReturn(Optional.of("body"));

        HttpRule matchingRule = createMatchingRule();
        ScenarioPipeline pipeline = new ScenarioPipeline(
                new NoOpConditionMatcher(), mockRuleService, mockLogService,
                scenario, matchingRule);

        MockRequest request = MockRequest.builder()
                .protocol(protocol)
                .method("GET")
                .path(path)
                .clientIp("127.0.0.1")
                .build();

        pipeline.execute(request);

        // Capture the arguments passed to record()
        ArgumentCaptor<String> ruleIdCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Boolean> matchedCaptor = ArgumentCaptor.forClass(Boolean.class);

        verify(mockLogService).record(
                ruleIdCaptor.capture(),    // ruleId
                any(Protocol.class),       // protocol
                any(),                     // method
                any(),                     // endpoint
                matchedCaptor.capture(),   // matched
                anyInt(),                  // responseTimeMs
                any(),                     // clientIp
                any(),                     // matchChain
                any(),                     // targetHost
                any(),                     // proxyStatus
                any(),                     // proxyError
                any(),                     // responseStatus
                any(),                     // matchTimeMs
                any(),                     // requestBody
                any(),                     // responseBody
                anyList(),                 // candidates
                any(),                     // preparedBody
                any(),                     // queryString
                any(),                     // headers
                any(),                     // faultType
                any(),                     // scenarioName
                any(),                     // scenarioFromState
                any()                      // scenarioToState
        );

        boolean matched = matchedCaptor.getValue();
        String ruleId = ruleIdCaptor.getValue();

        if (matched) {
            assertThat(ruleId)
                    .as("When matched=true, ruleId must be non-null (scenario=%s)", scenario)
                    .isNotNull();
        } else {
            assertThat(ruleId)
                    .as("When matched=false, ruleId must be null (scenario=%s)", scenario)
                    .isNull();
        }
    }

    /**
     * Feature: protocol-pipeline-refactor, Property 5: Pipeline 日誌參數完整性
     * <p>
     * 對任意 pipeline 執行情境，驗證 recordLog() 傳入的 protocol 與請求的 protocol 一致。
     *
     * <b>Validates: Requirements 6.5</b>
     */
    @Property(tries = 150)
    void protocolMatchesRequest(
            @ForAll("scenarios") Scenario scenario,
            @ForAll("protocols") Protocol protocol,
            @ForAll("paths") String path) {

        RequestLogService mockLogService = mock(RequestLogService.class);
        RuleService mockRuleService = mock(RuleService.class);
        when(mockRuleService.findResponseBodyById(any())).thenReturn(Optional.of("body"));

        HttpRule matchingRule = createMatchingRule();
        ScenarioPipeline pipeline = new ScenarioPipeline(
                new NoOpConditionMatcher(), mockRuleService, mockLogService,
                scenario, matchingRule);

        MockRequest request = MockRequest.builder()
                .protocol(protocol)
                .method("POST")
                .path(path)
                .clientIp("10.0.0.1")
                .build();

        pipeline.execute(request);

        ArgumentCaptor<Protocol> protocolCaptor = ArgumentCaptor.forClass(Protocol.class);

        verify(mockLogService).record(
                any(),                     // ruleId
                protocolCaptor.capture(),  // protocol
                any(),                     // method
                any(),                     // endpoint
                anyBoolean(),              // matched
                anyInt(),                  // responseTimeMs
                any(),                     // clientIp
                any(),                     // matchChain
                any(),                     // targetHost
                any(),                     // proxyStatus
                any(),                     // proxyError
                any(),                     // responseStatus
                any(),                     // matchTimeMs
                any(),                     // requestBody
                any(),                     // responseBody
                anyList(),                 // candidates
                any(),                     // preparedBody
                any(),                     // queryString
                any(),                     // headers
                any(),                     // faultType
                any(),                     // scenarioName
                any(),                     // scenarioFromState
                any()                      // scenarioToState
        );

        assertThat(protocolCaptor.getValue())
                .as("Logged protocol must match request protocol (scenario=%s)", scenario)
                .isEqualTo(protocol);
    }

    /**
     * Feature: protocol-pipeline-refactor, Property 5: Pipeline 日誌參數完整性
     * <p>
     * 對任意 pipeline 執行情境，驗證 recordLog() 傳入的 endpoint 與請求的 path 一致。
     *
     * <b>Validates: Requirements 6.5</b>
     */
    @Property(tries = 150)
    void endpointMatchesRequestPath(
            @ForAll("scenarios") Scenario scenario,
            @ForAll("protocols") Protocol protocol,
            @ForAll("paths") String path) {

        RequestLogService mockLogService = mock(RequestLogService.class);
        RuleService mockRuleService = mock(RuleService.class);
        when(mockRuleService.findResponseBodyById(any())).thenReturn(Optional.of("body"));

        HttpRule matchingRule = createMatchingRule();
        ScenarioPipeline pipeline = new ScenarioPipeline(
                new NoOpConditionMatcher(), mockRuleService, mockLogService,
                scenario, matchingRule);

        MockRequest request = MockRequest.builder()
                .protocol(protocol)
                .method("GET")
                .path(path)
                .clientIp("192.168.1.1")
                .build();

        pipeline.execute(request);

        ArgumentCaptor<String> endpointCaptor = ArgumentCaptor.forClass(String.class);

        verify(mockLogService).record(
                any(),                     // ruleId
                any(Protocol.class),       // protocol
                any(),                     // method
                endpointCaptor.capture(),  // endpoint
                anyBoolean(),              // matched
                anyInt(),                  // responseTimeMs
                any(),                     // clientIp
                any(),                     // matchChain
                any(),                     // targetHost
                any(),                     // proxyStatus
                any(),                     // proxyError
                any(),                     // responseStatus
                any(),                     // matchTimeMs
                any(),                     // requestBody
                any(),                     // responseBody
                anyList(),                 // candidates
                any(),                     // preparedBody
                any(),                     // queryString
                any(),                     // headers
                any(),                     // faultType
                any(),                     // scenarioName
                any(),                     // scenarioFromState
                any()                      // scenarioToState
        );

        assertThat(endpointCaptor.getValue())
                .as("Logged endpoint must match request path (scenario=%s)", scenario)
                .isEqualTo(path);
    }

    /**
     * Feature: protocol-pipeline-refactor, Property 5: Pipeline 日誌參數完整性
     * <p>
     * 綜合驗證：對任意情境，所有四個日誌參數同時正確。
     *
     * <b>Validates: Requirements 6.5</b>
     */
    @Property(tries = 150)
    void allLogParametersCorrectTogether(
            @ForAll("scenarios") Scenario scenario,
            @ForAll("protocols") Protocol protocol,
            @ForAll("paths") String path,
            @ForAll("methods") String method) {

        RequestLogService mockLogService = mock(RequestLogService.class);
        RuleService mockRuleService = mock(RuleService.class);
        when(mockRuleService.findResponseBodyById(any())).thenReturn(Optional.of("body"));

        HttpRule matchingRule = createMatchingRule();
        ScenarioPipeline pipeline = new ScenarioPipeline(
                new NoOpConditionMatcher(), mockRuleService, mockLogService,
                scenario, matchingRule);

        MockRequest request = MockRequest.builder()
                .protocol(protocol)
                .method(method)
                .path(path)
                .clientIp("10.0.0.1")
                .build();

        pipeline.execute(request);

        ArgumentCaptor<String> ruleIdCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Protocol> protocolCaptor = ArgumentCaptor.forClass(Protocol.class);
        ArgumentCaptor<String> endpointCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Boolean> matchedCaptor = ArgumentCaptor.forClass(Boolean.class);

        verify(mockLogService).record(
                ruleIdCaptor.capture(),    // ruleId
                protocolCaptor.capture(),  // protocol
                any(),                     // method
                endpointCaptor.capture(),  // endpoint
                matchedCaptor.capture(),   // matched
                anyInt(),                  // responseTimeMs
                any(),                     // clientIp
                any(),                     // matchChain
                any(),                     // targetHost
                any(),                     // proxyStatus
                any(),                     // proxyError
                any(),                     // responseStatus
                any(),                     // matchTimeMs
                any(),                     // requestBody
                any(),                     // responseBody
                anyList(),                 // candidates
                any(),                     // preparedBody
                any(),                     // queryString
                any(),                     // headers
                any(),                     // faultType
                any(),                     // scenarioName
                any(),                     // scenarioFromState
                any()                      // scenarioToState
        );

        boolean matched = matchedCaptor.getValue();
        String ruleId = ruleIdCaptor.getValue();

        // ruleId consistency
        if (matched) {
            assertThat(ruleId)
                    .as("matched=true → ruleId non-null (scenario=%s)", scenario)
                    .isNotNull();
        } else {
            assertThat(ruleId)
                    .as("matched=false → ruleId null (scenario=%s)", scenario)
                    .isNull();
        }

        // protocol consistency
        assertThat(protocolCaptor.getValue())
                .as("protocol must match request")
                .isEqualTo(protocol);

        // endpoint consistency
        assertThat(endpointCaptor.getValue())
                .as("endpoint must match request path")
                .isEqualTo(path);
    }
}
