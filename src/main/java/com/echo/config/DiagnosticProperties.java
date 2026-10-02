package com.echo.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Supplemental file diagnostics only; never changes durable Request Log policy. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "echo.diagnostics")
public class DiagnosticProperties {
    private boolean enabled = true;
    private boolean jmsEnabled = true;
    private boolean httpEnabled = true;
    private boolean sseEnabled = true;
    private int queueCapacity = 1024;
    /** Anomalies only by default; detail automatically expires after startup. */
    private boolean detailedEnabled;
    private int detailedSeconds = 300;
    private long dailyBytes = 10 * 1024 * 1024L;
    private long retainedBytes = 50 * 1024 * 1024L;
    private int retentionDays = 7;
    private int recordsPerSecond = 20;

    public int boundedDetailedSeconds() { return Math.max(1, Math.min(600, detailedSeconds)); }
    public long boundedDailyBytes() { return Math.max(1, Math.min(100 * 1024 * 1024L, dailyBytes)); }
    public long boundedRetainedBytes() { return Math.max(1, Math.min(500 * 1024 * 1024L, retainedBytes)); }
    public int boundedRetentionDays() { return Math.max(1, Math.min(7, retentionDays)); }
    public int boundedRecordsPerSecond() { return Math.max(1, Math.min(100, recordsPerSecond)); }

    public int boundedQueueCapacity() {
        return Math.max(32, Math.min(4096, queueCapacity));
    }
}
