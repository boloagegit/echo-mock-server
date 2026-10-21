package com.echo.core.match;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

class RegexStackCapacityTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void recursiveRegexReportsCapacityFailureInsteadOfAnErrorOrFalseMatch(boolean legacy) throws Exception {
        var mapper = new ObjectMapper();
        var specification = legacy
                ? mapper.readTree("{\"legacy\":{\"source\":\"header\",\"condition\":\"X-Probe~=^(a|aa)+$\"}}")
                : mapper.readTree("{\"source\":\"header\",\"name\":\"X-Probe\",\"op\":\"matches\",\"value\":\"^(a|aa)+$\"}");
        var matcher = MatcherCompiler.withDefaults().compile(specification);
        var request = RequestView.of("GET", "/", null, Map.of("X-Probe", "a".repeat(10000)), BodyView.empty());
        var worker = Executors.newSingleThreadExecutor(task -> new Thread(null, task, "regex-capacity-test", 128 * 1024L));
        try {
            var result = worker.submit(() -> matcher.matches(new EvalContext(request)));
            try {
                result.get(5, TimeUnit.SECONDS);
                fail("An indeterminate match must not become a false match or fall through to forwarding");
            } catch (ExecutionException failure) {
                assertThat(failure.getCause()).isInstanceOf(RuntimeException.class)
                        .hasMessage("REGEX_STACK_CAPACITY_EXHAUSTED");
            }
            assertThat(worker.submit(() -> MatcherCompiler.withDefaults()
                    .compile(mapper.readTree("{\"source\":\"header\",\"name\":\"X-Probe\",\"op\":\"matches\",\"value\":\"a+\"}"))
                    .matches(new EvalContext(request))).get(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            worker.shutdownNow();
            assertThat(worker.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }
}
