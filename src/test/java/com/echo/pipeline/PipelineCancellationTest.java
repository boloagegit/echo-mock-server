package com.echo.pipeline;

import com.echo.entity.HttpForwardTargetMode;
import com.echo.entity.HttpRule;
import com.echo.entity.HttpRuleAction;
import com.echo.entity.Protocol;
import com.echo.service.ConditionMatcher;
import com.echo.service.HttpOutboundForwarder;
import com.echo.service.HttpRuleService;
import com.echo.service.RequestLogService;
import com.echo.service.RuleService;
import com.echo.service.ScenarioService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PipelineCancellationTest {
    @Test
    void cancellationAfterACommittedScenarioTransitionDoesNotUndoOrReplayIt() {
        var rules = mock(HttpRuleService.class);
        var forwarder = mock(HttpOutboundForwarder.class);
        var scenarios = mock(ScenarioService.class);
        var pipeline = new HttpMockPipeline(new ConditionMatcher(), mock(RuleService.class),
                mock(RequestLogService.class), rules, null, forwarder, scenarios);
        when(rules.findPreparedHttpRules(anyString(), anyString(), anyString())).thenReturn(List.of(
                HttpRule.builder().id("forward").enabled(true).action(HttpRuleAction.FORWARD)
                        .forwardTargetMode(HttpForwardTargetMode.DEFAULT_CONNECTION)
                        .scenarioName("flow").requiredScenarioState("START").newScenarioState("NEXT").build()));
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicReference<String> committedState = new AtomicReference<>("START");
        when(scenarios.getCurrentState("flow")).thenAnswer(invocation -> committedState.get());
        when(scenarios.advanceState("flow", "START", "NEXT")).thenAnswer(invocation -> {
            committedState.set("NEXT");
            cancelled.set(true);
            return true;
        });
        RequestWorkContext context = new RequestWorkContext() {
            @Override public void checkActive() {
                if (cancelled.get()) throw new CancellationException("test cancellation after commit");
            }
            @Override public Scope begin() { checkActive(); return () -> { }; }
        };
        var request = MockRequest.builder().protocol(Protocol.HTTP).method("GET").path("/state")
                .targetHost("default").headers(Map.of()).workContext(context).build();
        assertThat(pipeline.executeAsync(request).toCompletableFuture()).isCancelled();
        assertThat(committedState).hasValue("NEXT");
        verify(scenarios).advanceState("flow", "START", "NEXT");
        verify(forwarder, never()).forwardAsync(any(), any(), anyBoolean());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6})
    void cancellationAtEachSynchronousBoundaryPreventsForwardingAndKeepsCancellationSemantics(int cancelAt) {
        var rules = mock(HttpRuleService.class);
        var forwarder = mock(HttpOutboundForwarder.class);
        var logs = mock(RequestLogService.class);
        var pipeline = new HttpMockPipeline(new ConditionMatcher(), mock(RuleService.class), logs,
                rules, null, forwarder, mock(ScenarioService.class));
        when(rules.findPreparedHttpRules(anyString(), anyString(), anyString())).thenReturn(List.of(
                HttpRule.builder().id("forward").enabled(true).action(HttpRuleAction.FORWARD)
                        .forwardTargetMode(HttpForwardTargetMode.DEFAULT_CONNECTION).build()));
        AtomicInteger checks = new AtomicInteger();
        RequestWorkContext context = new RequestWorkContext() {
            @Override public void checkActive() {
                if (checks.incrementAndGet() >= cancelAt) throw new CancellationException("test cancellation");
            }
            @Override public Scope begin() { checkActive(); return () -> { }; }
        };
        var request = MockRequest.builder().protocol(Protocol.HTTP).method("GET").path("/cancelled")
                .headers(Map.of()).targetHost("default").workContext(context).build();
        var result = pipeline.executeAsync(request).toCompletableFuture();
        assertThat(result).isCancelled();
        verify(forwarder, never()).forwardAsync(any(), any(), anyBoolean());
        verify(logs, never()).record(any());
    }
}
