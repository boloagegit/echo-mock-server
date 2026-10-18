package com.echo.service;

import com.echo.entity.HttpRule;
import com.echo.entity.JmsRule;
import com.echo.entity.Response;
import com.echo.repository.HttpRuleRepository;
import com.echo.repository.JmsRuleRepository;
import com.echo.repository.ResponseRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "echo.cleanup.enabled=true", "echo.cleanup.cron=0 0 0 1 1 *",
        "echo.cleanup.response-retention-days=90", "echo.jms.enabled=false",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
@ActiveProfiles("test")
class CleanupBatchIntegrationTest {
    @Autowired CleanupService cleanup;
    @Autowired ResponseRepository responses;
    @Autowired HttpRuleRepository httpRules;
    @Autowired JmsRuleRepository jmsRules;
    @Autowired EntityManager entityManager;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void removeFixtures() {
        jdbc.execute("DROP TRIGGER IF EXISTS fail_cleanup_fixture");
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            httpRules.deleteAllInBatch();
            jmsRules.deleteAllInBatch();
            responses.deleteAllInBatch();
        });
    }

    @Test
    void hundredsOfOldResponsesAreDeletedWithoutLoadingBodiesAndReferencedOrExtendedRowsSurvive() {
        var transactions = new TransactionTemplate(transactionManager);
        List<Long> ids = seed(205, transactions);
        transactions.executeWithoutResult(status -> {
            httpRules.save(HttpRule.builder().matchKey("/protected").method("GET").responseId(ids.get(0)).build());
            jmsRules.save(JmsRule.builder().queueName("PROTECTED").responseId(ids.get(1)).build());
            responses.extendResponses(List.of(ids.get(2)), LocalDateTime.now());
        });
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        cleanup.cleanup();
        assertThat(statistics.getEntityStatistics(Response.class.getName()).getLoadCount()).isZero();
        assertThat(responses.findExistingIds(ids)).containsExactlyInAnyOrderElementsOf(ids.subList(0, 3));
        // A repeat run is safe and still does not materialize response bodies.
        cleanup.cleanup();
        assertThat(responses.findExistingIds(ids)).containsExactlyInAnyOrderElementsOf(ids.subList(0, 3));
        transactions.executeWithoutResult(status -> {
            httpRules.deleteAllInBatch();
            jmsRules.deleteAllInBatch();
            responses.deleteAllInBatch();
        });
    }

    @Test
    void aLaterBatchFailureRollsBackThatBatchAndTheNextRunFinishesTheRemainingRows() {
        var transactions = new TransactionTemplate(transactionManager);
        var ids = seed(250, transactions);
        jdbc.execute("CREATE TRIGGER fail_cleanup_fixture BEFORE DELETE ON responses WHEN OLD.id = "
                + ids.get(150) + " BEGIN SELECT RAISE(ABORT, 'fixture batch failure'); END");
        assertThatThrownBy(cleanup::cleanup).isInstanceOf(DataAccessException.class);
        assertThat(responses.findExistingIds(ids))
                .containsExactlyInAnyOrderElementsOf(ids.subList(100, 250));
        jdbc.execute("DROP TRIGGER fail_cleanup_fixture");
        cleanup.cleanup();
        assertThat(responses.findExistingIds(ids)).isEmpty();
    }

    @Test
    void deletingASelectedPageRechecksBothNewReferencesAndExtensions() {
        var transactions = new TransactionTemplate(transactionManager);
        var ids = seed(3, transactions);
        LocalDateTime cutoff = LocalDateTime.now().minusDays(90);
        assertThat(responses.findOrphanResponseIds(cutoff, 0, ids.get(2), PageRequest.of(0, 100)))
                .containsAll(ids);
        transactions.executeWithoutResult(status -> {
            httpRules.save(HttpRule.builder().matchKey("/new-reference").method("GET").responseId(ids.get(0)).build());
            responses.extendResponses(List.of(ids.get(1)), LocalDateTime.now());
        });
        int deleted = transactions.execute(status -> responses.deleteExpiredOrphans(ids, cutoff));
        assertThat(deleted).isEqualTo(1);
        assertThat(responses.findExistingIds(ids)).containsExactlyInAnyOrder(ids.get(0), ids.get(1));
        transactions.executeWithoutResult(status -> {
            httpRules.deleteAllInBatch();
            responses.deleteAllInBatch();
        });
    }

    private List<Long> seed(int count, TransactionTemplate transactions) {
        return transactions.execute(status -> {
            var ids = new ArrayList<Long>();
            String body = "x".repeat(32 * 1024);
            for (int i = 0; i < count; i++) {
                ids.add(responses.save(Response.builder().description("old fixture").body(body).build()).getId());
            }
            entityManager.flush();
            entityManager.createQuery("UPDATE Response r SET r.updatedAt = :time WHERE r.id IN :ids")
                    .setParameter("time", LocalDateTime.now().minusDays(365)).setParameter("ids", ids).executeUpdate();
            entityManager.clear();
            return ids;
        });
    }
}
