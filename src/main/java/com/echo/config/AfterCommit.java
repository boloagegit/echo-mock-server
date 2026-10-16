package com.echo.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 與目前交易綁定的快取清除輔助。
 *
 * <p>交易內清除快取時，提交前若有其他請求讀到舊資料，會把舊資料再放回快取，
 * 要等下次變更或過期才會更新。呼叫端先立即清一次，再用 {@link #schedule} 在提交後補清一次。
 * 同一個交易內重複排程的相同動作只執行一次；巢狀的新交易（REQUIRES_NEW）各自獨立。</p>
 */
@Slf4j
public final class AfterCommit {

    private AfterCommit() {
    }

    /**
     * 目前在交易內時，排程在提交後執行；同一交易內相同 key 只排一次。
     * 回傳是否在交易內（不在交易內時不排程，由呼叫端立即處理即可）。
     */
    public static boolean schedule(Object key, Runnable action) {
        Tracker tracker = tracker();
        if (tracker == null) {
            return false;
        }
        tracker.actions.putIfAbsent(key, action);
        return true;
    }

    /** 同 {@link #schedule}，但排在所有 {@link #schedule} 的動作之後執行（例如等快取都補清完才通知）。 */
    public static boolean scheduleLast(Object key, Runnable action) {
        Tracker tracker = tracker();
        if (tracker == null) {
            return false;
        }
        tracker.lastActions.putIfAbsent(key, action);
        return true;
    }

    /** 同一交易內第一次看到這個 key 時回傳 true；不在交易內時永遠回傳 true。 */
    public static boolean firstInTransaction(Object key) {
        Tracker tracker = tracker();
        return tracker == null || tracker.seen.add(key);
    }

    private static Tracker tracker() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return null;
        }
        // 交易暫停（REQUIRES_NEW）時 synchronization 會一起暫停，所以每個交易各有一個 Tracker。
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
