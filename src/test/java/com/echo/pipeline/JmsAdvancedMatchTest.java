package com.echo.pipeline;

import com.echo.config.JmsProperties;
import com.echo.entity.JmsRule;
import com.echo.entity.Protocol;
import com.echo.jms.JmsTargetForwarder;
import com.echo.service.ConditionMatcher;
import com.echo.service.JmsRuleService;
import com.echo.service.RequestLogService;
import com.echo.service.RuleService;
import com.echo.service.ScenarioService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Structured conditions of the JMS rule: Simple XPath also performs stream scanning (DOM is not created), and selects rules based on the conditions. */
class JmsAdvancedMatchTest {

    private static final String SPEC = """
            {"any": [
              {"source": "body", "selector": {"xpath": "//Amount"}, "op": "gte", "value": 1000},
              {"source": "body", "selector": {"xpath": "//Vip"}, "op": "exists"}
            ]}""";

    private final ConditionMatcher conditionMatcher = spy(new ConditionMatcher());
    private final JmsRuleService jmsRuleService = mock(JmsRuleService.class);
    private final RuleService ruleService = mock(RuleService.class);
    private final JmsProperties jmsProperties = mock(JmsProperties.class);
    private final JmsMockPipeline pipeline = new JmsMockPipeline(conditionMatcher, ruleService,
            mock(RequestLogService.class), jmsRuleService, mock(JmsTargetForwarder.class), jmsProperties,
            mock(ScenarioService.class));

    private static JmsRule rule(String id, String matchSpec, long responseId) {
        return JmsRule.builder().id(id).enabled(true).queueName("ORDER.Q").matchSpec(matchSpec)
                .responseId(responseId).priority(0).delayMs(0L).build();
    }

    private String respond(String body) {
        return pipeline.execute(MockRequest.builder().protocol(Protocol.JMS).path("ORDER.Q").body(body)
                .clientIp("JMS").build()).getResponse().getBody();
    }

    @Test
    void advancedCandidatesWithSimpleXPathAreStreamedAndSelectByCondition() {
        when(jmsProperties.getQueue()).thenReturn("ORDER.Q");
        when(jmsRuleService.findPreparedJmsRules("ORDER.Q"))
                .thenReturn(List.of(rule("big", SPEC, 1L), rule("default", null, 2L)));
        when(ruleService.findResponseBodyById(1L)).thenReturn(Optional.of("<big/>"));
        when(ruleService.findResponseBodyById(2L)).thenReturn(Optional.of("<default/>"));

        assertThat(respond("<Order><Amount>1500</Amount></Order>")).isEqualTo("<big/>");
        assertThat(respond("<Order><Amount>5</Amount><Vip/></Order>")).isEqualTo("<big/>");
        assertThat(respond("<Order><Amount>5</Amount></Order>")).isEqualTo("<default/>");

        // Stream scan does not call full parsing
        verify(conditionMatcher, never()).prepareBody(anyString());
    }

    @Test
    void legacyOnlyCandidatesKeepTheStreamingPath() {
        when(jmsProperties.getQueue()).thenReturn("ORDER.Q");
        JmsRule legacy = JmsRule.builder().id("legacy").enabled(true).queueName("ORDER.Q")
                .bodyCondition("Amount=5").responseId(1L).priority(0).delayMs(0L).build();
        when(jmsRuleService.findPreparedJmsRules("ORDER.Q")).thenReturn(List.of(legacy));
        when(ruleService.findResponseBodyById(1L)).thenReturn(Optional.of("<legacy/>"));

        assertThat(respond("<Order><Amount>5</Amount></Order>")).isEqualTo("<legacy/>");
        verify(conditionMatcher, never()).prepareBody(anyString());
    }
}
