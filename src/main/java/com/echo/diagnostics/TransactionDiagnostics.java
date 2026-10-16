package com.echo.diagnostics;

import com.echo.config.DiagnosticProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/** Anomaly-only, bounded metadata. Healthy requests never format/enqueue/write a diagnostic line.
 * No body, Message, exception, MDC, per-ID global registry or synchronous overflow fallback. */
@Component
public final class TransactionDiagnostics implements AutoCloseable {
    public static final int MAX_LINE_CHARS = 2048;
    public static final int MAX_LINE_BYTES = 2048;
    private final boolean enabled, jmsEnabled, httpEnabled, sseEnabled, detailedEnabled;
    private final int detailedSeconds, queueCapacity, rateLimit;
    private final long dailyLimit, retainedLimit;
    private final ArrayBlockingQueue<String> queue;
    private final Consumer<String> sink;
    private final String processId = UUID.randomUUID().toString().substring(0, 8);
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean initialized = new AtomicBoolean();
    private final LongAdder accepted = new LongAdder();
    private final LongAdder dropped = new LongAdder();
    private final LongAdder emissionAttempts = new LongAdder();
    private final LongAdder sinkFailures = new LongAdder();
    private final LongAdder rateLimited = new LongAdder();
    private volatile long detailDeadline;
    private long rateSecond = -1;
    private int rateUsed;
    private Thread worker;

    @org.springframework.beans.factory.annotation.Autowired
    public TransactionDiagnostics(DiagnosticProperties properties,
                                  @Value("${logging.file.path:./logs}") String logPath) {
        this(properties, new DiagnosticFileSink(Path.of(logPath).resolve("diagnostics"), Clock.systemDefaultZone(),
                properties.boundedDailyBytes(), properties.boundedRetainedBytes(), properties.boundedRetentionDays()));
    }
    public TransactionDiagnostics(DiagnosticProperties properties) { this(properties, "./logs"); }
    /** Sink seam for overload/failure tests and repeatable short cost probes. */
    public TransactionDiagnostics(DiagnosticProperties properties, Consumer<String> sink) {
        enabled = properties.isEnabled();
        jmsEnabled = properties.isJmsEnabled(); httpEnabled = properties.isHttpEnabled(); sseEnabled = properties.isSseEnabled();
        detailedEnabled = properties.isDetailedEnabled(); detailedSeconds = properties.boundedDetailedSeconds();
        queueCapacity = properties.boundedQueueCapacity(); rateLimit = properties.boundedRecordsPerSecond();
        dailyLimit = properties.boundedDailyBytes(); retainedLimit = properties.boundedRetainedBytes();
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.sink = sink;
    }

    @PostConstruct
    public synchronized void start() {
        if (!enabled || !initialized.compareAndSet(false, true)) return;
        detailDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(detailedSeconds);
        running.set(true);
        worker = new Thread(this::drain, "transaction-diagnostics");
        worker.setDaemon(true);
        worker.start();
    }

    public Trace begin(String protocol) {
        boolean group = switch (protocol) {
            case "JMS" -> jmsEnabled;
            case "HTTP" -> httpEnabled;
            case "SSE" -> sseEnabled;
            default -> false;
        };
        if (!enabled || !group || !running.get()) return Trace.NONE;
        return new Trace(this, processId + "-" + Long.toUnsignedString(sequence.incrementAndGet(), 36), protocol);
    }
    private boolean detailed() {
        return initialized.get() && detailedEnabled && System.nanoTime() - detailDeadline < 0;
    }
    private void offer(String line) {
        if (!running.get() || !queue.offer(line)) dropped.increment();
        else { accepted.increment(); if (!running.get() && queue.remove(line)) dropped.increment(); }
    }
    private void drain() {
        long nextMaintenance = 0;
        try {
            while (running.get() || !queue.isEmpty()) {
                long now = System.nanoTime();
                if (now - nextMaintenance >= 0) {
                    nextMaintenance = now + TimeUnit.MINUTES.toNanos(1);
                    if (sink instanceof DiagnosticFileSink file) {
                        try { file.maintain(); }
                        catch (RuntimeException ignored) { sinkFailures.increment(); }
                    }
                }
                try {
                    String line = queue.poll(100, TimeUnit.MILLISECONDS);
                    if (line == null) continue;
                    long second = System.nanoTime() / 1_000_000_000L;
                    if (second != rateSecond) { rateSecond = second; rateUsed = 0; }
                    if (rateUsed++ >= rateLimit) { rateLimited.increment(); continue; }
                    emissionAttempts.increment();
                    try { sink.accept(line); }
                    catch (RuntimeException ignored) { sinkFailures.increment(); }
                } catch (InterruptedException interrupted) { if (!running.get()) break; }
            }
        } finally {
            running.set(false);
            if (sink instanceof DiagnosticFileSink file) file.close();
        }
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> values = new java.util.LinkedHashMap<>();
        values.put("diagnosticEnabled", enabled);
        values.put("diagnosticRunning", running.get());
        values.put("diagnosticDetailed", detailed());
        values.put("diagnosticQueueUsed", queue.size());
        values.put("diagnosticQueueCapacity", queueCapacity);
        values.put("diagnosticAccepted", accepted.sum());
        values.put("diagnosticDropped", dropped.sum());
        values.put("diagnosticEmissionAttempts", emissionAttempts.sum());
        values.put("diagnosticSinkFailures", sinkFailures.sum());
        values.put("diagnosticRateLimited", rateLimited.sum());
        values.put("diagnosticDailyLimitBytes", dailyLimit);
        values.put("diagnosticRetainedLimitBytes", retainedLimit);
        if (sink instanceof DiagnosticFileSink file) {
            values.put("diagnosticTodayBytes", file.todayBytes());
            values.put("diagnosticRetainedBytes", file.retainedBytes());
            values.put("diagnosticBudgetDropped", file.budgetDropped());
            values.put("diagnosticFileWriteAttempted", file.attempted());
            if (file.attempted() || !file.available()) values.put("diagnosticFileAvailable", file.available());
        }
        return Map.copyOf(values);
    }

    @PreDestroy
    @Override
    public synchronized void close() {
        running.set(false);
        if (worker != null) {
            try { worker.join(1000); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            worker.interrupt();
        }
        while (queue.poll() != null) dropped.increment();
    }

    public static final class Trace {
        public static final Trace NONE = new Trace(null, null, "-");
        private static final String[] KEYS = { "inId", "inCorrelation", "callerReplyTo", "redelivered", "deliveryCount",
                "endpoint", "mode", "ruleId", "ruleVersion", "responseId", "targetQueue", "source", "connectionRevision",
                "provider", "timeoutMs", "outId", "outCorrelation", "outReplyTo", "replyId", "replyCorrelation",
                "replyType", "waitResult", "waitMs", "possibleConsumerClose", "sendReturned", "callerReply",
                "callerReplyId", "httpStatus", "sentEvents", "metadataPartial", "cleanupFailure", "callerExceptionType", "cleanupExceptionType",
                "connectionId", "connectionVersion" };
        // Preserve the observed result even if unusually long provider identifiers force truncation.
        private static final int[] RESULT_SLOTS = {21, 22, 23, 24, 25, 27, 28, 29, 30, 31, 32};
        private final TransactionDiagnostics owner;
        private final String id;
        private final String protocol;
        private final long started = System.nanoTime();
        private final AtomicBoolean ended = new AtomicBoolean();
        private Object[] metadata;
        private volatile String phase = "RECEIVE";
        private String failureEvent;
        private String failurePhase;
        private String exceptionType;
        private String providerCode;
        private boolean anomaly;

        private Trace(TransactionDiagnostics owner, String id, String protocol) {
            this.owner = owner; this.id = id; this.protocol = protocol;
        }
        public boolean enabled() { return owner != null; }
        public String id() { return id; }
        public void stage(String stage) { if (owner != null) phase = bounded(stage); }

        public void event(String event, Object... fields) {
            if (owner == null || event == null) return;
            synchronized (this) {
            if (ended.get()) return;
            if (metadata == null) metadata = new Object[KEYS.length];
            switch (event) {
                case "OUTBOUND_SEND_RETURNED" -> { metadata[24] = true; phase = "RECEIVE_REPLY"; }
                case "REPLY_SEND_BEGIN" -> phase = "CALLER_REPLY";
                case "REPLY_SEND_RETURNED" -> metadata[25] = "SEND_RETURNED";
                case "FORWARD_UNAVAILABLE", "FORWARD_REJECTED" -> { anomaly = true; failureEvent = event; failurePhase = phase; }
                default -> { }
            }
            for (int i = 0; i + 1 < fields.length && i < 24; i += 2) {
                if (!(fields[i] instanceof String key)) continue;
                Object value = safeValue(fields[i + 1]);
                int slot = switch (key) {
                    case "messageId" -> switch (event) { case "RECEIVED" -> 0; case "OUTBOUND_SEND_RETURNED" -> 15;
                        case "REPLY_WAIT_END" -> 18; case "REPLY_SEND_RETURNED" -> 26; default -> -1; };
                    case "correlationId" -> switch (event) { case "RECEIVED" -> 1; case "OUTBOUND_SEND_RETURNED" -> 16;
                        case "REPLY_WAIT_END" -> 19; default -> -1; };
                    case "replyTo" -> "OUTBOUND_SEND_RETURNED".equals(event) ? 17 : 2;
                    case "redelivered" -> 3; case "deliveryCount" -> 4;
                    case "endpoint", "path" -> 5; case "mode" -> 6; case "ruleId" -> 7;
                    case "ruleVersion" -> 8; case "responseId" -> 9; case "queue" -> 10;
                    case "source" -> 11; case "connectionRevision" -> 12;
                    case "provider" -> 13; case "timeoutMs", "readTimeoutMs" -> 14;
                    case "messageType" -> "REPLY_WAIT_END".equals(event) ? 20 : -1;
                    case "outcome" -> "REPLY_WAIT_END".equals(event) ? 21 : -1;
                    case "waitMs" -> 22; case "possibleConsumerClose" -> 23;
                    case "reason" -> "REPLY_SKIPPED".equals(event) ? 25 : -1;
                    case "status" -> 27; case "sentEvents" -> 28; case "metadataPartial" -> 29;
                    case "connectionId" -> 33; case "connectionVersion" -> 34;
                    default -> -1;
                };
                if (slot >= 0) metadata[slot] = value;
                if ("REPLY_WAIT_END".equals(event) && "outcome".equals(key)
                        && ("NO_REPLY".equals(value) || "INVALID_TYPE".equals(value))) {
                    anomaly = true; failureEvent = (String) value; failurePhase = "RECEIVE_REPLY";
                    exceptionType = null; providerCode = null;
                }
                if ("REPLY_SKIPPED".equals(event) && "reason".equals(key) && !"NO_REPLY_TO".equals(value)) {
                    anomaly = true; failureEvent = "REPLY_SKIPPED"; failurePhase = "CALLER_REPLY";
                }
                if ("HTTP_TARGET_RESPONSE".equals(event) && "status".equals(key)
                        && value instanceof Number number && number.intValue() >= 500) {
                    anomaly = true; failureEvent = "HTTP_TARGET_ERROR_STATUS"; failurePhase = "RECEIVE_REPLY";
                }
            }
            if (owner.detailed()) emit(event, null);
            }
        }

        public void failure(String event, Throwable error) {
            if (owner == null) return;
            synchronized (this) {
            if (ended.get()) return;
            anomaly = true;
            if (failureEvent == null || ("METADATA_UNAVAILABLE".equals(failureEvent) && !"METADATA_UNAVAILABLE".equals(event))) {
                failureEvent = bounded(event); failurePhase = phase;
                exceptionType = error == null ? "unknown" : bounded(error.getClass().getSimpleName());
                if (error instanceof jakarta.jms.JMSException jms) {
                    try { providerCode = bounded(jms.getErrorCode()); }
                    catch (RuntimeException ignored) {
                        // Provider diagnostics must never change the business exception path.
                    }
                }
            }
            if ("REPLY_SEND_FAILED".equals(event)) {
                if (metadata == null) metadata = new Object[KEYS.length];
                metadata[25] = "SEND_FAILED";
                metadata[31] = error == null ? "unknown" : bounded(error.getClass().getSimpleName());
            }
            if ("TEMP_QUEUE_CLEANUP_FAILED".equals(event)) {
                if (metadata == null) metadata = new Object[KEYS.length];
                metadata[30] = true;
                metadata[32] = error == null ? "unknown" : bounded(error.getClass().getSimpleName());
            }
            if (owner.detailed()) emit(event, null);
            }
        }
        public void end(String outcome) {
            if (owner == null) return;
            synchronized (this) {
            if (!ended.compareAndSet(false, true)) return;
            if (outcome != null && (outcome.contains("TIMEOUT") || outcome.contains("FAILED") || outcome.contains("INTERRUPTED")
                    || "MISSING_RESPONSE".equals(outcome) || "INVALID_EVENTS".equals(outcome))) {
                anomaly = true;
                if (failureEvent == null) { failureEvent = bounded(outcome); failurePhase = phase; }
            }
            if (anomaly || owner.detailed()) emit("END", outcome);
            }
        }
        private void emit(String event, String outcome) {
            if (!owner.running.get() || owner.queue.remainingCapacity() == 0) { owner.dropped.increment(); return; }
            try {
                StringBuilder line = new StringBuilder(1024);
                line.append("ECHO_TX trace=").append(id).append(" protocol=").append(protocol)
                        .append(" event=").append(event).append(" atMs=").append(System.currentTimeMillis())
                        .append(" elapsedMs=").append(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                append(line, "outcome", outcome);
                append(line, "failure", failureEvent); append(line, "stage", failurePhase);
                append(line, "exceptionType", exceptionType); append(line, "providerCode", providerCode);
                if (metadata != null) {
                    for (int slot : RESULT_SLOTS) append(line, KEYS[slot], metadata[slot]);
                    for (int i = 0; i < KEYS.length; i++) {
                        if ((i >= 21 && i <= 25) || (i >= 27 && i <= 32)) continue;
                        append(line, KEYS[i], metadata[i]);
                    }
                }
                append(line, "omittedTotal", owner.dropped.sum() + owner.rateLimited.sum());
                owner.offer(boundedLine(line.toString()));
            } catch (RuntimeException ignored) { owner.dropped.increment(); }
        }
        private static String boundedLine(String line) {
            byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
            if (bytes.length < MAX_LINE_BYTES) return line;
            int end = MAX_LINE_BYTES - 32;
            while (end > 0 && (bytes[end] & 0xc0) == 0x80) end--;
            return new String(bytes, 0, end, StandardCharsets.UTF_8) + " truncated=true";
        }
        private static void append(StringBuilder line, String key, Object value) {
            if (value == null) return;
            line.append(' ').append(key).append('=');
            String text = value instanceof String string ? string : value.toString();
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                line.append(Character.isISOControl(c) || Character.isWhitespace(c) || c == '=' ? '_' : c);
            }
        }
        private static Object safeValue(Object value) {
            if (value instanceof String string) return bounded(string);
            if (value instanceof Integer || value instanceof Long || value instanceof Double || value instanceof Float
                    || value instanceof Short || value instanceof Byte || value instanceof Boolean) return value;
            if (value instanceof Enum<?> enumeration) return enumeration.name();
            return null;
        }
        private static String bounded(String value) { return value == null || value.length() <= 128 ? value : value.substring(0, 128); }
    }
}
