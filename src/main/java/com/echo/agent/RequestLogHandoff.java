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
 * 並定期記錄丟了幾筆，請求不會因為記錄而變慢或失敗。還沒交給暫存檔的記錄在寫入暫時失敗時保留下來稍後重試；
 * 已交出但沒有確認寫入的計入丟失（重送可能重複）。程序意外結束時，尚未寫入的記錄會遺失。</p>
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
    /** 以下受 {@link #lock} 保護。等待寫入的記錄。 */
    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    /** 等待中與正在寫入的記錄的估計大小：寫入完成前仍佔著記憶體，一起計入上限。 */
    private long bytes;
    private boolean running;
    /** 停止完成：寫入端結束，之後寫不完的記錄計入丟棄、不再放回。 */
    private boolean closed;
    private boolean writing;
    /** 正在寫入的那批筆數：寫入完成前仍佔著記憶體，計入筆數上限與 {@link #size()}。 */
    private int inFlight;
    private boolean writeFailureLogged;

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
        boolean droppedAny;
        lock.lock();
        try {
            if (!running || size > maxBytes) {
                // 單筆就超過上限的不保留，也不為它丟掉其他記錄
                dropped.incrementAndGet();
                droppedAny = true;
            } else {
                entries.addLast(new Entry(task, size));
                bytes += size;
                droppedAny = dropOldestOverLimit();
                available.signal();
            }
        } finally {
            lock.unlock();
        }
        if (droppedAny) {
            // 寫入端卡住時也要看得到警告（最多每 30 秒一次）
            warnAboutDrops(false);
        }
    }

    /** 計入因其他原因（例如建立記錄失敗）沒有放進暫存區的記錄。 */
    void countDropped() {
        dropped.incrementAndGet();
        warnAboutDrops(false);
    }

    /**
     * 停止接收，最多等 {@code drainTimeoutMillis} 把暫存區寫完，之後剩下的丟掉。
     */
    void stop(long drainTimeoutMillis) {
        long remaining = TimeUnit.MILLISECONDS.toNanos(drainTimeoutMillis);
        int left;
        lock.lock();
        try {
            if (!running) {
                return;
            }
            running = false;
            available.signalAll();
            try {
                while ((!entries.isEmpty() || writing) && writer != null && writer.isAlive() && remaining > 0) {
                    remaining = drained.awaitNanos(remaining);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            closed = true;
            left = entries.size();
            for (Entry entry : entries) {
                bytes -= entry.bytes();
            }
            entries.clear();
        } finally {
            lock.unlock();
        }
        if (left > 0) {
            dropped.addAndGet(left);
            log.warn("Request-log hand-off stopped with {} entries not yet written; they are dropped", left);
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
            return entries.size() + inFlight;
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

    /** 測試用：寫入端是否仍在執行。 */
    boolean writerAlive() {
        return writer != null && writer.isAlive();
    }

    /** 超過上限時從最舊的等待記錄開始丟（正在寫入的那批不能丟，但計入記憶體）。 */
    private boolean dropOldestOverLimit() {
        boolean droppedAny = false;
        while (!entries.isEmpty() && (entries.size() + inFlight > maxEntries || bytes > maxBytes)) {
            bytes -= entries.removeFirst().bytes();
            dropped.incrementAndGet();
            droppedAny = true;
        }
        return droppedAny;
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
                lostInBatch = Math.min(result.lost(), handled);
                writeFailureLogged = false;
            } catch (Throwable e) {
                // 包含 Error：寫入端不能就此停止，否則記錄只會一直被丟棄
                handled = 0;
                lostInBatch = 0;
                if (!writeFailureLogged) {
                    writeFailureLogged = true;
                    log.warn("Request-log hand-off could not write to the spool; will retry: {}", e.toString());
                } else {
                    log.debug("Request-log hand-off write failed again: {}", e.toString());
                }
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
            while (entries.isEmpty() && !closed) {
                if (!running) {
                    return null;
                }
                available.await();
            }
            if (closed) {
                return null;
            }
            int count = Math.min(BATCH_SIZE, entries.size());
            List<Entry> batch = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                batch.add(entries.removeFirst());
            }
            writing = true;
            inFlight = batch.size();
            return batch;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            lock.unlock();
        }
    }

    /** 沒寫完的放回最前面（再超過上限時，丟掉的仍是最舊的）；已停止時改為計入丟棄。 */
    private void finish(List<Entry> batch, int handled) {
        boolean droppedAfterStop = false;
        lock.lock();
        try {
            for (Entry entry : batch) {
                bytes -= entry.bytes();
            }
            inFlight = 0;
            if (closed) {
                dropped.addAndGet(batch.size() - handled);
                droppedAfterStop = batch.size() > handled;
            } else {
                for (int i = batch.size() - 1; i >= handled; i--) {
                    Entry entry = batch.get(i);
                    entries.addFirst(entry);
                    bytes += entry.bytes();
                }
                dropOldestOverLimit();
            }
            writing = false;
            drained.signalAll();
        } finally {
            lock.unlock();
        }
        if (droppedAfterStop) {
            // stop() 的最後一次警告早於這批的結果：補報這批，停止後的記錄不會少算
            warnAboutDrops(true);
        }
    }

    /** 寫入失敗後稍等再試（停止中也等，不在失敗時空轉）。 */
    private void pauseBeforeRetry() {
        lock.lock();
        try {
            long remaining = retryNanos;
            while (remaining > 0 && !closed) {
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
        log.warn("Request logging dropped {} entries (the oldest first when writing falls behind; buffer limit "
                        + "{} entries / {} MB). Mock responses were not delayed",
                total - droppedAtLastWarning, maxEntries, maxBytes / (1024 * 1024));
        droppedAtLastWarning = total;
        lastWarningNanos = now;
    }

    /**
     * 估計一筆記錄在記憶體中的大小，用來限制暫存區的記憶體：字串以每字元 2 bytes 計，
     * 另加物件本身、每個 header、候選規則與比對結果的固定成本。
     */
    static long estimateBytes(LogTask task) {
        long fixed = 512;
        if (task.getCandidates() != null) {
            fixed += (long) task.getCandidates().size() * 128;
        }
        if (task.getMatchOutcomes() != null) {
            fixed += (long) task.getMatchOutcomes().size() * 128;
        }
        // 每個字串欄位都計入：任何一個都可能很大（例如 scenarioSkipped 帶著被略過規則的條件）
        long chars = 0;
        for (String value : new String[] {
                task.getRuleId(), task.getMethod(), task.getEndpoint(), task.getClientIp(),
                task.getMatchChain(), task.getTargetHost(), task.getForwardTarget(), task.getProxyError(),
                task.getRequestBody(), task.getResponseBody(), task.getFaultType(), task.getScenarioName(),
                task.getScenarioFromState(), task.getScenarioToState(), task.getScenarioSkipped(),
                task.getAnalysisBody(), task.getQueryString(), task.getDiagnosticId()}) {
            chars += length(value);
        }
        Map<String, String> headers = task.getHeaders();
        if (headers != null) {
            for (Map.Entry<String, String> header : headers.entrySet()) {
                chars += length(header.getKey()) + length(header.getValue());
                fixed += 96;
            }
        }
        return fixed + chars * 2;
    }

    private static long length(String value) {
        return value == null ? 0 : value.length();
    }

    private record Entry(LogTask task, long bytes) {
    }

    /** 測試用：等寫入端把目前的暫存區處理完。 */
    boolean awaitIdle(long timeoutMillis) throws InterruptedException {
        long remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        lock.lock();
        try {
            while (!entries.isEmpty() || writing) {
                if (remaining <= 0) {
                    return false;
                }
                remaining = drained.awaitNanos(remaining);
            }
            return true;
        } finally {
            lock.unlock();
        }
    }
}
