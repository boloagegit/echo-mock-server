package com.echo.repository;

import static org.assertj.core.api.Assertions.assertThat;

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

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:sqlite:file:response-id-projection-test?mode=memory&cache=shared",
            "spring.datasource.driver-class-name=org.sqlite.JDBC",
            "spring.datasource.hikari.maximum-pool-size=1",
            "spring.datasource.hikari.minimum-idle=1",
            "spring.jpa.database-platform=com.echo.config.EchoSqliteDialect",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.properties.hibernate.generate_statistics=true"
        })
class ResponseIdProjectionSqliteTest {

    @Autowired private ResponseRepository repository;

    @Autowired private EntityManager entityManager;

    @Autowired private EntityManagerFactory entityManagerFactory;

    @Test
    void detachedMergeCannotRestoreAnOldIncarnationAfterIdReuse() {
        Response detached =
                repository.saveAndFlush(
                        Response.builder().description("original").body("first").build());
        Long id = detached.getId();
        entityManager.clear();
        Response edited = repository.findById(id).orElseThrow();
        edited.setBody("second");
        repository.saveAndFlush(edited);
        var oldRevision = repository.findVersionedBodyById(id).orElseThrow();
        entityManager.clear();

        // Recreate the same numeric ID at the same starting version, with a new identity.
        entityManager
                .createNativeQuery("DELETE FROM responses WHERE id = :id")
                .setParameter("id", id)
                .executeUpdate();
        entityManager
                .createNativeQuery(
                        "INSERT INTO responses (id, version, incarnation, description, body) "
                                + "VALUES (:id, :version, :incarnation, 'replacement', 'replacement')")
                .setParameter("id", id)
                .setParameter("version", detached.getVersion())
                .setParameter("incarnation", "replacement-row")
                .executeUpdate();
        detached.setBody("late write");
        repository.saveAndFlush(detached);
        entityManager.clear();

        var current = repository.findVersionedBodyById(id).orElseThrow();
        assertThat(current.version()).isEqualTo(oldRevision.version());
        assertThat(current.incarnation()).isEqualTo("replacement-row");
        assertThat(current.incarnation()).isNotEqualTo(oldRevision.incarnation());
    }

    @Test
    void explicitBackfillStillInitializesIdentityWithoutChangingPublicFields() {
        Response response =
                repository.saveAndFlush(
                        Response.builder().description("legacy").body("unchanged").build());
        Long id = response.getId();
        entityManager.clear();
        Response persistedBefore = repository.findById(id).orElseThrow();
        entityManager.clear();
        entityManager
                .createNativeQuery("UPDATE responses SET incarnation = NULL WHERE id = :id")
                .setParameter("id", id)
                .executeUpdate();
        assertThat(repository.initializeIncarnation(id, "backfilled-row")).isEqualTo(1);
        entityManager.clear();
        Response after = repository.findById(id).orElseThrow();
        assertThat(after.getIncarnation()).isEqualTo("backfilled-row");
        assertThat(after.getVersion()).isEqualTo(persistedBefore.getVersion());
        assertThat(after.getBody()).isEqualTo(persistedBefore.getBody());
        assertThat(after.getDescription()).isEqualTo(persistedBefore.getDescription());
        assertThat(after.getCreatedAt()).isEqualTo(persistedBefore.getCreatedAt());
        assertThat(after.getUpdatedAt()).isEqualTo(persistedBefore.getUpdatedAt());
        assertThat(repository.initializeIncarnation(id, "must-not-replace")).isZero();
        entityManager.clear();
        assertThat(repository.findById(id).orElseThrow().getIncarnation())
                .isEqualTo("backfilled-row");
    }

    @Test
    void updatingLegacyContentKeepsIdentityUnknownUntilExplicitBackfill() {
        Response response = repository.saveAndFlush(Response.builder().body("legacy").build());
        Long id = response.getId();
        entityManager.clear();
        entityManager
                .createNativeQuery("UPDATE responses SET incarnation = NULL WHERE id = :id")
                .setParameter("id", id)
                .executeUpdate();
        Response legacy = repository.findById(id).orElseThrow();
        legacy.setBody("edited");
        Response saved = repository.saveAndFlush(legacy);
        assertThat(saved.getIncarnation()).isNull();
        entityManager.clear();
        var body = repository.findVersionedBodyById(id).orElseThrow();
        assertThat(body.body()).isEqualTo("edited");
        assertThat(body.incarnation()).isNull();
        assertThat(body.version()).isEqualTo(response.getVersion() + 1);
    }

    @Test
    void findExistingIds_shouldUseOneQueryAndLoadNoEntitiesOrLobsOnSqlite() {
        String largeBody = "資料".repeat(1024 * 1024);
        Response first =
                repository.save(Response.builder().description("first").body(largeBody).build());
        Response second =
                repository.save(Response.builder().description("second").body(largeBody).build());
        repository.flush();
        entityManager.clear();

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        List<Long> ids = repository.findExistingIds(Set.of(first.getId(), second.getId(), 999L));

        assertThat(ids).containsExactlyInAnyOrder(first.getId(), second.getId());
        assertThat(statistics.getQueryExecutionCount()).isEqualTo(1);
        assertThat(statistics.getEntityLoadCount()).isZero();
    }

    @Test
    void findAllIds_shouldUseOneQueryAndLoadNoEntitiesOrLobsOnSqlite() {
        String largeBody = "資料".repeat(1024 * 1024);
        Response first =
                repository.save(Response.builder().description("first").body(largeBody).build());
        Response second =
                repository.save(Response.builder().description("second").body(largeBody).build());
        repository.flush();
        entityManager.clear();

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        List<Long> ids = repository.findAllIds();

        assertThat(ids).contains(first.getId(), second.getId());
        assertThat(statistics.getQueryExecutionCount()).isEqualTo(1);
        assertThat(statistics.getEntityLoadCount()).isZero();
    }
}
