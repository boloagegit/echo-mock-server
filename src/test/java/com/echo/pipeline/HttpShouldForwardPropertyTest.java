package com.echo.pipeline;

import com.echo.entity.HttpRule;
import com.echo.service.ConditionMatcher;
import com.echo.service.MatchChainEntry;
import com.echo.service.RequestLogService;
import com.echo.service.RuleService;
import net.jqwik.api.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP shouldForward Decision correctness attribute test
 * <p>
 * Feature: protocol-pipeline-refactor, Property 4: HTTP shouldForward Decision correctness
 * <p>
 * Use jqwik to generate a random targetHost string (including null, "default", empty string, blank, and any string),
 * Verify {@code HttpMockPipeline.shouldForward()} and return {@code true} if and only if
 * {@code targetHost != null && !targetHost.equals("default")}。
 *
 * <b>Validates: Requirements 5.2</b>
 */
class HttpShouldForwardPropertyTest {

    /**
     * Minimized HttpMockPipeline, only tests shouldForward(), no real dependencies required.
     */
    private final HttpMockPipeline pipeline = new HttpMockPipeline(
            new StubConditionMatcher(), null, null, null, null, null);

    // ==================== Stub ConditionMatcher ====================

    static class StubConditionMatcher extends ConditionMatcher {
        @Override
        public boolean matchesPrepared(String bodyCondition, String queryCondition,
                                       String headerCondition,
                                       PreparedBody prepared, String queryString,
                                       Map<String, String> headers) {
            return false;
        }

        @Override
        public PreparedBody prepareBody(String body) {
            return PreparedBody.empty();
        }
    }

    // ==================== Generators ====================

    @Provide
    Arbitrary<String> targetHostStrings() {
        return Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.just("default"),
                Arbitraries.just(""),
                Arbitraries.just("   "),
                Arbitraries.just("\t"),
                Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(30),
                Arbitraries.strings().withCharRange('A', 'Z').ofMinLength(1).ofMaxLength(20),
                Arbitraries.of("backend.example.com", "10.0.0.1", "localhost", "DEFAULT",
                        "Default", "default ", " default")
        );
    }

    // ==================== Property Tests ====================

    /**
     * Feature: protocol-pipeline-refactor, Property 4: HTTP shouldForward Decision correctness
     * <p>
     * For any targetHost string, shouldForward() returns true if and only if
     * targetHost != null and targetHost is not equal to "default".
     *
     * <b>Validates: Requirements 5.2</b>
     */
    @Property(tries = 200)
    void shouldForwardReturnsTrueIffTargetHostIsNotNullAndNotDefault(
            @ForAll("targetHostStrings") String targetHost) {

        MockRequest request = MockRequest.builder()
                .targetHost(targetHost)
                .build();

        boolean result = pipeline.shouldForward(request);
        boolean expected = targetHost != null && !"default".equals(targetHost);

        assertThat(result)
                .as("shouldForward() for targetHost='%s' should be %s", targetHost, expected)
                .isEqualTo(expected);
    }

    /**
     * Feature: protocol-pipeline-refactor, Property 4: HTTP shouldForward Decision correctness
     * <p>
     * null targetHost should never be forwarded.
     *
     * <b>Validates: Requirements 5.2</b>
     */
    @Property(tries = 100)
    void nullTargetHostNeverForwards() {
        MockRequest request = MockRequest.builder()
                .targetHost(null)
                .build();

        assertThat(pipeline.shouldForward(request))
                .as("null targetHost should never forward")
                .isFalse();
    }

    /**
     * Feature: protocol-pipeline-refactor, Property 4: HTTP shouldForward Decision correctness
     * <p>
     * "default" targetHost should never be forwarded.
     *
     * <b>Validates: Requirements 5.2</b>
     */
    @Property(tries = 100)
    void defaultTargetHostNeverForwards() {
        MockRequest request = MockRequest.builder()
                .targetHost("default")
                .build();

        assertThat(pipeline.shouldForward(request))
                .as("'default' targetHost should never forward")
                .isFalse();
    }

    /**
     * Feature: protocol-pipeline-refactor, Property 4: HTTP shouldForward Decision correctness
     * <p>
     * Any targetHost that is not null and is not "default" should be forwarded.
     *
     * <b>Validates: Requirements 5.2</b>
     */
    @Property(tries = 200)
    void nonNullNonDefaultTargetHostAlwaysForwards(
            @ForAll("nonDefaultNonNullHosts") String targetHost) {

        MockRequest request = MockRequest.builder()
                .targetHost(targetHost)
                .build();

        assertThat(pipeline.shouldForward(request))
                .as("Non-null, non-'default' targetHost '%s' should always forward", targetHost)
                .isTrue();
    }

    @Provide
    Arbitrary<String> nonDefaultNonNullHosts() {
        return Arbitraries.strings()
                .ofMinLength(0)
                .ofMaxLength(50)
                .filter(s -> !"default".equals(s));
    }
}
