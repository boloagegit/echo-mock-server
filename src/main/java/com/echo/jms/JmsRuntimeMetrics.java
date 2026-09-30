package com.echo.jms;

import com.echo.config.MonitoringProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Fixed-cardinality telemetry: no messages, destinations, identities, or per-request entries. */
@Component
public final class JmsRuntimeMetrics {
    private final boolean enabled;
    private final AtomicInteger listenerActive = new AtomicInteger();
    private final AtomicInteger forwardActive = new AtomicInteger();
    private final LongAdder listenerExits = new LongAdder();
    private final LongAdder replySent = new LongAdder();
    private final LongAdder replyFailures = new LongAdder();
    private final LongAdder forwardExits = new LongAdder();
    private final LongAdder forwardFailures = new LongAdder();
    private final LongAdder receiveTimeouts = new LongAdder();
    private final LongAdder invalidReplies = new LongAdder();
    private final LongAdder cleanupFailures = new LongAdder();
    private final AtomicLong lastListenerExit = new AtomicLong();
    private final AtomicLong lastForwardExit = new AtomicLong();

    public JmsRuntimeMetrics(MonitoringProperties properties) {
        enabled = properties.isEnabled() && properties.isJmsEnabled();
    }

    public void listenerStarted() { if (enabled) listenerActive.incrementAndGet(); }
    public void listenerExited() {
        if (!enabled) return;
        listenerActive.decrementAndGet();
        listenerExits.increment();
        lastListenerExit.set(System.currentTimeMillis());
    }
    public void replySent() { if (enabled) replySent.increment(); }
    public void replyFailed() { if (enabled) replyFailures.increment(); }
    public void forwardStarted() { if (enabled) forwardActive.incrementAndGet(); }
    public void forwardExited() {
        if (!enabled) return;
        forwardActive.decrementAndGet();
        forwardExits.increment();
        lastForwardExit.set(System.currentTimeMillis());
    }
    public void forwardFailed() { if (enabled) forwardFailures.increment(); }
    public void receiveTimedOut() { if (enabled) receiveTimeouts.increment(); }
    public void invalidReply() { if (enabled) invalidReplies.increment(); }
    public void cleanupFailed() { if (enabled) cleanupFailures.increment(); }

    public Map<String, Object> snapshot() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("listenerActive", listenerActive.get());
        values.put("listenerExits", listenerExits.sum());
        values.put("replySent", replySent.sum());
        values.put("replyFailures", replyFailures.sum());
        values.put("forwardActive", forwardActive.get());
        values.put("forwardExits", forwardExits.sum());
        values.put("forwardFailures", forwardFailures.sum());
        values.put("receiveTimeouts", receiveTimeouts.sum());
        values.put("invalidReplies", invalidReplies.sum());
        values.put("cleanupFailures", cleanupFailures.sum());
        if (lastListenerExit.get() > 0) values.put("lastListenerExit", lastListenerExit.get());
        if (lastForwardExit.get() > 0) values.put("lastForwardExit", lastForwardExit.get());
        return Map.copyOf(values);
    }
}
