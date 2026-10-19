package com.echo.core.match;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Illegal conditions must be rejected during compilation (storage rules) and the location of the error must be pointed out. */
class MatcherCompilerValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final MatcherCompiler compiler = MatcherCompiler.withDefaults();

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            // Source
            "{'source': 'cookie', 'name': 'a', 'op': 'exists'}                    | $.source      | unknown source",
            "{'source': 'header', 'op': 'exists'}                                 | $.name        | is required",
            "{'source': 'method', 'name': 'x', 'op': 'exists'}                    | $.name        | is not allowed",
            "{'source': 'header', 'name': 'a', 'selector': {'raw': true}, 'op': 'exists'} | $.selector | is not allowed",
            "{'source': 'body', 'op': 'exists'}                                   | $.selector    | is required",
            "{'source': 'body', 'selector': {'jsonPath': '$[?(@.a'}, 'op': 'exists'} | $.selector | Invalid JSONPath",
            "{'source': 'body', 'selector': {'xpath': '//a['}, 'op': 'exists'}    | $.selector    | Invalid XPath",
            "{'source': 'body', 'selector': {'field': 'a..b'}, 'op': 'exists'}    | $.selector    | Invalid field path",
            // Operator
            "{'source': 'header', 'name': 'a', 'op': 'like', 'value': 'x'}        | $.op          | unknown op",
            "{'source': 'header', 'name': 'a', 'op': 'equals'}                    | $.value       | is required",
            "{'source': 'header', 'name': 'a', 'op': 'exists', 'value': 'x'}      | $.value       | is not allowed",
            "{'source': 'header', 'name': 'a', 'op': 'absent', 'onMissing': 'fail'} | $.onMissing | is not allowed",
            "{'source': 'header', 'name': 'a', 'op': 'matches', 'value': 'x', 'options': {'caseInsensitive': true}} | $.options.caseInsensitive | is not supported",
            "{'source': 'header', 'name': 'a', 'op': 'equals', 'value': 'x', 'options': {'caseInsensitive': 'yes'}} | $ | must be a boolean",
            "{'source': 'header', 'name': 'a', 'op': 'equals', 'value': {'a': 1}} | $             | equals requires",
            "{'source': 'header', 'name': 'a', 'op': 'contains', 'value': null}   | $             | does not accept null",
            "{'source': 'header', 'name': 'a', 'op': 'matches', 'value': 1}       | $             | string pattern",
            "{'source': 'header', 'name': 'a', 'op': 'matches', 'value': '(a'}    | $             | Invalid regex",
            "{'source': 'header', 'name': 'a', 'op': 'gt', 'value': 'ten'}        | $             | requires a number",
            "{'source': 'header', 'name': 'a', 'op': 'in', 'value': 'a'}          | $             | requires an array",
            // Nest location
            "{'all': [{'any': [{'source': 'header', 'name': 'a', 'op': 'nope', 'value': 1}]}]} | $.all[0].any[0].op | unknown op"
    })
    void rejectsInvalidLeaf(String spec, String expectedPath, String messageFragment) {
        assertThatThrownBy(() -> compiler.compile(MAPPER.readTree(spec.replace('\'', '"'))))
                .isInstanceOf(InvalidMatchSpecException.class)
                .hasMessageContaining(messageFragment)
                .satisfies(e -> assertThat(((InvalidMatchSpecException) e).path()).isEqualTo(expectedPath));
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 201})
    void enforcesRegexPatternLengthLimit(int length) {
        String spec = "{\"source\":\"header\",\"name\":\"a\",\"op\":\"matches\",\"value\":\"" + "a".repeat(length) + "\"}";
        if (length <= MatcherLimits.DEFAULT.maxRegexPatternLength()) {
            assertThat(compiler.compile(readTree(spec))).isNotNull();
        } else {
            assertThatThrownBy(() -> compiler.compile(readTree(spec)))
                    .isInstanceOf(InvalidMatchSpecException.class)
                    .hasMessageContaining("exceeds 200 characters");
        }
    }

    private static JsonNode readTree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
