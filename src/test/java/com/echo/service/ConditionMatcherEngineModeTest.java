package com.echo.service;

import com.echo.service.ConditionMatcher.EngineMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@code echo.engine.matcher} 開關：設定綁定、compiled 模式與 legacy 的一致性。 */
class ConditionMatcherEngineModeTest {

    private static final String XML = "<Order><Status>OPEN</Status><Item sku=\"S1\">2</Item>"
            + "<Item sku=\"S2\">5</Item></Order>";
    private static final String JSON = "{\"user\":{\"type\":\"vip\",\"tags\":[\"a\"]},\"n\":1}";

    private final ConditionMatcher legacy = new ConditionMatcher(2 * 1024 * 1024, EngineMode.LEGACY);
    private final ConditionMatcher compiled = new ConditionMatcher(2 * 1024 * 1024, EngineMode.COMPILED);

    @ParameterizedTest
    @CsvSource({"legacy, LEGACY", "LEGACY, LEGACY", "compiled, COMPILED", " Compiled , COMPILED"})
    void parsesEngineMode(String value, EngineMode expected) {
        assertThat(EngineMode.parse(value)).isEqualTo(expected);
    }

    @Test
    void blankEngineModeFallsBackToLegacyAndUnknownFailsFast() {
        assertThat(EngineMode.parse(null)).isEqualTo(EngineMode.LEGACY);
        assertThat(EngineMode.parse(" ")).isEqualTo(EngineMode.LEGACY);
        assertThatThrownBy(() -> EngineMode.parse("fast"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("echo.engine.matcher");
    }

    @Test
    void springBindsEngineModeAndFieldValueLimit() {
        try (AnnotationConfigApplicationContext context = springContext(Map.of(
                "echo.engine.matcher", "compiled",
                "echo.matcher.max-field-value-length", "2"))) {
            ConditionMatcher matcher = context.getBean(ConditionMatcher.class);

            assertThat(matcher.getEngineMode()).isEqualTo(EngineMode.COMPILED);
            // 先前 Spring 會選無參數建構子，這個設定一直沒有生效
            assertThat(matcher.matches("user.type=vip", "{\"user\":{\"type\":\"vip\"}}")).isFalse();
            assertThat(matcher.matches("user.type=ab", "{\"user\":{\"type\":\"ab\"}}")).isTrue();
        }
    }

    @Test
    void springDefaultsToLegacy() {
        try (AnnotationConfigApplicationContext context = springContext(Map.of())) {
            assertThat(context.getBean(ConditionMatcher.class).getEngineMode()).isEqualTo(EngineMode.LEGACY);
        }
    }

    @Test
    void compiledModeReusesJmsStreamingOutcomes() {
        List<String> streamable = List.of("//Status=OPEN", "Item=5");
        ConditionMatcher.PreparedBody legacyBody = legacy.prepareBodyForConditions(XML, streamable);
        ConditionMatcher.PreparedBody compiledBody = compiled.prepareBodyForConditions(XML, streamable);

        for (String condition : List.of("//Status=OPEN", "Item=5", "Item=9", "//Item/@sku=S2")) {
            assertThat(compiled.matchesPrepared(condition, null, null, compiledBody, null, null))
                    .as(condition)
                    .isEqualTo(legacy.matchesPrepared(condition, null, null, legacyBody, null, null));
        }
        // 串流預先算好的結果沿用同一份暫存，key 與舊版相同
        assertThat(compiledBody.getMatchOutcomesSnapshot()).isEqualTo(legacyBody.getMatchOutcomesSnapshot());
    }

    @Test
    void compiledModeMatchesLegacyThroughPublicApi() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Type", "vip");
        String[][] rules = {
                {"user.type=vip", null, null},
                {"user.type=normal;n=1", null, null},
                {"$.user.tags[0]=a", "page=1", "x-type=vip"},
                {null, "page!=2", "X-Type~=v.*"},
                {null, null, null},
                {"user.missing!=x;$.user.missing!=x", null, null}};

        for (String[] rule : rules) {
            ConditionMatcher.PreparedBody legacyBody = legacy.prepareBody(JSON);
            ConditionMatcher.PreparedBody compiledBody = compiled.prepareBody(JSON);
            assertThat(compiled.matchesPrepared(rule[0], rule[1], rule[2], compiledBody, "page=1", headers))
                    .isEqualTo(legacy.matchesPrepared(rule[0], rule[1], rule[2], legacyBody, "page=1", headers));

            ConditionMatcher.ConditionDetail expected = legacy.matchesPreparedWithDetail(
                    rule[0], rule[1], rule[2], legacy.prepareBody(JSON), "page=1", headers);
            ConditionMatcher.ConditionDetail actual = compiled.matchesPreparedWithDetail(
                    rule[0], rule[1], rule[2], compiled.prepareBody(JSON), "page=1", headers);
            assertThat(actual.isOverallMatch()).isEqualTo(expected.isOverallMatch());
            assertThat(actual.score()).isEqualTo(expected.score());
            assertThat(actual.getResults()).usingRecursiveFieldByFieldElementComparator()
                    .isEqualTo(expected.getResults());
        }
    }

    @Test
    void compiledModeRestoresOutcomesForBackgroundAnalysis() {
        ConditionMatcher.PreparedBody requestBody = compiled.prepareBody(JSON);
        compiled.matchesPrepared("user.type=vip", null, null, requestBody, null, null);

        ConditionMatcher.PreparedBody analysisBody =
                compiled.prepareBody(JSON, requestBody.getMatchOutcomesSnapshot());

        assertThat(analysisBody.getMatchOutcomesSnapshot()).containsEntry("body:user.type=vip", true);
        assertThat(compiled.matchesPreparedWithDetail("user.type=vip", null, null, analysisBody, null, null)
                .isOverallMatch()).isTrue();
    }

    private static AnnotationConfigApplicationContext springContext(Map<String, Object> properties) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        // CI 以 -Decho.engine.matcher=compiled 執行整套測試；這裡只驗證明確給定的設定
        context.getEnvironment().getPropertySources().remove("systemProperties");
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
        context.registerBean(PropertySourcesPlaceholderConfigurer.class);
        context.register(ConditionMatcher.class);
        context.refresh();
        return context;
    }
}
