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
 * 抽象 Mock Pipeline — Template Method 模式
 * <p>
 * 定義 pipeline 各步驟的執行順序，共用步驟由基底類別實作，
 * 協定特有步驟由子類別覆寫。
 *
 * @param <T> 規則類型（HttpRule 或 JmsRule）
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

    /** 請求紀錄之外的其他 listener（例如指標、Webhook）；請求紀錄永遠排在第一個。 */
    @Autowired(required = false)
    public void setServeEventListeners(List<ServeEventListener> listeners) {
        this.serveEvents = ServeEventDispatcher.of(requestLogService, listeners);
    }

    // ==================== Template Method: 主流程 ====================

    /**
     * 執行 pipeline 主流程（Template Method）
     * <p>
     * 步驟：findCandidateRules → prepareBody → matchRule → buildResponse/forward/handleNoMatch → recordLog → 回傳 PipelineResult
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
     * 執行 pipeline，並允許協定實作將慢速 I/O 以非同步方式完成。
     * Mock 回應仍在原請求執行緒直接完成，不增加額外排程成本。
     */
    public CompletionStage<PipelineResult> executeAsync(MockRequest request) {
        long startTime = System.currentTimeMillis();

        try {
            // 1. 查詢候選規則（計入 matchTimeMs）
            long matchStart = System.currentTimeMillis();
            List<T> candidates = findCandidateRules(request);

            // 2. 準備 body（若 MockRequest 已有 preparedBody 則直接使用）
            ConditionMatcher.PreparedBody preparedBody = request.getPreparedBody();
            if (preparedBody == null) {
                preparedBody = prepareBodyForMatching(request, candidates);
            }

            // 3. 匹配規則
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

                // 4a. Scenario 狀態轉移（在產生回應之前）
                ScenarioTransition scenarioTransition = advanceScenarioState(rule);
                if (scenarioTransition != null) {
                    scenarioName = scenarioTransition.name();
                    scenarioFromState = scenarioTransition.fromState();
                    scenarioNewState = scenarioTransition.toState();
                }

                // 4b. 由 responder 產生回應
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
                // 5. 無匹配：判斷是否轉發
                boolean forward = shouldForward(request);
                request.getTrace().event("ROUTE", "ruleId", null, "mode", forward ? "FORWARD" : "UNMATCHED",
                        "endpoint", request.getEndpointValue() != null ? request.getEndpointValue() : request.getPath());
                if (forward) {
                    responseStage = forwardAsync(request);
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

    // ==================== 延遲計算 ====================

    /**
     * 計算實際延遲時間。
     * 若 maxDelayMs 有設定且大於 delayMs，則在 [delayMs, maxDelayMs] 範圍內隨機取值。
     */
    public static long calculateDelay(long delayMs, Long maxDelayMs) {
        if (maxDelayMs != null && maxDelayMs > delayMs) {
            return ThreadLocalRandom.current().nextLong(delayMs, maxDelayMs + 1);
        }
        return delayMs;
    }

    /**
     * 套用規則的 Scenario 狀態轉移並回傳實際轉移結果。
     * 沒有設定新狀態時仍回傳當前狀態，供請求紀錄呈現。
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

    // ==================== 共用匹配邏輯 ====================

    /**
     * 共用匹配邏輯：依序挑選規則（見 {@link RuleSelector}），並記錄匹配鏈。
     * <p>
     * 匹配優先順序：
     * <ol>
     *   <li>Scenario 狀態不符的規則略過，並記入匹配鏈</li>
     *   <li>有條件且條件匹配成功 → 立即選中</li>
     *   <li>都不成立 → 依 {@link #fallbackPolicy()} 選無條件規則，或沒有匹配</li>
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
        return new MatchResult<>(matched, chain);
    }

    /** 沒有條件成立時如何挑選無條件規則；JMS 覆寫為非萬用優先。 */
    protected RuleSelector.FallbackPolicy fallbackPolicy() {
        return RuleSelector.FallbackPolicy.FIRST_UNCONDITIONAL;
    }

    /** 萬用規則（只在 {@link RuleSelector.FallbackPolicy#EXACT_BEFORE_WILDCARD} 時有意義）。 */
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

    /** Scenario 狀態不符時記入匹配鏈並回傳 false。 */
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
                rule.getId(), "scenario_state_mismatch",
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
     * 依候選規則條件的需求準備 body（§4.5）：沒有條件讀 body 時不解析；XML 且條件都能串流時只掃描一次、不建立 DOM；
     * 其他情況照常解析。HTTP 與 JMS 用同一套規則。
     */
    public ConditionMatcher.PreparedBody prepareBodyForMatching(MockRequest request, List<T> candidates) {
        if (candidateListsAreStable()) {
            // 合併 2,000 條規則的需求約 0.7 ms；候選清單來自規則快照、物件固定，因此依物件快取
            return conditionMatcher.prepareBody(request.getBody(),
                    bodyRequirementsByCandidates.get(candidates, this::bodyRequirementsOf));
        }
        return prepareBody(request.getBody(), candidates);
    }

    /**
     * 候選清單是否來自規則快照、在快照存在期間是同一個物件（JMS 的 queue 與分桶清單）。
     * 是的話合併後的 body 需求依清單物件快取；HTTP 的候選清單依 path 每次重新篩選，不快取。
     */
    protected boolean candidateListsAreStable() {
        return false;
    }

    /** 以清單物件本身為 key（weakKeys 用 identity 比較），快照淘汰後隨清單一起回收。 */
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

    /** 會讀 body 的啟用規則條件；只有 query／header 條件的規則不必查詢需求。 */
    private List<ConditionMatcher.RuleConditions> bodyConditionsOf(List<T> candidates) {
        List<ConditionMatcher.RuleConditions> conditions = null;
        for (T rule : candidates) {
            if (Boolean.FALSE.equals(rule.getEnabled())) {
                continue;
            }
            ConditionSet set = extractConditions(rule);
            String bodyCondition = set.getBodyCondition();
            // 只有 query／header 條件的規則不讀 body，不必查詢它們的需求
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

    // ==================== 共用實作方法 ====================

    /**
     * 從 Response 表查詢回應內容
     */
    protected String resolveResponseBody(Long responseId) {
        if (responseId == null) {
            return "";
        }
        return ruleService.findResponseBodyById(responseId).orElse("");
    }

    /**
     * 執行延遲（同步 Thread.sleep，供 JMS pipeline 使用）
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

    /** 把處理結果交給 listener（請求紀錄為 critical，失敗時往上傳）。 */
    protected void publish(ServeEvent event) {
        serveEvents.dispatch(event);
    }

    // ==================== Responder ====================

    /**
     * 依序挑選適用的 responder；預設為故障注入 → 命中規則轉發 → mock。
     * 子類別可覆寫 {@link #responders()} 加入協定特有的回應方式。
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

    /** 故障注入：EMPTY_RESPONSE 回空 body；CONNECTION_RESET 由協定在寫回時關閉連線。 */
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
            MockResponse response = buildResponse(rule, request, resolveResponseBody(rule.getResponseId()));
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

    /** 規則動作為轉發（見 {@link #shouldForwardMatchedRule}）。 */
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

    /** 回傳規則設定的回應內容（含協定特有的模板渲染等，見 {@link #buildResponse}）。 */
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
            return new ResponsePlan.Content(buildResponse(rule, request, resolveResponseBody(rule.getResponseId())));
        }
    }

    // ==================== 抽象方法：由子類別實作 ====================

    /** 查詢候選規則 */
    protected abstract List<T> findCandidateRules(MockRequest request);

    /** 建構回應（含模板渲染等協定特有邏輯） */
    protected abstract MockResponse buildResponse(T rule, MockRequest request, String responseBody);

    /** 執行轉發 */
    protected abstract MockResponse forward(MockRequest request);

    /**
     * 非同步轉發 hook。未覆寫的協定維持原有同步行為。
     */
    protected CompletionStage<MockResponse> forwardAsync(MockRequest request) {
        return CompletableFuture.completedFuture(forward(request));
    }

    /** 判斷是否應轉發 */
    protected abstract boolean shouldForward(MockRequest request);

    /** 處理無匹配情況 */
    protected abstract MockResponse handleNoMatch(MockRequest request);

    /** 判斷規則是否有條件 */
    protected abstract boolean hasCondition(T rule);

    /** 提取規則的條件集合 */
    protected abstract ConditionSet extractConditions(T rule);

    /** 建立匹配鏈條目 */
    protected abstract MatchChainEntry createMatchChainEntry(T rule, String reason);

    /** Explicit matched-rule forwarding hook; disabled for all existing rules by default. */
    protected boolean shouldForwardMatchedRule(T rule, MockRequest request) {
        return false;
    }

    protected MockResponse forwardMatchedRule(T rule, MockRequest request) {
        throw new UnsupportedOperationException("Matched-rule forwarding is not supported");
    }

    /**
     * 非同步的命中規則轉發 hook。預設委派原有同步實作。
     */
    protected CompletionStage<MockResponse> forwardMatchedRuleAsync(T rule, MockRequest request) {
        return CompletableFuture.completedFuture(forwardMatchedRule(rule, request));
    }
}
