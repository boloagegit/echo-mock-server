package com.echo.protocol.http.sse;

import com.echo.diagnostics.TransactionDiagnostics.Trace;
import com.echo.service.ResponseTemplateService;
import com.echo.service.SseEventSequence;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SSE 事件序列播放器：依每個事件的 delayMs 排程送出，支援 error／abort／loop。
 * <p>
 * 每個事件只在實際發送時短暫使用 scheduler；等待期間不占用執行緒。
 * 播放中的串流在關機時一併停止。
 */
@Component
@Slf4j
public class SsePlayer {

    private static final int SCHEDULER_POOL_SIZE = 8;
    /** 同時進行中的寫入上限；每個串流同時最多一個寫入，全部忙碌時其他串流稍後重試。 */
    static final int MAX_WRITERS = 256;
    static final long DEFAULT_STALLED_WRITE_TIMEOUT_MS = 30_000;
    static final long MIN_STALLED_WRITE_TIMEOUT_MS = 1_000;
    private static final long STALL_CHECK_INTERVAL_MS = 1_000;
    /** writer 全部忙碌時，多久後重試寫入。 */
    private static final long WRITER_RETRY_MS = 200;
    private static final long OVERLOAD_LOG_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(10);
    private final AtomicLong lastOverloadLog = new AtomicLong();
    private static final MediaType TEXT_UTF8 = new MediaType("text", "plain", StandardCharsets.UTF_8);

    private final ResponseTemplateService templateService;
    // 排程執行緒只負責計時；實際寫入在 writer 執行，客戶端不讀資料時只卡住自己的 writer，
    // 不會讓其他串流或一般 HTTP 延遲工作跟著等待。
    private final ScheduledExecutorService scheduler;
    private final Executor writer;
    private final ExecutorService ownedWriter;
    private final long stalledWriteTimeoutNanos;
    private final ScheduledExecutorService watchdog;
    private final ScheduledFuture<?> stallCheck;
    private final Set<Playback> playbacks = ConcurrentHashMap.newKeySet();

    public SsePlayer(ResponseTemplateService templateService) {
        this(templateService, DEFAULT_STALLED_WRITE_TIMEOUT_MS);
    }

    @Autowired
    public SsePlayer(ResponseTemplateService templateService,
                     @Value("${echo.sse.stalled-write-timeout-ms:30000}") long stalledWriteTimeoutMs) {
        this(templateService, null, null, stalledWriteTimeoutMs);
    }

    /** 測試用：自訂排程器時，寫入直接在排程的執行緒進行，維持原本的同步行為。 */
    public SsePlayer(ResponseTemplateService templateService, ScheduledExecutorService scheduler) {
        this(templateService, scheduler, scheduler != null ? Runnable::run : null, DEFAULT_STALLED_WRITE_TIMEOUT_MS);
    }

    SsePlayer(ResponseTemplateService templateService, ScheduledExecutorService scheduler, Executor writer,
              long stalledWriteTimeoutMs) {
        if (stalledWriteTimeoutMs < MIN_STALLED_WRITE_TIMEOUT_MS) {
            log.warn("echo.sse.stalled-write-timeout-ms={} is below {}; using {}", stalledWriteTimeoutMs,
                    MIN_STALLED_WRITE_TIMEOUT_MS, MIN_STALLED_WRITE_TIMEOUT_MS);
        }
        this.templateService = templateService;
        this.scheduler = scheduler != null ? scheduler : createScheduler();
        this.ownedWriter = writer == null ? createWriter() : null;
        this.writer = writer != null ? writer : ownedWriter;
        this.stalledWriteTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(
                Math.max(MIN_STALLED_WRITE_TIMEOUT_MS, stalledWriteTimeoutMs));
        // 看門狗用獨立執行緒，不佔用事件排程，也不計入待送事件數
        this.watchdog = scheduler == null ? createWatchdog() : null;
        this.stallCheck = watchdog == null ? null : watchdog.scheduleWithFixedDelay(this::closeStalledWrites,
                STALL_CHECK_INTERVAL_MS, STALL_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private static ScheduledExecutorService createWatchdog() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sse-stall-watchdog");
            t.setDaemon(true);
            return t;
        });
    }

    private static ExecutorService createWriter() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(SCHEDULER_POOL_SIZE, MAX_WRITERS, 60, TimeUnit.SECONDS,
                new SynchronousQueue<>(), r -> {
                    Thread t = new Thread(r, "sse-writer");
                    t.setDaemon(true);
                    return t;
                });
        return executor;
    }

    private void logOverload() {
        long now = System.nanoTime();
        long last = lastOverloadLog.get();
        if ((last == 0 || now - last >= OVERLOAD_LOG_INTERVAL_NANOS) && lastOverloadLog.compareAndSet(last, now)) {
            log.warn("All {} SSE writers are waiting for clients that do not read; delaying other streams until "
                    + "those connections time out", MAX_WRITERS);
        }
    }

    /**
     * 寫入超過 stalled-write-timeout 仍未完成（客戶端連著但不讀資料）時，關閉該連線讓卡住的寫入結束、
     * 釋放 writer。只看「正在進行的寫入」，事件之間的等待時間不算，所以間隔很長的串流不受影響。
     */
    void closeStalledWrites() {
        long now = System.nanoTime();
        for (Playback playback : playbacks) {
            try {
                playback.closeIfStalled(now);
            } catch (RuntimeException error) {
                // 週期工作遇到例外會停止排程，因此逐一攔下
                log.warn("Stalled SSE check failed: {}", error.getMessage());
            }
        }
    }

    private static ScheduledThreadPoolExecutor createScheduler() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(SCHEDULER_POOL_SIZE, r -> {
            Thread t = new Thread(r, "sse-scheduler");
            t.setDaemon(true);
            return t;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return executor;
    }

    @PreDestroy
    public void shutdown() {
        playbacks.forEach(Playback::stop);
        scheduler.shutdownNow();
        if (ownedWriter != null) ownedWriter.shutdownNow();
        if (stallCheck != null) stallCheck.cancel(false);
        if (watchdog != null) watchdog.shutdownNow();
    }

    public int getPendingTaskCount() {
        return scheduler instanceof ScheduledThreadPoolExecutor executor ? executor.getQueue().size() : 0;
    }

    /** 單一 SSE 事件；欄位與 {@link SseEventSequence.Event} 相同。 */
    public record SseEvent(String event, String data, String id, Long delayMs, String type) {}

    /** 模板渲染所需的請求資訊。 */
    public record RequestContext(String queryString, Map<String, String> headers,
                                 String path, String method, String body) {
        ResponseTemplateService.TemplateContext templateContext() {
            return ResponseTemplateService.TemplateContext.of(path, method, queryString, headers, body);
        }
    }

    /** Invalid persisted sequences fail as a whole using the same contract as rule saves. */
    public static List<SseEvent> parseEvents(String json) {
        try {
            return SseEventSequence.parse(json).stream()
                    .map(event -> new SseEvent(event.event(), event.data(), event.id(), event.delayMs(), event.type()))
                    .toList();
        } catch (IllegalArgumentException error) {
            log.warn("Failed to parse SSE events JSON: {}", error.getMessage());
            return List.of();
        }
    }

    /**
     * 開始播放；回傳的 future 在串流完成、失敗或取消時完成。
     * <p>
     * type=normal（或 null）：正常發送事件<br>
     * type=error：發送 error event（name="error"）後 completeWithError<br>
     * type=abort：不送事件，直接 completeWithError<br>
     * loopEnabled=true：事件列表發完後從頭重複，直到客戶端斷開或遇到 error/abort
     */
    public CompletableFuture<Void> play(SseEmitter emitter, List<SseEvent> events, boolean loopEnabled,
                                        RequestContext request, long initialDelay, Trace trace) {
        return play(emitter, events, loopEnabled, request, initialDelay, trace, null);
    }

    /**
     * 開始播放，並在客戶端長時間不讀資料時關閉連線。
     *
     * @param closeConnection 關閉底層連線；用來結束卡住的寫入，可為 null
     */
    public CompletableFuture<Void> play(SseEmitter emitter, List<SseEvent> events, boolean loopEnabled,
                                        RequestContext request, long initialDelay, Trace trace,
                                        Runnable closeConnection) {
        Playback playback = new Playback(emitter, events, loopEnabled, request, initialDelay, trace, closeConnection);
        playback.start();
        return playback.completion;
    }

    /** Package seam for deterministic early-execution/cancellation tests. */
    protected ScheduledFuture<?> scheduleEvent(Runnable delivery, long delayMs) {
        return scheduler.schedule(delivery, delayMs, TimeUnit.MILLISECONDS);
    }

    /** Non-blocking event state machine: delays are scheduler timestamps, not Thread.sleep. */
    private final class Playback {
        private final SseEmitter emitter;
        private final List<SseEvent> events;
        private final boolean loopEnabled;
        private final ResponseTemplateService.TemplateContext templateContext;
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final AtomicReference<PendingTask> scheduled = new AtomicReference<>();
        private long initialDelay;
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private final Trace trace;
        private final AtomicLong sentEvents = new AtomicLong();
        private final Runnable closeConnection;
        /** 目前寫入開始的時間；0 表示沒有寫入進行中。 */
        private volatile long writeStartedNanos;
        /** 看門狗關閉了連線；之後寫入失敗是預期的，不記成客戶端斷線。 */
        private volatile boolean closedAsStalled;
        private int index;

        private Playback(SseEmitter emitter, List<SseEvent> events, boolean loopEnabled,
                         RequestContext request, long initialDelay, Trace trace, Runnable closeConnection) {
            this.closeConnection = closeConnection;
            this.emitter = emitter;
            this.events = events;
            this.loopEnabled = loopEnabled;
            this.templateContext = request.templateContext();
            this.initialDelay = initialDelay;
            this.trace = trace;
        }

        private void start() {
            playbacks.add(this);
            emitter.onCompletion(this::stop);
            emitter.onTimeout(() -> stop("SSE_TIMEOUT"));
            emitter.onError(error -> {
                trace.failure("SSE_TRANSPORT_ERROR", error);
                stop("SSE_TRANSPORT_ERROR");
            });
            scheduleNext();
        }

        private void scheduleNext() {
            if (stopped.get()) return;
            SseEvent event = events.get(index);
            long delayMs = (event.delayMs() == null ? 0 : Math.max(0, event.delayMs())) + initialDelay;
            initialDelay = 0;
            PendingTask pending = new PendingTask();
            // Publish ownership before schedule(): a zero-delay task can run before it returns.
            if (!scheduled.compareAndSet(null, pending)) return;
            if (stopped.get()) {
                cancelPending();
                return;
            }
            try {
                pending.attach(scheduleEvent(() -> {
                    if (scheduled.compareAndSet(pending, null)) write(event);
                }, delayMs));
            } catch (RuntimeException error) {
                fail(error, "SSE scheduling failed");
            }
        }

        private void write(SseEvent event) {
            if (stopped.get()) return;
            try {
                writer.execute(() -> deliver(event));
            } catch (RejectedExecutionException error) {
                // 所有 writer 都在等不讀資料的客戶端；稍後再試，等那些連線逾時關閉後就會有空位，
                // 健康的串流只是延後，不會被停掉。
                logOverload();
                retryLater(event);
            }
        }

        private void retryLater(SseEvent event) {
            if (stopped.get()) return;
            PendingTask pending = new PendingTask();
            if (!scheduled.compareAndSet(null, pending)) return;
            if (stopped.get()) {
                cancelPending();
                return;
            }
            try {
                pending.attach(scheduleEvent(() -> {
                    if (scheduled.compareAndSet(pending, null)) write(event);
                }, WRITER_RETRY_MS));
            } catch (RuntimeException error) {
                fail(error, "SSE scheduling failed");
            }
        }

        private void send(SseEmitter.SseEventBuilder builder) throws IOException {
            writeStartedNanos = System.nanoTime();
            try {
                emitter.send(builder);
            } finally {
                writeStartedNanos = 0;
            }
        }

        private void closeIfStalled(long now) {
            long started = writeStartedNanos;
            if (started == 0 || now - started < stalledWriteTimeoutNanos) return;
            // 寫入剛好在此時完成並結束串流時，這裡搶不到停止權，不能再關閉已經完成的連線
            if (!stopped.compareAndSet(false, true)) return;
            closedAsStalled = true;
            long waitedMs = TimeUnit.NANOSECONDS.toMillis(now - started);
            if (closeConnection == null) {
                // 不在看門狗呼叫 emitter：它會等卡住的寫入釋放同一把鎖，讓整個看門狗停擺
                log.warn("SSE write has not finished for {} ms; stream stopped (connection cannot be closed)", waitedMs);
            } else {
                log.warn("SSE write has not finished for {} ms; closing the connection", waitedMs);
                try {
                    closeConnection.run();
                } catch (RuntimeException error) {
                    log.warn("Failed to close stalled SSE connection: {}", error.getMessage());
                }
            }
            finishStop("SSE_CLIENT_STALLED");
        }

        private void deliver(SseEvent event) {
            if (stopped.get()) return;
            try {
                String data = render(event.data());
                String effectiveType = event.type() == null ? "normal" : event.type();
                switch (effectiveType) {
                    case "error" -> {
                        SseEmitter.SseEventBuilder builder = SseEmitter.event()
                                .name("error").data(wireData(data), TEXT_UTF8);
                        if (event.id() != null) builder.id(event.id());
                        send(builder);
                        sentEvents.incrementAndGet();
                        fail(new RuntimeException("SSE error event"), null);
                    }
                    case "abort" -> fail(new RuntimeException("SSE abort"), null);
                    default -> {
                        SseEmitter.SseEventBuilder builder = SseEmitter.event().data(wireData(data), TEXT_UTF8);
                        if (event.event() != null) builder.name(event.event());
                        if (event.id() != null) builder.id(event.id());
                        send(builder);
                        sentEvents.incrementAndGet();
                        advance();
                    }
                }
            } catch (IOException error) {
                if (!closedAsStalled) log.warn("SSE client disconnected: {}", error.getMessage());
                stop("SSE_DISCONNECTED");
            } catch (Exception error) {
                fail(error, "SSE event send error");
            }
        }

        private String render(String data) {
            try {
                return ResponseTemplateService.renderIfTemplate(templateService, data, () -> templateContext);
            } catch (Exception error) {
                log.warn("SSE template rendering failed, using raw data: {}", error.getMessage());
                return data;
            }
        }

        private void advance() {
            index++;
            if (index < events.size()) {
                scheduleNext();
            } else if (loopEnabled) {
                index = 0;
                scheduleNext();
            } else {
                complete();
            }
        }

        private void complete() {
            if (!stopped.compareAndSet(false, true)) return;
            cancelPending();
            playbacks.remove(this);
            finishTrace("SSE_COMPLETED");
            emitter.complete();
            completion.complete(null);
        }

        private void fail(Exception error, String message) {
            if (!stopped.compareAndSet(false, true)) return;
            cancelPending();
            playbacks.remove(this);
            trace.failure("SSE_FAILED", error);
            finishTrace("SSE_FAILED");
            if (message != null) log.error("{}: {}", message, error.getMessage());
            emitter.completeWithError(error);
            completion.complete(null);
        }

        private void stop() {
            stop("SSE_CANCELLED");
        }

        private void stop(String outcome) {
            if (!stopped.compareAndSet(false, true)) return;
            finishStop(outcome);
        }

        private void finishStop(String outcome) {
            cancelPending();
            playbacks.remove(this);
            finishTrace(outcome);
            completion.complete(null);
        }

        private void finishTrace(String outcome) {
            trace.event("SSE_FINISHED", "sentEvents", sentEvents.get());
            trace.end(outcome);
        }

        private void cancelPending() {
            PendingTask task = scheduled.getAndSet(null);
            if (task != null) task.cancel();
        }
    }

    private static String wireData(String data) {
        return data.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static final class PendingTask {
        private ScheduledFuture<?> future;
        private boolean cancelled;

        synchronized void attach(ScheduledFuture<?> task) {
            future = task;
            if (cancelled) task.cancel(false);
        }

        synchronized void cancel() {
            cancelled = true;
            if (future != null) future.cancel(false);
        }
    }
}
