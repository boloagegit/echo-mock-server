package com.echo.pipeline;

import com.echo.entity.BaseRule;
import com.echo.entity.FaultType;
import com.echo.entity.Protocol;
import com.echo.service.ConditionMatcher;
import com.echo.service.MatchChainEntry;
import com.echo.service.MatchDescriptionBuilder;
import com.echo.service.MatchResult;
import com.echo.service.RequestLogService;
import com.echo.service.RuleService;
import com.echo.service.ScenarioService;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
        long startTime = System.currentTimeMillis();

        try {
            // 1. 查詢候選規則（計入 matchTimeMs）
            long matchStart = System.currentTimeMillis();
            List<T> candidates = findCandidateRules(request);

            // 2. 準備 body（若 MockRequest 已有 preparedBody 則直接使用）
            ConditionMatcher.PreparedBody preparedBody = request.getPreparedBody();
            if (preparedBody == null) {
                preparedBody = conditionMatcher.prepareBody(request.getBody());
            }

            // 3. 匹配規則
            MatchResult<T> matchResult = matchRule(candidates, preparedBody,
                    request.getQueryString(), request.getHeaders());
            int matchTimeMs = (int) (System.currentTimeMillis() - matchStart);

            String matchChainJson = MatchDescriptionBuilder.toMatchChainJson(
                    matchResult.getMatchChain(), matchResult.isMatched());

            MockResponse response;
            String ruleId = null;
            long delayMs = 0;
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
                if (rule.getScenarioName() != null && !rule.getScenarioName().isBlank()
                        && rule.getNewScenarioState() != null && !rule.getNewScenarioState().isBlank()) {
                    String actualState = scenarioService.getCurrentState(rule.getScenarioName());
                    scenarioFromState = actualState;
                    String expectedState = rule.getRequiredScenarioState();
                    if (expectedState == null || expectedState.isBlank()) {
                        expectedState = actualState;
                    }
                    scenarioService.advanceState(rule.getScenarioName(), expectedState, rule.getNewScenarioState());
                    scenarioName = rule.getScenarioName();
                    scenarioNewState = rule.getNewScenarioState();
                }

                // 4b. 解析回應內容
                String responseBody = resolveResponseBody(rule.getResponseId());

                // 4c. 建構回應（子類別實作，含模板渲染等）
                response = buildResponse(rule, request, responseBody);

                // 4d. 故障注入處理
                FaultType faultType = rule.getFaultType() != null ? rule.getFaultType() : FaultType.NONE;
                if (faultType == FaultType.EMPTY_RESPONSE) {
                    response = MockResponse.builder()
                            .status(response.getStatus())
                            .body("")
                            .matched(true)
                            .forwarded(false)
                            .build();
                }
                if (faultType != FaultType.NONE) {
                    faultTypeName = faultType.name();
                }
            } else {
                // 5. 無匹配：判斷是否轉發
                if (shouldForward(request)) {
                    response = forward(request);
                } else {
                    response = handleNoMatch(request);
                }
            }

            // 6. 記錄日誌
            int responseTimeMs = (int) (System.currentTimeMillis() - startTime);

            // proxy status: 轉發成功時為 response status，轉發失敗（有 proxyError）時為 null
            Integer proxyStatus = null;
            if (response.isForwarded() && response.getProxyError() == null) {
                proxyStatus = response.getStatus();
            }

            // targetHost: 匹配成功或轉發時傳入，handleNoMatch 時傳 null（與現有行為一致）
            String logTargetHost = (matchResult.isMatched() || response.isForwarded())
                    ? request.getTargetHost() : null;

            recordLog(
                    ruleId,
                    request.getProtocol(),
                    request.getMethod(),
                    request.getPath(),
                    matchResult.isMatched(),
                    responseTimeMs,
                    request.getClientIp(),
                    matchChainJson,
                    logTargetHost,
                    proxyStatus,
                    response.getProxyError(),
                    response.getStatus(),
                    matchTimeMs,
                    request.getBody(),
                    response.getBody(),
                    candidates,
                    preparedBody,
                    request.getQueryString(),
                    request.getHeaders(),
                    faultTypeName,
                    scenarioName,
                    scenarioFromState,
                    scenarioNewState
            );

            // 7. 回傳 PipelineResult
            return PipelineResult.builder()
                    .response(response)
                    .ruleId(ruleId)
                    .matched(matchResult.isMatched())
                    .matchTimeMs(matchTimeMs)
                    .responseTimeMs(responseTimeMs)
                    .matchChainJson(matchChainJson)
                    .delayMs(delayMs)
                    .faultType(faultTypeName)
                    .scenarioName(scenarioName)
                    .scenarioNewState(scenarioNewState)
                    .build();

        } catch (Exception e) {
            log.error("Pipeline execution error: {}", e.getMessage(), e);
            int responseTimeMs = (int) (System.currentTimeMillis() - startTime);
            MockResponse errorResponse = MockResponse.builder()
                    .status(500)
                    .body("Pipeline error: " + e.getMessage())
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

    // ==================== 抽象方法：由子類別實作 ====================

    /** 查詢候選規則 */
    protected abstract List<T> findCandidateRules(MockRequest request);

    /** 建構回應（含模板渲染等協定特有邏輯） */
    protected abstract MockResponse buildResponse(T rule, MockRequest request, String responseBody);

    /** 執行轉發 */
    protected abstract MockResponse forward(MockRequest request);

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
}
