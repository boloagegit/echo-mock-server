package com.echo.agent;

import com.echo.entity.Protocol;
import com.echo.repository.RequestLogRepository;
import com.echo.service.ConditionMatcher;
import com.echo.service.RequestLogService;
import com.echo.service.SystemConfigService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 匹配鏈分析單元測試 — 具體場景驗證。
 * <p>
 * 直接建構 LogAgent（非 Spring），使用真實 ConditionMatcher，
 * 呼叫 package-private analyzeMatchChain(task) 驗證分析結果。
 * <p>
 * Validates: Requirements 7.1, 7.4, 8.1, 9.1, 10.3
 */
class MatchChainAnalysisTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LogAgent analysisEnabledAgent;
    private LogAgent analysisDisabledAgent;

    @BeforeEach
    void setUp() {
        ConditionMatcher realMatcher = new ConditionMatcher();
        analysisEnabledAgent = new LogAgent(
                mock(RequestLogRepository.class),
                mock(SystemConfigService.class),
                realMatcher,
                mock(RequestLogService.class),
                500, 50, 5, true);
        analysisDisabledAgent = new LogAgent(
                mock(RequestLogRepository.class),
                mock(SystemConfigService.class),
                realMatcher,
                mock(RequestLogService.class),
                500, 50, 5, false);
    }

    // ==================== Combined Scenario: match + near-miss + shadowed + skipped ====================

    /**
     * 測試具體場景：一個 match + 一個 near-miss + 一個 shadowed + 一個 skipped 的組合。
     * <p>
     * Request body: {"type":"ORDER","env":"prod"}
     * <ul>
     *   <li>rule-match: body type=ORDER;env=prod → match (all pass, is matched rule)</li>
     *   <li>rule-nearmiss: body type=ORDER;env=staging → near-miss (1/2 pass)</li>
     *   <li>rule-shadowed: body type=ORDER;env=prod → shadowed (all pass, but not matched rule)</li>
     *   <li>rule-skipped: disabled → skipped</li>
     * </ul>
     * <p>
     * Validates: Requirements 7.1, 8.1, 9.1, 10.3
     */
    @Test
    void analyzeMatchChain_combinedScenario_matchNearMissShadowedSkipped() throws Exception {
        // Arrange
        String bodyJson = "{\"type\":\"ORDER\",\"env\":\"prod\"}";
        ConditionMatcher.PreparedBody prepared = new ConditionMatcher().prepareBody(bodyJson);

        CandidateSnapshot matchCandidate = CandidateSnapshot.builder()
                .ruleId("rule-match")
                .endpoint("/api/orders")
                .description("Matched rule")
                .enabled(true)
                .bodyCondition("type=ORDER;env=prod")
                .priority(1)
                .build();

        CandidateSnapshot nearMissCandidate = CandidateSnapshot.builder()
                .ruleId("rule-nearmiss")
                .endpoint("/api/orders")
                .description("Near-miss rule")
                .enabled(true)
                .bodyCondition("type=ORDER;env=staging")
                .priority(2)
                .build();

        CandidateSnapshot shadowedCandidate = CandidateSnapshot.builder()
                .ruleId("rule-shadowed")
                .endpoint("/api/orders")
                .description("Shadowed rule")
                .enabled(true)
                .bodyCondition("type=ORDER;env=prod")
                .priority(3)
                .build();

        CandidateSnapshot skippedCandidate = CandidateSnapshot.builder()
                .ruleId("rule-skipped")
                .endpoint("/api/orders")
                .description("Disabled rule")
                .enabled(false)
                .bodyCondition("type=ORDER")
                .priority(4)
                .build();

        LogTask task = LogTask.builder()
                .ruleId("rule-match")
                .protocol(Protocol.HTTP)
                .method("POST")
                .endpoint("/api/orders")
                .matched(true)
                .responseTimeMs(15)
                .requestTime(LocalDateTime.of(2026, 1, 15, 10, 30, 0))
                .matchChain("[{\"ruleId\":\"rule-match\"}]")
                .candidates(List.of(matchCandidate, nearMissCandidate, shadowedCandidate, skippedCandidate))
                .preparedBody(prepared)
                .queryString(null)
                .headers(Map.of())
                .build();

        // Act
        String json = analysisEnabledAgent.analyzeMatchChain(task);

        // Assert
        assertThat(json).isNotNull();
        List<Map<String, Object>> chain = MAPPER.readValue(json, new TypeReference<>() {});
        assertThat(chain).hasSize(4);

        // Entry 0: match
        Map<String, Object> matchEntry = chain.get(0);
        assertThat(matchEntry.get("ruleId")).isEqualTo("rule-match");
        assertThat(matchEntry.get("reason")).isEqualTo("match");
        assertThat(matchEntry.get("endpoint")).isEqualTo("/api/orders");
        assertThat(matchEntry.get("score")).isEqualTo("2/2");
        assertThat(matchEntry.get("detail")).asString().contains("PASS");
        assertThat(matchEntry.get("nearMiss")).isNull(); // false is omitted in JSON

        // Entry 1: near-miss
        Map<String, Object> nearMissEntry = chain.get(1);
        assertThat(nearMissEntry.get("ruleId")).isEqualTo("rule-nearmiss");
        assertThat(nearMissEntry.get("reason")).isEqualTo("near-miss");
        assertThat(nearMissEntry.get("score")).isEqualTo("1/2");
        assertThat(nearMissEntry.get("nearMiss")).isEqualTo(true);
        assertThat(nearMissEntry.get("detail")).asString().contains("PASS");
        assertThat(nearMissEntry.get("detail")).asString().contains("FAIL");

        // Entry 2: shadowed
        Map<String, Object> shadowedEntry = chain.get(2);
        assertThat(shadowedEntry.get("ruleId")).isEqualTo("rule-shadowed");
        assertThat(shadowedEntry.get("reason")).isEqualTo("shadowed");
        assertThat(shadowedEntry.get("score")).isEqualTo("2/2");
        assertThat(shadowedEntry.get("detail")).asString().contains("PASS");

        // Entry 3: skipped
        Map<String, Object> skippedEntry = chain.get(3);
        assertThat(skippedEntry.get("ruleId")).isEqualTo("rule-skipped");
        assertThat(skippedEntry.get("reason")).isEqualTo("skipped");
        assertThat(skippedEntry.get("detail")).isEqualTo("Rule disabled");
    }

    // ==================== Analysis Disabled ====================

    /**
     * 測試 analysis disabled 時只保留基本 matchChain（原始值不變）。
     * <p>
     * Validates: Requirements 7.4
     */
    @Test
    void analyzeMatchChain_analysisDisabled_shouldReturnOriginalMatchChain() {
        // Arrange
        String originalChain = "[{\"ruleId\":\"rule-1\",\"reason\":\"match\",\"endpoint\":\"/api/test\"}]";
        ConditionMatcher.PreparedBody prepared = new ConditionMatcher().prepareBody("{\"key\":\"value\"}");

        CandidateSnapshot candidate = CandidateSnapshot.builder()
                .ruleId("rule-1")
                .endpoint("/api/test")
                .description("Some rule")
                .enabled(true)
                .bodyCondition("key=value")
                .priority(1)
                .build();

        LogTask task = LogTask.builder()
                .ruleId("rule-1")
                .protocol(Protocol.HTTP)
                .method("GET")
                .endpoint("/api/test")
                .matched(true)
                .responseTimeMs(5)
                .requestTime(LocalDateTime.now())
                .matchChain(originalChain)
                .candidates(List.of(candidate))
                .preparedBody(prepared)
                .queryString("status=active")
                .headers(Map.of("X-Env", "prod"))
                .build();

        // Act
        String result = analysisDisabledAgent.analyzeMatchChain(task);

        // Assert — returns original matchChain unchanged, no analysis performed
        assertThat(result).isEqualTo(originalChain);
    }

    // ==================== Match with query + header conditions ====================

    /**
     * 測試含 body + query + header 三種條件的 match 場景，
     * 驗證 detail 包含所有條件類型的 PASS 資訊。
     * <p>
     * Validates: Requirements 7.1
     */
    @Test
    void analyzeMatchChain_matchWithAllConditionTypes() throws Exception {
        // Arrange
        String bodyJson = "{\"type\":\"ORDER\"}";
        ConditionMatcher.PreparedBody prepared = new ConditionMatcher().prepareBody(bodyJson);

        CandidateSnapshot candidate = CandidateSnapshot.builder()
                .ruleId("rule-full")
                .endpoint("/api/orders")
                .description("Full condition rule")
                .enabled(true)
                .bodyCondition("type=ORDER")
                .queryCondition("status=active")
                .headerCondition("X-Tenant=abc")
                .priority(1)
                .build();

        LogTask task = LogTask.builder()
                .ruleId("rule-full")
                .protocol(Protocol.HTTP)
                .method("GET")
                .endpoint("/api/orders")
                .matched(true)
                .responseTimeMs(10)
                .requestTime(LocalDateTime.now())
                .candidates(List.of(candidate))
                .preparedBody(prepared)
                .queryString("status=active")
                .headers(Map.of("X-Tenant", "abc"))
                .build();

        // Act
        String json = analysisEnabledAgent.analyzeMatchChain(task);

        // Assert
        List<Map<String, Object>> chain = MAPPER.readValue(json, new TypeReference<>() {});
        assertThat(chain).hasSize(1);

        Map<String, Object> entry = chain.get(0);
        assertThat(entry.get("reason")).isEqualTo("match");
        assertThat(entry.get("score")).isEqualTo("3/3");
        String detail = (String) entry.get("detail");
        assertThat(detail).contains("bodyCondition PASS");
        assertThat(detail).contains("queryCondition PASS");
        assertThat(detail).contains("headerCondition PASS");
    }

    // ==================== Null/empty candidates ====================

    /**
     * 測試 candidates 為空時回傳原始 matchChain。
     */
    @Test
    void analyzeMatchChain_emptyCandidates_shouldReturnOriginalMatchChain() {
        // Arrange
        String originalChain = "[{\"ruleId\":\"rule-1\"}]";
        LogTask task = LogTask.builder()
                .ruleId("rule-1")
                .protocol(Protocol.HTTP)
                .method("GET")
                .endpoint("/api/test")
                .matched(true)
                .responseTimeMs(5)
                .requestTime(LocalDateTime.now())
                .matchChain(originalChain)
                .candidates(List.of())
                .preparedBody(new ConditionMatcher().prepareBody("{\"a\":1}"))
                .build();

        // Act
        String result = analysisEnabledAgent.analyzeMatchChain(task);

        // Assert
        assertThat(result).isEqualTo(originalChain);
    }

    /**
     * 測試 preparedBody 為 null 時回傳原始 matchChain。
     */
    @Test
    void analyzeMatchChain_nullPreparedBody_shouldReturnOriginalMatchChain() {
        // Arrange
        String originalChain = "[{\"ruleId\":\"rule-1\"}]";
        CandidateSnapshot candidate = CandidateSnapshot.builder()
                .ruleId("rule-1")
                .endpoint("/api/test")
                .enabled(true)
                .bodyCondition("key=value")
                .priority(1)
                .build();

        LogTask task = LogTask.builder()
                .ruleId("rule-1")
                .protocol(Protocol.HTTP)
                .method("GET")
                .endpoint("/api/test")
                .matched(true)
                .responseTimeMs(5)
                .requestTime(LocalDateTime.now())
                .matchChain(originalChain)
                .candidates(List.of(candidate))
                .preparedBody(null)
                .build();

        // Act
        String result = analysisEnabledAgent.analyzeMatchChain(task);

        // Assert
        assertThat(result).isEqualTo(originalChain);
    }

    // ==================== Mismatch (all conditions fail) ====================

    /**
     * 測試全部條件都不通過時不記錄 mismatch（簡化匹配鏈）。
     */
    @Test
    void analyzeMatchChain_allConditionsFail_shouldNotIncludeMismatch() {
        // Arrange
        String bodyJson = "{\"type\":\"REFUND\"}";
        ConditionMatcher.PreparedBody prepared = new ConditionMatcher().prepareBody(bodyJson);

        CandidateSnapshot candidate = CandidateSnapshot.builder()
                .ruleId("rule-mismatch")
                .endpoint("/api/orders")
                .description("Mismatch rule")
                .enabled(true)
                .bodyCondition("type=ORDER")
                .priority(1)
                .build();

        LogTask task = LogTask.builder()
                .ruleId("other-rule")
                .protocol(Protocol.HTTP)
                .method("GET")
                .endpoint("/api/orders")
                .matched(true)
                .responseTimeMs(10)
                .requestTime(LocalDateTime.now())
                .candidates(List.of(candidate))
                .preparedBody(prepared)
                .queryString(null)
                .headers(Map.of())
                .build();

        // Act
        String json = analysisEnabledAgent.analyzeMatchChain(task);

        // Assert — mismatch 不記錄，chain 為空回傳 null
        assertThat(json).isNull();
    }

    // ==================== Fallback rule (no conditions) ====================

    /**
     * 測試無條件規則（fallback）的處理。
     */
    @Test
    void analyzeMatchChain_fallbackRule_noConditions() throws Exception {
        // Arrange
        String bodyJson = "{\"key\":\"value\"}";
        ConditionMatcher.PreparedBody prepared = new ConditionMatcher().prepareBody(bodyJson);

        CandidateSnapshot fallbackCandidate = CandidateSnapshot.builder()
                .ruleId("rule-fallback")
                .endpoint("/api/test")
                .description("Fallback rule")
                .enabled(true)
                .bodyCondition(null)
                .queryCondition(null)
                .headerCondition(null)
                .priority(99)
                .build();

        LogTask task = LogTask.builder()
                .ruleId("rule-fallback")
                .protocol(Protocol.HTTP)
                .method("GET")
                .endpoint("/api/test")
                .matched(true)
                .responseTimeMs(5)
                .requestTime(LocalDateTime.now())
                .candidates(List.of(fallbackCandidate))
                .preparedBody(prepared)
                .queryString(null)
                .headers(Map.of())
                .build();

        // Act
        String json = analysisEnabledAgent.analyzeMatchChain(task);

        // Assert
        List<Map<String, Object>> chain = MAPPER.readValue(json, new TypeReference<>() {});
        assertThat(chain).hasSize(1);

        Map<String, Object> entry = chain.get(0);
        assertThat(entry.get("ruleId")).isEqualTo("rule-fallback");
        assertThat(entry.get("reason")).isEqualTo("match");
    }
}
