package com.echo.service;

import com.echo.config.JmsProperties;
import com.echo.config.MonitoringProperties;
import com.echo.dto.ResourceSnapshotDto.State;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.file.Path;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ResourceMonitoringServiceTest {
    @Test
    void supplementalDiagnosticsAreAvailableOnManualSnapshotWithoutIo() {
        try (var diagnostics = new com.echo.diagnostics.TransactionDiagnostics(
                new com.echo.config.DiagnosticProperties(), line -> { })) {
            diagnostics.start();
            var monitor = service(new MonitoringProperties(), new AtomicInteger());
            monitor.setDiagnostics(diagnostics);
            assertThat(monitor.snapshot().sections().get("applicationLog").values())
                    .containsEntry("diagnosticRunning", true).containsEntry("diagnosticDropped", 0L);
        }
    }
    @org.junit.jupiter.api.io.TempDir private Path temporary;
    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> emptyProvider() { return mock(ObjectProvider.class); }

    private static ResourceMonitoringService service(MonitoringProperties properties, AtomicInteger jvmReads) {
        return new ResourceMonitoringService(properties, emptyProvider(), emptyProvider(), emptyProvider(),
                emptyProvider(), emptyProvider(), emptyProvider(), emptyProvider(), emptyProvider(),
                emptyProvider(), emptyProvider(), new JmsProperties(), "jdbc:h2:mem:test", "nonexistent-backup-dir") {
            @Override protected Map<String, Object> jvm() {
                jvmReads.incrementAndGet();
                throw new IllegalStateException("credentials=private; internal-host; request-body");
            }
        };
    }

    @Test
    void globalDisableSkipsAllCollectors() {
        var properties = new MonitoringProperties();
        properties.setEnabled(false);
        var reads = new AtomicInteger();
        var snapshot = service(properties, reads).snapshot();
        assertThat(snapshot.enabled()).isFalse();
        assertThat(snapshot.sections()).hasSize(9);
        snapshot.sections().values().forEach(section -> {
            assertThat(section.state()).isEqualTo(State.DISABLED);
            assertThat(section.values()).isEmpty();
        });
        assertThat(reads.get()).isZero();
    }

    @Test
    void individualDisableSkipsItsCollectorButNotOtherGroups() {
        var properties = new MonitoringProperties();
        properties.setJvmEnabled(false);
        var reads = new AtomicInteger();
        var snapshot = service(properties, reads).snapshot();
        assertThat(snapshot.sections().get("jvm").state()).isEqualTo(State.DISABLED);
        assertThat(snapshot.sections().get("database").state()).isEqualTo(State.UNSUPPORTED);
        assertThat(reads.get()).isZero();
    }

    @Test
    void failureIsIsolatedAndNeverLeaksExceptionDetails() throws Exception {
        var snapshot = service(new MonitoringProperties(), new AtomicInteger()).snapshot();
        assertThat(snapshot.sections().get("jvm").state()).isEqualTo(State.FAILED);
        assertThat(snapshot.sections().get("jvm").values()).isEmpty();
        assertThat(snapshot.sections().get("database").state()).isEqualTo(State.UNSUPPORTED);
        String json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().writeValueAsString(snapshot);
        assertThat(json).doesNotContain("credentials", "internal-host", "request-body", "nonexistent-backup-dir");
    }

    @Test
    void databaseSnapshotDoesNotAcquireOrTestConnections() throws Exception {
        var source = mock(com.zaxxer.hikari.HikariDataSource.class);
        var pool = mock(com.zaxxer.hikari.HikariPoolMXBean.class);
        when(source.getHikariPoolMXBean()).thenReturn(pool);
        when(pool.getActiveConnections()).thenReturn(2);
        ObjectProvider<javax.sql.DataSource> sources = emptyProvider();
        when(sources.getIfAvailable()).thenReturn(source);
        var monitor = new ResourceMonitoringService(new MonitoringProperties(), emptyProvider(), emptyProvider(),
                emptyProvider(), emptyProvider(), sources, emptyProvider(), emptyProvider(), emptyProvider(),
                emptyProvider(), emptyProvider(), new JmsProperties(), "jdbc:h2:mem:test", "missing-backups");
        assertThat(monitor.snapshot().sections().get("database").values()).containsEntry("poolActive", 2);
        verify(source, never()).getConnection();
        verify(source, never()).getConnection(anyString(), anyString());
        verify(source, never()).getJdbcUrl();
    }

    @Test
    void cacheStatsAreAllowlistedWithoutMaintenanceOrQueries() {
        var response = mock(ResponseService.class);
        when(response.getBodyCacheStats()).thenReturn(Map.of("entries", 0L, "maximumWeight", 100L,
                "password", "private", "targetUrl", "internal-host"));
        ObjectProvider<ResponseService> provider = emptyProvider();
        when(provider.getIfAvailable()).thenReturn(response);
        var monitor = new ResourceMonitoringService(new MonitoringProperties(), emptyProvider(), emptyProvider(),
                emptyProvider(), emptyProvider(), emptyProvider(), emptyProvider(), emptyProvider(), provider,
                emptyProvider(), emptyProvider(), new JmsProperties(), "jdbc:h2:mem:test", "missing-backups");
        var values = monitor.snapshot().sections().get("caches").values();
        assertThat(values).containsExactlyInAnyOrderEntriesOf(Map.of("body_entries", 0L, "body_maximumWeight", 100L));
        verify(response, never()).count();
        verify(response, never()).findAll();
    }

    @Test
    void standardSqliteUrlParametersDoNotHideLocalStorage() throws Exception {
        Path file = Files.createFile(temporary.resolve("test.sqlite"));
        var plain = storageMonitor("jdbc:sqlite:" + file, temporary.toString()).snapshot().sections().get("storage");
        var parameters = storageMonitor("jdbc:sqlite:" + file + "?journal_mode=WAL&busy_timeout=10000&synchronous=NORMAL&foreign_keys=ON",
                temporary.toString()).snapshot().sections().get("storage");
        assertThat(parameters.state()).isEqualTo(plain.state());
        assertThat(parameters.values().keySet()).containsExactlyInAnyOrderElementsOf(plain.values().keySet());
        if (plain.values().containsKey("backupFreeBytes")) {
            assertThat(parameters.values()).containsKey("dataFreeBytes");
        }
    }

    @Test
    void missingOrMemoryStorageDoesNotFabricateZero() {
        var missing = storageMonitor("jdbc:sqlite:" + temporary.resolve("absent.sqlite"),
                temporary.resolve("absent-backups").toString()).snapshot().sections().get("storage");
        assertThat(missing.state()).isEqualTo(State.UNSUPPORTED);
        assertThat(missing.values()).isEmpty();
        var memory = storageMonitor("jdbc:sqlite:file:memory?mode=memory&cache=shared",
                temporary.resolve("absent-backups").toString()).snapshot().sections().get("storage");
        assertThat(memory.state()).isEqualTo(State.UNSUPPORTED);
        assertThat(memory.values()).isEmpty();
    }

    private static ResourceMonitoringService storageMonitor(String url, String backups) {
        return new ResourceMonitoringService(new MonitoringProperties(), emptyProvider(), emptyProvider(),
                emptyProvider(), emptyProvider(), emptyProvider(), emptyProvider(), emptyProvider(), emptyProvider(),
                emptyProvider(), emptyProvider(), new JmsProperties(), url, backups);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"jvm", "caches", "scheduler", "jms", "http", "database", "requestLog", "applicationLog", "storage"})
    void everyGroupSwitchSkipsItsCollector(String disabled) throws Exception {
        var properties = new MonitoringProperties();
        String setter = "set" + Character.toUpperCase(disabled.charAt(0)) + disabled.substring(1) + "Enabled";
        MonitoringProperties.class.getMethod(setter, boolean.class).invoke(properties, false);
        var reads = new java.util.HashSet<String>();
        var monitor = new ResourceMonitoringService(properties, emptyProvider(), emptyProvider(), emptyProvider(),
                emptyProvider(), emptyProvider(), emptyProvider(), emptyProvider(), emptyProvider(), emptyProvider(),
                emptyProvider(), new JmsProperties(), "jdbc:h2:mem:test", "missing") {
            private Map<String, Object> read(String name) { reads.add(name); return Map.of("value", 0); }
            @Override protected Map<String, Object> jvm() { return read("jvm"); }
            @Override protected Map<String, Object> caches() { return read("caches"); }
            @Override protected Map<String, Object> scheduler() { return read("scheduler"); }
            @Override protected Map<String, Object> jms() { return read("jms"); }
            @Override protected Map<String, Object> http() { return read("http"); }
            @Override protected Map<String, Object> database() { return read("database"); }
            @Override protected Map<String, Object> requestLog() { return read("requestLog"); }
            @Override protected Map<String, Object> applicationLog() { return read("applicationLog"); }
            @Override protected Map<String, Object> storage() { return read("storage"); }
        };
        var snapshot = monitor.snapshot();
        assertThat(reads).hasSize(8).doesNotContain(disabled);
        assertThat(snapshot.sections().get(disabled).state()).isEqualTo(State.DISABLED);
    }
}
