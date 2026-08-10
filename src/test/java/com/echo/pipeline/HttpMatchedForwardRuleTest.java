package com.echo.pipeline;

import com.echo.entity.HttpForwardTargetMode;
import com.echo.entity.HttpRule;
import com.echo.entity.HttpRuleAction;
import com.echo.entity.Protocol;
import com.echo.service.ConditionMatcher;
import com.echo.service.HttpOutboundForwarder;
import com.echo.service.HttpRuleService;
import com.echo.service.RequestLogService;
import com.echo.service.ResponseTemplateService;
import com.echo.service.RuleService;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HttpMatchedForwardRuleTest {

    @Test
    void explicitForwardRuleUsesSelectedConnectionWithoutResolvingMockResponse() {
        ConditionMatcher matcher = mock(ConditionMatcher.class);
        RuleService ruleService = mock(RuleService.class);
        RequestLogService logService = mock(RequestLogService.class);
        HttpRuleService httpRuleService = mock(HttpRuleService.class);
        HttpOutboundForwarder forwarder = mock(HttpOutboundForwarder.class);
        HttpMockPipeline pipeline = new HttpMockPipeline(matcher, ruleService, logService,
                httpRuleService, mock(ResponseTemplateService.class), mock(RestTemplate.class), forwarder);
        HttpRule rule = HttpRule.builder().id("forward-1").enabled(true).matchKey("/orders")
                .method("GET").priority(0).delayMs(0L).action(HttpRuleAction.FORWARD)
                .forwardTargetMode(HttpForwardTargetMode.CONNECTION).httpTargetConnectionId(8L).build();
        MockRequest request = MockRequest.builder().protocol(Protocol.HTTP).method("GET")
                .path("/orders").targetHost("default").headers(Map.of()).body("").build();
        when(httpRuleService.findPreparedHttpRules("default", "/orders", "GET"))
                .thenReturn(List.of(rule));
        when(matcher.prepareBody("")).thenReturn(ConditionMatcher.PreparedBody.empty());
        MockResponse forwarded = MockResponse.builder()
                .status(200).body("forwarded").matched(true).forwarded(true).build();
        when(forwarder.forwardAsync(request, 8L, false))
                .thenReturn(CompletableFuture.completedFuture(forwarded));

        PipelineResult result = pipeline.execute(request);

        assertThat(result.isMatched()).isTrue();
        assertThat(result.getResponse().getBody()).isEqualTo("forwarded");
        verify(forwarder).forwardAsync(request, 8L, false);
        verify(ruleService, never()).findResponseBodyById(null);
    }
}
