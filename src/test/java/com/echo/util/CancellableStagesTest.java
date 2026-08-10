package com.echo.util;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CancellableStagesTest {

    @Test
    void mapPropagatesCancellationToSource() {
        CompletableFuture<String> source = new CompletableFuture<>();

        CompletableFuture<Integer> result = CancellableStages.map(source, String::length)
                .toCompletableFuture();
        result.cancel(true);

        assertThat(source).isCancelled();
    }

    @Test
    void handlePropagatesCancellationToSource() {
        CompletableFuture<String> source = new CompletableFuture<>();

        CompletableFuture<String> result = CancellableStages.handle(
                source, (value, error) -> value).toCompletableFuture();
        result.cancel(true);

        assertThat(source).isCancelled();
    }

    @Test
    void composePropagatesCancellationToActiveChild() {
        CompletableFuture<String> source = CompletableFuture.completedFuture("ready");
        CompletableFuture<Integer> child = new CompletableFuture<>();
        AtomicReference<String> mapped = new AtomicReference<>();

        CompletableFuture<Integer> result = CancellableStages.thenCompose(source, value -> {
            mapped.set(value);
            return child;
        }).toCompletableFuture();
        result.cancel(true);

        assertThat(mapped).hasValue("ready");
        assertThat(child).isCancelled();
    }

    @Test
    void completedSourceUsesImmediatePathWithoutChangingFailureSemantics() {
        var source = CompletableFuture.<String>failedFuture(
                new IllegalArgumentException("invalid"));

        var handled = CancellableStages.handle(source,
                (value, error) -> error.getMessage()).toCompletableFuture();
        var mapped = CancellableStages.map(source, String::length).toCompletableFuture();

        assertThat(handled.join()).isEqualTo("invalid");
        assertThat(mapped).isCompletedExceptionally();
        assertThat(mapped.handle((value, error) -> error).join())
                .isInstanceOf(IllegalArgumentException.class);
    }
}
