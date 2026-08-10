package com.echo.repository;

import com.echo.entity.Response;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:sqlite:file:response-id-projection-test?mode=memory&cache=shared",
        "spring.datasource.driver-class-name=org.sqlite.JDBC",
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.datasource.hikari.minimum-idle=1",
        "spring.jpa.database-platform=org.hibernate.community.dialect.SQLiteDialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
class ResponseIdProjectionSqliteTest {

    @Autowired
    private ResponseRepository repository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void findExistingIds_shouldUseOneQueryAndLoadNoEntitiesOrLobsOnSqlite() {
        String largeBody = "資料".repeat(1024 * 1024);
        Response first = repository.save(Response.builder().description("first").body(largeBody).build());
        Response second = repository.save(Response.builder().description("second").body(largeBody).build());
        repository.flush();
        entityManager.clear();

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        List<Long> ids = repository.findExistingIds(Set.of(first.getId(), second.getId(), 999L));

        assertThat(ids).containsExactlyInAnyOrder(first.getId(), second.getId());
        assertThat(statistics.getQueryExecutionCount()).isEqualTo(1);
        assertThat(statistics.getEntityLoadCount()).isZero();
    }
}
