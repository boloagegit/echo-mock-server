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
 * SSE event sequence player: sent according to the delayMs schedule of each event, supporting error/abort/loop.
 * <p>
 * Each event uses scheduler only briefly when actually sent; the thread is not occupied during the waiting period.
 * Stop active streams during application shutdown.
 */
@Component
@Slf4j
public class SsePlayer {

    private static final int SCHEDULER_POOL_SIZE = 8;
    /** Maximum number of simultaneous writes in progress; each stream can have at most one write at a time, and other streams will try again later when all are busy. */
    static final int MAX_WRITERS = 256;
    static final long DEFAULT_STALLED_WRITE_TIMEOUT_MS = 120_000;
    static final long MIN_STALLED_WRITE_TIMEOUT_MS = 1_000;
    private static final long STALL_CHECK_INTERVAL_MS = 1_000;
    /** writer When all are busy, how long will it take to retry writing. */
    private static final long WRITER_RETRY_MS = 200;
    private static final long OVERLOAD_LOG_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(10);
    private final AtomicLong lastOverloadLog = new AtomicLong();
    private static final MediaType TEXT_UTF8 = new MediaType("text", "plain", StandardCharsets.UTF_8);

    private final ResponseTemplateService templateService;
    // The scheduled execution thread is only responsible for timing; the actual writing is executed at writer. When the client does not read data, it only blocks its own writer.
    // Will not let other streams or general HTTP delay work wait.
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
                     @Value("${echo.sse.stalled-write-timeout-ms:120000}") long stalledWriteTimeoutMs) {
        this(templateService, null, null, stalledWriteTimeoutMs);
    }

    /** For testing: When customizing the scheduler, writing is performed directly on the execution thread of the schedule, maintaining the original synchronization behavior. */
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
        // The watchdog uses an independent execution thread, does not occupy the event schedule, and is not counted in the number of events to be sent.
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
     * When the write exceeds stalled-write-timeout and is not completed (the client is connected but does not read data), close the connection to allow the stuck write to end.
     * Release writer. Only looking at "ongoing writes", the waiting time between events is not counted, so streams with long intervals are not affected.
     */
    void closeStalledWrites() {
        long now = System.nanoTime();
        for (Playback playback : playbacks) {
            try {
                playback.closeIfStalled(now);
            } catch (RuntimeException error) {
                // Periodic tasks will stop scheduling when encountering exceptions, so they are stopped one by one.
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

    /** Single SSE event; fields are the same as {@link SseEventSequence.Event}. */
    public record SseEvent(String event, String data, String id, Long delayMs, String type) {}

    /** Request information required for template rendering. */
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
     * Start playback; the returned future completes when the stream finishes, fails, or is cancelled.
     * <p>
     * type=normal (or null): Send event <br> normally
     * type=error: After sending error event (name="error") completeWithError<br>
     * type=abort: No event is sent, directly completeWithError<br>
     * loopEnabled=true: After the event list is sent, it repeats from the beginning until the client disconnects or encounters error/abort
     */
    public CompletableFuture<Void> play(SseEmitter emitter, List<SseEvent> events, boolean loopEnabled,
                                        RequestContext request, long initialDelay, Trace trace) {
        return play(emitter, events, loopEnabled, request, initialDelay, trace, null);
    }

    /**
     * Start playing and close the connection if the client does not read data for a long time.
     *
     * @param closeConnection Close the underlying connection; used to end stuck writing, can be null
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
        /** The current writing start time; 0 means no writing is in progress. */
        private volatile long writeStartedNanos;
        /** The watchdog closed the connection; subsequent write failures are expected and are not recorded as client disconnections. */
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
                // All writer are waiting for clients that cannot read data; try again later, and there will be vacancies after those connections time out and are closed.
                // Healthy streaming is only delayed and will not be stopped.
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
            // When the writing is completed and the streaming ends at this time, the stop right cannot be obtained here, and the completed connection cannot be closed.
            if (!stopped.compareAndSet(false, true)) return;
            closedAsStalled = true;
            long waitedMs = TimeUnit.NANOSECONDS.toMillis(now - started);
            if (closeConnection == null) {
                // Not watching watchdog call emitter: it will wait for the stuck write to release the same lock, stalling the entire watchdog
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
