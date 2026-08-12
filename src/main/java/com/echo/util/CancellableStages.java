package com.echo.util;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;

/** CompletionStage transformations that propagate downstream cancellation upstream. */
@SuppressWarnings("FutureReturnValueIgnored")
public final class CancellableStages {

    private CancellableStages() {
    }

    public static <T, R> CompletionStage<R> map(
            CompletionStage<T> source,
            Function<? super T, ? extends R> mapper) {
        CompletableFuture<T> sourceFuture = source.toCompletableFuture();
        if (sourceFuture.isDone()) return mapCompleted(sourceFuture, mapper);
        CompletableFuture<R> result = new CompletableFuture<>();
        cancelSourceWithResult(result, sourceFuture);
        sourceFuture.whenComplete((value, error) -> {
            if (result.isDone()) return;
            if (error != null) {
                completeFailure(result, error);
                return;
            }
            try {
                result.complete(mapper.apply(value));
            } catch (Throwable mappingError) {
                result.completeExceptionally(mappingError);
            }
        });
        return result;
    }

    public static <T, R> CompletionStage<R> handle(
            CompletionStage<T> source,
            BiFunction<? super T, Throwable, ? extends R> handler) {
        CompletableFuture<T> sourceFuture = source.toCompletableFuture();
        if (sourceFuture.isDone()) return handleCompleted(sourceFuture, handler);
        CompletableFuture<R> result = new CompletableFuture<>();
        cancelSourceWithResult(result, sourceFuture);
        sourceFuture.whenComplete((value, error) -> {
            if (result.isDone()) return;
            try {
                result.complete(handler.apply(value, error));
            } catch (Throwable handlingError) {
                result.completeExceptionally(handlingError);
            }
        });
        return result;
    }

    public static <T, R> CompletionStage<R> thenCompose(
            CompletionStage<T> source,
            Function<? super T, ? extends CompletionStage<R>> mapper) {
        CompletableFuture<T> sourceFuture = source.toCompletableFuture();
        if (sourceFuture.isDone()) return composeCompleted(sourceFuture, mapper);
        CompletableFuture<R> result = new CompletableFuture<>();
        AtomicReference<CompletableFuture<?>> active = new AtomicReference<>(sourceFuture);
        result.whenComplete((value, error) -> {
            if (!result.isCancelled()) return;
            sourceFuture.cancel(true);
            CompletableFuture<?> current = active.get();
            if (current != sourceFuture) current.cancel(true);
        });
        sourceFuture.whenComplete((value, error) -> {
            if (result.isDone()) return;
            if (error != null) {
                completeFailure(result, error);
                return;
            }
            try {
                CompletableFuture<R> next = mapper.apply(value).toCompletableFuture();
                active.set(next);
                if (result.isCancelled()) {
                    next.cancel(true);
                    return;
                }
                next.whenComplete((mapped, mappedError) -> {
                    if (result.isDone()) return;
                    if (mappedError == null) result.complete(mapped);
                    else completeFailure(result, mappedError);
                });
            } catch (Throwable mappingError) {
                result.completeExceptionally(mappingError);
            }
        });
        return result;
    }

    private static <T, R> CompletionStage<R> mapCompleted(
            CompletableFuture<T> source,
            Function<? super T, ? extends R> mapper) {
        T value;
        try {
            value = source.join();
        } catch (CancellationException error) {
            return cancelledFuture();
        } catch (CompletionException error) {
            return CompletableFuture.failedFuture(unwrapCompletion(error));
        }
        try {
            return CompletableFuture.completedFuture(mapper.apply(value));
        } catch (Throwable error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    private static <T, R> CompletionStage<R> handleCompleted(
            CompletableFuture<T> source,
            BiFunction<? super T, Throwable, ? extends R> handler) {
        T value = null;
        Throwable failure = null;
        try {
            value = source.join();
        } catch (CancellationException error) {
            failure = error;
        } catch (CompletionException error) {
            failure = unwrapCompletion(error);
        }
        try {
            return CompletableFuture.completedFuture(handler.apply(value, failure));
        } catch (Throwable error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    private static <T, R> CompletionStage<R> composeCompleted(
            CompletableFuture<T> source,
            Function<? super T, ? extends CompletionStage<R>> mapper) {
        T value;
        try {
            value = source.join();
        } catch (CancellationException error) {
            return cancelledFuture();
        } catch (CompletionException error) {
            return CompletableFuture.failedFuture(unwrapCompletion(error));
        }
        try {
            return Objects.requireNonNull(mapper.apply(value), "mapper returned null");
        } catch (Throwable error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    private static Throwable unwrapCompletion(CompletionException error) {
        return error.getCause() == null ? error : error.getCause();
    }

    private static <T> CompletableFuture<T> cancelledFuture() {
        CompletableFuture<T> cancelled = new CompletableFuture<>();
        cancelled.cancel(false);
        return cancelled;
    }

    private static void cancelSourceWithResult(CompletableFuture<?> result,
                                               CompletableFuture<?> source) {
        result.whenComplete((value, error) -> {
            if (result.isCancelled()) source.cancel(true);
        });
    }

    private static void completeFailure(CompletableFuture<?> result, Throwable error) {
        if (error instanceof CancellationException) result.cancel(false);
        else result.completeExceptionally(error);
    }
}
