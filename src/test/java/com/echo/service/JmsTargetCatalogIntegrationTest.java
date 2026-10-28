package com.echo.service;

import com.echo.config.JmsProperties;
import com.echo.config.SqliteTransactionConfiguration;
import com.echo.dto.JmsTargetConnectionRequest;
import com.echo.dto.JmsTargetConnectionDto;
import com.echo.entity.JmsTargetConnection;
import com.echo.repository.JmsTargetConnectionRepository;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JmsTargetConnectionService.class, JmsProperties.class, JmsCredentialCipher.class,
        SqliteTransactionConfiguration.class})
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:sqlite:file:jms-target-catalog-test?mode=memory&cache=shared",
        "spring.datasource.driver-class-name=org.sqlite.JDBC",
        "spring.datasource.hikari.maximum-pool-size=3",
        "spring.datasource.hikari.minimum-idle=3",
        "spring.datasource.hikari.connection-timeout=500",
        "spring.jpa.database-platform=com.echo.config.EchoSqliteDialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "echo.jms.target.enabled=false",
        "echo.jms.target-catalog-enabled=true",
        "echo.jms.credential-key=catalog-test-key"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class JmsTargetCatalogIntegrationTest {
    @Autowired JmsTargetConnectionService service;
    @Autowired JmsTargetConnectionRepository repository;
    @Autowired DataSource dataSource;
    @Autowired JmsProperties properties;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired PlatformTransactionManager transactions;

    @BeforeEach
    void reset() {
        properties.getTarget().setEnabled(false);
        properties.setTargetCatalogEnabled(true);
        service.configureRuntimeLookup(entityManagerFactory, transactions);
        repository.deleteAllInBatch();
    }

    @Test
    void warmTargetSelectionDoesNotWaitForManagementDatabaseConnections() throws Exception {
        var dto = service.create(new JmsTargetConnectionRequest(null, "Primary", "tibco",
                "tcp://esb:7222", "user", "test-secret", false, "TARGET.REQUEST", 5, true, true));
        var expected = service.resolveActive().orElseThrow();
        assertThat(service.resolveEnabled(dto.id()).cacheKey()).isEqualTo(expected.cacheKey());

        List<Connection> held = new ArrayList<>();
        var worker = Executors.newSingleThreadExecutor();
        try {
            for (int i = 0; i < 3; i++) held.add(dataSource.getConnection());
            var result = worker.submit(() -> {
                for (int i = 0; i < 100; i++) {
                    assertThat(service.resolveActive().orElseThrow().cacheKey()).isEqualTo(expected.cacheKey());
                    var target = service.resolveEnabled(dto.id()).target();
                    assertThat(target.getQueue()).isEqualTo("TARGET.REQUEST");
                    assertThat(target.getPassword()).isEqualTo("test-secret");
                }
                return true;
            });
            assertThat(result.get(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            for (Connection connection : held) connection.close();
            worker.shutdownNow();
            assertThat(worker.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void uncachedAndColdLookupsFailUnderPoolExhaustionThenRecoverWithoutPublishingFailures() throws Exception {
        var dto = create("Primary", true, "test-secret");
        properties.setTargetCatalogEnabled(false);
        service.configureRuntimeLookup(entityManagerFactory, transactions);
        assertThat(service.resolveEnabled(dto.id()).target().getQueue()).isEqualTo("TARGET.REQUEST");

        List<Connection> held = new ArrayList<>();
        var worker = Executors.newSingleThreadExecutor();
        try {
            for (int i = 0; i < 3; i++) held.add(dataSource.getConnection());
            assertThat(worker.submit(() -> {
                assertThatThrownBy(service::resolveActive).isInstanceOf(RuntimeException.class)
                        .hasRootCauseInstanceOf(java.sql.SQLTransientConnectionException.class);
                properties.setTargetCatalogEnabled(true);
                service.configureRuntimeLookup(entityManagerFactory, transactions);
                assertThatThrownBy(() -> service.resolveEnabled(dto.id())).isInstanceOf(RuntimeException.class)
                        .hasRootCauseInstanceOf(java.sql.SQLTransientConnectionException.class);
                return true;
            }).get(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            for (Connection connection : held) connection.close();
            worker.shutdownNow();
            assertThat(worker.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(service.resolveActive().orElseThrow().target().getQueue()).isEqualTo("TARGET.REQUEST");
        assertThat(service.resolveEnabled(dto.id()).target().getPassword()).isEqualTo("test-secret");
    }

    @Test
    void concurrentColdFailuresShareOnePoolTimeoutAndRecoverAfterConnectionsReturn() throws Exception {
        var dto = create("Primary", true, "test-secret");
        List<Connection> held = new ArrayList<>();
        var workers = Executors.newFixedThreadPool(8);
        var ready = new CountDownLatch(8);
        var start = new CountDownLatch(1);
        try {
            for (int i = 0; i < 3; i++) held.add(dataSource.getConnection());
            var calls = new ArrayList<java.util.concurrent.Future<RuntimeException>>();
            for (int i = 0; i < 8; i++) {
                calls.add(workers.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("Start gate timed out");
                    try {
                        service.resolveEnabled(dto.id());
                        throw new AssertionError("Cold lookup must fail while the pool is exhausted");
                    } catch (RuntimeException failure) {
                        return failure;
                    }
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            start.countDown();
            // One common deadline catches a serial chain of eight 500 ms pool timeouts.
            for (var call : calls) {
                var failure = call.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                assertThat(failure).hasRootCauseInstanceOf(java.sql.SQLTransientConnectionException.class);
            }
        } finally {
            start.countDown();
            workers.shutdownNow();
            for (Connection connection : held) connection.close();
            assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(service.resolveEnabled(dto.id()).target().getQueue()).isEqualTo("TARGET.REQUEST");
        assertThat(service.resolveActive().orElseThrow().target().getPassword()).isEqualTo("test-secret");
    }

    @Test
    void committedUpdatesSwitchDefaultsRotateCredentialsAndPreserveOldRequests() {
        var first = create("First", true, "first-secret");
        var second = create("Second", false, "second-secret");
        var old = service.resolveEnabled(second.id());
        assertThat(service.resolveActive().orElseThrow().name()).isEqualTo("First");

        service.setDefault(Long.valueOf(second.id()));
        assertThat(service.resolveActive().orElseThrow().name()).isEqualTo("Second");
        var changed = service.update(Long.valueOf(second.id()), request(current(second.id()),
                true, true, "rotated-secret", false, "NEW.QUEUE"));

        var resolved = service.resolveActive().orElseThrow();
        assertThat(resolved.cacheKey()).isEqualTo("db:" + changed.id() + ":" + changed.version());
        assertThat(resolved.target().getQueue()).isEqualTo("NEW.QUEUE");
        assertThat(resolved.target().getPassword()).isEqualTo("rotated-secret");
        assertThat(old.target().getQueue()).isEqualTo("TARGET.REQUEST");
        assertThat(old.target().getPassword()).isEqualTo("second-secret");

        service.update(Long.valueOf(first.id()), request(current(first.id()), false, false,
                null, false, "TARGET.REQUEST"));
        assertThatThrownBy(() -> service.resolveEnabled(first.id())).hasMessage("JMS_CONNECTION_DISABLED");
        service.delete(Long.valueOf(first.id()));
        assertThatThrownBy(() -> service.resolveEnabled(first.id())).hasMessage("JMS_CONNECTION_NOT_FOUND");
        service.update(Long.valueOf(second.id()), request(current(second.id()), true, true,
                null, true, "NEW.QUEUE"));
        assertThat(service.resolveActive().orElseThrow().target().getPassword()).isNull();
    }

    @Test
    void rolledBackChangesAreVisibleOnlyToTheirOwnTransaction() throws Exception {
        var first = create("Primary", true, "old-secret");
        var before = service.resolveActive().orElseThrow();
        var worker = Executors.newSingleThreadExecutor();
        try {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                service.update(Long.valueOf(first.id()), request(current(first.id()), true, true,
                        "uncommitted-secret", false, "UNCOMMITTED"));
                assertThat(service.resolveEnabled(first.id()).target().getQueue()).isEqualTo("UNCOMMITTED");
                try {
                    assertThat(worker.submit(() -> service.resolveActive().orElseThrow().target().getQueue())
                            .get(2, TimeUnit.SECONDS)).isEqualTo("TARGET.REQUEST");
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
                status.setRollbackOnly();
            });
            assertThat(service.resolveActive().orElseThrow().cacheKey()).isEqualTo(before.cacheKey());
            assertThat(service.resolveActive().orElseThrow().target().getPassword()).isEqualTo("old-secret");
        } finally {
            worker.shutdownNow();
            assertThat(worker.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void invalidUnrelatedCredentialDoesNotPreventUsingAValidTarget() {
        var first = create("Primary", true, "valid-secret");
        var broken = repository.saveAndFlush(JmsTargetConnection.builder().name("Broken")
                .providerType("artemis").serverUrl("tcp://localhost:61616").queueName("BROKEN")
                .timeoutSeconds(5).enabled(true).defaultConnection(false).encryptedPassword("bad-format").build());
        service.invalidateTargetCatalog();
        assertThat(service.resolveEnabled(first.id()).target().getPassword()).isEqualTo("valid-secret");
        assertThatThrownBy(() -> service.resolveEnabled(broken.getId().toString()))
                .hasMessage("Unsupported JMS credential format");
        service.update(broken.getId(), request(current(broken.getId().toString()), true, false,
                "fixed-secret", false, "FIXED"));
        assertThat(service.resolveEnabled(broken.getId().toString()).target().getPassword()).isEqualTo("fixed-secret");
    }

    @Test
    void yamlSelectionNeedsNoDatabaseAndDisabledCatalogRetainsFreshRepositoryReads() throws Exception {
        var first = create("Stored", true, "db-secret");
        properties.getTarget().setEnabled(true);
        properties.getTarget().setServerUrl("tcp://yaml:7222");
        assertThat(service.resolveActive().orElseThrow().legacy()).isTrue();
        properties.getTarget().setEnabled(false);
        properties.setTargetCatalogEnabled(false);
        service.configureRuntimeLookup(entityManagerFactory, transactions);
        assertThat(service.resolveActive().orElseThrow().target().getQueue()).isEqualTo("TARGET.REQUEST");
        var row = repository.findById(Long.valueOf(first.id())).orElseThrow();
        row.setQueueName("OUTSIDE.SERVICE");
        repository.saveAndFlush(row);
        assertThat(service.resolveEnabled(first.id()).target().getQueue()).isEqualTo("OUTSIDE.SERVICE");
    }

    @Test
    void yamlTargetSelectionAlsoWorksWhileEveryDatabaseConnectionIsOccupied() throws Exception {
        properties.getTarget().setEnabled(true);
        properties.getTarget().setServerUrl("tcp://yaml:7222");
        properties.getTarget().setQueue("YAML.REQUEST");
        properties.setTargetCatalogEnabled(false);
        service.configureRuntimeLookup(entityManagerFactory, transactions);
        List<Connection> held = new ArrayList<>();
        var worker = Executors.newSingleThreadExecutor();
        try {
            for (int i = 0; i < 3; i++) held.add(dataSource.getConnection());
            var result = worker.submit(() -> {
                assertThat(service.resolveActive().orElseThrow().target().getQueue()).isEqualTo("YAML.REQUEST");
                assertThat(service.resolveEnabled(JmsTargetConnectionService.LEGACY_ID).legacy()).isTrue();
                return true;
            });
            assertThat(result.get(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            for (Connection connection : held) connection.close();
            worker.shutdownNow();
            assertThat(worker.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void legacyMultipleDefaultsRetainTheExistingRepositorySelection() {
        var first = create("Z first", true, "first-secret");
        var second = create("A second", false, "second-secret");
        var row = repository.findById(Long.valueOf(second.id())).orElseThrow();
        row.setDefaultConnection(true);
        repository.saveAndFlush(row);
        service.invalidateTargetCatalog();
        var expected = repository.findFirstByDefaultConnectionTrueAndEnabledTrue().orElseThrow();
        assertThat(expected.getId()).isEqualTo(Long.valueOf(first.id()));
        assertThat(service.resolveActive().orElseThrow().name()).isEqualTo(expected.getName());
    }

    private JmsTargetConnectionDto create(String name, boolean defaultConnection, String password) {
        return service.create(new JmsTargetConnectionRequest(null, name, "tibco", "tcp://esb:7222",
                "user", password, false, "TARGET.REQUEST", 5, true, defaultConnection));
    }

    private JmsTargetConnectionDto current(String id) {
        return service.list().stream().filter(dto -> dto.id().equals(id)).findFirst().orElseThrow();
    }

    private static JmsTargetConnectionRequest request(JmsTargetConnectionDto dto, boolean enabled,
            boolean defaultConnection, String password, boolean clearPassword, String queue) {
        return new JmsTargetConnectionRequest(dto.version(), dto.name(), dto.providerType(), dto.serverUrl(),
                dto.username(), password, clearPassword, queue, dto.timeoutSeconds(), enabled, defaultConnection);
    }
}
