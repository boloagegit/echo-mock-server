package com.echo.service;

import com.echo.entity.HttpRule;
import com.echo.entity.Protocol;
import com.echo.protocol.http.HttpProtocolHandler;
import net.jqwik.api.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 向後相容性驗證：確認 sseEnabled 欄位不影響既有 HTTP 規則行為。
 *
 * Validates: Requirements 7.1, 7.3
 */
@ExtendWith(MockitoExtension.class)
class SseBackwardCompatibilityTest {

    @Mock
    private HttpProtocolHandler httpHandler;
    @Mock
    private RuleService ruleService;
    @Mock
    private ConditionMatcher conditionMatcher;

    private HttpRuleService service;

    @BeforeEach
    void setUp() {
        service = new HttpRuleService(httpHandler, ruleService, null, conditionMatcher, Optional.empty(), null);
    }

    private Optional<HttpRule> findMatchingRule(String host, String path, String method, String body, String queryString) {
        MatchResult<HttpRule> result = service.findMatchingHttpRuleWithCandidates(host, path, method, body, queryString, null);
        return result.isMatched() ? Optional.of(result.getMatchedRule()) : Optional.empty();
    }

    // --- Unit tests: sseEnabled defaults to false ---

    @Test
    void existingRule_sseEnabled_defaultsToFalse() {
        HttpRule rule = HttpRule.builder()
                .matchKey("/test")
                .method("GET")
                .httpStatus(200)
                .build();
        assertThat(rule.getSseEnabled()).isFalse();
    }

    @Test
    void existingRule_withSseEnabledFalse_behavesNormally() {
        HttpRule rule = HttpRule.builder()
                .id("r1")
                .matchKey("/api/users")
                .method("GET")
                .targetHost("api.com")
                .httpStatus(200)
                .sseEnabled(false)
                .enabled(true)
                .priority(0)
                .build();
        when(httpHandler.findWithFallback(any(), any(), any())).thenReturn(List.of(rule));

        Optional<HttpRule> result = findMatchingRule("api.com", "/api/users", "GET", null, null);

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo("r1");
        assertThat(result.get().getSseEnabled()).isFalse();
        assertThat(result.get().getProtocol()).isEqualTo(Protocol.HTTP);
    }

    // --- Unit tests: rule matching priority unaffected by sseEnabled ---

    @Test
    void prioritySorting_notAffectedBySseEnabled() {
        HttpRule sseRule = HttpRule.builder()
                .id("sse")
                .matchKey("/api/data")
                .method("GET")
                .targetHost("api.com")
                .httpStatus(200)
                .priority(10)
                .sseEnabled(true)
                .enabled(true)
                .build();
        HttpRule normalRule = HttpRule.builder()
                .id("normal")
                .matchKey("/api/data")
                .method("GET")
                .targetHost("api.com")
                .httpStatus(200)
                .priority(1)
                .sseEnabled(false)
                .enabled(true)
                .build();
        when(httpHandler.findWithFallback(any(), any(), any())).thenReturn(List.of(normalRule, sseRule));

        Optional<HttpRule> result = findMatchingRule("api.com", "/api/data", "GET", null, null);

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo("sse");
    }

    @Test
    void conditionMatch_notAffectedBySseEnabled() {
        HttpRule sseWithCondition = HttpRule.builder()
                .id("sseCond")
                .matchKey("/api/data")
                .method("GET")
                .targetHost("api.com")
                .httpStatus(200)
                .priority(0)
                .bodyCondition("type=vip")
                .sseEnabled(true)
                .enabled(true)
                .build();
        HttpRule normalNoCondition = HttpRule.builder()
                .id("normalNoCond")
                .matchKey("/api/data")
                .method("GET")
                .targetHost("api.com")
                .httpStatus(200)
                .priority(0)
                .sseEnabled(false)
                .enabled(true)
                .build();
        when(httpHandler.findWithFallback(any(), any(), any()))
                .thenReturn(List.of(normalNoCondition, sseWithCondition));
        when(conditionMatcher.matchesPrepared(any(), any(), any(), any(), any(), any()))
                .thenReturn(true);

        Optional<HttpRule> result = findMatchingRule(
                "api.com", "/api/data", "GET", "{\"type\":\"vip\"}", null);

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo("sseCond");
    }

    @Test
    void wildcardFallback_notAffectedBySseEnabled() {
        HttpRule exactSse = HttpRule.builder()
                .id("exactSse")
                .matchKey("/api/data")
                .method("GET")
                .targetHost("api.com")
                .httpStatus(200)
                .priority(0)
                .sseEnabled(true)
                .enabled(true)
                .build();
        HttpRule wildcardNormal = HttpRule.builder()
                .id("wildcardNormal")
                .matchKey("*")
                .method("GET")
                .targetHost("api.com")
                .httpStatus(200)
                .priority(0)
                .sseEnabled(false)
                .enabled(true)
                .build();
        when(httpHandler.findWithFallback(any(), any(), any()))
                .thenReturn(List.of(wildcardNormal, exactSse));

        Optional<HttpRule> result = findMatchingRule("api.com", "/api/data", "GET", null, null);

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo("exactSse");
    }

    @Test
    void disabledRule_skippedRegardlessOfSseEnabled() {
        HttpRule disabledSse = HttpRule.builder()
                .id("disabledSse")
                .matchKey("/api/data")
                .method("GET")
                .targetHost("api.com")
                .httpStatus(200)
                .priority(0)
                .sseEnabled(true)
                .enabled(false)
                .build();
        HttpRule enabledNormal = HttpRule.builder()
                .id("enabledNormal")
                .matchKey("/api/data")
                .method("GET")
                .targetHost("api.com")
                .httpStatus(200)
                .priority(1)
                .sseEnabled(false)
                .enabled(true)
                .build();
        when(httpHandler.findWithFallback(any(), any(), any()))
                .thenReturn(List.of(disabledSse, enabledNormal));

        Optional<HttpRule> result = findMatchingRule("api.com", "/api/data", "GET", null, null);

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo("enabledNormal");
    }

    @Test
    void matchChain_notAffectedBySseEnabled() {
        HttpRule sseRule = HttpRule.builder()
                .id("sse")
                .matchKey("/api/data")
                .method("GET")
                .targetHost("api.com")
                .httpStatus(200)
                .priority(10)
                .sseEnabled(true)
                .enabled(true)
                .build();
        HttpRule normalRule = HttpRule.builder()
                .id("normal")
                .matchKey("/api/data")
                .method("GET")
                .targetHost("api.com")
                .httpStatus(200)
                .priority(1)
                .sseEnabled(false)
                .enabled(true)
                .build();
        when(httpHandler.findWithFallback(any(), any(), any()))
                .thenReturn(List.of(normalRule, sseRule));

        MatchResult<HttpRule> matchResult = service.findMatchingHttpRuleWithCandidates(
                "api.com", "/api/data", "GET", null, null, null);

        assertThat(matchResult.isMatched()).isTrue();
        assertThat(matchResult.getMatchedRule().getId()).isEqualTo("sse");
        assertThat(matchResult.getMatchChain()).isNotEmpty();
    }

    // --- Property 7: Backward compatibility — sseEnabled does not affect matching behavior ---

    /**
     * Validates: Requirements 7.1, 7.3
     *
     * For any set of HttpRules, the rule matching priority and result
     * is NOT affected by the sseEnabled field value. Toggling sseEnabled
     * on any rule does not change which rule is matched.
     */
    @Property(tries = 100)
    void sseEnabledDoesNotAffectMatchingBehavior(
            @ForAll("httpRulePairs") List<HttpRule> rules,
            @ForAll("optionalBody") String body,
            @ForAll("optionalQuery") String queryString) {

        ConditionMatcher realMatcher = new ConditionMatcher();

        List<HttpRule> flippedRules = rules.stream().map(r -> {
            HttpRule clone = HttpRule.builder()
                    .id(r.getId())
                    .matchKey(r.getMatchKey())
                    .method(r.getMethod())
                    .targetHost(r.getTargetHost())
                    .httpStatus(r.getHttpStatus())
                    .priority(r.getPriority())
                    .enabled(r.getEnabled())
                    .bodyCondition(r.getBodyCondition())
                    .queryCondition(r.getQueryCondition())
                    .headerCondition(r.getHeaderCondition())
                    .responseId(r.getResponseId())
                    .createdAt(r.getCreatedAt())
                    .sseEnabled(!Boolean.TRUE.equals(r.getSseEnabled()))
                    .build();
            return clone;
        }).toList();

        HttpProtocolHandler handler1 = new HttpProtocolHandler(null) {
            @Override
            public List<HttpRule> findWithFallback(String host, String path, String method) {
                return rules;
            }
        };
        HttpProtocolHandler handler2 = new HttpProtocolHandler(null) {
            @Override
            public List<HttpRule> findWithFallback(String host, String path, String method) {
                return flippedRules;
            }
        };

        RuleService mockRuleService = new RuleService(null, null, null, null, Optional.empty(), Optional.empty(), null) {
            @Override
            public java.util.Set<Long> getValidResponseIds(java.util.Set<Long> ids) {
                return ids;
            }
        };

        HttpRuleService svc1 = new HttpRuleService(handler1, mockRuleService, null, realMatcher, Optional.empty(), null);
        HttpRuleService svc2 = new HttpRuleService(handler2, mockRuleService, null, realMatcher, Optional.empty(), null);

        MatchResult<HttpRule> result1 = svc1.findMatchingHttpRuleWithCandidates("api.com", "/test", "GET", body, queryString, null);
        MatchResult<HttpRule> result2 = svc2.findMatchingHttpRuleWithCandidates("api.com", "/test", "GET", body, queryString, null);

        assertThat(result1.isMatched()).isEqualTo(result2.isMatched());
        if (result1.isMatched()) {
            assertThat(result1.getMatchedRule().getId()).isEqualTo(result2.getMatchedRule().getId());
        }
    }

    @Provide
    Arbitrary<List<HttpRule>> httpRulePairs() {
        Arbitrary<HttpRule> ruleArb = Combinators.combine(
                Arbitraries.integers().between(1, 5).map(String::valueOf),
                Arbitraries.of("/api/users", "/api/orders", "/api/data", "*"),
                Arbitraries.of("GET", "POST", "PUT"),
                Arbitraries.of("api.com", "other.com", "", null),
                Arbitraries.integers().between(0, 10),
                Arbitraries.of(true, false),
                Arbitraries.of(true, false)
        ).as((id, matchKey, method, targetHost, priority, enabled, sseEnabled) -> {
            HttpRule rule = HttpRule.builder()
                    .id(id)
                    .matchKey(matchKey)
                    .method(method)
                    .targetHost(targetHost)
                    .httpStatus(200)
                    .priority(priority)
                    .enabled(enabled)
                    .sseEnabled(sseEnabled)
                    .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0).plusDays(Integer.parseInt(id)))
                    .build();
            return rule;
        });
        return ruleArb.list().ofMinSize(1).ofMaxSize(5);
    }

    @Provide
    Arbitrary<String> optionalBody() {
        return Arbitraries.of(null, "", "{\"type\":\"vip\"}", "{\"status\":\"active\"}");
    }

    @Provide
    Arbitrary<String> optionalQuery() {
        return Arbitraries.of(null, "", "id=123", "type=vip");
    }
}