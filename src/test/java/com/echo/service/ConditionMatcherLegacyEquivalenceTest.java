package com.echo.service;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 舊版字串條件經由 {@link ConditionMatcher} 公開 API（新引擎）的結果，與舊版原始實作
 * （{@link LegacyConditionOracle}）一致；細部語意的差異測試見 {@code LegacyDifferentialTest}。
 */
class ConditionMatcherLegacyEquivalenceTest {

    private static final String XML = "<Order><Status>OPEN</Status><Item sku=\"S1\">2</Item>"
            + "<Item sku=\"S2\">5</Item></Order>";
    private static final String JSON = "{\"user\":{\"type\":\"vip\",\"tags\":[\"a\"]},\"n\":1}";

    private final LegacyConditionOracle oracle = new LegacyConditionOracle(2 * 1024 * 1024);
    private final ConditionMatcher matcher = new ConditionMatcher(2 * 1024 * 1024);

    @Test
    void springBindsFieldValueLimit() {
        try (AnnotationConfigApplicationContext context = springContext(Map.of(
                "echo.matcher.max-field-value-length", "2"))) {
            ConditionMatcher bound = context.getBean(ConditionMatcher.class);

            // 先前 Spring 會選無參數建構子，這個設定一直沒有生效
            assertThat(bound.matches("user.type=vip", "{\"user\":{\"type\":\"vip\"}}")).isFalse();
            assertThat(bound.matches("user.type=ab", "{\"user\":{\"type\":\"ab\"}}")).isTrue();
        }
    }

    @Test
    void jmsStreamingOutcomesMatchFullDomEvaluation() {
        List<String> streamable = List.of("//Status=OPEN", "Item=5");
        ConditionMatcher.PreparedBody streamed = prepareBodyFor(matcher, XML, streamable);

        for (String condition : List.of("//Status=OPEN", "Item=5", "Item=9", "//Item/@sku=S2")) {
            assertThat(matcher.matchesPrepared(condition, null, null, streamed, null, null))
                    .as(condition)
                    .isEqualTo(oracle.matchesPrepared(condition, null, null, oracle.prepareBody(XML), null, null));
        }
        // 串流預先算好的結果使用與比對引擎相同的暫存 key
        assertThat(streamed.getMatchOutcomesSnapshot())
                .containsEntry("body://Status=OPEN", true)
                .containsEntry("body:Item=5", true);
    }

    @Test
    void publicApiMatchesLegacyResultsAndDetails() {
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
            assertThat(matcher.matchesPrepared(rule[0], rule[1], rule[2], matcher.prepareBody(JSON), "page=1", headers))
                    .isEqualTo(oracle.matchesPrepared(rule[0], rule[1], rule[2], oracle.prepareBody(JSON),
                            "page=1", headers));

            ConditionMatcher.ConditionDetail expected = oracle.matchesPreparedWithDetail(
                    rule[0], rule[1], rule[2], oracle.prepareBody(JSON), "page=1", headers);
            ConditionMatcher.ConditionDetail actual = matcher.matchesPreparedWithDetail(
                    rule[0], rule[1], rule[2], matcher.prepareBody(JSON), "page=1", headers);
            assertThat(actual.isOverallMatch()).isEqualTo(expected.isOverallMatch());
            assertThat(actual.score()).isEqualTo(expected.score());
            assertThat(actual.getResults()).usingRecursiveFieldByFieldElementComparator()
                    .isEqualTo(expected.getResults());
        }
    }

    @Test
    void singleConditionStringSplitsQueryAndBodyConditions() {
        assertThat(matcher.matches("user.type=vip;?page=1", JSON, "page=1")).isTrue();
        assertThat(matcher.matches("user.type=vip;?page=2", JSON, "page=1")).isFalse();
        assertThat(matcher.matches("?page=1", null, "page=1")).isTrue();
        assertThat(matcher.matches(" ; ", JSON, null)).isTrue();
    }

    @Test
    void restoresOutcomesForBackgroundAnalysis() {
        ConditionMatcher.PreparedBody requestBody = matcher.prepareBody(JSON);
        matcher.matchesPrepared("user.type=vip", null, null, requestBody, null, null);

        ConditionMatcher.PreparedBody analysisBody =
                matcher.prepareBody(JSON, requestBody.getMatchOutcomesSnapshot());

        assertThat(analysisBody.getMatchOutcomesSnapshot()).containsEntry("body:user.type=vip", true);
        assertThat(matcher.matchesPreparedWithDetail("user.type=vip", null, null, analysisBody, null, null)
                .isOverallMatch()).isTrue();
    }

    private static AnnotationConfigApplicationContext springContext(Map<String, Object> properties) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
        context.registerBean(PropertySourcesPlaceholderConfigurer.class);
        context.register(ConditionMatcher.class);
        context.refresh();
        return context;
    }

    /** 以候選規則的 body 條件準備 body（與 pipeline 相同的規則）。 */
    private static ConditionMatcher.PreparedBody prepareBodyFor(ConditionMatcher matcher, String body,
                                                                java.util.List<String> bodyConditions) {
        return matcher.prepareBody(body, bodyConditions.stream()
                .map(condition -> matcher.conditions(condition, null, null))
                .toList());
    }
}
