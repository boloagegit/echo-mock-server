package com.echo.pipeline;

import com.echo.service.ServeEvent;
import com.echo.service.ServeEventCaptor;
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
 * Pipeline Log parameter integrity attribute test
 * <p>
 * Feature: protocol-pipeline-refactor, Property 5: Pipeline Log parameter integrity
 * <p>
 * Use jqwik to generate a random pipeline execution situation (match successfully, forward, no match),
 * Verify the correctness of the parameters passed into {@code RequestLogService.record()} by {@code recordLog()}:
 * <ul>
 *   <li>matched=true when ruleId is not null</li>
 *   <li>ruleId is null when matched=false</li>
 *   <li>protocol is consistent with the request protocol</li>
 *   <li>endpoint is consistent with the requested path</li>
 * </ul>
 *
 * <b>Validates: Requirements 6.5</b>
 */
class PipelineLogParameterPropertyTest {

    // ==================== Scenario Enum ====================

    enum Scenario {
        /** There is a matching rule among the candidate rules */
        MATCH_SUCCESS,
        /** No match, shouldForward=true, forward returns response */
        FORWARD,
        /** No match, shouldForward=false, handleNoMatch returns response */
        NO_MATCH
    }

    // ==================== Test Subclass ====================

    /**
     * Concrete test subclass controlling pipeline behavior according to the scenario.
     * <p>
     * - MATCH_SUCCESS: The findCandidateRules return contains an unconditional rule for enabled (matched as fallback)
     * - FORWARD: findCandidateRules returns an empty list, shouldForward=true
     * - NO_MATCH: findCandidateRules returns an empty list, shouldForward=false
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
     * Feature: protocol-pipeline-refactor, Property 5: Pipeline Log parameter integrity
     * <p>
     * For any pipeline execution context, when verifying that pipeline is handed over to ServeEvent that requested the record:
     * matched=true → ruleId is not null; matched=false → ruleId is null.
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

        // Get the event that submitted the request record

        ServeEvent served = ServeEventCaptor.single(mockLogService);

        boolean matched = served.isMatched();
        String ruleId = served.getRuleId();

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
     * Feature: protocol-pipeline-refactor, Property 5: Pipeline Log parameter integrity
     * <p>
     * For any pipeline execution scenario, verify that the protocol in the event pipeline handed to the request record is consistent with the requested protocol.
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


        ServeEvent served = ServeEventCaptor.single(mockLogService);

        assertThat(served.getProtocol())
                .as("Logged protocol must match request protocol (scenario=%s)", scenario)
                .isEqualTo(protocol);
    }

    /**
     * Feature: protocol-pipeline-refactor, Property 5: Pipeline Log parameter integrity
     * <p>
     * For any pipeline execution scenario, verify that the endpoint in the event pipeline handed to the request record is consistent with the requested path.
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


        ServeEvent served = ServeEventCaptor.single(mockLogService);

        assertThat(served.getEndpoint())
                .as("Logged endpoint must match request path (scenario=%s)", scenario)
                .isEqualTo(path);
    }

    /**
     * Feature: protocol-pipeline-refactor, Property 5: Pipeline Log parameter integrity
     * <p>
     * Comprehensive verification: for any scenario, all four log parameters are correct at the same time.
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


        ServeEvent served = ServeEventCaptor.single(mockLogService);

        boolean matched = served.isMatched();
        String ruleId = served.getRuleId();

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
        assertThat(served.getProtocol())
                .as("protocol must match request")
                .isEqualTo(protocol);

        // endpoint consistency
        assertThat(served.getEndpoint())
                .as("endpoint must match request path")
                .isEqualTo(path);
    }
}
