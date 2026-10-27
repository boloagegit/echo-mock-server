package com.echo.pipeline;

import com.echo.config.JmsProperties;
import com.echo.core.engine.RuleSelector;
import com.echo.entity.JmsForwardTargetMode;
import com.echo.entity.JmsRule;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * MockPipeline implementation of JMS protocol
 * <p>
 * Implement JMS-specific pipeline steps:
 * <ul>
 *   <li>Candidate lookup: delegate prepared/bucketed queries to {@link JmsRuleService}.</li>
 *   <li>Rule selection: {@link RuleSelector.FallbackPolicy#EXACT_BEFORE_WILDCARD} preserves JMS's three-level fallback.</li>
 *   <li>Forwarding: delegate to {@link JmsTargetForwarder}.</li>
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
    protected List<JmsRule> findFreshCandidateRules(MockRequest request) {
        return jmsRuleService.freshCandidates(jmsProperties.getQueue(), request.getEndpointValue());
    }

    /** JMS candidate lists come from the rule snapshot's queue and bucket lists; their identity remains stable for the snapshot's lifetime. */
    @Override
    protected boolean candidateListsAreStable() {
        return true;
    }

    /**
     * JMS-specific three-level fallback:
     * <ol>
     *   <li>Level 1: a conditional rule whose conditions match.</li>
     *   <li>Level 2: an unconditional rule for the exact queue.</li>
     *   <li>Level 3: an unconditional wildcard queue {@code *} rule, as a last resort.</li>
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
    protected Supplier<CompletionStage<MockResponse>> forwardingAction(MockRequest request) {
        var prepared = targetForwarder.prepareDefaultForward(request.getBody(), null, request.getTrace());
        if (prepared.isEmpty()) return null;
        var forwarding = prepared.get();
        return () -> CompletableFuture.completedFuture(forwardedResponse(forwarding.get(), false));
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
        return rule.getMatchSpec() != null
                || (rule.getBodyCondition() != null && !rule.getBodyCondition().isBlank());
    }

    /** Read current rule fields directly; avoid a temporary ConditionSet in managed matching. */
    @Override
    protected ConditionMatcher.RuleConditions resolveMatchConditions(
            JmsRule rule, ConditionMatcher.RuleConditions reusable) {
        return resolveMatchConditions(
                reusable, rule.getMatchSpec(), rule.getBodyCondition(), null, null);
    }

    @Override
    protected List<ConditionMatcher.RuleConditions> collectBodyConditions(
            List<ConditionMatcher.RuleConditions> collected,
            JmsRule rule,
            ConditionMatcher.RuleConditions reusable) {
        return collectBodyConditions(
                collected, reusable, rule.getMatchSpec(), rule.getBodyCondition(), null, null);
    }

    @Override
    protected ConditionSet extractConditions(JmsRule rule) {
        return ConditionSet.builder()
                .bodyCondition(rule.getBodyCondition())
                .matchSpec(rule.getMatchSpec())
                .build();
    }

    @Override
    protected MatchChainEntry createMatchChainEntry(JmsRule rule, String reason) {
        return MatchChainEntry.fromJms(rule, reason);
    }
}
