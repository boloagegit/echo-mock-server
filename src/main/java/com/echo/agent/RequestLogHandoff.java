package com.echo.agent;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * 請求記錄的記憶體暫存區：請求不必等記錄寫入硬碟，記錄放進這裡後由一條背景執行緒批次寫入。
 *
 * <p>筆數與估計記憶體都有上限；超過時丟掉最舊的（只保留最新的記錄本來就是記錄上限的做法），
 * 並定期記錄丟了幾筆，請求永遠不會因為記錄而變慢或失敗。寫入暫時失敗時保留在暫存區稍後重試。
 * 程序意外結束時，尚未寫入的記錄會遺失。</p>
 */
@Slf4j
final class RequestLogHandoff {

    /** 一次交給寫入端的筆數。 */
    static final int BATCH_SIZE = 500;
    private static final long DROP_WARNING_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(30);

    /** 寫入一批，回傳從頭算起處理完的筆數與其中沒存到的筆數；之後的會再交一次。 */
    interface Sink {
        RequestLogSpool.AppendResult write(List<LogTask> batch);
    }

    private final int maxEntries;
    private final long maxBytes;
    private final Sink sink;
    private final long retryNanos;
    private final LongSupplier nanoTime;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition available = lock.newCondition();
    private final Condition drained = lock.newCondition();
    /** 受 {@link #lock} 保護。 */
    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    private long bytes;
    private boolean running;
    private boolean writing;

    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong lost = new AtomicLong();
    private final AtomicLong written = new AtomicLong();
    private long droppedAtLastWarning;
    private long lastWarningNanos;
    private Thread writer;

    RequestLogHandoff(int maxEntries, long maxBytes, Sink sink, long retryMillis, LongSupplier nanoTime) {
        this.maxEntries = Math.max(1, maxEntries);
        this.maxBytes = Math.max(1, maxBytes);
        this.sink = sink;
        this.retryNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(10, retryMillis));
        this.nanoTime = nanoTime;
    }

    void start() {
        lock.lock();
        try {
            if (running) {
                return;
            }
            running = true;
        } finally {
            lock.unlock();
        }
        writer = new Thread(this::writeLoop, "request-log-handoff");
        writer.setDaemon(true);
        writer.start();
    }

    /** 放進暫存區，永不等待；超過上限時丟掉最舊的。停止後的記錄直接丟掉。 */
    void offer(LogTask task) {
        long size = estimateBytes(task);
        lock.lock();
        try {
            if (!running || size > maxBytes) {
                // 單筆就超過上限的不保留，也不為它丟掉其他記錄
                dropped.incrementAndGet();
                return;
            }
            entries.addLast(new Entry(task, size));
            bytes += size;
            dropOldestOverLimit();
            available.signal();
        } finally {
            lock.unlock();
        }
    }

    /** 計入因其他原因（例如建立記錄失敗）沒有放進暫存區的記錄。 */
    void countDropped() {
        dropped.incrementAndGet();
    }

    /**
     * 停止接收，最多等 {@code drainTimeoutMillis} 把暫存區寫完，之後剩下的丟掉。
     */
    void stop(long drainTimeoutMillis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(drainTimeoutMillis);
        lock.lock();
        try {
            if (!running) {
                return;
            }
            running = false;
            available.signalAll();
            while ((!entries.isEmpty() || writing) && writer != null && writer.isAlive()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    break;
                }
                drained.awaitNanos(remaining);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            int left = entries.size();
            entries.clear();
            bytes = 0;
            lock.unlock();
            if (left > 0) {
                dropped.addAndGet(left);
                log.warn("Request-log hand-off stopped with {} entries not yet written; they are dropped", left);
            }
        }
        if (writer != null) {
            writer.interrupt();
        }
        warnAboutDrops(true);
    }

    long droppedCount() {
        return dropped.get() + lost.get();
    }

    long writtenCount() {
        return written.get();
    }

    int size() {
        lock.lock();
        try {
            return entries.size();
        } finally {
            lock.unlock();
        }
    }

    long bytes() {
        lock.lock();
        try {
            return bytes;
        } finally {
            lock.unlock();
        }
    }

    long maxBytes() {
        return maxBytes;
    }

    private void dropOldestOverLimit() {
        while (!entries.isEmpty() && (entries.size() > maxEntries || bytes > maxBytes)) {
            bytes -= entries.removeFirst().bytes();
            dropped.incrementAndGet();
        }
    }

    private void writeLoop() {
        while (true) {
            List<Entry> batch = take();
            if (batch == null) {
                return;
            }
            List<LogTask> tasks = new ArrayList<>(batch.size());
            batch.forEach(entry -> tasks.add(entry.task()));
            int handled;
            int lostInBatch;
            try {
                RequestLogSpool.AppendResult result = sink.write(tasks);
                handled = Math.min(result.handled(), batch.size());
                lostInBatch = result.lost();
            } catch (RuntimeException e) {
                handled = 0;
                lostInBatch = 0;
                log.debug("Request-log hand-off write failed; will retry: {}", e.toString());
            }
            written.addAndGet(handled - lostInBatch);
            lost.addAndGet(lostInBatch);
            finish(batch, handled);
            warnAboutDrops(false);
            if (handled < batch.size()) {
                pauseBeforeRetry();
            }
        }
    }

    /** 等到有記錄或停止；停止且暫存區空了回傳 null。 */
    private List<Entry> take() {
        lock.lock();
        try {
            while (entries.isEmpty()) {
                if (!running) {
                    return null;
                }
                available.await();
            }
            int count = Math.min(BATCH_SIZE, entries.size());
            List<Entry> batch = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                Entry entry = entries.removeFirst();
                bytes -= entry.bytes();
                batch.add(entry);
            }
            writing = true;
            return batch;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            lock.unlock();
        }
    }

    /** 沒寫完的放回最前面（再超過上限時，丟掉的仍是最舊的）。 */
    private void finish(List<Entry> batch, int handled) {
        lock.lock();
        try {
            for (int i = batch.size() - 1; i >= handled; i--) {
                Entry entry = batch.get(i);
                entries.addFirst(entry);
                bytes += entry.bytes();
            }
            dropOldestOverLimit();
            writing = false;
            drained.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** 寫入失敗後稍等再試（停止中也等，不在失敗時空轉）。 */
    private void pauseBeforeRetry() {
        lock.lock();
        try {
            long remaining = retryNanos;
            while (remaining > 0) {
                remaining = drained.awaitNanos(remaining);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            lock.unlock();
        }
    }

    private synchronized void warnAboutDrops(boolean force) {
        long total = droppedCount();
        long now = nanoTime.getAsLong();
        if (total == droppedAtLastWarning
                || (!force && lastWarningNanos != 0 && now - lastWarningNanos < DROP_WARNING_INTERVAL_NANOS)) {
            return;
        }
        log.warn("Request logs arrived faster than they could be written; dropped {} of the oldest entries "
                        + "(buffer limit {} entries / {} MB). Mock responses were not delayed",
                total - droppedAtLastWarning, maxEntries, maxBytes / (1024 * 1024));
        droppedAtLastWarning = total;
        lastWarningNanos = now;
    }

    /** 估計一筆記錄在記憶體中的大小（字串以每字元 2 bytes 計），用來限制暫存區的記憶體。 */
    static long estimateBytes(LogTask task) {
        long chars = 0;
        chars += length(task.getRequestBody());
        chars += length(task.getResponseBody());
        chars += length(task.getAnalysisBody());
        chars += length(task.getMatchChain());
        chars += length(task.getQueryString());
        chars += length(task.getEndpoint());
        chars += length(task.getForwardTarget());
        chars += length(task.getProxyError());
        Map<String, String> headers = task.getHeaders();
        if (headers != null) {
            for (Map.Entry<String, String> header : headers.entrySet()) {
                chars += length(header.getKey()) + length(header.getValue()) + 16;
            }
        }
        return 512 + chars * 2;
    }

    private static long length(String value) {
        return value == null ? 0 : value.length();
    }

    private record Entry(LogTask task, long bytes) {
    }

    /** 測試用：等寫入端把目前的暫存區處理完。 */
    boolean awaitIdle(long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        lock.lock();
        try {
            while (!entries.isEmpty() || writing) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                drained.awaitNanos(remaining);
            }
            return true;
        } finally {
            lock.unlock();
        }
    }
}
