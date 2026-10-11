package com.echo.pipeline;

import com.echo.config.JmsProperties;
import com.echo.core.engine.RuleSelector;
import com.echo.entity.JmsRule;
import com.echo.entity.JmsForwardTargetMode;
import com.echo.entity.JmsRuleAction;
import com.echo.jms.JmsTargetForwarder;
import com.echo.service.ConditionMatcher;
import com.echo.service.JmsRuleService;
import com.echo.service.MatchChainEntry;
import com.echo.service.RequestLogService;
import com.echo.service.RuleService;
import com.echo.service.ScenarioService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * JMS 協定的 MockPipeline 實作
 * <p>
 * 實作 JMS 特有的 pipeline 步驟：
 * <ul>
 *   <li>候選規則查詢：委派給 {@link JmsRuleService} 的 prepared/bucketed 查詢</li>
 *   <li>匹配規則：以 {@link RuleSelector.FallbackPolicy#EXACT_BEFORE_WILDCARD} 保留 JMS 三層 fallback</li>
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

    @Override
    protected ConditionMatcher.PreparedBody prepareBody(
            MockRequest request, List<JmsRule> candidates) {
        List<String> bodyConditions = candidates.stream()
                .filter(rule -> !Boolean.FALSE.equals(rule.getEnabled()))
                .map(JmsRule::getBodyCondition)
                .filter(condition -> condition != null && !condition.isBlank())
                .toList();
        return conditionMatcher.prepareBodyForConditions(request.getBody(), bodyConditions);
    }

    /**
     * JMS 特有的三層 fallback：
     * <ol>
     *   <li>Layer 1: 有條件且條件匹配成功的規則</li>
     *   <li>Layer 2: 無條件的精確 queue 規則</li>
     *   <li>Layer 3: 無條件的 wildcard queue {@code *} 規則（last resort）</li>
     * </ol>
     */
    @Override
    protected RuleSelector.FallbackPolicy fallbackPolicy() {
        return RuleSelector.FallbackPolicy.EXACT_BEFORE_WILDCARD;
    }

    @Override
    protected boolean isWildcard(JmsRule rule) {
        return "*".equals(rule.getQueueName());
    }

    @Override
    protected MockResponse forward(MockRequest request) {
        log.debug("No rule matched, forwarding through the selected outbound JMS connection");

        return forwardedResponse(
                request.getTrace().enabled()
                        ? targetForwarder.forwardWithMetadata(request.getBody(), null, request.getTrace())
                        : targetForwarder.forwardWithMetadata(request.getBody(), null), false);
    }

    @Override
    protected boolean shouldForwardMatchedRule(JmsRule rule, MockRequest request) {
        return JmsRuleAction.FORWARD.equals(rule.getAction());
    }

    @Override
    protected MockResponse forwardMatchedRule(JmsRule rule, MockRequest request) {
        JmsForwardTargetMode mode = rule.getForwardTargetMode() == null
                ? JmsForwardTargetMode.DEFAULT_CONNECTION : rule.getForwardTargetMode();
        JmsTargetForwarder.ForwardResult result = request.getTrace().enabled()
                ? targetForwarder.forwardWithMetadata(request.getBody(), null, rule.getJmsTargetConnectionId(),
                        mode == JmsForwardTargetMode.DEFAULT_CONNECTION, request.getTrace())
                : targetForwarder.forwardWithMetadata(request.getBody(), null, rule.getJmsTargetConnectionId(),
                        mode == JmsForwardTargetMode.DEFAULT_CONNECTION);
        return forwardedResponse(result, true);
    }

    private MockResponse forwardedResponse(JmsTargetForwarder.ForwardResult result,
                                           boolean matched) {
        String responseBody = result.body();

        String proxyError = null;
        if (responseBody != null && responseBody.contains("<error>")) {
            proxyError = responseBody;
        }

        return MockResponse.builder()
                .status(200)
                .body(responseBody)
                .matched(matched)
                .forwarded(true)
                .forwardTarget(result.target())
                .proxyError(proxyError)
                .build();
    }

    @Override
    protected boolean shouldForward(MockRequest request) {
        return targetForwarder.hasActiveTarget();
    }

    @Override
    protected MockResponse handleNoMatch(MockRequest request) {
        String queueName = jmsProperties.getQueue();
        log.debug("No JMS rule matched for queue: {}", queueName);

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
