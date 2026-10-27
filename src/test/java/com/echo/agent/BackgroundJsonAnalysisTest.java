package com.echo.agent;

import com.echo.core.match.BodyRequirements;
import com.echo.core.match.MatchExplanation;
import com.echo.entity.Protocol;
import com.echo.repository.RequestLogRepository;
import com.echo.service.ConditionMatcher;
import com.echo.service.RequestLogService;
import com.echo.service.SystemConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class BackgroundJsonAnalysisTest {
    private static final String PADDING = "x".repeat(65536);

    @Test
    void backgroundAnalysisDoesNotMaterializeUnselectedLargeValues() {
        var matcher = new ObservingMatcher();
        String body = "{\"id\":\"yes\",\"padding\":\"" + PADDING + "\"}";
        var task = task(body, List.of(candidate("match", "$.id=yes", null)), Map.of());
        String actual = agent(matcher).analyzeMatchChain(task);
        assertThat(actual).contains("\"reason\":\"match\"").isEqualTo(agent(new FullTreeMatcher()).analyzeMatchChain(task));
        JsonNode projected = (JsonNode) ReflectionTestUtils.getField(matcher.observed, "projection");
        assertThat(projected).isNotNull();
        assertThat(projected.path("id").asText()).isEqualTo("yes");
        assertThat(projected.has("padding")).isFalse();
        assertThat(ReflectionTestUtils.getField(matcher.observed, "jsonNode")).isNull();
    }

    @Test
    void completeExplanationsRemainEquivalentForHistoricalCandidatesAndCachedOutcomes() {
        var matcher = new ConditionMatcher();
        List<CandidateSnapshot> candidates = List.of(
                candidate("match", "$.id=yes", null),
                candidate("near", "$.id=yes;$.env=other", null),
                candidate("shadowed", "$.group.value*=中文", null),
                candidate("array", "$.items[0].id=7", null),
                candidate("wildcard", "$.items[*].id*=7", null),
                candidate("parent", "$.group*=value", null),
                CandidateSnapshot.builder().ruleId("disabled").enabled(false)
                        .bodyCondition("$.padding=x").build(),
                candidate("fallback", null, null));
        for (String fragment : List.of(
                "\"id\":\"yes\",\"env\":\"prod\",\"group\":{\"value\":\"中文😀\"},\"items\":[{\"id\":7}]",
                "\"id\":null,\"env\":\"prod\"",
                "\"id\":\"no\",\"id\":\"yes\",\"env\":\"prod\"",
                "\"env\":\"prod\",\"items\":[]")) {
            String body = "{" + fragment + ",\"padding\":\"" + PADDING + "\"}";
            var prepared = matcher.prepareBody(body);
            for (var c : candidates) if (c.isEnabled()) {
                matcher.matchesPrepared(c.getMatchSpec(), c.getBodyCondition(), c.getQueryCondition(),
                        c.getHeaderCondition(), prepared, null, Map.of());
            }
            for (Map<String, Boolean> outcomes : List.of(Map.<String, Boolean>of(), prepared.getMatchOutcomesSnapshot())) {
                var task = task(body, candidates, outcomes);
                assertThat(agent(matcher).analyzeMatchChain(task))
                        .isEqualTo(agent(new FullTreeMatcher()).analyzeMatchChain(task));
            }
        }
    }

    @Test
    void structuredLogicAndInvalidInputKeepFullTreeExplanationSemantics() {
        String spec = "{\"all\":[{\"source\":\"body\",\"selector\":{\"jsonPath\":\"$.id\"},"
                + "\"op\":\"equals\",\"value\":\"yes\"},{\"not\":{\"source\":\"body\",\"selector\":{"
                + "\"jsonPath\":\"$.blocked\"},\"op\":\"equals\",\"value\":\"true\"}}]}";
        var candidates = List.of(candidate("match", null, spec), candidate("legacy", "$.id=yes", null));
        String valid = "{\"id\":\"yes\",\"padding\":\"" + PADDING + "\"}";
        for (String body : List.of(valid, valid + " trailing", valid.substring(0, valid.length() - 1),
                valid.replace(PADDING, PADDING + "\\q"), valid.replace("\"yes\"", "1e999999"),
                "  " + valid, "[" + valid + "]")) {
            var task = task(body, candidates, Map.of());
            assertThat(agent(new ConditionMatcher()).analyzeMatchChain(task))
                    .isEqualTo(agent(new FullTreeMatcher()).analyzeMatchChain(task));
        }
    }

    @Test
    void concurrentAnalysisKeepsRequestStateIsolated() throws Exception {
        var matcher = new ConditionMatcher();
        var analyzer = agent(matcher);
        var candidates = List.of(candidate("match", "$.id=yes", null));
        var pool = Executors.newFixedThreadPool(4);
        try {
            var calls = new ArrayList<Callable<Boolean>>();
            for (int i = 0; i < 40; i++) {
                String body = "{\"id\":\"" + (i % 2 == 0 ? "yes" : "no")
                        + "\",\"padding\":\"" + PADDING + "\"}";
                var task = task(body, candidates, Map.of());
                String expected = agent(new FullTreeMatcher()).analyzeMatchChain(task);
                calls.add(() -> java.util.Objects.equals(expected, analyzer.analyzeMatchChain(task)));
            }
            for (var result : pool.invokeAll(calls)) assertThat(result.get()).isTrue();
        } finally {
            pool.shutdownNow();
        }
    }

    private static CandidateSnapshot candidate(String id, String body, String spec) {
        return CandidateSnapshot.builder().ruleId(id).endpoint("/historical")
                .enabled(true).bodyCondition(body).matchSpec(spec).build();
    }

    private static LogTask task(String body, List<CandidateSnapshot> candidates, Map<String, Boolean> outcomes) {
        return LogTask.builder().protocol(Protocol.HTTP).ruleId("match").endpoint("/historical")
                .matched(true).requestBody(body).analysisBody(body).candidates(candidates)
                .matchOutcomes(outcomes).matchChain("[]").build();
    }

    private static LogAgent agent(ConditionMatcher matcher) {
        return new LogAgent(mock(RequestLogRepository.class), mock(SystemConfigService.class), matcher,
                mock(RequestLogService.class), 500, 50, 5, true);
    }

    private static class FullTreeMatcher extends ConditionMatcher {
        @Override
        public PreparedBody prepareBody(String body, BodyRequirements requirements) {
            return super.prepareBody(body);
        }
    }

    private static final class ObservingMatcher extends ConditionMatcher {
        private PreparedBody observed;

        @Override
        public MatchExplanation explainPrepared(String spec, String body, String query, String header,
                PreparedBody prepared, String queryString, Map<String, String> headers) {
            observed = prepared;
            return super.explainPrepared(spec, body, query, header, prepared, queryString, headers);
        }
    }
}
