package com.echo.repository;

import com.echo.entity.Response;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class ResponseIdProjectionTest {

    @Autowired
    private ResponseRepository repository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void findExistingIds_shouldUseOneQueryAndLoadNoEntitiesOrLobsOnH2() {
        String largeBody = "x".repeat(2 * 1024 * 1024);
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
