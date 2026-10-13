package com.echo.protocol.http.sse;

import com.echo.diagnostics.TransactionDiagnostics.Trace;
import com.echo.service.ResponseTemplateService;
import com.echo.service.SseEventSequence;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
    private static final MediaType TEXT_UTF8 = new MediaType("text", "plain", StandardCharsets.UTF_8);

    private final ResponseTemplateService templateService;
    // Blocking servlet writes must not consume the general HTTP delay workers.
    private final ScheduledExecutorService scheduler;
    private final Set<Playback> playbacks = ConcurrentHashMap.newKeySet();

    @Autowired
    public SsePlayer(ResponseTemplateService templateService) {
        this(templateService, null);
    }

    public SsePlayer(ResponseTemplateService templateService, ScheduledExecutorService scheduler) {
        this.templateService = templateService;
        this.scheduler = scheduler != null ? scheduler : createScheduler();
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
        Playback playback = new Playback(emitter, events, loopEnabled, request, initialDelay, trace);
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
        private int index;

        private Playback(SseEmitter emitter, List<SseEvent> events, boolean loopEnabled,
                         RequestContext request, long initialDelay, Trace trace) {
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
                    if (scheduled.compareAndSet(pending, null)) deliver(event);
                }, delayMs));
            } catch (RuntimeException error) {
                fail(error, "SSE scheduling failed");
            }
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
                        emitter.send(builder);
                        sentEvents.incrementAndGet();
                        fail(new RuntimeException("SSE error event"), null);
                    }
                    case "abort" -> fail(new RuntimeException("SSE abort"), null);
                    default -> {
                        SseEmitter.SseEventBuilder builder = SseEmitter.event().data(wireData(data), TEXT_UTF8);
                        if (event.event() != null) builder.name(event.event());
                        if (event.id() != null) builder.id(event.id());
                        emitter.send(builder);
                        sentEvents.incrementAndGet();
                        advance();
                    }
                }
            } catch (IOException error) {
                log.warn("SSE client disconnected: {}", error.getMessage());
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
