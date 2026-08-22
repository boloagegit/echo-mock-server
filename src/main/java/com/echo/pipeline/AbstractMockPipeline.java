package com.echo.pipeline;

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
import com.echo.util.CancellableStages;
import com.echo.service.ScenarioService;
import lombok.extern.slf4j.Slf4j;

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

    protected AbstractMockPipeline(ConditionMatcher conditionMatcher,
                                   RuleService ruleService,
                                   RequestLogService requestLogService,
                                   ScenarioService scenarioService) {
        this.conditionMatcher = conditionMatcher;
        this.ruleService = ruleService;
        this.requestLogService = requestLogService;
        this.scenarioService = scenarioService;
    }

    // ==================== Template Method: 主流程 ====================

    /**
     * 執行 pipeline 主流程（Template Method）
     * <p>
     * 步驟：findCandidateRules → prepareBody → matchRule → buildResponse/forward/handleNoMatch → recordLog → 回傳 PipelineResult
     */
    public PipelineResult execute(MockRequest request) {
        return executeAsync(request).toCompletableFuture().join();
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
                preparedBody = requiresBodyParsing(candidates)
                        ? prepareBody(request, candidates)
                        : ConditionMatcher.PreparedBody.rawOnly(request.getBody());
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
                ruleId = rule.getId();
                delayMs = calculateDelay(
                    rule.getDelayMs() != null ? rule.getDelayMs() : 0,
                    rule.getMaxDelayMs()
                );

                // 4a. Scenario 狀態轉移（在 buildResponse 之前）
                ScenarioTransition scenarioTransition = advanceScenarioState(rule);
                if (scenarioTransition != null) {
                    scenarioName = scenarioTransition.name();
                    scenarioFromState = scenarioTransition.fromState();
                    scenarioNewState = scenarioTransition.toState();
                }

                FaultType faultType = rule.getFaultType() != null ? rule.getFaultType() : FaultType.NONE;
                if (faultType != FaultType.NONE) {
                    String responseBody = resolveResponseBody(rule.getResponseId());
                    immediateResponse = buildResponse(rule, request, responseBody);
                    faultTypeName = faultType.name();
                    if (faultType == FaultType.EMPTY_RESPONSE) {
                        immediateResponse = MockResponse.builder()
                            .status(immediateResponse.getStatus())
                            .body("")
                            .matched(true)
                            .forwarded(false)
                            .build();
                    }
                } else if (shouldForwardMatchedRule(rule, request)) {
                    responseStage = forwardMatchedRuleAsync(rule, request);
                } else {
                    // 4b. 解析回應內容
                    String responseBody = resolveResponseBody(rule.getResponseId());

                    // 4c. 建構回應（子類別實作，含模板渲染等）
                    immediateResponse = buildResponse(rule, request, responseBody);
                }
            } else {
                ruleId = null;
                delayMs = 0;
                // 5. 無匹配：判斷是否轉發
                if (shouldForward(request)) {
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
                    return pipelineError(startTime, unwrap(error));
                }
                try {
                    return completeResult(request, startTime, candidates, finalPreparedBody,
                            matchResult, matchTimeMs, matchChainJson,
                            finalRuleId, finalDelayMs, response,
                            finalFaultTypeName, finalScenarioName,
                            finalScenarioFromState, finalScenarioNewState);
                } catch (Exception e) {
                    return pipelineError(startTime, e);
                }
            });

        } catch (Exception e) {
            return CompletableFuture.completedFuture(pipelineError(startTime, e));
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
        recordLog(ruleId, request.getProtocol(), request.getMethod(), request.getPath(),
                matchResult.isMatched(), responseTimeMs, request.getClientIp(), matchChainJson,
                logTargetHost, response.isForwarded(), response.getForwardTarget(),
                proxyStatus, response.getProxyError(), loggedResponseStatus,
                matchTimeMs, request.getBody(), response.getBody(), candidates, preparedBody,
                request.getQueryString(), request.getHeaders(), faultType,
                scenarioName, scenarioFromState, scenarioToState);

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

    private PipelineResult pipelineError(long startTime, Throwable error) {
        log.error("Pipeline execution error: {}", error.getMessage(), error);
        int responseTimeMs = (int) (System.currentTimeMillis() - startTime);
        boolean logUnavailable = error instanceof RequestLogUnavailableException;
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
    static long calculateDelay(long delayMs, Long maxDelayMs) {
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
     * 共用匹配邏輯：遍歷候選規則，跳過 disabled，條件匹配或 fallback
     * <p>
     * 匹配優先順序：
     * <ol>
     *   <li>有條件且條件匹配成功 → 立即回傳</li>
     *   <li>無條件 → 記為 fallback（第一個）</li>
     *   <li>遍歷完畢 → 回傳 fallback 或空 MatchResult</li>
     * </ol>
     */
    public MatchResult<T> matchRule(List<T> candidates,
                                    ConditionMatcher.PreparedBody prepared,
                                    String queryString,
                                    Map<String, String> headers) {
        T matched = null;
        T fallbackRule = null;
        List<MatchChainEntry> chain = new ArrayList<>();

        for (T rule : candidates) {
            if (Boolean.FALSE.equals(rule.getEnabled())) {
                continue;
            }

            // Scenario 狀態檢查
            if (rule.getScenarioName() != null && !rule.getScenarioName().isBlank()
                    && rule.getRequiredScenarioState() != null && !rule.getRequiredScenarioState().isBlank()) {
                String currentState = scenarioService.getCurrentState(rule.getScenarioName());
                if (!rule.getRequiredScenarioState().equals(currentState)) {
                    chain.add(new MatchChainEntry(
                            rule.getId(), "scenario_state_mismatch",
                            null, rule.getDescription(), null,
                            null,
                            "scenario: " + rule.getScenarioName()
                                    + " (required: " + rule.getRequiredScenarioState()
                                    + ", current: " + currentState + ")",
                            false));
                    continue;
                }
            }

            if (hasCondition(rule)) {
                ConditionSet conditions = extractConditions(rule);
                if (conditionMatcher.matchesPrepared(
                        conditions.getBodyCondition(),
                        conditions.getQueryCondition(),
                        conditions.getHeaderCondition(),
                        prepared, queryString, headers)) {
                    matched = rule;
                    chain.add(createMatchChainEntry(rule, "match"));
                    break;
                }
            } else {
                if (fallbackRule == null) {
                    fallbackRule = rule;
                }
            }
        }

        if (matched == null && fallbackRule != null) {
            matched = fallbackRule;
            chain.add(createMatchChainEntry(fallbackRule, "match"));
        }

        return new MatchResult<>(matched, chain);
    }

    /** JSON/XML 只在至少一條啟用候選規則真的使用 body 條件時解析。 */
    private boolean requiresBodyParsing(List<T> candidates) {
        for (T rule : candidates) {
            if (Boolean.FALSE.equals(rule.getEnabled())) {
                continue;
            }
            String bodyCondition = extractConditions(rule).getBodyCondition();
            if (bodyCondition != null && !bodyCondition.isBlank()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 協定可覆寫 body 準備策略；HTTP 維持既有完整解析，JMS 可依候選條件走串流路徑。
     */
    protected ConditionMatcher.PreparedBody prepareBody(MockRequest request, List<T> candidates) {
        return conditionMatcher.prepareBody(request.getBody());
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

    /**
     * 記錄請求日誌，呼叫 RequestLogService.record() 並傳入所有參數（含匹配上下文）
     */
    protected void recordLog(String ruleId, Protocol protocol, String method,
                             String endpoint, boolean matched, int responseTimeMs,
                             String clientIp, String matchChainJson, String targetHost,
                             Integer proxyStatus, String proxyError,
                             Integer responseStatus, Integer matchTimeMs,
                             String requestBody, String responseBody,
                             List<T> candidates,
                             ConditionMatcher.PreparedBody preparedBody,
                             String queryString,
                             Map<String, String> headers,
                             String faultType,
                             String scenarioName,
                             String scenarioFromState,
                             String scenarioToState) {
        requestLogService.record(ruleId, protocol, method, endpoint,
                matched, responseTimeMs, clientIp, matchChainJson, targetHost,
                proxyStatus, proxyError, responseStatus, matchTimeMs,
                requestBody, responseBody,
                candidates, preparedBody, queryString, headers, faultType,
                scenarioName, scenarioFromState, scenarioToState);
    }

    /** 記錄包含明確轉發狀態與安全目標資訊的請求日誌。 */
    protected void recordLog(String ruleId, Protocol protocol, String method,
                             String endpoint, boolean matched, int responseTimeMs,
                             String clientIp, String matchChainJson, String targetHost,
                             boolean forwarded, String forwardTarget,
                             Integer proxyStatus, String proxyError,
                             Integer responseStatus, Integer matchTimeMs,
                             String requestBody, String responseBody,
                             List<T> candidates,
                             ConditionMatcher.PreparedBody preparedBody,
                             String queryString,
                             Map<String, String> headers,
                             String faultType,
                             String scenarioName,
                             String scenarioFromState,
                             String scenarioToState) {
        requestLogService.record(ruleId, protocol, method, endpoint,
                matched, responseTimeMs, clientIp, matchChainJson, targetHost,
                forwarded, forwardTarget,
                proxyStatus, proxyError, responseStatus, matchTimeMs,
                requestBody, responseBody,
                candidates, preparedBody, queryString, headers, faultType,
                scenarioName, scenarioFromState, scenarioToState);
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
