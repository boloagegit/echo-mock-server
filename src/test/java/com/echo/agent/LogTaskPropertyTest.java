package com.echo.agent;

import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.StringLength;

import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LogTask immutability property test (Property 18).
 * <p>
 * Feature: agent-framework-log-agent, Property 18: LogTask immutability
 * <p>
 * Verify that LogTask candidates and headers are immutable collections,
 * Modify operation threw UnsupportedOperationException.
 */
class LogTaskPropertyTest {

    // ==================== Property 18 ====================

    /**
     * Feature: agent-framework-log-agent, Property 18: LogTask immutability
     * <p>
     * candidates is an unmodifiable collection, and the add operation throws UnsupportedOperationException.
     * <p>
     * **Validates: Requirements 11.3**
     */
    @Property(tries = 20)
    void candidatesListIsUnmodifiable(
            @ForAll @IntRange(min = 0, max = 10) int candidateCount) {

        List<CandidateSnapshot> mutableCandidates = new ArrayList<>();
        for (int i = 0; i < candidateCount; i++) {
            mutableCandidates.add(CandidateSnapshot.builder()
                    .ruleId("rule-" + i)
                    .endpoint("/api/test-" + i)
                    .description("desc-" + i)
                    .enabled(true)
                    .priority(i)
                    .build());
        }

        LogTask task = LogTask.builder()
                .endpoint("/test")
                .matched(false)
                .requestTime(LocalDateTime.now())
                .candidates(mutableCandidates)
                .headers(Map.of())
                .build();

        assertThat(task.getCandidates()).hasSize(candidateCount);

        assertThatThrownBy(() -> task.getCandidates().add(
                CandidateSnapshot.builder().ruleId("intruder").endpoint("/x").priority(0).build()))
                .isInstanceOf(UnsupportedOperationException.class);

        if (candidateCount > 0) {
            assertThatThrownBy(() -> task.getCandidates().remove(0))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> task.getCandidates().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    /**
     * Feature: agent-framework-log-agent, Property 18: LogTask immutability
     * <p>
     * headers is an unmodifiable collection, and the put operation throws UnsupportedOperationException.
     * <p>
     * **Validates: Requirements 11.3**
     */
    @Property(tries = 20)
    void headersMapIsUnmodifiable(
            @ForAll @IntRange(min = 0, max = 10) int headerCount) {

        Map<String, String> mutableHeaders = new HashMap<>();
        for (int i = 0; i < headerCount; i++) {
            mutableHeaders.put("Header-" + i, "value-" + i);
        }

        LogTask task = LogTask.builder()
                .endpoint("/test")
                .matched(false)
                .requestTime(LocalDateTime.now())
                .candidates(List.of())
                .headers(mutableHeaders)
                .build();

        assertThat(task.getHeaders()).hasSize(headerCount);

        assertThatThrownBy(() -> task.getHeaders().put("X-Intruder", "evil"))
                .isInstanceOf(UnsupportedOperationException.class);

        if (headerCount > 0) {
            String firstKey = task.getHeaders().keySet().iterator().next();
            assertThatThrownBy(() -> task.getHeaders().remove(firstKey))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> task.getHeaders().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    /**
     * Feature: agent-framework-log-agent, Property 18: LogTask immutability
     * <p>
     * Modifying the original mutable list/map does not affect candidates and headers inside LogTask.
     * <p>
     * **Validates: Requirements 11.3**
     */
    @Property(tries = 20)
    void mutatingOriginalCollectionsDoesNotAffectLogTask(
            @ForAll @IntRange(min = 1, max = 10) int count) {

        List<CandidateSnapshot> mutableCandidates = new ArrayList<>();
        Map<String, String> mutableHeaders = new HashMap<>();
        for (int i = 0; i < count; i++) {
            mutableCandidates.add(CandidateSnapshot.builder()
                    .ruleId("rule-" + i)
                    .endpoint("/api/" + i)
                    .priority(i)
                    .build());
            mutableHeaders.put("H-" + i, "v-" + i);
        }

        LogTask task = LogTask.builder()
                .endpoint("/test")
                .matched(false)
                .requestTime(LocalDateTime.now())
                .candidates(mutableCandidates)
                .headers(mutableHeaders)
                .build();

        int originalCandidateSize = task.getCandidates().size();
        int originalHeaderSize = task.getHeaders().size();

        // Mutate the original collections
        mutableCandidates.add(CandidateSnapshot.builder()
                .ruleId("extra").endpoint("/extra").priority(99).build());
        mutableHeaders.put("X-Extra", "extra");

        // LogTask's internal collections should be unaffected
        assertThat(task.getCandidates()).hasSize(originalCandidateSize);
        assertThat(task.getHeaders()).hasSize(originalHeaderSize);
    }

    /**
     * Feature: agent-framework-log-agent, Property 18: LogTask immutability
     * <p>
     * null candidates and null headers should produce empty immutable collections.
     * <p>
     * **Validates: Requirements 11.3**
     */
    @Property(tries = 20)
    void nullCandidatesAndHeadersProduceEmptyUnmodifiableCollections(
            @ForAll @StringLength(min = 1, max = 20) String endpoint) {

        LogTask task = LogTask.builder()
                .endpoint(endpoint)
                .matched(false)
                .requestTime(LocalDateTime.now())
                .candidates(null)
                .headers(null)
                .build();

        assertThat(task.getCandidates()).isEmpty();
        assertThat(task.getHeaders()).isEmpty();

        assertThatThrownBy(() -> task.getCandidates().add(
                CandidateSnapshot.builder().ruleId("x").endpoint("/x").priority(0).build()))
                .isInstanceOf(UnsupportedOperationException.class);

        assertThatThrownBy(() -> task.getHeaders().put("key", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
