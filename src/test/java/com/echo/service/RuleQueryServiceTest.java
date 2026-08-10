package com.echo.service;

import com.echo.entity.HttpRule;
import com.echo.entity.JmsRule;
import com.echo.entity.Protocol;
import com.echo.repository.HttpRuleRepository;
import com.echo.repository.JmsRuleRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "echo.jms.enabled=true",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
@Import(RuleQueryService.class)
class RuleQueryServiceTest {

    @Autowired
    private RuleQueryService service;

    @Autowired
    private HttpRuleRepository httpRepository;

    @Autowired
    private JmsRuleRepository jmsRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void query_shouldPreserveFiltersKeywordAndSortSemantics() {
        httpRepository.saveAll(List.of(
                http("http-match", "/payments/orders", "customer lookup", "PAYMENT.EXAMPLE",
                        "{\"env\":\"prod\"}", true, true, 20),
                http("http-disabled", "/payments/orders", "prod fallback", null,
                        null, false, true, 30),
                http("http-wrong-tag", "/payments/orders", "customer lookup", null,
                        "{\"env\":\"dev\"}", true, true, 40),
                http("http-second", "/payments/refunds", "prod payment", null,
                        null, true, true, 10)
        ));
        httpRepository.flush();
        entityManager.clear();

        RuleQueryService.RuleQueryResult result = service.query(new RuleQueryService.RuleQuery(
                Protocol.HTTP, true, true, "PAYMENT prod", 0, 20, "priority", "desc"));

        assertThat(result.totalElements()).isEqualTo(2);
        assertThat(result.rules()).extracting(r -> r.getId())
                .containsExactly("http-match", "http-second");
    }

    @Test
    void query_shouldBoundDatabaseWorkForLargeCrossProtocolFirstPage() {
        List<HttpRule> httpRules = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            httpRules.add(http("http-%03d".formatted(i), "/orders/" + i,
                    "HTTP rule " + i, null, "{\"env\":\"prod\"}", true, false, i));
        }
        List<JmsRule> jmsRules = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            jmsRules.add(jms("jms-%03d".formatted(i), "ORDER." + i, i));
        }
        httpRepository.saveAll(httpRules);
        jmsRepository.saveAll(jmsRules);
        httpRepository.flush();
        jmsRepository.flush();
        entityManager.clear();

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        RuleQueryService.RuleQueryResult result = service.query(new RuleQueryService.RuleQuery(
                null, null, null, null, 0, 20, "updatedAt", "desc"));

        assertThat(result.totalElements()).isEqualTo(300);
        assertThat(result.totalPages()).isEqualTo(15);
        assertThat(result.rules()).hasSize(20);
        assertThat(statistics.getQueryExecutionCount()).isEqualTo(4);
        assertThat(statistics.getEntityLoadCount()).isLessThanOrEqualTo(40);

        entityManager.clear();
        statistics.clear();
        RuleQueryService.RuleQuery groupQuery = new RuleQueryService.RuleQuery(
                null, null, null, null, 0, 20, "priority", "desc");
        RuleQueryService.RuleGroupSummary summary = service.queryGroupSummary(groupQuery);
        assertThat(summary.totalElements()).isEqualTo(300);
        assertThat(summary.counts()).containsEntry("env=prod", 250L)
                .containsEntry("_untagged", 50L);
        assertThat(statistics.getQueryExecutionCount()).isEqualTo(2);
        assertThat(statistics.getEntityLoadCount()).isZero();

        statistics.clear();
        RuleQueryService.RuleGroupContent group = service.queryGroup(groupQuery, "env", "prod", 20);
        assertThat(group.totalElements()).isEqualTo(250);
        assertThat(group.rules()).hasSize(20);
        assertThat(statistics.getQueryExecutionCount()).isEqualTo(3);
        assertThat(statistics.getEntityLoadCount()).isEqualTo(20);
    }

    @Test
    void query_shouldClampPageSizeAndRequestedPage() {
        httpRepository.save(http("only-rule", "/only", "single", null,
                null, true, false, 0));
        httpRepository.flush();

        RuleQueryService.RuleQueryResult result = service.query(new RuleQueryService.RuleQuery(
                Protocol.HTTP, null, null, null, 999, 1000, "unknown", "desc"));

        assertThat(result.page()).isZero();
        assertThat(result.size()).isEqualTo(100);
        assertThat(result.totalPages()).isEqualTo(1);
        assertThat(result.rules()).extracting(r -> r.getId()).containsExactly("only-rule");
    }

    @Test
    void groupSummary_shouldCountMultiTagAndUntaggedRulesWithoutLoadingEntities() {
        httpRepository.saveAll(List.of(
                http("untagged", "/untagged", "plain", null, null, true, false, 1),
                http("prod-team", "/prod-team", "tagged", null,
                        "{\"env\": \"prod\", \"team\":\"payment\"}", true, false, 30),
                http("prod", "/prod", "tagged", null,
                        "{\"env\":\"prod\"}", true, false, 20),
                http("dev", "/dev", "tagged", null,
                        "{\"env\":\"dev\"}", true, false, 10),
                http("malformed", "/malformed", "tagged", null,
                        "not-json", true, false, 5),
                http("whitespace", "/whitespace", "tagged", null,
                        "   ", true, false, 4)
        ));
        httpRepository.flush();
        entityManager.clear();
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        RuleQueryService.RuleGroupSummary summary = service.queryGroupSummary(
                new RuleQueryService.RuleQuery(Protocol.HTTP, null, null, null,
                        0, 20, null, null));

        assertThat(summary.totalElements()).isEqualTo(6);
        assertThat(summary.tagKeys()).containsEntry("env", List.of("dev", "prod"))
                .containsEntry("team", List.of("payment"));
        assertThat(summary.counts()).containsEntry("_untagged", 1L)
                .containsEntry("env=prod", 2L)
                .containsEntry("env=dev", 1L)
                .containsEntry("team=payment", 1L);
        assertThat(statistics.getQueryExecutionCount()).isEqualTo(1);
        assertThat(statistics.getEntityLoadCount()).isZero();
    }

    @Test
    void groupContent_shouldLoadOnlyRequestedSortedPrefix() {
        httpRepository.saveAll(List.of(
                http("prod-low", "/low", "tagged", null,
                        "{\"env\":\"prod\"}", true, false, 10),
                http("prod-high", "/high", "tagged", null,
                        "{\"env\": \"prod\"}", true, false, 30),
                http("dev", "/dev", "tagged", null,
                        "{\"env\":\"dev\"}", true, false, 50)
        ));
        httpRepository.flush();
        entityManager.clear();
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        RuleQueryService.RuleGroupContent content = service.queryGroup(
                new RuleQueryService.RuleQuery(Protocol.HTTP, null, null, null,
                        0, 1, "priority", "desc"), "env", "prod", 1);

        assertThat(content.totalElements()).isEqualTo(2);
        assertThat(content.rules()).extracting(r -> r.getId()).containsExactly("prod-high");
        assertThat(statistics.getQueryExecutionCount()).isEqualTo(2);
        assertThat(statistics.getEntityLoadCount()).isEqualTo(1);
    }

    private HttpRule http(String id, String matchKey, String description, String targetHost,
                          String tags, boolean enabled, boolean isProtected, int priority) {
        return HttpRule.builder()
                .id(id)
                .matchKey(matchKey)
                .method("GET")
                .description(description)
                .targetHost(targetHost)
                .tags(tags)
                .enabled(enabled)
                .isProtected(isProtected)
                .priority(priority)
                .build();
    }

    private JmsRule jms(String id, String queue, int priority) {
        return JmsRule.builder()
                .id(id)
                .queueName(queue)
                .description("JMS rule " + priority)
                .enabled(true)
                .isProtected(false)
                .priority(priority)
                .build();
    }
}
