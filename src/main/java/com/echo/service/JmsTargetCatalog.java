package com.echo.service;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Strict, request-pinned target snapshots for the single-process SQLite path. */
final class JmsTargetCatalog {
    private final Supplier<Snapshot> loader;
    private final Object loadLock = new Object();
    private final AtomicReference<State> state = new AtomicReference<>(new State(null, null));

    JmsTargetCatalog(Supplier<Snapshot> loader) {
        this.loader = loader;
    }

    Snapshot snapshot() {
        while (true) {
            State observed = state.get();
            if (observed.snapshot() != null) return observed.snapshot();
            CompletableFuture<LoadOutcome> pending;
            boolean owner = false;
            synchronized (loadLock) {
                observed = state.get();
                if (observed.snapshot() != null) return observed.snapshot();
                pending = observed.loading();
                if (pending == null) {
                    pending = new CompletableFuture<>();
                    State loading = new State(null, pending);
                    if (!state.compareAndSet(observed, loading)) continue;
                    observed = loading;
                    owner = true;
                }
            }
            if (owner) {
                LoadOutcome outcome;
                try {
                    Snapshot loaded = Objects.requireNonNull(loader.get(), "JMS target catalog is null");
                    // A commit during this load changes the state identity. Never
                    // overwrite a newer generation or publish pre-commit settings.
                    state.compareAndSet(observed, new State(loaded, null));
                    outcome = new LoadOutcome(null);
                } catch (RuntimeException | Error failure) {
                    // Existing waiters share this attempt's failure. Later requests
                    // may retry, without retaining a poisoned or obsolete snapshot.
                    state.compareAndSet(observed, new State(null, null));
                    outcome = new LoadOutcome(failure);
                }
                pending.complete(outcome);
            }
            pending.join().rethrowIfFailed();
            // Re-read the current state after success, including a load invalidated
            // while in progress. Warm lookups retain their lock-free fast path.
        }
    }

    void invalidate() {
        state.set(new State(null, null));
    }

    private record State(Snapshot snapshot, CompletableFuture<LoadOutcome> loading) { }

    /** Carry the original exception as data so join() never changes its classification. */
    private record LoadOutcome(Throwable failure) {
        void rethrowIfFailed() {
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
        }
    }

    static final class Snapshot {
        private final Map<Long, Profile> profiles;
        private final Long defaultId;

        Snapshot(Map<Long, Profile> profiles, Long defaultId) {
            this.profiles = Map.copyOf(profiles);
            this.defaultId = defaultId;
        }

        Optional<JmsTargetConnectionService.ResolvedTarget> active() {
            if (defaultId == null) return Optional.empty();
            Profile profile = profiles.get(defaultId);
            return profile == null || !profile.enabled ? Optional.empty() : Optional.of(profile.resolve());
        }

        JmsTargetConnectionService.ResolvedTarget enabled(long id) {
            Profile profile = profiles.get(id);
            if (profile == null) throw new IllegalArgumentException("JMS_CONNECTION_NOT_FOUND");
            if (!profile.enabled) throw new IllegalArgumentException("JMS_CONNECTION_DISABLED");
            return profile.resolve();
        }
    }

    /** Lazy decryption preserves isolation from unrelated invalid credentials. */
    static final class Profile {
        private final boolean enabled;
        private final Supplier<JmsTargetConnectionService.ResolvedTarget> loader;
        private volatile JmsTargetConnectionService.ResolvedTarget resolved;

        Profile(boolean enabled, Supplier<JmsTargetConnectionService.ResolvedTarget> loader) {
            this.enabled = enabled;
            this.loader = loader;
        }

        private JmsTargetConnectionService.ResolvedTarget resolve() {
            var value = resolved;
            if (value != null) return value;
            synchronized (this) {
                if (resolved == null) resolved = loader.get();
                return resolved;
            }
        }
    }
}
