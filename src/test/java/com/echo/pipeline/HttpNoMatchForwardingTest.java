package com.echo.pipeline;

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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HttpNoMatchForwardingTest {

    @Test
    void noMatchUsesDefaultConnectionBeforeOriginalHost() {
        HttpOutboundForwarder forwarder = mock(HttpOutboundForwarder.class);
        HttpMockPipeline pipeline = pipeline(forwarder, mock(RestTemplate.class));
        MockRequest request = request("legacy.internal");
        MockResponse forwarded = MockResponse.builder().status(200).body("default")
                .matched(false).forwarded(true).build();
        when(forwarder.forwardDefaultAsync(request))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(forwarded)));

        PipelineResult result = pipeline.execute(request);

        assertThat(result.isMatched()).isFalse();
        assertThat(result.getResponse()).isSameAs(forwarded);
        verify(forwarder).forwardDefaultAsync(request);
    }

    @Test
    void noMatchWithoutDefaultFallsBackToOriginalHost() {
        HttpOutboundForwarder forwarder = mock(HttpOutboundForwarder.class);
        RestTemplate restTemplate = mock(RestTemplate.class);
        HttpMockPipeline pipeline = pipeline(forwarder, restTemplate);
        MockRequest request = request("legacy.internal");
        when(forwarder.forwardDefaultAsync(request))
                .thenReturn(CompletableFuture.completedFuture(Optional.empty()));
        MockResponse legacy = MockResponse.builder().status(200).body("legacy")
                .matched(false).forwarded(true).build();
        when(forwarder.forwardOriginalHostAsync(request, false))
                .thenReturn(CompletableFuture.completedFuture(legacy));

        PipelineResult result = pipeline.execute(request);

        assertThat(result.getResponse().getBody()).isEqualTo("legacy");
        assertThat(result.getResponse().isForwarded()).isTrue();
        verify(forwarder).forwardOriginalHostAsync(request, false);
    }

    @Test
    void noMatchWithoutAnyDestinationReturnsNotFound() {
        HttpOutboundForwarder forwarder = mock(HttpOutboundForwarder.class);
        RestTemplate restTemplate = mock(RestTemplate.class);
        HttpMockPipeline pipeline = pipeline(forwarder, restTemplate);
        MockRequest request = request("default");
        when(forwarder.forwardDefaultAsync(request))
                .thenReturn(CompletableFuture.completedFuture(Optional.empty()));

        PipelineResult result = pipeline.execute(request);

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(result.getResponse().isForwarded()).isFalse();
        verify(restTemplate, never()).exchange(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(String.class));
    }

    @Test
    void defaultConnectionFailureRemainsUnmatched() {
        HttpOutboundForwarder forwarder = mock(HttpOutboundForwarder.class);
        HttpMockPipeline pipeline = pipeline(forwarder, mock(RestTemplate.class));
        MockRequest request = request("default");
        MockResponse failedForward = MockResponse.builder().status(502)
                .body("Proxy error: timeout").matched(false).forwarded(true).build();
        when(forwarder.forwardDefaultAsync(request))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(failedForward)));

        PipelineResult result = pipeline.execute(request);

        assertThat(result.isMatched()).isFalse();
        assertThat(result.getResponse().getStatus()).isEqualTo(502);
        assertThat(result.getResponse().isForwarded()).isTrue();
    }

    @Test
    void cancellationReachesActiveOriginalHostForward() {
        HttpOutboundForwarder forwarder = mock(HttpOutboundForwarder.class);
        HttpMockPipeline pipeline = pipeline(forwarder, mock(RestTemplate.class));
        MockRequest request = request("legacy.internal");
        CompletableFuture<MockResponse> activeForward = new CompletableFuture<>();
        when(forwarder.forwardDefaultAsync(request))
                .thenReturn(CompletableFuture.completedFuture(Optional.empty()));
        when(forwarder.forwardOriginalHostAsync(request, false)).thenReturn(activeForward);

        CompletableFuture<PipelineResult> result = pipeline.executeAsync(request).toCompletableFuture();
        result.cancel(true);

        assertThat(activeForward).isCancelled();
    }

    private static HttpMockPipeline pipeline(HttpOutboundForwarder forwarder,
                                             RestTemplate restTemplate) {
        ConditionMatcher matcher = mock(ConditionMatcher.class);
        RuleService ruleService = mock(RuleService.class);
        RequestLogService logService = mock(RequestLogService.class);
        HttpRuleService httpRuleService = mock(HttpRuleService.class);
        when(httpRuleService.findPreparedHttpRules(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(List.of());
        when(matcher.prepareBody(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(ConditionMatcher.PreparedBody.empty());
        return new HttpMockPipeline(matcher, ruleService, logService, httpRuleService,
                mock(ResponseTemplateService.class), restTemplate, forwarder);
    }

    private static MockRequest request(String targetHost) {
        return MockRequest.builder().protocol(Protocol.HTTP).method("GET")
                .path("/orders").targetHost(targetHost).headers(Map.of()).body("").build();
    }
}
