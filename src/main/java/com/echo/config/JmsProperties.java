package com.echo.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JMS configuration properties
 */
@Component
@ConfigurationProperties(prefix = "echo.jms")
@Getter
@Setter
public class JmsProperties {

    private boolean enabled = false;
    /** Startup policy description only; does not suppress Artemis warnings or change routing. */
    private boolean policySummaryEnabled = true;
    private String alias = "JMS";
    private int port = 61616;
    private String username = "admin";
    private String password = "admin";

    /** Queue listened to on Echo's embedded Artemis, where external services send requests. */
    private String queue = "ECHO.REQUEST";

    /** Opt in to source rollback after reply delivery failure; false preserves the legacy commit/skip policy. */
    private boolean rollbackOnReplyFailure = false;

    /** Cache committed target settings on SQLite; other databases retain per-exchange resolution. */
    private boolean targetCatalogEnabled = true;

    /** Endpoint-identifying field extracted from the JMS body: XML element name or JSON key. */
    private String endpointField = "ServiceName";

    /** Fraction of heap available for concurrent JMS message parsing; listeners wait when exhausted, applying backpressure through the broker. */
    private int processingMemoryPercent = 25;

    /** Conservative XML DOM memory-estimate multiplier relative to the encoded message size. */
    private int xmlMemoryExpansionFactor = 8;

    /** Heap fraction available to embedded Artemis; spill to disk using PAGE when exceeded. */
    private int brokerMemoryPercent = 15;

    /** Whether Artemis persists journals, paging, and large messages to disk; keep enabled in production. */
    private boolean persistent = true;

    /** Artemis journal, paging and large-message file directory. */
    private String dataDirectory = "./data/artemis";

    /** Maximum encoded bytes prefetched per listener consumer; avoid 30 consumers each retaining 1 MiB. */
    private int consumerWindowSize = 64 * 1024;

    /** Disk space to reserve before the broker blocks producers, preventing an actual ENOSPC failure. */
    private long minDiskFreeBytes = 512L * 1024 * 1024;

    /** Interval between Artemis disk-space checks. */
    private int diskScanPeriodMs = 1_000;

    /**
     * Idle timeout before the embedded broker deletes consumerless temporary queues used for request-reply.
     * Some long-lived clients create a temporary reply queue per request without deleting it; the broker would otherwise retain these until the connection closes,
     * potentially exhausting the heap within days. Set 0 to disable automatic deletion.
     */
    private long temporaryQueueIdleDeleteMs = 10_000;

    /** Initial redelivery delay after a transient listener failure. */
    private long redeliveryDelayMs = 250;

    /** Redelivery-delay multiplier, preventing a busy loop during storage or memory failures. */
    private double redeliveryMultiplier = 2.0;

    /** Maximum redelivery delay; negative values are not accepted. */
    private long maxRedeliveryDelayMs = 5_000;

    /** -1 disables automatic message discard based on the redelivery count. */
    private int maxDeliveryAttempts = -1;

    /** JMS request source: embedded Artemis by default, or an external shared broker such as an ESB. */
    private Source source = new Source();

    private Target target = new Target();

    /** Bounded idle outbound resource reuse; source transactions remain synchronous. */
    private Forward forward = new Forward();

    @Getter
    public static class Forward {
        private int sessionCacheSize = 8;
        /** Opt-in failure policy; false preserves provider connection/retry/failover settings. */
        @Setter
        private boolean boundedWaitsEnabled = false;

        public void setSessionCacheSize(int value) {
            if (value < 0 || value > 256) throw new IllegalArgumentException("JMS session-cache-size must be 0..256");
            sessionCacheSize = value;
        }
    }

    /**
     * JMS request source. {@code embedded} uses Echo's embedded Artemis;
     * {@code artemis} or {@code tibco} listens on {@link #queue} on an external broker.
     * When multiple Echo instances listen to the same queue, the broker delivers each message to only one instance.
     */
    @Getter
    @Setter
    public static class Source {
        public static final String EMBEDDED = "embedded";

        private String type = EMBEDDED;  // embedded | artemis | tibco
        private String serverUrl;
        private String username;
        private String password;
        /** Retry interval when an external broker is unavailable. */
        private long reconnectIntervalMs = 5_000;

        public boolean isExternal() {
            return isExternalType(type);
        }

        /** Used by configuration-class conditions: blank or any value other than {@code embedded} selects an external broker. */
        public static boolean isExternalType(String type) {
            return type != null && !type.isBlank() && !EMBEDDED.equalsIgnoreCase(type.trim());
        }

        /** Convert to the forwarding-target settings format, reusing each broker's ConnectionFactory creation logic. */
        public Target asConnectionTarget() {
            Target connection = new Target();
            connection.setType(type == null ? null : type.trim());
            connection.setServerUrl(serverUrl);
            connection.setUsername(username);
            connection.setPassword(password);
            return connection;
        }
    }

    @Getter
    @Setter
    public static class Target {
        /** Downstream name shown in the UI and request logs; display {@code application.yml} if blank. */
        private String name;
        private boolean enabled = false;
        private String type = "tibco";  // artemis | tibco
        private String serverUrl;
        private String username;
        private String password;
        private int timeoutSeconds = 30;

        /** Keep legacy YAML targets within the same finite range as database targets. */
        public void setTimeoutSeconds(int timeoutSeconds) {
            if (timeoutSeconds < 1 || timeoutSeconds > 300) {
                throw new IllegalArgumentException(
                        "echo.jms.target.timeout-seconds must be between 1 and 300");
            }
            this.timeoutSeconds = timeoutSeconds;
        }

        /** Target JMS server and queue for forwarding. */
        private String queue = "TARGET.REQUEST";
    }
}
