package com.echo.pipeline;

import com.echo.config.JmsProperties;
import com.echo.entity.JmsRule;
import com.echo.jms.JmsTargetForwarder;
import com.echo.service.ConditionMatcher;
import com.echo.service.JmsRuleService;
import com.echo.service.MatchChainEntry;
import com.echo.service.MatchResult;
import com.echo.service.RequestLogService;
import com.echo.service.RuleService;
import com.echo.service.ScenarioService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * JMS 協定的 MockPipeline 實作
 * <p>
 * 實作 JMS 特有的 pipeline 步驟：
 * <ul>
 *   <li>候選規則查詢：委派給 {@link JmsRuleService} 的 prepared/bucketed 查詢</li>
 *   <li>匹配規則：覆寫 matchRule() 保留 JMS 三層 fallback 邏輯</li>
 *   <li>轉發：委派給 {@link JmsTargetForwarder}</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "echo.jms.enabled", havingValue = "true")
@Slf4j
public class JmsMockPipeline extends AbstractMockPipeline<JmsRule> {

    private final JmsRuleService jmsRuleService;
    private final JmsTargetForwarder targetForwarder;
    private final JmsProperties jmsProperties;

    public JmsMockPipeline(ConditionMatcher conditionMatcher,
                           RuleService ruleService,
                           RequestLogService requestLogService,
                           JmsRuleService jmsRuleService,
                           JmsTargetForwarder targetForwarder,
                           JmsProperties jmsProperties,
                           ScenarioService scenarioService) {
        super(conditionMatcher, ruleService, requestLogService, scenarioService);
        this.jmsRuleService = jmsRuleService;
        this.targetForwarder = targetForwarder;
        this.jmsProperties = jmsProperties;
    }

    @Override
    protected List<JmsRule> findCandidateRules(MockRequest request) {
        String queueName = jmsProperties.getQueue();
        String endpointValue = request.getEndpointValue();

        if (endpointValue != null && !endpointValue.isBlank()) {
            return jmsRuleService.findBucketedJmsRules(queueName, endpointValue);
        }
        return jmsRuleService.findPreparedJmsRules(queueName);
    }

    /**
     * 覆寫匹配邏輯，保留 JMS 特有的三層 fallback：
     * <ol>
     *   <li>Layer 1: 有條件且條件匹配成功的精確 queue 規則</li>
     *   <li>Layer 2: 無條件的精確 queue 規則（fallback）</li>
     *   <li>Layer 3: wildcard queue {@code *} 規則（last resort）</li>
     * </ol>
     */
    @Override
    public MatchResult<JmsRule> matchRule(List<JmsRule> candidates,
                                          ConditionMatcher.PreparedBody prepared,
                                          String queryString,
                                          Map<String, String> headers) {
        JmsRule matched = null;
        JmsRule noConditionRule = null;
        JmsRule wildcardRule = null;
        List<MatchChainEntry> chain = new ArrayList<>();

        for (JmsRule rule : candidates) {
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

            boolean isWildcard = "*".equals(rule.getQueueName());
            boolean hasCondition = rule.getBodyCondition() != null
                    && !rule.getBodyCondition().isBlank();

            if (hasCondition) {
                if (conditionMatcher.matchesPrepared(
                        rule.getBodyCondition(), null, null,
                        prepared, null, null)) {
                    matched = rule;
                    break;
                }
            } else if (isWildcard) {
                if (wildcardRule == null) {
                    wildcardRule = rule;
                }
            } else {
                if (noConditionRule == null) {
                    noConditionRule = rule;
                }
            }
        }

        if (matched == null) {
            matched = noConditionRule != null ? noConditionRule : wildcardRule;
        }

        if (matched != null) {
            chain.add(MatchChainEntry.fromJms(matched, "match"));
        }

        return new MatchResult<>(matched, chain);
    }

    @Override
    protected MockResponse forward(MockRequest request) {
        JmsProperties.Target target = jmsProperties.getTarget();
        log.info("No rule matched, forwarding to target JMS: {} queue: {}",
                target.getServerUrl(), target.getQueue());

        String responseBody = targetForwarder.forward(request.getBody(), null);

        String proxyError = null;
        if (responseBody != null && responseBody.contains("<error>")) {
            proxyError = responseBody;
        }

        return MockResponse.builder()
                .status(200)
                .body(responseBody)
                .matched(false)
                .forwarded(true)
                .proxyError(proxyError)
                .build();
    }

    @Override
    protected boolean shouldForward(MockRequest request) {
        JmsProperties.Target target = jmsProperties.getTarget();
        return target.isEnabled()
                && target.getServerUrl() != null
                && !target.getServerUrl().isBlank();
    }

    @Override
    protected MockResponse handleNoMatch(MockRequest request) {
        String queueName = jmsProperties.getQueue();
        log.warn("No JMS rule matched for queue: {}", queueName);

        return MockResponse.builder()
                .status(200)
                .body("<error>No mock rule found for queue: " + queueName + "</error>")
                .matched(false)
                .forwarded(false)
                .build();
    }

    @Override
    protected MockResponse buildResponse(JmsRule rule, MockRequest request, String responseBody) {
        return MockResponse.builder()
                .status(200)
                .body(responseBody)
                .matched(true)
                .forwarded(false)
                .build();
    }

    @Override
    protected boolean hasCondition(JmsRule rule) {
        return rule.getBodyCondition() != null && !rule.getBodyCondition().isBlank();
    }

    @Override
    protected ConditionSet extractConditions(JmsRule rule) {
        return ConditionSet.builder()
                .bodyCondition(rule.getBodyCondition())
                .build();
    }

    @Override
    protected MatchChainEntry createMatchChainEntry(JmsRule rule, String reason) {
        return MatchChainEntry.fromJms(rule, reason);
    }
}
