package com.echo.agent;

import com.echo.repository.RequestLogRepository;
import com.echo.service.ConditionMatcher;
import com.echo.service.RequestLogService;
import com.echo.service.SystemConfigService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.StringLength;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 匹配鏈分析屬性測試（Property 13, 14, 15, 16, 17）。
 * <p>
 * Feature: agent-framework-log-agent
 * <p>
 * 直接建構 LogAgent（analysisEnabled=true），使用真實 ConditionMatcher，
 * 呼叫 package-private analyzeMatchChain(task) 驗證分析結果。
 */
class MatchChainAnalysisPropertyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> VALID_REASONS =
            Set.of("match", "mismatch", "near-miss", "skipped", "shadowed", "fallback");

    // ==================== Helpers ====================

    /**
     * 建立 LogAgent（analysisEnabled=true），使用真實 ConditionMatcher。
     */
    private LogAgent createAnalysisAgent() {
        return new LogAgent(
                mock(RequestLogRepository.class),
                mock(SystemConfigService.class),
                new ConditionMatcher(),
                mock(RequestLogService.class),
                500,  // queueCapacity
                50,   // batchSize
                5,    // flushIntervalSeconds
                true  // analysisEnabled
        );
    }

    /**
     * 建立 PreparedBody（JSON 格式）。
     */
    private ConditionMatcher.PreparedBody prepareJsonBody(String json) {
        return new ConditionMatcher().prepareBody(json);
    }

    // ==================== Property 13: Near-miss detection ====================

    /**
     * Feature: agent-framework-log-agent, Property 13: Near-miss detection
     * <p>
     * 部分條件通過時 reason="near-miss"，nearMiss=true，score 格式 "X/Y"
     * 其中 X = passedCount, Y = totalCount, 0 < X < Y。
     * <p>
     * 策略：建立一個候選規則有 2 個 body 條件，其中只有 1 個匹配。
     * <p>
     * **Validates: Requirements 8.1, 8.2, 8.3, 8.4**
     */
    @Property(tries = 20)
    void nearMissDetection(
            @ForAll("alphaRuleIds") String ruleId,
            @ForAll("alphaValues") String matchValue,
            @ForAll("alphaValues") String mismatchValue) throws Exception {

        Assume.that(!matchValue.equals(mismatchValue));

        LogAgent agent = createAnalysisAgent();

        // Body JSON: {"fieldA":"<matchValue>","fieldB":"something"}
        String bodyJson = String.format("{\"fieldA\":\"%s\",\"fieldB\":\"something\"}", matchValue);
        ConditionMatcher.PreparedBody prepared = prepareJsonBody(bodyJson);

        // Candidate: 2 body conditions — fieldA matches, fieldB does not
        CandidateSnapshot candidate = CandidateSnapshot.builder()
                .ruleId(ruleId)
                .endpoint("/api/test")
                .description("near-miss candidate")
                .enabled(true)
                .bodyCondition("fieldA=" + matchValue + ";fieldB=" + mismatchValue)
                .queryCondition(null)
                .headerCondition(null)
                .priority(1)
                .build();

        LogTask task = LogTask.builder()
                .ruleId("other-rule")  // matched rule is different
                .endpoint("/api/test")
                .matched(true)
                .responseTimeMs(10)
                .requestTime(LocalDateTime.now())
                .candidates(List.of(candidate))
                .preparedBody(prepared)
                .queryString(null)
                .headers(Map.of())
                .build();

        String json = agent.analyzeMatchChain(task);
        assertThat(json).isNotNull();

        List<Map<String, Object>> chain = MAPPER.readValue(json, new TypeReference<>() {});
        assertThat(chain).hasSize(1);

        Map<String, Object> entry = chain.get(0);
        assertThat(entry.get("ruleId")).isEqualTo(ruleId);
        assertThat(entry.get("reason")).isEqualTo("near-miss");
        assertThat(entry.get("nearMiss")).isEqualTo(true);

        // score format "X/Y" where 0 < X < Y
        String score = (String) entry.get("score");
        assertThat(score).matches("\\d+/\\d+");
        String[] parts = score.split("/");
        int passed = Integer.parseInt(parts[0]);
        int total = Integer.parseInt(parts[1]);
        assertThat(passed).isGreaterThan(0);
        assertThat(passed).isLessThan(total);
    }

    // ==================== Property 14: Shadowed rule detection ====================

    /**
     * Feature: agent-framework-log-agent, Property 14: Shadowed rule detection
     * <p>
     * 全部條件通過但非匹配規則時 reason="shadowed"。
     * <p>
     * 策略：建立 2 個候選規則，都匹配所有條件，但 ruleId 設為第一個。
     * 第二個候選規則應被標記為 shadowed。
     * <p>
     * **Validates: Requirements 9.1, 9.2, 9.3**
     */
    @Property(tries = 20)
    void shadowedRuleDetection(
            @ForAll("alphaValues") String fieldValue) throws Exception {

        LogAgent agent = createAnalysisAgent();

        String bodyJson = String.format("{\"type\":\"%s\"}", fieldValue);
        ConditionMatcher.PreparedBody prepared = prepareJsonBody(bodyJson);

        String matchedRuleId = "matched-rule";
        String shadowedRuleId = "shadowed-rule";

        CandidateSnapshot matchedCandidate = CandidateSnapshot.builder()
                .ruleId(matchedRuleId)
                .endpoint("/api/test")
                .description("matched candidate")
                .enabled(true)
                .bodyCondition("type=" + fieldValue)
                .queryCondition(null)
                .headerCondition(null)
                .priority(1)
                .build();

        CandidateSnapshot shadowedCandidate = CandidateSnapshot.builder()
                .ruleId(shadowedRuleId)
                .endpoint("/api/test")
                .description("shadowed candidate")
                .enabled(true)
                .bodyCondition("type=" + fieldValue)
                .queryCondition(null)
                .headerCondition(null)
                .priority(2)
                .build();

        LogTask task = LogTask.builder()
                .ruleId(matchedRuleId)
                .endpoint("/api/test")
                .matched(true)
                .responseTimeMs(10)
                .requestTime(LocalDateTime.now())
                .candidates(List.of(matchedCandidate, shadowedCandidate))
                .preparedBody(prepared)
                .queryString(null)
                .headers(Map.of())
                .build();

        String json = agent.analyzeMatchChain(task);
        assertThat(json).isNotNull();

        List<Map<String, Object>> chain = MAPPER.readValue(json, new TypeReference<>() {});
        assertThat(chain).hasSize(2);

        // First entry: matched
        Map<String, Object> matchEntry = chain.get(0);
        assertThat(matchEntry.get("ruleId")).isEqualTo(matchedRuleId);
        assertThat(matchEntry.get("reason")).isEqualTo("match");

        // Second entry: shadowed — all conditions passed but not the matched rule
        Map<String, Object> shadowEntry = chain.get(1);
        assertThat(shadowEntry.get("ruleId")).isEqualTo(shadowedRuleId);
        assertThat(shadowEntry.get("reason")).isEqualTo("shadowed");
    }

    // ==================== Property 15: Disabled rule skipped ====================

    /**
     * Feature: agent-framework-log-agent, Property 15: Disabled rule skipped in analysis
     * <p>
     * enabled=false 時 reason="skipped"。
     * <p>
     * **Validates: Requirements 10.3**
     */
    @Property(tries = 20)
    void disabledRuleSkippedInAnalysis(
            @ForAll("alphaRuleIds") String ruleId) throws Exception {

        LogAgent agent = createAnalysisAgent();

        String bodyJson = "{\"key\":\"value\"}";
        ConditionMatcher.PreparedBody prepared = prepareJsonBody(bodyJson);

        CandidateSnapshot disabledCandidate = CandidateSnapshot.builder()
                .ruleId(ruleId)
                .endpoint("/api/test")
                .description("disabled rule")
                .enabled(false)
                .bodyCondition("key=value")
                .queryCondition(null)
                .headerCondition(null)
                .priority(1)
                .build();

        LogTask task = LogTask.builder()
                .ruleId("other-rule")
                .endpoint("/api/test")
                .matched(true)
                .responseTimeMs(10)
                .requestTime(LocalDateTime.now())
                .candidates(List.of(disabledCandidate))
                .preparedBody(prepared)
                .queryString(null)
                .headers(Map.of())
                .build();

        String json = agent.analyzeMatchChain(task);
        assertThat(json).isNotNull();

        List<Map<String, Object>> chain = MAPPER.readValue(json, new TypeReference<>() {});
        assertThat(chain).hasSize(1);

        Map<String, Object> entry = chain.get(0);
        assertThat(entry.get("ruleId")).isEqualTo(ruleId);
        assertThat(entry.get("reason")).isEqualTo("skipped");
    }

    // ==================== Property 16: JSON structure and valid reason values ====================

    /**
     * Feature: agent-framework-log-agent, Property 16: Match chain JSON structure and valid reason values
     * <p>
     * JSON 包含 ruleId, endpoint, reason；reason 為合法值。
     * <p>
     * 策略：建立混合場景（match + near-miss + skipped），驗證每個 entry 的結構。
     * <p>
     * **Validates: Requirements 10.1, 10.2**
     */
    @Property(tries = 20)
    void matchChainJsonStructureAndValidReasons(
            @ForAll("alphaValues") String fieldValue,
            @ForAll("alphaValues") String mismatchValue) throws Exception {

        Assume.that(!fieldValue.equals(mismatchValue));

        LogAgent agent = createAnalysisAgent();

        String bodyJson = String.format("{\"type\":\"%s\",\"env\":\"prod\"}", fieldValue);
        ConditionMatcher.PreparedBody prepared = prepareJsonBody(bodyJson);

        String matchedRuleId = "rule-match";

        // Candidate 1: match (all conditions pass, is matched rule)
        CandidateSnapshot matchCandidate = CandidateSnapshot.builder()
                .ruleId(matchedRuleId)
                .endpoint("/api/orders")
                .description("match rule")
                .enabled(true)
                .bodyCondition("type=" + fieldValue)
                .queryCondition(null)
                .headerCondition(null)
                .priority(1)
                .build();

        // Candidate 2: near-miss (partial match)
        CandidateSnapshot nearMissCandidate = CandidateSnapshot.builder()
                .ruleId("rule-nearmiss")
                .endpoint("/api/orders")
                .description("near-miss rule")
                .enabled(true)
                .bodyCondition("type=" + fieldValue + ";env=" + mismatchValue)
                .queryCondition(null)
                .headerCondition(null)
                .priority(2)
                .build();

        // Candidate 3: skipped (disabled)
        CandidateSnapshot skippedCandidate = CandidateSnapshot.builder()
                .ruleId("rule-skipped")
                .endpoint("/api/orders")
                .description("skipped rule")
                .enabled(false)
                .bodyCondition("type=anything")
                .queryCondition(null)
                .headerCondition(null)
                .priority(3)
                .build();

        LogTask task = LogTask.builder()
                .ruleId(matchedRuleId)
                .endpoint("/api/orders")
                .matched(true)
                .responseTimeMs(10)
                .requestTime(LocalDateTime.now())
                .candidates(List.of(matchCandidate, nearMissCandidate, skippedCandidate))
                .preparedBody(prepared)
                .queryString(null)
                .headers(Map.of())
                .build();

        String json = agent.analyzeMatchChain(task);
        assertThat(json).isNotNull();

        List<Map<String, Object>> chain = MAPPER.readValue(json, new TypeReference<>() {});
        assertThat(chain).isNotEmpty();

        for (Map<String, Object> entry : chain) {
            // Must contain ruleId, endpoint, reason
            assertThat(entry).containsKey("ruleId");
            assertThat(entry).containsKey("endpoint");
            assertThat(entry).containsKey("reason");

            // reason must be a valid value
            String reason = (String) entry.get("reason");
            assertThat(VALID_REASONS).contains(reason);
        }
    }

    // ==================== Property 17: JSON round-trip ====================

    /**
     * Feature: agent-framework-log-agent, Property 17: Match chain JSON round-trip
     * <p>
     * 序列化再反序列化後物件等價。
     * <p>
     * **Validates: Requirements 10.4**
     */
    @Property(tries = 20)
    void matchChainJsonRoundTrip(
            @ForAll("alphaValues") String fieldValue) throws Exception {

        LogAgent agent = createAnalysisAgent();

        String bodyJson = String.format("{\"status\":\"%s\"}", fieldValue);
        ConditionMatcher.PreparedBody prepared = prepareJsonBody(bodyJson);

        CandidateSnapshot candidate = CandidateSnapshot.builder()
                .ruleId("rule-rt")
                .endpoint("/api/roundtrip")
                .description("round-trip test")
                .enabled(true)
                .bodyCondition("status=" + fieldValue)
                .queryCondition(null)
                .headerCondition(null)
                .priority(1)
                .build();

        LogTask task = LogTask.builder()
                .ruleId("rule-rt")
                .endpoint("/api/roundtrip")
                .matched(true)
                .responseTimeMs(10)
                .requestTime(LocalDateTime.now())
                .candidates(List.of(candidate))
                .preparedBody(prepared)
                .queryString(null)
                .headers(Map.of())
                .build();

        String json = agent.analyzeMatchChain(task);
        assertThat(json).isNotNull();

        // First parse
        List<Map<String, Object>> firstParse = MAPPER.readValue(json, new TypeReference<>() {});

        // Re-serialize and parse again
        String reserialized = MAPPER.writeValueAsString(firstParse);
        List<Map<String, Object>> secondParse = MAPPER.readValue(reserialized, new TypeReference<>() {});

        // Round-trip: both parses should be equivalent
        assertThat(secondParse).isEqualTo(firstParse);

        // Verify key fields preserved
        for (Map<String, Object> entry : secondParse) {
            assertThat(entry.get("ruleId")).isNotNull();
            assertThat(entry.get("reason")).isNotNull();
            assertThat(entry.get("endpoint")).isNotNull();
        }
    }

    // ==================== Providers ====================

    @Provide
    Arbitrary<String> alphaRuleIds() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(3)
                .ofMaxLength(10)
                .map(s -> "rule-" + s);
    }

    @Provide
    Arbitrary<String> alphaValues() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(2)
                .ofMaxLength(8);
    }
}
