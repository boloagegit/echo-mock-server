package com.echo.pipeline;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.echo.entity.*;
import com.echo.service.*;

import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Regression tests for selection/capture boundaries rather than parser implementation. */
class ResponseConsistencyPipelineTest {
    @Test
    void stableSelectionCopiesCallerOwnedCandidateLists() {
        var candidate = HttpRule.builder().id("caller-owned").build();
        var mutable = new ArrayList<>(List.of(candidate));
        var selected =
                new AbstractMockPipeline.StableSelection<>(
                        mutable, null, new MatchResult<>(candidate, List.of()), null, false);
        mutable.clear();
        assertThat(selected.candidates()).containsExactly(candidate);
        assertThatThrownBy(() -> selected.candidates().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void stableSelectionKeepsSnapshotOwnedConditionHandles() {
        var candidate = rule("snapshot-owned", null);
        var candidates = pin(candidate, oldRevision);
        var selected =
                new AbstractMockPipeline.StableSelection<>(
                        candidates, null, new MatchResult<>(candidate, List.of()), null, false);
        assertThat(selected.candidates()).isSameAs(candidates);
        assertThat(((RuleCandidates<?>) selected.candidates()).entry(0).conditions())
                .isSameAs(candidates.entry(0).conditions());
    }

    ConditionMatcher matcher;
    RuleService bodies;
    HttpRuleService rules;
    RequestLogService logs;
    ScenarioService scenarios;
    ResponseSnapshotReads reads;
    HttpMockPipeline pipeline;
    ResponseRevision oldRevision = new ResponseRevision("old", 0L),
            newRevision = new ResponseRevision("new", 1L);

    @BeforeEach
    void setup() {
        matcher = new ConditionMatcher();
        bodies = mock(RuleService.class);
        rules = mock(HttpRuleService.class);
        logs = mock(RequestLogService.class);
        scenarios = mock(ScenarioService.class);
        reads = mock(ResponseSnapshotReads.class);
        pipeline = new HttpMockPipeline(matcher, bodies, logs, rules, null, null, scenarios);
        ReflectionTestUtils.setField(pipeline, "responseSnapshotReads", reads);
        lenient()
                .when(reads.fallback(any()))
                .thenAnswer(call -> ((Supplier<?>) call.getArgument(0)).get());
    }

    HttpRule rule(String id, String condition) {
        return HttpRule.builder()
                .id(id)
                .enabled(true)
                .method("POST")
                .matchKey("/test")
                .httpStatus(201)
                .responseId(1L)
                .bodyCondition(condition)
                .build();
    }

    RuleCandidates<HttpRule> pin(HttpRule r, ResponseRevision revision) {
        return RuleCandidates.ofEntries(
                List.of(
                        new RuleCandidates.Entry<>(
                                r,
                                matcher.conditions(
                                        r.getMatchSpec(),
                                        r.getBodyCondition(),
                                        r.getQueryCondition(),
                                        r.getHeaderCondition()),
                                revision)));
    }

    MockRequest request() {
        return MockRequest.builder()
                .protocol(Protocol.HTTP)
                .path("/test")
                .method("POST")
                .body("{\"kind\":\"A\"}")
                .headers(Map.of())
                .build();
    }

    void stale(HttpRule fresh) {
        when(rules.findPreparedHttpRules(null, "/test", "POST"))
                .thenReturn(pin(rule("old", null), oldRevision));
        when(bodies.findResponseBodyById(1L, oldRevision)).thenThrow(new ResponseRevisionChanged());
        when(rules.freshCandidates(null, "/test", "POST")).thenReturn(pin(fresh, newRevision));
    }

    @Test
    void recomputesJsonRequirementsAndAdvancesOnlyFreshScenarioOnce() {
        var fresh = rule("new", "$.kind=A");
        fresh.setScenarioName("checkout");
        fresh.setRequiredScenarioState("Started");
        fresh.setNewScenarioState("Done");
        stale(fresh);
        when(scenarios.getCurrentState("checkout")).thenReturn("Started");
        when(scenarios.advanceState("checkout", "Started", "Done")).thenReturn(true);
        when(bodies.findUncachedResponseBodyById(1L)).thenReturn("new-body");
        // A caller may have prepared an old unconditional request; fallback must discard that plan.
        var oldPrepared =
                pipeline.prepareBodyForMatching(request(), pin(rule("old", null), oldRevision));
        var result = pipeline.execute(request().toBuilder().preparedBody(oldPrepared).build());
        assertThat(result.getRuleId()).isEqualTo("new");
        assertThat(result.getResponse().getBody()).isEqualTo("new-body");
        verify(reads, times(1)).fallback(any());
        verify(scenarios, times(1)).advanceState("checkout", "Started", "Done");
        verify(bodies, times(1)).findUncachedResponseBodyById(1L);
    }

    @Test
    void databaseFailureDoesNotRetryOrAdvanceScenario() {
        var fresh = rule("new", "$.kind=A");
        stale(fresh);
        doThrow(new DataAccessResourceFailureException("diagnostic database unavailable"))
                .when(reads)
                .fallback(any());
        var result = pipeline.execute(request());
        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        verify(reads, times(1)).fallback(any());
        verify(scenarios, never()).advanceState(any(), any(), any());
        verify(bodies, never()).findUncachedResponseBodyById(any());
    }

    @Test
    void cancellationAfterFreshMetadataReadStopsBeforeBodyAndScenario() {
        var fresh = rule("new", "$.kind=A");
        stale(fresh);
        var cancelled = new AtomicBoolean();
        when(rules.freshCandidates(null, "/test", "POST"))
                .thenAnswer(
                        call -> {
                            cancelled.set(true);
                            return pin(fresh, newRevision);
                        });
        var context =
                new RequestWorkContext() {
                    @Override
                    public void checkActive() {
                        if (cancelled.get()) throw new CancellationException("diagnostic");
                    }

                    @Override
                    public Scope begin() {
                        return () -> {};
                    }
                };
        assertThatThrownBy(
                        () ->
                                pipeline.executeAsync(
                                                request().toBuilder().workContext(context).build())
                                        .toCompletableFuture()
                                        .join())
                .isInstanceOf(CancellationException.class);
        verify(reads, times(1)).fallback(any());
        verify(bodies, never()).findUncachedResponseBodyById(any());
        verify(scenarios, never()).advanceState(any(), any(), any());
    }

    @Test
    void matchedForwardingDoesNotReadMockBodyOrOpenFallbackTransaction() {
        pipeline =
                new HttpMockPipeline(matcher, bodies, logs, rules, null, null, scenarios) {
                    @Override
                    protected CompletionStage<MockResponse> forwardMatchedRuleAsync(
                            HttpRule rule, MockRequest request) {
                        return CompletableFuture.completedFuture(
                                MockResponse.builder()
                                        .status(200)
                                        .body("downstream")
                                        .matched(true)
                                        .forwarded(true)
                                        .build());
                    }
                };
        ReflectionTestUtils.setField(pipeline, "responseSnapshotReads", reads);
        var forward = rule("forward", null);
        forward.setAction(HttpRuleAction.FORWARD);
        when(rules.findPreparedHttpRules(null, "/test", "POST"))
                .thenReturn(pin(forward, oldRevision));
        var result = pipeline.execute(request());
        assertThat(result.getResponse().getBody()).isEqualTo("downstream");
        verifyNoInteractions(bodies);
        verify(reads, never()).fallback(any());
    }

    @Test
    void customResponderDoesNotReadStoredBodyByDiagnosticMode() {
        pipeline =
                new HttpMockPipeline(matcher, bodies, logs, rules, null, null, scenarios) {
                    @Override
                    protected List<Responder<HttpRule>> responders() {
                        return List.of(
                                new Responder<>() {
                                    @Override
                                    public String mode() {
                                        return "MOCK";
                                    }

                                    @Override
                                    public boolean appliesTo(HttpRule rule, MockRequest request) {
                                        return true;
                                    }

                                    @Override
                                    public ResponsePlan respond(
                                            HttpRule rule, MockRequest request) {
                                        return new ResponsePlan.Content(
                                                MockResponse.builder()
                                                        .status(200)
                                                        .body("custom-body")
                                                        .matched(true)
                                                        .build());
                                    }
                                });
                    }
                };
        when(rules.findPreparedHttpRules(null, "/test", "POST"))
                .thenReturn(pin(rule("custom", null), oldRevision));
        var result = pipeline.execute(request());
        assertThat(result.getResponse().getBody()).isEqualTo("custom-body");
        verifyNoInteractions(bodies);
    }

    @Test
    void customResponderCanExplicitlyRequestTheCapturedBody() {
        pipeline =
                new HttpMockPipeline(matcher, bodies, logs, rules, null, null, scenarios) {
                    @Override
                    protected List<Responder<HttpRule>> responders() {
                        return List.of(
                                new Responder<>() {
                                    @Override
                                    public String mode() {
                                        return "CUSTOM";
                                    }

                                    @Override
                                    public boolean requiresResponseBody() {
                                        return true;
                                    }

                                    @Override
                                    public boolean appliesTo(HttpRule rule, MockRequest request) {
                                        return true;
                                    }

                                    @Override
                                    public ResponsePlan respond(
                                            HttpRule rule, MockRequest request) {
                                        throw new AssertionError(
                                                "The pipeline must supply the captured body");
                                    }

                                    @Override
                                    public ResponsePlan respondWithBody(
                                            HttpRule rule, MockRequest request, String body) {
                                        return new ResponsePlan.Content(
                                                MockResponse.builder()
                                                        .status(200)
                                                        .body("custom:" + body)
                                                        .matched(true)
                                                        .build());
                                    }
                                });
                    }
                };
        when(rules.findPreparedHttpRules(null, "/test", "POST"))
                .thenReturn(pin(rule("custom", null), oldRevision));
        when(bodies.findResponseBodyById(1L, oldRevision)).thenReturn("captured-body");
        var result = pipeline.execute(request());
        assertThat(result.getResponse().getBody()).isEqualTo("custom:captured-body");
        verify(bodies, times(1)).findResponseBodyById(1L, oldRevision);
        verify(bodies, never()).findResponseBodyById(anyLong());
    }
}
