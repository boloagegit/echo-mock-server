package com.echo.service;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.LoggerContext;
import com.echo.agent.LogAgent;
import com.echo.diagnostics.TransactionDiagnostics;
import com.echo.config.CacheConfig;
import com.echo.config.JmsProperties;
import com.echo.config.MonitoringProperties;
import com.echo.config.SqliteSerializingJpaTransactionManager;
import com.echo.config.SqliteRecoveryEnvironmentPostProcessor;
import com.echo.controller.UniversalMockController;
import com.echo.dto.ResourceSnapshotDto;
import com.echo.dto.ResourceSnapshotDto.Section;
import com.echo.dto.ResourceSnapshotDto.State;
import com.echo.jms.JmsMessageMemoryBudget;
import com.echo.jms.JmsRuntimeMetrics;
import com.github.benmanes.caffeine.cache.Cache;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** No SQL, connection tests, queue/key inventories, maintenance, histories, or periodic collection. */
@Service
public class ResourceMonitoringService {
    private final MonitoringProperties properties;
    private final ObjectProvider<JmsMessageMemoryBudget> jmsBudget;
    private final ObjectProvider<JmsRuntimeMetrics> jmsMetrics;
    private final ObjectProvider<HttpOutboundForwarder> http;
    private final ObjectProvider<UniversalMockController> scheduler;
    private final ObjectProvider<DataSource> datasource;
    private final ObjectProvider<PlatformTransactionManager> transactions;
    private final ObjectProvider<CacheManager> caches;
    private final ObjectProvider<ResponseService> responses;
    private final ObjectProvider<ResponseTemplateService> templates;
    private final ObjectProvider<LogAgent> logAgent;
    private final JmsProperties jmsProperties;
    private TransactionDiagnostics diagnostics;

    @org.springframework.beans.factory.annotation.Autowired
    public void setDiagnostics(TransactionDiagnostics diagnostics) { this.diagnostics = diagnostics; }
    private final String datasourceUrl;
    private final String backupPath;
    private final Instant processStartedAt = Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime());

    public ResourceMonitoringService(MonitoringProperties properties,
            ObjectProvider<JmsMessageMemoryBudget> jmsBudget, ObjectProvider<JmsRuntimeMetrics> jmsMetrics,
            ObjectProvider<HttpOutboundForwarder> http, ObjectProvider<UniversalMockController> scheduler,
            ObjectProvider<DataSource> datasource, ObjectProvider<PlatformTransactionManager> transactions,
            ObjectProvider<CacheManager> caches, ObjectProvider<ResponseService> responses,
            ObjectProvider<ResponseTemplateService> templates, ObjectProvider<LogAgent> logAgent,
            JmsProperties jmsProperties, @Value("${spring.datasource.url:}") String datasourceUrl,
            @Value("${echo.backup.path:./backups}") String backupPath) {
        this.properties = properties;
        this.jmsBudget = jmsBudget;
        this.jmsMetrics = jmsMetrics;
        this.http = http;
        this.scheduler = scheduler;
        this.datasource = datasource;
        this.transactions = transactions;
        this.caches = caches;
        this.responses = responses;
        this.templates = templates;
        this.logAgent = logAgent;
        this.jmsProperties = jmsProperties;
        this.datasourceUrl = datasourceUrl;
        this.backupPath = backupPath;
    }

    public ResourceSnapshotDto snapshot() {
        Map<String, Section> sections = new LinkedHashMap<>();
        sections.put("jvm", collect(properties.isJvmEnabled(), this::jvm));
        sections.put("caches", collect(properties.isCachesEnabled(), this::caches));
        sections.put("scheduler", collect(properties.isSchedulerEnabled(), this::scheduler));
        sections.put("jms", collect(properties.isJmsEnabled(), this::jms));
        sections.put("http", collect(properties.isHttpEnabled(), this::http));
        sections.put("database", collect(properties.isDatabaseEnabled(), this::database));
        sections.put("requestLog", collect(properties.isRequestLogEnabled(), this::requestLog));
        sections.put("applicationLog", collect(properties.isApplicationLogEnabled(), this::applicationLog));
        sections.put("storage", collect(properties.isStorageEnabled(), this::storage));
        return new ResourceSnapshotDto(properties.isEnabled(), Instant.now(), processStartedAt, Map.copyOf(sections));
    }

    private Section collect(boolean enabled, Supplier<Map<String, Object>> collector) {
        if (!properties.isEnabled() || !enabled) return Section.empty(State.DISABLED);
        try {
            Map<String, Object> values = collector.get();
            if (values == null || values.isEmpty()) return Section.empty(State.UNSUPPORTED);
            return new Section(Boolean.FALSE.equals(values.get("coverageComplete")) ? State.PARTIAL : State.AVAILABLE,
                    Map.copyOf(values));
        } catch (RuntimeException e) {
            // Exceptions may contain URLs, paths, credentials, or body contents; never export them.
            return Section.empty(State.FAILED);
        }
    }

    protected Map<String, Object> jvm() {
        Map<String, Object> values = new LinkedHashMap<>();
        var memory = ManagementFactory.getMemoryMXBean();
        var heap = memory.getHeapMemoryUsage();
        var nonHeap = memory.getNonHeapMemoryUsage();
        putNonnegative(values, "heapUsedBytes", heap.getUsed());
        putNonnegative(values, "heapMaxBytes", heap.getMax());
        putNonnegative(values, "nonHeapUsedBytes", nonHeap.getUsed());
        putNonnegative(values, "nonHeapCommittedBytes", nonHeap.getCommitted());
        values.put("threads", ManagementFactory.getThreadMXBean().getThreadCount());
        values.put("uptimeSeconds", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
        long count = 0, time = 0;
        boolean gcAvailable = false;
        for (var gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (gc.getCollectionCount() >= 0 && gc.getCollectionTime() >= 0) {
                gcAvailable = true;
                count += gc.getCollectionCount();
                time += gc.getCollectionTime();
            }
        }
        if (gcAvailable) { values.put("gcCount", count); values.put("gcTimeMs", time); }
        for (BufferPoolMXBean pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
            if (!Set.of("direct", "mapped").contains(pool.getName())) continue;
            putNonnegative(values, pool.getName() + "Bytes", pool.getMemoryUsed());
            putNonnegative(values, pool.getName() + "Count", pool.getCount());
        }
        return values;
    }

    protected Map<String, Object> caches() {
        Map<String, Object> values = new LinkedHashMap<>();
        CacheManager manager = caches.getIfAvailable();
        if (manager != null) {
            for (String name : CacheConfig.ALL_RULE_CACHES) {
                var springCache = manager.getCache(name);
                if (springCache != null && springCache.getNativeCache() instanceof Cache<?, ?> cache) {
                    values.put(name + "Entries", cache.estimatedSize());
                    values.put(name + "Evictions", cache.stats().evictionCount());
                }
            }
        }
        ResponseService response = responses.getIfAvailable();
        ResponseTemplateService template = templates.getIfAvailable();
        if (response != null) cacheValues(values, "body", response.getBodyCacheStats());
        if (template != null) cacheValues(values, "template", template.getTemplateCacheStats());
        return values;
    }

    private static void cacheValues(Map<String, Object> values, String prefix, Map<String, Object> stats) {
        for (String key : Set.of("entries", "weightedSize", "maximumWeight", "requestCount", "evictionCount")) {
            Object value = stats.get(key);
            if (value instanceof Number) values.put(prefix + "_" + key, value);
        }
    }

    protected Map<String, Object> scheduler() {
        UniversalMockController controller = scheduler.getIfAvailable();
        return controller == null ? Map.of() : Map.of("waitingTasks", controller.getPendingDelayTaskCount(),
                "activeWorkers", controller.getActiveDelayWorkerCount(), "workerCapacity", controller.getDelayWorkerCapacity());
    }

    protected Map<String, Object> jms() {
        if (!jmsProperties.isEnabled()) return Map.of();
        Map<String, Object> values = new LinkedHashMap<>();
        JmsRuntimeMetrics metrics = jmsMetrics.getIfAvailable();
        JmsMessageMemoryBudget budget = jmsBudget.getIfAvailable();
        if (metrics != null) values.putAll(metrics.snapshot());
        if (budget != null) {
            var state = budget.snapshot();
            values.put("reservedBytes", state.reservedBytes());
            values.put("maximumBytes", state.maximumBytes());
            values.put("waitingThreads", state.waitingThreads());
            values.put("processingRunning", state.running());
        }
        // These reflect Echo's unchanged wildcard policy, not downstream broker health.
        values.put("maxDeliveryAttempts", jmsProperties.getMaxDeliveryAttempts());
        return values;
    }

    protected Map<String, Object> http() {
        HttpOutboundForwarder forwarder = http.getIfAvailable();
        return forwarder == null ? Map.of() : forwarder.resourceMetricsSnapshot();
    }

    protected Map<String, Object> database() {
        Map<String, Object> values = new LinkedHashMap<>();
        DataSource source = datasource.getIfAvailable();
        if (source instanceof HikariDataSource hikari && hikari.getHikariPoolMXBean() != null) {
            var pool = hikari.getHikariPoolMXBean();
            values.put("poolActive", pool.getActiveConnections());
            values.put("poolIdle", pool.getIdleConnections());
            values.put("poolTotal", pool.getTotalConnections());
            values.put("poolWaiting", pool.getThreadsAwaitingConnection());
        }
        PlatformTransactionManager manager = transactions.getIfAvailable();
        if (manager instanceof SqliteSerializingJpaTransactionManager sqlite) {
            values.put("writerWaiting", sqlite.getQueuedWriterCount());
            values.put("writerActive", sqlite.getActiveWriterCount());
        }
        return values;
    }

    protected Map<String, Object> requestLog() {
        LogAgent agent = logAgent.getIfAvailable();
        if (agent == null) return Map.of();
        var stats = agent.getStats();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("queueItems", stats.getQueueSize());
        values.put("processed", stats.getProcessedCount());
        values.put("dropped", stats.getDroppedCount());
        if (agent.isDurableMode()) {
            values.put("queueBytes", stats.getQueueBytes());
            values.put("capacityBytes", stats.getQueueCapacityBytes());
            values.put("inFlightBytes", stats.getInFlightBytes());
            values.put("byteLimit", stats.getInFlightByteLimit());
            values.put("waitingProducers", stats.getWaitingProducers());
            values.put("backpressureActive", stats.isBackpressureActive());
            values.put("storageUnavailable", agent.isStorageUnavailable());
            values.put("consumerRunning", agent.isDurableConsumerRunning());
        }
        return values;
    }

    protected Map<String, Object> applicationLog() {
        Map<String, Object> values = new LinkedHashMap<>();
        if (diagnostics != null) values.putAll(diagnostics.snapshot());
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)) return values;
        var appenders = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).iteratorForAppenders();
        int examined = 0;
        while (appenders.hasNext() && examined++ < 8) {
            var appender = appenders.next();
            if (appender instanceof AsyncAppender async && "ASYNC_FILE".equals(appender.getName())) {
                values.put("queueUsed", async.getNumberOfElementsInQueue());
                values.put("queueCapacity", async.getQueueSize());
                values.put("started", async.isStarted());
                break;
            }
        }
        return values;
    }

    protected Map<String, Object> storage() {
        Map<String, Object> values = new LinkedHashMap<>();
        if (datasourceUrl.startsWith("jdbc:sqlite:")) {
            String name = datasourceUrl.substring("jdbc:sqlite:".length());
            // Standard SQLite profiles carry PRAGMA query parameters. URI databases are
            // intentionally excluded because mode=memory may not represent a local file.
            if (!name.startsWith(":") && !name.startsWith("file:")) {
                SqliteRecoveryEnvironmentPostProcessor.databasePath(datasourceUrl)
                        .ifPresent(path -> localFree(values, "dataFreeBytes", path));
            }
        } else if (datasourceUrl.startsWith("jdbc:h2:file:")) {
            String name = datasourceUrl.substring("jdbc:h2:file:".length()).split(";", 2)[0];
            if (!name.startsWith("~")) localFree(values, "dataFreeBytes", Path.of(name).toAbsolutePath().getParent());
        }
        localFree(values, "backupFreeBytes", Path.of(backupPath));
        return values;
    }

    private static void localFree(Map<String, Object> values, String key, Path path) {
        try {
            if (!Files.exists(path)) return;
            var store = Files.getFileStore(path);
            if (!Set.of("apfs", "hfs", "ext4", "ext3", "xfs", "btrfs", "ntfs", "overlay", "tmpfs")
                    .contains(store.type().toLowerCase(Locale.ROOT))) return;
            putNonnegative(values, key, store.getUsableSpace());
        } catch (java.io.IOException | RuntimeException e) {
            // Other configured storage locations remain independently available.
            values.put("coverageComplete", false);
        }
    }

    private static void putNonnegative(Map<String, Object> values, String key, long value) {
        if (value >= 0) values.put(key, value);
    }
}
