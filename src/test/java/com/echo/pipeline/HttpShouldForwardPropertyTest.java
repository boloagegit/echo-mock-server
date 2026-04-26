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
 * HTTP shouldForward 決策正確性屬性測試
 * <p>
 * Feature: protocol-pipeline-refactor, Property 4: HTTP shouldForward 決策正確性
 * <p>
 * 使用 jqwik 產生隨機 targetHost 字串（含 null、"default"、空字串、空白、任意字串），
 * 驗證 {@code HttpMockPipeline.shouldForward()} 回傳 {@code true} 若且唯若
 * {@code targetHost != null && !targetHost.equals("default")}。
 *
 * <b>Validates: Requirements 5.2</b>
 */
class HttpShouldForwardPropertyTest {

    /**
     * 最小化的 HttpMockPipeline，只測試 shouldForward()，不需要真正的依賴。
     */
    private final HttpMockPipeline pipeline = new HttpMockPipeline(
            new StubConditionMatcher(), null, null, null, null, null, null);

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
     * Feature: protocol-pipeline-refactor, Property 4: HTTP shouldForward 決策正確性
     * <p>
     * 對任意 targetHost 字串，shouldForward() 回傳 true 若且唯若
     * targetHost != null 且 targetHost 不等於 "default"。
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
     * Feature: protocol-pipeline-refactor, Property 4: HTTP shouldForward 決策正確性
     * <p>
     * null targetHost 永遠不應轉發。
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
     * Feature: protocol-pipeline-refactor, Property 4: HTTP shouldForward 決策正確性
     * <p>
     * "default" targetHost 永遠不應轉發。
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
     * Feature: protocol-pipeline-refactor, Property 4: HTTP shouldForward 決策正確性
     * <p>
     * 任何非 null 且非 "default" 的 targetHost 都應轉發。
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
