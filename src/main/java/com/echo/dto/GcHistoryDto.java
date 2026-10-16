package com.echo.dto;

import java.time.Instant;
import java.util.List;

/** Bounded observations of JVM notifications, not a heap dump or a leak diagnosis. */
public record GcHistoryDto(ResourceSnapshotDto.State state, Instant processStartedAt,
        Instant recordingStartedAt, int capacity, long maxAgeSeconds, long totalEvents, long overwrittenEvents, long expiredEvents,
        long notificationFailures, List<String> collectors, List<String> unsupportedCollectors,
        long oldestSequence, long lastSequence, long missedEvents, long nextAfter, boolean hasMore,
        List<Event> events) {

    public record Event(long sequence, long collectorId, String collector, String action, String cause,
            Instant startedAt, Instant endedAt, long durationMs, Long heapBeforeBytes, Long heapAfterBytes,
            Long reclaimedBytes, long heapMaxBytes, boolean heapCoverageComplete, List<Pool> pools) { }

    public record Pool(String name, String type, long beforeBytes, long afterBytes,
                       long committedAfterBytes, long maxBytes) { }
}
