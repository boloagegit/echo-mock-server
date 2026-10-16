package com.echo.service;

import com.echo.config.MonitoringProperties;

/** Tiny standalone JVM: exercises actual collector notifications without GC in the test runner. */
public final class GcNotificationProbe {
    private static volatile byte[] allocation;
    private GcNotificationProbe() { }

    public static void main(String[] args) throws Exception {
        try (var monitor = new GcMonitoringService(new MonitoringProperties())) {
            monitor.start();
            // More cumulative allocation than the child JVM's 64 MiB heap; no retained list.
            for (int i = 0; i < 2000; i++) {
                allocation = new byte[128 * 1024];
            }
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (monitor.snapshot(null, 512).totalEvents() < 2 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            var history = monitor.snapshot(null, 512);
            if (history.totalEvents() < 2 || history.notificationFailures() != 0
                    || history.events().stream().noneMatch(event -> event.heapCoverageComplete()
                    && event.reclaimedBytes() != null && event.reclaimedBytes() > 0)) {
                throw new AssertionError("Actual GC notifications unavailable or incomplete: " + history);
            }
            System.out.println("GC_PROBE_OK events=" + history.totalEvents() + " lastAllocation=" + allocation.length);
        }
    }
}
