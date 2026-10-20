package com.echo.pipeline;

import com.echo.core.engine.RuleSelector;
import com.echo.core.match.BodyRequirements;
import com.echo.entity.BaseRule;
import com.echo.entity.FaultType;
import com.echo.entity.Protocol;
import com.echo.service.ConditionMatcher;
import com.echo.service.MatchChainEntry;
import com.echo.service.MatchDescriptionBuilder;
import com.echo.service.MatchResult;
import com.echo.service.RequestLogService;
import com.echo.service.ResponseTemplateService;
import com.echo.service.RequestLogUnavailableException;
import com.echo.service.RuleService;
import com.echo.service.ServeEvent;
import com.echo.service.ServeEventDispatcher;
import com.echo.service.ServeEventListener;
import com.echo.util.CancellableStages;
import com.echo.service.ScenarioService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Abstract Mock Pipeline — Template Method pattern
 * <p>
 * Define the execution sequence of each step of pipeline. The common steps are implemented by the base class.
 * Protocol-specific steps are overridden by subclasses.
 *
 * @param <T> Rule type (HttpRule or JmsRule)
 * @see com.echo.entity.BaseRule
 */
@Slf4j
public abstract class AbstractMockPipeline<T extends BaseRule> {

    protected final ConditionMatcher conditionMatcher;
    protected final RuleService ruleService;
    protected final RequestLogService requestLogService;
    protected final ScenarioService scenarioService;
    private final List<Responder<T>> responders;
    private ServeEventDispatcher serveEvents;
    private ResponseTemplateService templateService;

    protected AbstractMockPipeline(ConditionMatcher conditionMatcher,
                                   RuleService ruleService,
                                   RequestLogService requestLogService,
                                   ScenarioService scenarioService) {
        this.conditionMatcher = conditionMatcher;
        this.ruleService = ruleService;
        this.requestLogService = requestLogService;
        this.scenarioService = scenarioService;
        this.responders = List.of(new FaultResponder(), new ForwardResponder(), new MockResponder());
        this.serveEvents = ServeEventDispatcher.of(requestLogService, List.of());
    }

    /** listener other than the request record (such as indicators, Webhook); the request record is always ranked first. */
    @Autowired(required = false)
    public void setServeEventListeners(List<ServeEventListener> listeners) {
        this.serveEvents = ServeEventDispatcher.of(requestLogService, listeners);
    }

    /** Response template; all protocols share the same rendering step before generating a response (see {@link #ruleResponseBody}). */
    @Autowired
    public void setTemplateService(ResponseTemplateService templateService) {
        this.templateService = templateService;
    }

    // ==================== Template Method: Main process ====================

    /**
     * Execute pipeline main process (Template Method)
     * <p>
     * Steps: findCandidateRules → prepareBody → matchRule → buildResponse/forward/handleNoMatch → recordLog → Return PipelineResult
     */
    public PipelineResult execute(MockRequest request) {
        try {
            return executeAsync(request).toCompletableFuture().join();
        } catch (CompletionException e) {
            Throwable cause = unwrap(e);
            if (cause instanceof RequestLogUnavailableException logUnavailable) {
                throw logUnavailable;
            }
            throw e;
        }
    }

    /**
     * Executing pipeline and allowing the protocol implementation to complete the slow I/O asynchronously.
     * The Mock response is completed directly in the original request thread without adding additional scheduling costs.
     */
    public CompletionStage<PipelineResult> executeAsync(MockRequest request) {
        long startTime = System.currentTimeMillis();

        try {
            // 1. Query candidate rules (counted into matchTimeMs)
            long matchStart = System.currentTimeMillis();
            List<T> candidates = findCandidateRules(request);

            // 2. Prepare body (if MockRequest already has preparedBody, use it directly)
            ConditionMatcher.PreparedBody preparedBody = request.getPreparedBody();
            if (preparedBody == null) {
                preparedBody = prepareBodyForMatching(request, candidates);
            }

            // 3. Matching rules
            MatchResult<T> matchResult = matchRule(candidates, preparedBody,
                    request.getQueryString(), request.getHeaders());
            int matchTimeMs = (int) (System.currentTimeMillis() - matchStart);

            String matchChainJson = MatchDescriptionBuilder.toMatchChainJson(
                    matchResult.getMatchChain(), matchResult.isMatched());

            CompletionStage<MockResponse> responseStage = null;
            MockResponse immediateResponse = null;
            String ruleId;
            long delayMs;
            String faultTypeName = null;
            String scenarioName = null;
            String scenarioFromState = null;
            String scenarioNewState = null;

            if (matchResult.isMatched()) {
                T rule = matchResult.getMatchedRule();
                Responder<T> responder = responderFor(rule, request);
                request.getTrace().event("ROUTE", "ruleId", rule.getId(), "ruleVersion", rule.getVersion(),
                        "responseId", rule.getResponseId(), "mode", responder.mode(),
                        "endpoint", request.getEndpointValue() != null ? request.getEndpointValue() : request.getPath());
                ruleId = rule.getId();
                delayMs = calculateDelay(
                    rule.getDelayMs() != null ? rule.getDelayMs() : 0,
                    rule.getMaxDelayMs()
                );

                // 4a. Scenario state transition (before generating response)
                ScenarioTransition scenarioTransition = advanceScenarioState(rule);
                if (scenarioTransition != null) {
                    scenarioName = scenarioTransition.name();
                    scenarioFromState = scenarioTransition.fromState();
                    scenarioNewState = scenarioTransition.toState();
                }

                // 4b. Response generated by responder
                ResponsePlan plan = responder.respond(rule, request);
                if (plan instanceof ResponsePlan.Content content) {
                    immediateResponse = content.response();
                } else if (plan instanceof ResponsePlan.Fault fault) {
                    immediateResponse = fault.response();
                    faultTypeName = fault.type().name();
                } else if (plan instanceof ResponsePlan.Forwarded forwarded) {
                    responseStage = forwarded.response();
                }
            } else {
                ruleId = null;
                delayMs = 0;
                // 5. No match: determine whether to forward
                var forwarding = forwardingAction(request);
                request.getTrace().event("ROUTE", "ruleId", null, "mode", forwarding != null ? "FORWARD" : "UNMATCHED",
                        "endpoint", request.getEndpointValue() != null ? request.getEndpointValue() : request.getPath());
                if (forwarding != null) {
                    responseStage = forwarding.get();
                } else {
                    immediateResponse = handleNoMatch(request);
                }
            }

            String finalRuleId = ruleId;
            long finalDelayMs = delayMs;
            ConditionMatcher.PreparedBody finalPreparedBody = preparedBody;
            String finalFaultTypeName = faultTypeName;
            String finalScenarioName = scenarioName;
            String finalScenarioFromState = scenarioFromState;
            String finalScenarioNewState = scenarioNewState;
            if (immediateResponse != null) {
                return CompletableFuture.completedFuture(completeResult(
                        request, startTime, candidates, finalPreparedBody,
                        matchResult, matchTimeMs, matchChainJson,
                        finalRuleId, finalDelayMs, immediateResponse,
                        finalFaultTypeName, finalScenarioName,
                        finalScenarioFromState, finalScenarioNewState));
            }
            return CancellableStages.handle(responseStage, (response, error) -> {
                if (error != null) {
                    return pipelineError(request, startTime, unwrap(error));
                }
                try {
                    return completeResult(request, startTime, candidates, finalPreparedBody,
                            matchResult, matchTimeMs, matchChainJson,
                            finalRuleId, finalDelayMs, response,
                            finalFaultTypeName, finalScenarioName,
                            finalScenarioFromState, finalScenarioNewState);
                } catch (Exception e) {
                    return pipelineError(request, startTime, e);
                }
            });

        } catch (Exception e) {
            return CompletableFuture.completedFuture(pipelineError(request, startTime, e));
        }
    }

    private PipelineResult completeResult(MockRequest request,
                                          long startTime,
                                          List<T> candidates,
                                          ConditionMatcher.PreparedBody preparedBody,
                                          MatchResult<T> matchResult,
                                          int matchTimeMs,
                                          String matchChainJson,
                                          String ruleId,
                                          long delayMs,
                                          MockResponse response,
                                          String faultType,
                                          String scenarioName,
                                          String scenarioFromState,
                                          String scenarioToState) {
        int responseTimeMs = (int) (System.currentTimeMillis() - startTime);
        Integer proxyStatus = response.isForwarded() && response.getProxyError() == null
                ? response.getStatus() : null;
        String logTargetHost = (matchResult.isMatched() || response.isForwarded())
                ? request.getTargetHost() : null;

        Integer loggedResponseStatus = "CONNECTION_RESET".equals(faultType)
                ? null : response.getStatus();
        publish(ServeEvent.builder()
                .ruleId(ruleId).protocol(request.getProtocol())
                .method(request.getMethod()).endpoint(request.getPath())
                .matched(matchResult.isMatched()).responseTimeMs(responseTimeMs)
                .clientIp(request.getClientIp()).matchChain(matchChainJson).targetHost(logTargetHost)
                .forwarded(response.isForwarded()).forwardTarget(response.getForwardTarget())
                .proxyStatus(proxyStatus).proxyError(response.getProxyError())
                .responseStatus(loggedResponseStatus).matchTimeMs(matchTimeMs)
                .requestBody(request.getBody()).responseBody(response.getBody())
                .candidates(candidates).preparedBody(preparedBody)
                .queryString(request.getQueryString()).headers(request.getHeaders())
                .faultType(faultType).scenarioName(scenarioName)
                .scenarioFromState(scenarioFromState).scenarioToState(scenarioToState)
                .candidateCount(candidates != null ? candidates.size() : null)
                .matchedByFallback(matchResult.isMatched() ? matchResult.isByFallback() : null)
                .delayMs(matchResult.isMatched() ? (int) Math.min(delayMs, Integer.MAX_VALUE) : null)
                .scenarioSkipped(MatchDescriptionBuilder.toScenarioSkippedJson(matchResult.getMatchChain()))
                .diagnosticId(request.getTrace().id())
                .build());

        return PipelineResult.builder()
                .response(response)
                .ruleId(ruleId)
                .matched(matchResult.isMatched())
                .matchTimeMs(matchTimeMs)
                .responseTimeMs(responseTimeMs)
                .matchChainJson(matchChainJson)
                .delayMs(delayMs)
                .faultType(faultType)
                .scenarioName(scenarioName)
                .scenarioNewState(scenarioToState)
                .build();
    }

    private PipelineResult pipelineError(MockRequest request, long startTime, Throwable error) {
        request.getTrace().failure("PIPELINE_FAILED", error);
        if (!request.getTrace().enabled()) log.error("Pipeline execution error: {}", error.getMessage(), error);
        int responseTimeMs = (int) (System.currentTimeMillis() - startTime);
        boolean logUnavailable = error instanceof RequestLogUnavailableException;
        if (logUnavailable && request.getProtocol() == Protocol.JMS) {
            // A JMS listener must fail the delivery so Artemis retains/redelivers
            // it after durable request logging recovers. HTTP callers instead
            // receive the explicit 503 response below.
            throw (RequestLogUnavailableException) error;
        }
        MockResponse errorResponse = MockResponse.builder()
                .status(logUnavailable ? 503 : 500)
                .body(logUnavailable
                        ? "Request logging is temporarily unavailable"
                        : "Pipeline error: " + error.getMessage())
                .matched(false)
                .forwarded(false)
                .build();
        return PipelineResult.builder()
                .response(errorResponse)
                .matched(false)
                .matchTimeMs(0)
                .responseTimeMs(responseTimeMs)
                .delayMs(0)
                .build();
    }

    private static Throwable unwrap(Throwable error) {
        if (error instanceof CompletionException && error.getCause() != null) {
            return error.getCause();
        }
        return error;
    }

    // ==================== Delayed calculation ====================

    /**
     * Calculate actual delay time.
     * If maxDelayMs is set and is greater than delayMs, the value will be randomly selected within the range of [delayMs, maxDelayMs].
     */
    public static long calculateDelay(long delayMs, Long maxDelayMs) {
        if (maxDelayMs != null && maxDelayMs > delayMs) {
            return ThreadLocalRandom.current().nextLong(delayMs, maxDelayMs + 1);
        }
        return delayMs;
    }

    /**
     * Apply the rule Scenario state transfer and return the actual transfer result.
     * When no new status is set, the current status is still returned for request record presentation.
     */
    public ScenarioTransition advanceScenarioState(T rule) {
        if (rule.getScenarioName() == null || rule.getScenarioName().isBlank()) {
            return null;
        }
        String currentState = scenarioService.getCurrentState(rule.getScenarioName());
        if (rule.getNewScenarioState() == null || rule.getNewScenarioState().isBlank()) {
            return new ScenarioTransition(rule.getScenarioName(), currentState, null, false);
        }
        String expectedState = rule.getRequiredScenarioState();
        if (expectedState == null || expectedState.isBlank()) {
            expectedState = currentState;
        }
        boolean advanced = scenarioService.advanceState(
                rule.getScenarioName(), expectedState, rule.getNewScenarioState());
        return new ScenarioTransition(rule.getScenarioName(), currentState,
                advanced ? rule.getNewScenarioState() : null, advanced);
    }

    public record ScenarioTransition(String name, String fromState, String toState, boolean advanced) {}

    // ==================== Shared matching logic ====================

    /**
     * Shared matching logic: select rules in order (see {@link RuleSelector}), and record the matching chain.
     * <p>
     * Match priority:
     * <ol>
     *   <li>Scenario The rules with inconsistent status are ignored and recorded in the matching chain</li>
     *   <li>is conditional and the condition matches successfully → Select  immediately</li>
     *   <li>Neither  is true → Select an unconditional rule based on {@link #fallbackPolicy()}, or there is no matching</li>
     * </ol>
     */
    public MatchResult<T> matchRule(List<T> candidates,
                                    ConditionMatcher.PreparedBody prepared,
                                    String queryString,
                                    Map<String, String> headers) {
        List<MatchChainEntry> chain = new ArrayList<>();
        RuleSelector.Selection<T> selection = RuleSelector.select(candidates, fallbackPolicy(), ruleTraits,
                rule -> passesScenarioGate(rule, chain),
                rule -> conditionsMatch(rule, prepared, queryString, headers));
        T matched = selection.selected();
        if (matched != null) {
            chain.add(createMatchChainEntry(matched, "match"));
        }
        return new MatchResult<>(matched, chain, matched != null && !selection.conditionMatched());
    }

    /** Select unconditional rules when no conditional rule matches; JMS overrides this to prefer exact queues before wildcards. */
    protected RuleSelector.FallbackPolicy fallbackPolicy() {
        return RuleSelector.FallbackPolicy.FIRST_UNCONDITIONAL;
    }

    /** Wildcard rule; relevant only to {@link RuleSelector.FallbackPolicy#EXACT_BEFORE_WILDCARD}. */
    protected boolean isWildcard(T rule) {
        return false;
    }

    private final RuleSelector.Traits<T> ruleTraits = new RuleSelector.Traits<>() {
        @Override
        public boolean enabled(T rule) {
            return !Boolean.FALSE.equals(rule.getEnabled());
        }

        @Override
        public boolean unconditional(T rule) {
            return !hasCondition(rule);
        }

        @Override
        public boolean wildcard(T rule) {
            return isWildcard(rule);
        }
    };

    /** When the Scenario status does not match, it is recorded in the matching chain and returns false. */
    private boolean passesScenarioGate(T rule, List<MatchChainEntry> chain) {
        if (rule.getScenarioName() == null || rule.getScenarioName().isBlank()
                || rule.getRequiredScenarioState() == null || rule.getRequiredScenarioState().isBlank()) {
            return true;
        }
        String currentState = scenarioService.getCurrentState(rule.getScenarioName());
        if (rule.getRequiredScenarioState().equals(currentState)) {
            return true;
        }
        chain.add(new MatchChainEntry(
                rule.getId(), MatchDescriptionBuilder.SCENARIO_STATE_MISMATCH,
                null, rule.getDescription(), null,
                null,
                "scenario: " + rule.getScenarioName()
                        + " (required: " + rule.getRequiredScenarioState()
                        + ", current: " + currentState + ")",
                false));
        return false;
    }

    private boolean conditionsMatch(T rule, ConditionMatcher.PreparedBody prepared,
                                    String queryString, Map<String, String> headers) {
        ConditionSet conditions = extractConditions(rule);
        if (conditions.getMatchSpec() != null) {
            return conditionMatcher.matchesPrepared(conditions.getMatchSpec(), null, null, null,
                    prepared, queryString, headers);
        }
        return conditionMatcher.matchesPrepared(
                conditions.getBodyCondition(),
                conditions.getQueryCondition(),
                conditions.getHeaderCondition(),
                prepared, queryString, headers);
    }

    /**
     * Prepare body according to the requirements of the candidate rule conditions (§4.5): do not parse body when there is no condition to read; XML only scans once and does not create DOM when the conditions can be streamed;
     * Other situations will be parsed as usual. HTTP and JMS use the same set of rules.
     */
    public ConditionMatcher.PreparedBody prepareBodyForMatching(MockRequest request, List<T> candidates) {
        if (candidateListsAreStable()) {
            // The requirement for merging 2,000 rules is about 0.7 ms; the candidate list comes from rule snapshots and object fixes, so it is cached by object
            return conditionMatcher.prepareBody(request.getBody(),
                    bodyRequirementsByCandidates.get(candidates, this::bodyRequirementsOf));
        }
        return prepareBody(request.getBody(), candidates);
    }

    /**
     * Whether the candidate list comes from the rule snapshot and is the same object during the snapshot existence (queue of JMS and the bucket list).
     * If yes, the merged body needs to be cached according to the list object; the candidate list of HTTP will be re-screened each time according to path and will not be cached.
     */
    protected boolean candidateListsAreStable() {
        return false;
    }

    /** The list object itself is key (weakKeys is compared with identity), and the snapshot is discarded and recycled together with the list. */
    private final Cache<List<?>, BodyRequirements> bodyRequirementsByCandidates =
            Caffeine.newBuilder().weakKeys().build();

    private BodyRequirements bodyRequirementsOf(List<?> candidates) {
        List<BodyRequirements> requirements = new ArrayList<>();
        for (ConditionMatcher.RuleConditions conditions : bodyConditionsOf(castCandidates(candidates))) {
            requirements.add(conditions.bodyRequirements());
        }
        return BodyRequirements.merge(requirements);
    }

    @SuppressWarnings("unchecked")
    private List<T> castCandidates(List<?> candidates) {
        return (List<T>) candidates;
    }

    private ConditionMatcher.PreparedBody prepareBody(String body, List<T> candidates) {
        List<ConditionMatcher.RuleConditions> conditions = bodyConditionsOf(candidates);
        return conditions.isEmpty() ? ConditionMatcher.PreparedBody.rawOnly(body)
                : conditionMatcher.prepareBody(body, conditions);
    }

    /** Can read the enabling rule condition of body; rules with only query/header conditions do not need to query requirements. */
    private List<ConditionMatcher.RuleConditions> bodyConditionsOf(List<T> candidates) {
        List<ConditionMatcher.RuleConditions> conditions = null;
        for (T rule : candidates) {
            if (Boolean.FALSE.equals(rule.getEnabled())) {
                continue;
            }
            ConditionSet set = extractConditions(rule);
            String bodyCondition = set.getBodyCondition();
            // Rules with only query/header conditions do not read body, so there is no need to query their requirements
            if ((bodyCondition == null || bodyCondition.isBlank()) && set.getMatchSpec() == null) {
                continue;
            }
            if (conditions == null) {
                conditions = new ArrayList<>();
            }
            conditions.add(conditionMatcher.conditions(set.getMatchSpec(), bodyCondition,
                    set.getQueryCondition(), set.getHeaderCondition()));
        }
        return conditions == null ? List.of() : conditions;
    }

    // ==================== Shared implementation methods ====================

    /**
     * Query the response content from the Response table
     */
    /** The response content of the rule includes template rendering; no additional processing is performed when there is no template syntax. */
    protected String ruleResponseBody(T rule, MockRequest request) {
        String body = resolveResponseBody(rule.getResponseId());
        return ResponseTemplateService.renderIfTemplate(templateService, body, request::templateContext);
    }

    protected String resolveResponseBody(Long responseId) {
        if (responseId == null) {
            return "";
        }
        return ruleService.findResponseBodyById(responseId).orElse("");
    }

    /**
     * Execution delay (synchronous Thread.sleep, for use by JMS pipeline)
     */
    protected void applyDelay(long delayMs) {
        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                log.warn("Delay interrupted: {}ms", delayMs);
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Give the processing result to listener (the request record is critical, and upload it when it fails). */
    protected void publish(ServeEvent event) {
        serveEvents.dispatch(event);
    }

    // ==================== Responder ====================

    /**
     * Select the applicable responder in order; the default is fault injection → hit rule forwarding → mock.
     * Subclasses can override {@link #responders()} to add protocol-specific responders.
     */
    private Responder<T> responderFor(T rule, MockRequest request) {
        for (Responder<T> responder : responders()) {
            if (responder.appliesTo(rule, request)) {
                return responder;
            }
        }
        throw new IllegalStateException("No responder applies to rule " + rule.getId());
    }

    protected List<Responder<T>> responders() {
        return responders;
    }

    private static boolean hasFault(BaseRule rule) {
        return rule.getFaultType() != null && rule.getFaultType() != FaultType.NONE;
    }

    /** Fault injection: EMPTY_RESPONSE nulled back body; CONNECTION_RESET is closed by protocol on writeback. */
    private final class FaultResponder implements Responder<T> {
        @Override
        public String mode() {
            return "FAULT";
        }

        @Override
        public boolean appliesTo(T rule, MockRequest request) {
            return hasFault(rule);
        }

        @Override
        public ResponsePlan respond(T rule, MockRequest request) {
            MockResponse response = buildResponse(rule, request, ruleResponseBody(rule, request));
            if (rule.getFaultType() == FaultType.EMPTY_RESPONSE) {
                response = MockResponse.builder()
                        .status(response.getStatus())
                        .body("")
                        .matched(true)
                        .forwarded(false)
                        .build();
            }
            return new ResponsePlan.Fault(rule.getFaultType(), response);
        }
    }

    /** The rule action is forwarding (see {@link #shouldForwardMatchedRule}). */
    private final class ForwardResponder implements Responder<T> {
        @Override
        public String mode() {
            return "FORWARD";
        }

        @Override
        public boolean appliesTo(T rule, MockRequest request) {
            return shouldForwardMatchedRule(rule, request);
        }

        @Override
        public ResponsePlan respond(T rule, MockRequest request) {
            return new ResponsePlan.Forwarded(forwardMatchedRuleAsync(rule, request));
        }
    }

    /** Return the response content set by the rules (the template has been rendered in the shared step, see {@link #buildResponse} for protocol-specific parts). */
    private final class MockResponder implements Responder<T> {
        @Override
        public String mode() {
            return "MOCK";
        }

        @Override
        public boolean appliesTo(T rule, MockRequest request) {
            return true;
        }

        @Override
        public ResponsePlan respond(T rule, MockRequest request) {
            return new ResponsePlan.Content(buildResponse(rule, request, ruleResponseBody(rule, request)));
        }
    }

    // ==================== Abstract methods: implemented by subclasses ====================

    /** Query candidate rules */
    protected abstract List<T> findCandidateRules(MockRequest request);

    /** Constructing response (protocol-specific logic; responseBody template rendering completed) */
    protected abstract MockResponse buildResponse(T rule, MockRequest request, String responseBody);

    /** Perform forwarding */
    protected abstract MockResponse forward(MockRequest request);

    /**
     * Asynchronous forwarding hook. Protocols that do not override it keep their existing synchronous behavior.
     */
    protected CompletionStage<MockResponse> forwardAsync(MockRequest request) {
        return CompletableFuture.completedFuture(forward(request));
    }

    /** Determine whether it should be forwarded */
    protected abstract boolean shouldForward(MockRequest request);

    /** Resolve the routing decision once; protocol adapters may capture the selected target. */
    protected java.util.function.Supplier<CompletionStage<MockResponse>> forwardingAction(MockRequest request) {
        return shouldForward(request) ? () -> forwardAsync(request) : null;
    }

    /** Handling no match situation */
    protected abstract MockResponse handleNoMatch(MockRequest request);

    /** Determine whether the rule is conditional */
    protected abstract boolean hasCondition(T rule);

    /** Extract condition set of rules */
    protected abstract ConditionSet extractConditions(T rule);

    /** Creating match chain entries */
    protected abstract MatchChainEntry createMatchChainEntry(T rule, String reason);

    /** Explicit matched-rule forwarding hook; disabled for all existing rules by default. */
    protected boolean shouldForwardMatchedRule(T rule, MockRequest request) {
        return false;
    }

    protected MockResponse forwardMatchedRule(T rule, MockRequest request) {
        throw new UnsupportedOperationException("Matched-rule forwarding is not supported");
    }

    /**
     * Asynchronous hit rule forward hook. The default is to delegate the original synchronization implementation.
     */
    protected CompletionStage<MockResponse> forwardMatchedRuleAsync(T rule, MockRequest request) {
        return CompletableFuture.completedFuture(forwardMatchedRule(rule, request));
    }
}
