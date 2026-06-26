package com.echo.service;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InterruptibleCharSequenceTest {

    @Test
    void shouldTimeoutOnCatastrophicBacktracking() {
        // (.*a){25} causes catastrophic backtracking even on modern JVMs
        String evilPattern = "(.*a){25}";
        String input = "a".repeat(25) + "b";
        Pattern compiled = Pattern.compile(evilPattern);
        CharSequence wrapped = new InterruptibleCharSequence(input, 100);

        assertThatThrownBy(() -> compiled.matcher(wrapped).matches())
                .isInstanceOf(InterruptibleCharSequence.RegexTimeoutException.class);
    }

    @Test
    void shouldSucceedForNormalPattern() {
        String pattern = "hello.*world";
        String input = "hello beautiful world";
        Pattern compiled = Pattern.compile(pattern);
        CharSequence wrapped = new InterruptibleCharSequence(input, 1000);

        assertThat(compiled.matcher(wrapped).matches()).isTrue();
    }

    @Test
    void shouldReturnCorrectLength() {
        CharSequence wrapped = new InterruptibleCharSequence("test", 1000);
        assertThat(wrapped.length()).isEqualTo(4);
    }

    @Test
    void shouldReturnCorrectSubSequence() {
        CharSequence wrapped = new InterruptibleCharSequence("hello world", 1000);
        CharSequence sub = wrapped.subSequence(0, 5);
        assertThat(sub.toString()).isEqualTo("hello");
    }
}
