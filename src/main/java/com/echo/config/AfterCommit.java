package com.echo.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Cache invalidation coordinated with the current transaction.
 *
 * <p>If a cache is cleared inside a transaction, concurrent requests may read and recache old data before the commit.
 * That stale data would survive until the next change or expiry. Clear immediately, then use {@link #schedule} to clear again after commit.
 * Schedule each action only once per transaction; nested REQUIRES_NEW transactions remain independent.</p>
 */
@Slf4j
public final class AfterCommit {

    private AfterCommit() {
    }

    /**
     * Schedule an action after commit when inside a transaction, deduplicating the same key within that transaction.
     * Return whether a transaction is active; otherwise schedule nothing and let the caller act immediately.
     */
    public static boolean schedule(Object key, Runnable action) {
        Tracker tracker = tracker();
        if (tracker == null) {
            return false;
        }
        tracker.actions.putIfAbsent(key, action);
        return true;
    }

    /** Like {@link #schedule}, but run after all {@link #schedule} actions, for example to notify only after all cache invalidations finish. */
    public static boolean scheduleLast(Object key, Runnable action) {
        Tracker tracker = tracker();
        if (tracker == null) {
            return false;
        }
        tracker.lastActions.putIfAbsent(key, action);
        return true;
    }

    /** Return true the first time a key appears in a transaction; always return true outside transactions. */
    public static boolean firstInTransaction(Object key) {
        Tracker tracker = tracker();
        return tracker == null || tracker.seen.add(key);
    }

    private static Tracker tracker() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return null;
        }
        // Suspend synchronization with a suspended transaction (REQUIRES_NEW), giving each transaction its own Tracker.
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            if (synchronization instanceof Tracker tracker) {
                return tracker;
            }
        }
        Tracker tracker = new Tracker();
        TransactionSynchronizationManager.registerSynchronization(tracker);
        return tracker;
    }

    private static final class Tracker implements TransactionSynchronization {
        private final Map<Object, Runnable> actions = new LinkedHashMap<>();
        private final Map<Object, Runnable> lastActions = new LinkedHashMap<>();
        private final Set<Object> seen = new HashSet<>();

        @Override
        public void afterCommit() {
            run(actions);
            run(lastActions);
        }

        private static void run(Map<Object, Runnable> scheduled) {
            for (Runnable action : scheduled.values()) {
                try {
                    action.run();
                } catch (RuntimeException e) {
                    log.warn("Post-commit cache eviction failed: {}", e.toString());
                }
            }
        }
    }
}
