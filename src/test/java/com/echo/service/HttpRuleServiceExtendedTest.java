package com.echo.service;

import com.echo.entity.HttpRule;
import com.echo.entity.Protocol;
import com.echo.protocol.http.HttpProtocolHandler;
import com.echo.repository.HttpRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HttpRuleServiceExtendedTest {

    @Mock private HttpProtocolHandler httpHandler;
    @Mock private RuleService ruleService;
    @Mock private HttpRuleRepository httpRuleRepository;
    @Mock private ConditionMatcher conditionMatcher;
    @Mock private CacheInvalidationService cacheInvalidationService;

    private HttpRuleService service;

    @BeforeEach
    void setUp() {
        service = new HttpRuleService(httpHandler, ruleService, httpRuleRepository,
                conditionMatcher, Optional.of(cacheInvalidationService), null);
    }

    @Test
    void findMatchingHttpRule_shouldSkipDisabledRules() {
        HttpRule disabled = HttpRule.builder().id("d1").targetHost("h").matchKey("/api")
                .method("GET").enabled(false).priority(0).createdAt(LocalDateTime.now()).build();
        HttpRule enabled = HttpRule.builder().id("e1").targetHost("h").matchKey("/api")
                .method("GET").enabled(true).priority(0).createdAt(LocalDateTime.now()).build();

        when(httpHandler.findWithFallback(any(), any(), any())).thenReturn(List.of(disabled, enabled));

        MatchResult<HttpRule> result = service.findMatchingHttpRuleWithCandidates("h", "/api", "GET", null, null, null);

        assertThat(result.isMatched()).isTrue();
        assertThat(result.getMatchedRule().getId()).isEqualTo("e1");
    }

    @Test
    void findMatchingHttpRule_shouldReturnEmpty_whenNoRules() {
        when(httpHandler.findWithFallback(any(), any(), any())).thenReturn(List.of());

        MatchResult<HttpRule> result = service.findMatchingHttpRuleWithCandidates("h", "/api", "GET", null, null, null);

        assertThat(result.isMatched()).isFalse();
    }

    @Test
    void saveHttpRule_shouldPublishInvalidation() {
        HttpRule rule = HttpRule.builder().matchKey("/api").method("GET").build();
        when(httpHandler.save(rule)).thenReturn(rule);

        service.saveHttpRule(rule);

        verify(cacheInvalidationService).publishInvalidation(Protocol.HTTP);
    }

    @Test
    void deleteHttpRuleById_shouldPublishInvalidation() {
        service.deleteHttpRuleById("id-1");

        verify(httpHandler).deleteById("id-1");
        verify(cacheInvalidationService).publishInvalidation(Protocol.HTTP);
    }

    @Test
    void deleteAllHttpRules_shouldPublishInvalidation_whenDeleted() {
        when(httpHandler.deleteAll()).thenReturn(3);

        int count = service.deleteAllHttpRules();

        assertThat(count).isEqualTo(3);
        verify(cacheInvalidationService).publishInvalidation(Protocol.HTTP);
    }

    @Test
    void deleteAllHttpRules_shouldNotPublishInvalidation_whenNoneDeleted() {
        when(httpHandler.deleteAll()).thenReturn(0);

        int count = service.deleteAllHttpRules();

        assertThat(count).isEqualTo(0);
        verify(cacheInvalidationService, never()).publishInvalidation(any(Protocol.class));
    }

    @Test
    void findHttpRulesByResponseId_shouldDelegateToRepository() {
        HttpRule rule = HttpRule.builder().id("r1").matchKey("/api").method("GET").responseId(100L).build();
        when(httpRuleRepository.findByResponseId(100L)).thenReturn(List.of(rule));

        List<HttpRule> result = service.findHttpRulesByResponseId(100L);

        assertThat(result).hasSize(1);
    }

    @Test
    void findHttpRuleById_shouldDelegateToHandler() {
        HttpRule rule = HttpRule.builder().id("r1").matchKey("/api").method("GET").build();
        when(httpHandler.findById("r1")).thenReturn(Optional.of(rule));

        Optional<HttpRule> result = service.findHttpRuleById("r1");

        assertThat(result).isPresent();
    }

    @Test
    void findAllHttpRules_shouldDelegateToHandler() {
        when(httpHandler.findAllRules()).thenReturn(List.of());

        List<HttpRule> result = service.findAllHttpRules();

        assertThat(result).isEmpty();
        verify(httpHandler).findAllRules();
    }

    @Test
    void prepareHttpRules_shouldFilterInvalidResponseIds() {
        HttpRule valid = HttpRule.builder().id("r1").targetHost("h").matchKey("/api")
                .method("GET").priority(0).responseId(1L).createdAt(LocalDateTime.now()).build();
        HttpRule invalid = HttpRule.builder().id("r2").targetHost("h").matchKey("/api")
                .method("GET").priority(1).responseId(999L).createdAt(LocalDateTime.now()).build();

        when(httpHandler.findWithFallback(any(), any(), any())).thenReturn(List.of(valid, invalid));
        when(ruleService.getValidResponseIds(any())).thenReturn(Set.of(1L));

        List<HttpRule> result = service.findPreparedHttpRules("h", "/api", "GET");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo("r1");
    }

    @Test
    void findMatchingHttpRule_secondFallbackShouldBeSkipped() {
        HttpRule fallback1 = HttpRule.builder().id("f1").targetHost("h").matchKey("/api")
                .method("GET").enabled(true).priority(10).createdAt(LocalDateTime.now()).build();
        HttpRule fallback2 = HttpRule.builder().id("f2").targetHost("h").matchKey("/api")
                .method("GET").enabled(true).priority(1).createdAt(LocalDateTime.now()).build();

        when(httpHandler.findWithFallback(any(), any(), any())).thenReturn(List.of(fallback1, fallback2));

        MatchResult<HttpRule> result = service.findMatchingHttpRuleWithCandidates("h", "/api", "GET", null, null, null);

        assertThat(result.isMatched()).isTrue();
        assertThat(result.getMatchedRule().getId()).isEqualTo("f1");
    }
}
