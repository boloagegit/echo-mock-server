package com.echo.repository;

import com.echo.entity.JmsTargetConnection;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:sqlite:file:jms-target-repository-test?mode=memory&cache=shared",
        "spring.datasource.driver-class-name=org.sqlite.JDBC",
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.datasource.hikari.minimum-idle=1",
        "spring.jpa.database-platform=org.hibernate.community.dialect.SQLiteDialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class JmsTargetConnectionRepositorySqliteTest {

    @Autowired
    private JmsTargetConnectionRepository repository;

    @Test
    void persistsAndFindsDefaultConnectionOnSqlite() {
        JmsTargetConnection saved = repository.saveAndFlush(connection("Primary", true));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(repository.findFirstByDefaultConnectionTrueAndEnabledTrue())
                .get()
                .extracting(JmsTargetConnection::getName)
                .isEqualTo("Primary");
    }

    @Test
    void enforcesUniqueConnectionNamesOnSqlite() {
        repository.saveAndFlush(connection("Duplicate", true));

        assertThatThrownBy(() -> repository.saveAndFlush(connection("Duplicate", false)))
                .isInstanceOf(DataAccessException.class);
    }

    private static JmsTargetConnection connection(String name, boolean defaultConnection) {
        return JmsTargetConnection.builder()
                .name(name)
                .providerType("artemis")
                .serverUrl("tcp://localhost:61616")
                .queueName("TARGET.REQUEST")
                .timeoutSeconds(30)
                .enabled(true)
                .defaultConnection(defaultConnection)
                .build();
    }
}
