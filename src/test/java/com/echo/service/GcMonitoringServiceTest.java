package com.echo.service;

import com.echo.config.MonitoringProperties;
import com.echo.dto.ResourceSnapshotDto.State;
import org.junit.jupiter.api.Test;

import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.MBeanNotificationInfo;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.Future;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class GcMonitoringServiceTest {
    private static final Instant START = Instant.parse("2026-10-16T00:00:00Z");

    private static GcMonitoringService monitor() {
        var properties = new MonitoringProperties();
        properties.setGcHistoryCapacity(16);
        var service = new GcMonitoringService(properties, START,
                Map.of("young", MemoryType.HEAP, "old", MemoryType.HEAP, "meta", MemoryType.NON_HEAP), 1000);
        service.start(List.of());
        return service;
    }

    private static MemoryUsage usage(long bytes) { return new MemoryUsage(0, bytes, 1000, 1000); }

    private static void event(GcMonitoringService service, int id) {
        service.record(id, "young GC", "minor", "allocation", 100, 110, 10,
                Map.of("young", usage(300), "old", usage(100), "meta", usage(90)),
                Map.of("young", usage(20), "old", usage(140), "meta", usage(95)));
    }

    @Test
    void separatesHeapFromMetaspaceAndPreservesOldGenerationPromotion() {
        try (var service = monitor()) {
            event(service, 5);
            var event = service.snapshot(null, 512).events().get(0);
            assertThat(event.heapBeforeBytes()).isEqualTo(400);
            assertThat(event.heapAfterBytes()).isEqualTo(160);
            assertThat(event.reclaimedBytes()).isEqualTo(240);
            assertThat(event.startedAt()).isEqualTo(START.plusMillis(100));
            assertThat(event.endedAt()).isEqualTo(START.plusMillis(110));
            assertThat(event.collectorId()).isEqualTo(5);
            assertThat(event.pools()).hasSize(3).anySatisfy(pool -> {
                assertThat(pool.name()).isEqualTo("old");
                assertThat(pool.afterBytes()).isEqualTo(140);
            });
        }
    }

    @Test
    void missingHeapPoolsAreUnknownInsteadOfZeroOrFalseReclamation() {
        try (var service = monitor()) {
            service.record(1, "gc", "minor", "allocation", 1, 2, 1,
                    Map.of("young", usage(300)), Map.of("young", usage(10)));
            var event = service.snapshot(null, 1).events().get(0);
            assertThat(event.heapCoverageComplete()).isFalse();
            assertThat(event.heapAfterBytes()).isNull();
            assertThat(event.reclaimedBytes()).isNull();
        }
    }

    @Test
    void concurrentCollectionGrowthIsNotClampedToZero() {
        try (var service = monitor()) {
            var before = Map.of("young", usage(10), "old", usage(100));
            var after = Map.of("young", usage(30), "old", usage(140));
            service.record(1, "gc", "concurrent", "allocation", 1, 2, 1, before, after);
            assertThat(service.snapshot(null, 1).events().get(0).reclaimedBytes()).isEqualTo(-60);
        }
    }

    @Test
    void boundedHistoryExposesOverwritesAndSupportsLossAwareCursorPaging() {
        try (var service = monitor()) {
            for (int i = 0; i < 100; i++) { event(service, i); }
            var first = service.snapshot(0L, 4);
            assertThat(first.events()).hasSize(4);
            assertThat(first.oldestSequence()).isEqualTo(85);
            assertThat(first.missedEvents()).isEqualTo(84);
            assertThat(first.overwrittenEvents()).isEqualTo(84);
            assertThat(first.nextAfter()).isEqualTo(88);
            assertThat(first.hasMore()).isTrue();
            var next = service.snapshot(first.nextAfter(), 512);
            assertThat(next.events()).hasSize(12);
            assertThat(next.missedEvents()).isZero();
            assertThat(next.hasMore()).isFalse();
            assertThat(service.snapshot(null, 2).events()).extracting("sequence").containsExactly(99L, 100L);
            assertThat(service.snapshot(100L, 512).events()).isEmpty();
        }
    }

    @Test
    void idleHousekeepingExpiresEventsAndAdvancesAnEmptyCursorWithoutDoubleCounting() {
        var properties = new MonitoringProperties();
        properties.setGcHistoryCapacity(16);
        properties.setGcHistoryMaxAgeSeconds(60);
        var clock = new AtomicLong();
        try (var service = new GcMonitoringService(properties, START,
                Map.of("young", MemoryType.HEAP, "old", MemoryType.HEAP, "meta", MemoryType.NON_HEAP),
                1000, clock::get)) {
            service.start(List.of());
            for (int i = 0; i < 20; i++) { event(service, i); }
            clock.set(TimeUnit.SECONDS.toNanos(59));
            service.purgeExpired();
            assertThat(service.snapshot(null, 512).events()).hasSize(16);
            clock.set(TimeUnit.SECONDS.toNanos(60));
            // No notification or browser request is needed to release old records.
            service.purgeExpired();
            var empty = service.snapshot(0L, 512);
            assertThat(empty.events()).isEmpty();
            assertThat(empty.expiredEvents()).isEqualTo(16);
            assertThat(empty.overwrittenEvents()).isEqualTo(4);
            assertThat(empty.totalEvents()).isEqualTo(20);
            assertThat(empty.missedEvents()).isEqualTo(20);
            assertThat(empty.nextAfter()).isEqualTo(20);
            assertThat(empty.hasMore()).isFalse();
            assertThat(service.snapshot(empty.nextAfter(), 512).missedEvents()).isZero();
            event(service, 21);
            var next = service.snapshot(empty.nextAfter(), 512);
            assertThat(next.events()).extracting("sequence").containsExactly(21L);
            assertThat(next.expiredEvents()).isEqualTo(16);
            assertThat(next.maxAgeSeconds()).isEqualTo(60);
        }
    }

    @Test
    void readsAndNewNotificationsAlsoPruneExpiredHistory() {
        var properties = new MonitoringProperties();
        properties.setGcHistoryMaxAgeSeconds(60);
        var clock = new AtomicLong();
        try (var service = new GcMonitoringService(properties, START, Map.of(), 1000, clock::get)) {
            service.start(List.of());
            event(service, 1);
            clock.set(TimeUnit.SECONDS.toNanos(60));
            event(service, 2);
            assertThat(service.snapshot(0L, 512).missedEvents()).isEqualTo(1);
            clock.set(TimeUnit.SECONDS.toNanos(120));
            assertThat(service.snapshot(0L, 512).expiredEvents()).isEqualTo(2);
            assertThat(service.snapshot(null, 512).events()).isEmpty();
        }
        for (long invalid : new long[]{0, 59, 604801, Long.MAX_VALUE}) {
            properties.setGcHistoryMaxAgeSeconds(invalid);
            try (var service = new GcMonitoringService(properties, START, Map.of(), 1000)) {
                assertThatThrownBy(() -> service.start(List.of())).isInstanceOf(IllegalArgumentException.class);
            }
        }
    }

    @Test
    void concurrentWritersAndReadersKeepSequencesUniqueAndStorageBounded() throws Exception {
        try (var service = monitor()) {
            var executor = Executors.newFixedThreadPool(4);
            try {
                List<Future<?>> writers = new ArrayList<>();
                for (int worker = 0; worker < 3; worker++) {
                    writers.add(executor.submit(() -> { for (int i = 0; i < 1000; i++) { event(service, i); } }));
                }
                var reader = executor.submit(() -> {
                    for (int i = 0; i < 1000; i++) {
                        var page = service.snapshot(null, 512);
                        assertThat(page.events()).hasSizeLessThanOrEqualTo(16);
                        assertThat(page.events().stream().map(event -> event.sequence()).toList()).isSorted().doesNotHaveDuplicates();
                    }
                });
                reader.get(10, TimeUnit.SECONDS);
                for (var writer : writers) { writer.get(10, TimeUnit.SECONDS); }
                executor.shutdown();
                assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
                assertThat(service.snapshot(null, 512).totalEvents()).isEqualTo(3000);
            } finally { executor.shutdownNow(); }
        }
    }

    @Test
    void listenerFailuresAreVisibleAndClosingDetachesExactlyOnce() throws Exception {
        GarbageCollectorMXBean bean = mock(GarbageCollectorMXBean.class, withSettings().extraInterfaces(NotificationEmitter.class));
        when(bean.getName()).thenReturn("test collector");
        var emitter = (NotificationEmitter) bean;
        when(emitter.getNotificationInfo()).thenReturn(new MBeanNotificationInfo[]{new MBeanNotificationInfo(
                new String[]{"com.sun.management.gc.notification"}, Notification.class.getName(), "GC")});
        var service = new GcMonitoringService(new MonitoringProperties(), START, Map.of(), 1000);
        service.start(List.of(bean));
        service.start(List.of(bean));
        var listener = org.mockito.ArgumentCaptor.forClass(NotificationListener.class);
        verify(emitter).addNotificationListener(listener.capture(), isNull(), isNull());
        listener.getValue().handleNotification(new Notification("unrelated", "jvm", 1), null);
        assertThat(service.snapshot(null, 1).notificationFailures()).isZero();
        listener.getValue().handleNotification(new Notification("com.sun.management.gc.notification", "jvm", 2), null);
        assertThat(service.snapshot(null, 1).state()).isEqualTo(State.PARTIAL);
        assertThat(service.snapshot(null, 1).notificationFailures()).isEqualTo(1);
        service.close();
        service.close();
        verify(emitter).removeNotificationListener(listener.getValue());
        event(service, 1);
        assertThat(service.snapshot(null, 1).events()).isEmpty();
    }

    @Test
    void unsupportedNotificationProvidersCannotBreakApplicationStartup() {
        GarbageCollectorMXBean bean = mock(GarbageCollectorMXBean.class, withSettings().extraInterfaces(NotificationEmitter.class));
        when(bean.getName()).thenReturn("unsupported collector");
        var emitter = (NotificationEmitter) bean;
        when(emitter.getNotificationInfo()).thenThrow(new UnsupportedOperationException());
        try (var service = new GcMonitoringService(new MonitoringProperties())) {
            service.start(List.of(bean));
            assertThat(service.snapshot(null, 1).state()).isEqualTo(State.UNSUPPORTED);
            assertThat(service.snapshot(null, 1).unsupportedCollectors()).containsExactly("unsupported collector");
        }
    }

    @Test
    void disabledMonitoringNeverRegistersOrRetainsEvents() {
        for (int setting = 0; setting < 3; setting++) {
            var properties = new MonitoringProperties();
            if (setting == 0) { properties.setEnabled(false); }
            if (setting == 1) { properties.setJvmEnabled(false); }
            if (setting == 2) { properties.setGcEnabled(false); }
            try (var service = new GcMonitoringService(properties)) {
                GarbageCollectorMXBean bean = mock(GarbageCollectorMXBean.class);
                service.start(List.of(bean));
                event(service, 1);
                assertThat(service.snapshot(null, 512).state()).isEqualTo(State.DISABLED);
                assertThat(service.snapshot(null, 512).totalEvents()).isZero();
                verifyNoInteractions(bean);
            }
        }
    }

    @Test
    void rejectsUnboundedConfigurationAndReadLimits() {
        var properties = new MonitoringProperties();
        properties.setGcHistoryCapacity(4097);
        try (var service = new GcMonitoringService(properties)) {
            assertThatThrownBy(service::start).isInstanceOf(IllegalArgumentException.class);
        }
        try (var service = monitor()) {
            assertThatThrownBy(() -> service.snapshot(-1L, 10)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.snapshot(null, 513)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
