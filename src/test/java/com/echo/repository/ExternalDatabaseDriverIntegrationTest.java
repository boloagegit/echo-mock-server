package com.echo.repository;

import com.echo.entity.BuiltinUser;
import com.echo.entity.JmsTargetConnection;
import com.echo.entity.Response;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.transaction.TestTransaction;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;

/** Opt-in only: creates/drops schema in a disposable loopback database named echo_audit. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "ECHO_SECURITY_DB_URL", matches = ".+")
class ExternalDatabaseDriverIntegrationTest {
    @Autowired BuiltinUserRepository users;
    @Autowired ResponseRepository responses;
    @Autowired JmsTargetConnectionRepository targets;
    @Autowired EntityManager entities;
    @Autowired DataSource dataSource;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("ECHO_SECURITY_DB_URL");
        if (url == null || !url.matches("jdbc:(postgresql|mariadb)://127\\.0\\.0\\.1:[0-9]+/echo_audit")) {
            throw new IllegalArgumentException("This destructive test requires a disposable loopback echo_audit database");
        }
        boolean postgres = url.startsWith("jdbc:postgresql:");
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> "echo_audit");
        registry.add("spring.datasource.password", () -> "local-audit-password");
        registry.add("spring.datasource.driver-class-name", () -> postgres ? "org.postgresql.Driver" : "org.mariadb.jdbc.Driver");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
        registry.add("spring.jpa.database-platform", () -> postgres
                ? "org.hibernate.dialect.PostgreSQLDialect" : "org.hibernate.dialect.MariaDBDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }
    @Test void verifiesTheActualJdbcDriverVersion() throws Exception {
        try (var connection = dataSource.getConnection()) {
            var metadata = connection.getMetaData();
            System.out.println("Database driver: " + metadata.getDriverName() + " " + metadata.getDriverVersion());
            assertThat(metadata.getDriverVersion()).contains(System.getenv("ECHO_SECURITY_EXPECTED_DRIVER_VERSION"));
        }
    }
    @Test void accountCrudPreservesUnicodeRolesAndDisabledState() {
        var saved = users.saveAndFlush(BuiltinUser.builder().username("audit_使用者").password("test-only-hash")
                .role("ROLE_USER").enabled(true).build());
        Long id = saved.getId();
        entities.clear();
        var loaded = users.findByUsername("audit_使用者").orElseThrow();
        assertThat(loaded.getId()).isEqualTo(id);
        assertThat(loaded.getCreatedAt()).isNotNull();
        loaded.setEnabled(false);
        users.saveAndFlush(loaded);
        entities.clear();
        assertThat(users.findById(id).orElseThrow().getEnabled()).isFalse();
        users.deleteById(id);
        users.flush();
        assertThat(users.findById(id)).isEmpty();
    }
    @Test void largeResponseAndOptimisticVersionRoundTrip() {
        String body = "資料🙂".repeat(8192);
        var saved = responses.saveAndFlush(Response.builder().description("driver audit").body(body).build());
        Long id = saved.getId(), version = saved.getVersion();
        entities.clear();
        var loaded = responses.findById(id).orElseThrow();
        assertThat(loaded.getBody()).isEqualTo(body);
        assertThat(loaded.getBodySize()).isEqualTo(body.getBytes(StandardCharsets.UTF_8).length);
        loaded.setDescription("updated");
        responses.saveAndFlush(loaded);
        entities.clear();
        assertThat(responses.findById(id).orElseThrow().getVersion()).isGreaterThan(version);
    }
    @Test void jmsTargetConfigurationRemainsPersistent() {
        var saved = targets.saveAndFlush(JmsTargetConnection.builder().name("audit-target").providerType("artemis")
                .serverUrl("tcp://localhost:61616").queueName("AUDIT.REQUEST").timeoutSeconds(30)
                .enabled(true).defaultConnection(true).build());
        entities.clear();
        var loaded = targets.findFirstByDefaultConnectionTrueAndEnabledTrue().orElseThrow();
        assertThat(loaded.getId()).isEqualTo(saved.getId());
        assertThat(loaded.getQueueName()).isEqualTo("AUDIT.REQUEST");
        assertThat(loaded.getCreatedAt()).isNotNull();
    }
    @Test void rollbackDoesNotLeaveAnAccountBehind() {
        users.saveAndFlush(BuiltinUser.builder().username("rollback-audit").password("test-only-hash")
                .role("ROLE_USER").enabled(true).build());
        TestTransaction.flagForRollback();
        TestTransaction.end();
        TestTransaction.start();
        assertThat(users.findByUsername("rollback-audit")).isEmpty();
    }
}
