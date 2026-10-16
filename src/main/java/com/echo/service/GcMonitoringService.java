package com.echo.service;

import com.echo.config.MonitoringProperties;
import com.echo.dto.GcHistoryDto;
import com.echo.dto.GcHistoryDto.Event;
import com.echo.dto.GcHistoryDto.Pool;
import com.echo.dto.ResourceSnapshotDto.State;
import com.sun.management.GarbageCollectionNotificationInfo;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;

import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.ListenerNotFoundException;
import javax.management.openmbean.CompositeData;
import java.lang.management.ManagementFactory;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** One small record per JVM GC notification. No polling, disk IO, heap walks or explicit GC. */
@Service
public class GcMonitoringService implements AutoCloseable {
    private final MonitoringProperties properties;
    private final Instant processStartedAt;
    private final Map<String, MemoryType> poolTypes;
    private final long heapMaxBytes;
    private record RetainedEvent(Event event, long recordedNanos) { }
    private final ArrayDeque<RetainedEvent> history = new ArrayDeque<>();
    private final LongSupplier nanoTime;
    private final Map<NotificationEmitter, NotificationListener> registrations = new LinkedHashMap<>();
    private final List<String> collectors = new ArrayList<>();
    private final List<String> unsupported = new ArrayList<>();
    private Instant recordingStartedAt;
    private long totalEvents;
    private long overwrittenEvents;
    private long expiredEvents;
    private long notificationFailures;
    private int capacity;
    private long maxAgeSeconds;
    private boolean started;
    private boolean closed;

    @Autowired
    public GcMonitoringService(MonitoringProperties properties) {
        this(properties, Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime()),
                memoryPoolTypes(), ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax());
    }

    GcMonitoringService(MonitoringProperties properties, Instant processStartedAt,
                        Map<String, MemoryType> poolTypes, long heapMaxBytes) {
        this(properties, processStartedAt, poolTypes, heapMaxBytes, System::nanoTime);
    }

    GcMonitoringService(MonitoringProperties properties, Instant processStartedAt,
                        Map<String, MemoryType> poolTypes, long heapMaxBytes, LongSupplier nanoTime) {
        this.properties = properties;
        this.processStartedAt = processStartedAt;
        this.poolTypes = Map.copyOf(poolTypes);
        this.heapMaxBytes = heapMaxBytes;
        this.nanoTime = nanoTime;
    }

    private static Map<String, MemoryType> memoryPoolTypes() {
        Map<String, MemoryType> types = new LinkedHashMap<>();
        for (var pool : ManagementFactory.getMemoryPoolMXBeans()) {
            types.put(pool.getName(), pool.getType());
        }
        return types;
    }

    @PostConstruct
    public void start() {
        start(ManagementFactory.getGarbageCollectorMXBeans());
    }

    synchronized void start(List<GarbageCollectorMXBean> beans) {
        if (started || closed) {
            return;
        }
        capacity = properties.getGcHistoryCapacity();
        if (capacity < 16 || capacity > 4096) {
            throw new IllegalArgumentException("echo.monitoring.gc-history-capacity must be between 16 and 4096");
        }
        maxAgeSeconds = properties.getGcHistoryMaxAgeSeconds();
        if (maxAgeSeconds < 60 || maxAgeSeconds > 604800) {
            throw new IllegalArgumentException("echo.monitoring.gc-history-max-age-seconds must be between 60 and 604800");
        }
        started = true;
        recordingStartedAt = Instant.now();
        if (!enabled()) {
            return;
        }
        for (var bean : beans) {
            if (!(bean instanceof NotificationEmitter emitter)) {
                unsupported.add(bounded(bean.getName()));
                continue;
            }
            if (!supportsGcNotifications(emitter)) {
                unsupported.add(bounded(bean.getName()));
                continue;
            }
            NotificationListener listener = (notification, handback) -> {
                if (!GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(notification.getType())) {
                    return;
                }
                try {
                    var info = GarbageCollectionNotificationInfo.from((CompositeData) notification.getUserData());
                    var gc = info.getGcInfo();
                    record(gc.getId(), info.getGcName(), info.getGcAction(), info.getGcCause(),
                            gc.getStartTime(), gc.getEndTime(), gc.getDuration(),
                            gc.getMemoryUsageBeforeGc(), gc.getMemoryUsageAfterGc());
                } catch (RuntimeException failure) {
                    synchronized (this) {
                        notificationFailures++;
                    }
                }
            };
            try {
                emitter.addNotificationListener(listener, null, null);
                registrations.put(emitter, listener);
                collectors.add(bounded(bean.getName()));
            } catch (RuntimeException failure) {
                unsupported.add(bounded(bean.getName()));
            }
        }
    }

    // Package-visible to exercise real event arithmetic and retention without inducing production GC.
    synchronized void record(long id, String collector, String action, String cause,
            long startMs, long endMs, long durationMs, Map<String, MemoryUsage> before,
            Map<String, MemoryUsage> after) {
        if (closed || !started || !enabled()) {
            return;
        }
        purgeExpired();
        long beforeHeap = 0;
        long afterHeap = 0;
        int heapPools = 0;
        boolean complete = true;
        List<Pool> pools = new ArrayList<>();
        for (var entry : poolTypes.entrySet()) {
            MemoryUsage previous = before.get(entry.getKey());
            MemoryUsage current = after.get(entry.getKey());
            if (previous == null || current == null) {
                if (entry.getValue() == MemoryType.HEAP) {
                    complete = false;
                }
                continue;
            }
            if (entry.getValue() == MemoryType.HEAP) {
                beforeHeap += previous.getUsed();
                afterHeap += current.getUsed();
                heapPools++;
            }
            if (pools.size() < 32) {
                pools.add(new Pool(bounded(entry.getKey()), entry.getValue().name(), previous.getUsed(),
                        current.getUsed(), current.getCommitted(), current.getMax()));
            }
        }
        // An unrecognised pool must not silently be counted as non-heap or produce a false total.
        complete &= heapPools > 0 && poolTypes.keySet().containsAll(before.keySet())
                && poolTypes.keySet().containsAll(after.keySet());
        Event event = new Event(totalEvents + 1, id, bounded(collector), bounded(action), bounded(cause),
                processStartedAt.plusMillis(startMs), processStartedAt.plusMillis(endMs), durationMs,
                complete ? beforeHeap : null, complete ? afterHeap : null,
                complete ? beforeHeap - afterHeap : null, heapMaxBytes, complete, List.copyOf(pools));
        if (history.size() == capacity) {
            history.removeFirst();
            overwrittenEvents++;
        }
        history.addLast(new RetainedEvent(event, nanoTime.getAsLong()));
        totalEvents++;
    }

    public synchronized GcHistoryDto snapshot(Long after, int limit) {
        if ((after != null && after < 0) || limit < 1 || limit > 512) {
            throw new IllegalArgumentException("after must be nonnegative and limit must be between 1 and 512");
        }
        purgeExpired();
        long oldest = history.isEmpty() ? 0 : history.getFirst().event().sequence();
        long cursor = after == null ? Math.max(0, totalEvents - limit) : after;
        List<Event> events = history.stream().map(RetainedEvent::event)
                .filter(event -> event.sequence() > cursor).limit(limit).toList();
        long next = events.isEmpty() ? Math.max(cursor, totalEvents) : events.get(events.size() - 1).sequence();
        boolean incomplete = history.stream().anyMatch(retained -> !retained.event().heapCoverageComplete());
        State state = closed || !enabled() ? State.DISABLED : collectors.isEmpty() ? State.UNSUPPORTED
                : !unsupported.isEmpty() || notificationFailures > 0 || incomplete ? State.PARTIAL : State.AVAILABLE;
        long missed = after == null ? 0 : Math.max(0, (history.isEmpty() ? totalEvents : oldest - 1) - after);
        return new GcHistoryDto(state, processStartedAt, recordingStartedAt, capacity, maxAgeSeconds, totalEvents,
                overwrittenEvents, expiredEvents, notificationFailures, List.copyOf(collectors), List.copyOf(unsupported),
                oldest, totalEvents, missed, next,
                next < totalEvents, events);
    }

    /** Monotonic expiry still runs on an idle server with no new GC or open admin page. */
    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    public synchronized void purgeExpired() {
        if (!started || closed) {
            return;
        }
        long now = nanoTime.getAsLong();
        long age = TimeUnit.SECONDS.toNanos(maxAgeSeconds);
        while (!history.isEmpty() && now - history.getFirst().recordedNanos() >= age) {
            history.removeFirst();
            expiredEvents++;
        }
    }

    private boolean enabled() {
        return properties.isEnabled() && properties.isJvmEnabled() && properties.isGcEnabled();
    }

    private static boolean supportsGcNotifications(NotificationEmitter emitter) {
        try {
            var notifications = emitter.getNotificationInfo();
            return notifications != null && Arrays.stream(notifications)
                    .flatMap(info -> Arrays.stream(info.getNotifTypes()))
                    .anyMatch(GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION::equals);
        } catch (RuntimeException unsupported) {
            return false;
        }
    }

    private static String bounded(String value) {
        return value == null ? "" : value.substring(0, Math.min(value.length(), 160));
    }

    @Override
    @PreDestroy
    public synchronized void close() {
        closed = true;
        registrations.forEach((emitter, listener) -> {
            try {
                emitter.removeNotificationListener(listener);
            } catch (ListenerNotFoundException ignored) {
                // Already removed; no listener remains attached to this service.
            }
        });
        registrations.clear();
        history.clear();
    }
}
